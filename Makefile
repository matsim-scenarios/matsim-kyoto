
N := kyoto
V := v1.0
CRS := EPSG:32653

MEMORY ?= 20G
JAR := matsim-$(N)-*.jar

kyoto := ../public-svn/matsim/scenarios/countries/jp/kyoto
confidential := ../shared-svn/projects/matsim-kyoto

osmosis := osmosis/bin/osmosis

# Scenario creation tool
sc := java -Xmx$(MEMORY) -XX:+UseParallelGC -cp $(JAR) org.matsim.prepare.RunOpenKyotoCalibration

.PHONY: prepare

$(JAR):
	mvn package

# Required files
input/kansai.osm.pbf:
	curl https://download.geofabrik.de/asia/japan/kansai-240101.osm.pbf -o $@

input/network.osm: input/kansai.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="input/area.poly"\
	 --used-node --wb input/network-detailed.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction\
	 --used-node --wb input/network-coarse.osm.pbf


	$(osmosis) --rb file=input/network-coarse.osm.pbf --rb file=input/network-detailed.osm.pbf\
  	 --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm input/network-detailed.osm.pbf
	rm input/network-coarse.osm.pbf

input/sumo.net.xml: input/network.osm

	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=53 +ellps=WGS84 +datum=WGS84 +units=m +no_defs"\
	 --lefthand true\
	 --osm-files $< -o=$@


input/$V/$N-$V-network.xml.gz: input/sumo.net.xml
	$(sc) prepare network-from-sumo $< --target-crs $(CRS) --output $@

	$(sc) prepare clean-network $@  --output $@ --modes car,ride,truck --remove-turn-restrictions


input/$V/$N-$V-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2024-08-08" --target-crs $(CRS) \
	 $(kyoto)/data/public_transit/bus_schedule/Kyoto_City_Bus_GTFS-20240726.zip\
	 $(kyoto)/data/public_transit/bus_schedule/Kyotobus-20240808.zip\
	 --prefix city_bus_,kyoto_bus_

input/facilities.gpkg: input/kansai.osm.pbf
	$(sc) prepare facility-shp\
	 --activity-mapping input/activity_mapping.json\
	 --input $<\
	 --target-crs $(CRS)\
	 --output $@

input/$V/$N-$V-facilities.xml.gz: input/$V/$N-$V-network.xml.gz input/facilities.gpkg
	$(sc) prepare facilities --network $< --shp $(word 2,$^)\
	 --facility-mapping input/facility_mapping.json\
	 --output $@

# Static population only contains the home locations
input/$V/$N-static-$V-10pct.plans.xml.gz: input/facilities.gpkg
	$(sc) prepare kansai-population\
		--input $(kyoto)/data/census_kansai_region.csv\
		--shp $(kyoto)/data/kansai-region.gpkg\
		--postal-shp $(kyoto)/data/postalcodes.gpkg\
		--income $(confidential)/data/income_distribution.csv\
		--facilities $< --facilities-attr all\
		--output $@


# Assigns daily activity chains including locations
input/$V/$N-activities-$V-10pct.plans.xml.gz: input/$V/$N-static-$V-10pct.plans.xml.gz input/$V/$N-$V-facilities.xml.gz input/$V/$N-$V-network.xml.gz
	$(sc) prepare create-daily-plans --input $< --output $@\
	 --persons src/main/python/table-persons.csv\
  	 --activities src/main/python/table-activities.csv\
  	 --commuter src/main/python/work-commuter.csv\
	 --shp $(kyoto)/data/postalcodes.gpkg\
	 --facilities $(word 2,$^)\
	 --network $(word 3,$^)\


input/$V/$N-$V-10pct.plans-initial.xml.gz: input/$V/$N-activities-$V-10pct.plans.xml.gz input/$V/$N-$V-facilities.xml.gz input/$V/$N-$V-network.xml.gz

	$(sc) prepare filter-relevant-agents\
	 --input $< --output $@\
	 --input-crs $(CRS)\
	 --shp input/area.gpkg\
	 --facilities $(word 2,$^)\
	 --network $(word 3,$^)

	$(sc) prepare split-activity-types-duration\
 	 --exclude commercial_start,commercial_end,freight_start,freight_end\
	 --input $@ --output $@

	$(sc) prepare set-car-avail --input $@ --output $@

	$(sc) prepare check-car-avail --input $@ --output $@ --mode walk

	$(sc) prepare fix-subtour-modes --input $@ --output $@ --coord-dist 100

	$(sc) prepare downsample-population $@\
		 --sample-size 0.1\
		 --samples 0.03 0.01\


# Aggregated target for input plans to calibration
prepare: input/$V/$N-$V-10pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
	echo "Done"