
N := kyoto
V := v1.0
CRS := EPSG:32653

MEMORY ?= 20G
JAR := matsim-$(N)-*.jar

kyoto := ../public-svn/matsim/scenarios/countries/jp/kyoto

osmosis := osmosis/bin/osmosis

# Scenario creation tool
sc := java -Xmx$(MEMORY) -XX:+UseParallelGC -cp $(JAR) org.matsim.prepare.RunOpenKyotoCalibration

.PHONY: prepare

$(JAR):
	mvn package

# Required files
input/kansai.osm.pbf:
	curl https://download.geofabrik.de/asia/japan/kansai-210101.osm.pbf -o $@

input/network.osm: input/kansai.osm.pbf

	# FIXME: Adjust level of details and area

	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="../shared-svn/projects/$N/data/area.poly"\
	 --used-node --wb input/network-detailed.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction\
	 --bounding-box top=51.92 left=11.45 bottom=50.83 right=13.36\
	 --used-node --wb input/network-coarse.osm.pbf

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
	 --used-node --wb input/network-germany.osm.pbf

	$(osmosis) --rb file=input/network-germany.osm.pbf --rb file=input/network-coarse.osm.pbf --rb file=input/network-detailed.osm.pbf\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm input/network-detailed.osm.pbf
	rm input/network-coarse.osm.pbf
	rm input/network-germany.osm.pbf


input/sumo.net.xml: input/network.osm

	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=53 +ellps=WGS84 +datum=WGS84 +units=m +no_defs"\
	 --osm-files $< -o=$@


input/$V/$N-$V-network.xml.gz:
	$(sc) prepare network-from-sumo $(kyoto)/data/Kyoto_Network_C_2021/network_C.net.xml\
	  --target-crs $(CRS) --output $@


input/$V/$N-$V-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	# FIXME: Adjust GTFS

	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2021-08-18" --target-crs $(CRS) \
	 ../shared-svn/projects/$N/data/20210816_regio.zip\
	 ../shared-svn/projects/$N/data/20210816_train_short.zip\
	 ../shared-svn/projects/$N/data/20210816_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp ../shared-svn/projects/$N/data/pt-area/pt-area.shp\
	 --shp ../shared-svn/projects/$N/data/Bayern.zip\
	 --shp ../shared-svn/projects/$N/data/germany-area/germany-area.shp\


input/facilities.gpkg: input/kansai.osm.pbf
	$(sc) prepare facility-shp\
	 --activity-mapping input/activity_mapping.json\
	 --input $<\
	 --target-crs $(CRS)\
	 --output $@

input/$V/$N-$V-facilities.xml.gz: input/$V/$N-$V-network.xml.gz input/facilities.gpkg
	$(sc) prepare facilities --network $< --shp $(word 2,$^)\
	 --output $@

# Static population only contains the home locations
input/$V/$N-static-$V-10pct.plans.xml.gz: $(kyoto)/data/census_kansai_region.csv $(kyoto)/data/kansai-region-epsg4612.gpkg input/facilities.gpkg
	$(sc) prepare kyoto-population\
		--input $<\
		--shp $(word 2,$^) --shp-crs $(CRS)\
		--facilities $(word 3,$^) --facilities-attr resident\
		--output $@


# Assigns daily activity chains (without locations)
$p/$N-activities-$V-10pct.plans.xml.gz: input/$V/$N-static-$V-25pct.plans.xml.gz input/$V/$N-$V-facilities.xml.gz input/$V/$N-$V-network.xml.gz
	$(sc) prepare activity-sampling --seed 1 --input $< --output $@ --persons src/main/python/table-persons.csv --activities src/main/python/table-activities.csv

	$(sc) prepare assign-reference-population --population $@ --output $@\
	 --persons src/main/python/table-persons.csv\
  	 --activities src/main/python/table-activities.csv\
  	 --shp $(germany)/../matsim-berlin/data/SrV/zones/zones.shp\
  	 --shp-crs $(CRS)\
	 --facilities $(word 2,$^)\
	 --network $(word 3,$^)\


# Assigns locations to the activities
$p/berlin-initial-$V-10pct.plans.xml.gz: $p/$N-activities-$V-25pct.plans.xml.gz input/$V/$N-$V-facilities.xml.gz input/$V/$N-$V-network.xml.gz
	$(sc) prepare init-location-choice\
	 --input $<\
	 --output $@\
	 --facilities $(word 2,$^)\
	 --network $(word 3,$^)\
	 --shp $(germany)/vg5000/vg5000_ebenen_0101/VG5000_GEM.shp\
	 --commuter $(germany)/regionalstatistik/commuter.csv\

	# For debugging and visualization
	$(sc) prepare downsample-population $@\
		 --sample-size 0.25\
		 --samples 0.1 0.03 0.01\


# Aggregated target
# TODO:
#prepare: input/$V/$N-$V-25pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
#	echo "Done"