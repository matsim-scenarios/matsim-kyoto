package org.matsim.prepare.population;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.population.TripAnalysis;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.facilities.ActivityFacility;
import org.matsim.prepare.facilities.AttributedActivityFacility;
import org.matsim.run.OpenKyotoScenario;
import picocli.CommandLine;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.matsim.prepare.facilities.CreateMATSimFacilities.IGNORED_LINK_TYPES;


@CommandLine.Command(
	name = "create-daily-plans",
	description = "Create daily plans for population."
)
public class CreateDailyPlans implements MATSimAppCommand, PersonAlgorithm {

	private static final Logger log = LogManager.getLogger(CreateDailyPlans.class);
	private final Map<String, CSVRecord> persons = new HashMap<>();
	private final Map<Key, List<String>> groups = new HashMap<>();
	private final AtomicInteger counter = new AtomicInteger();
	@CommandLine.Option(names = "--input", description = "Path to input population.")
	private Path input;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;
	@CommandLine.Option(names = "--persons", description = "Path to person table", required = true)
	private Path personsPath;
	@CommandLine.Option(names = "--activities", description = "Path to activity table", required = true)
	private Path activityPath;
	@CommandLine.Option(names = "--facilities", description = "Path to MATSim facilities", required = true)
	private Path facilityPath;
	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private Path networkPath;
	@CommandLine.Option(names = "--commuter", description = "Path to commuter csv file", required = true)
	private Path commuterPath;
	@CommandLine.Option(names = "--seed", description = "Seed used to sample locations", defaultValue = "1")
	private long seed;
	@CommandLine.Mixin
	private ShpOptions shp;

	private Population population;
	private FacilityIndex facilities;
	private Network network;
	private Map<String, List<CSVRecord>> activities;
	private Map<String, Geometry> zones;
	private Object2DoubleMap<Pair<String, String>> commuter;
	private PlanBuilder planBuilder;
	private ProgressBar pb;

	public static void main(String[] args) {
		new CreateDailyPlans().execute(args);
	}

	private static String getZone(String location, String zone) {
		if (zone.isBlank() || zone.equals(location))
			return location;
		else
			// The last two digits of the postal code are not known
			return location + "_" + zone.substring(0, zone.length() - 2);
	}

	/**
	 * Initializes random number generator with person specific seed.
	 */
	private SplittableRandom initRandomNumberGenerator(Person person) {
		BigInteger i = new BigInteger(person.getId().toString().getBytes());
		return new SplittableRandom(i.longValue() + seed);
	}

	@Override
	public Integer call() throws Exception {

		if (!shp.isDefined()) {
			log.error("Shape file with zones is required.");
			return 2;
		}

		zones = readZones(shp);
		activities = RunActivitySampling.readActivities(activityPath);

		facilities = new FacilityIndex(facilityPath.toString(), createFacilityFilter(), OpenKyotoScenario.CRS);
		planBuilder = new PlanBuilder(createZoneSelector());

		// Remove activities with missing leg duration
		activities.values().removeIf(
			rows -> rows.stream().anyMatch(act -> act.get("leg_duration").isBlank() || act.get("duration").isBlank())
		);

		log.info("Got {} persons after cleaning", activities.size());

		try (CSVParser csv = CSVParser.parse(personsPath, StandardCharsets.UTF_8,
			CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
			readPersons(csv);
		}

		commuter = new Object2DoubleOpenHashMap<>();
		try (CSVParser csv = CSVParser.parse(commuterPath, StandardCharsets.UTF_8,
			CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
			for (CSVRecord r : csv) {
				commuter.put(Pair.of(r.get("home"), r.get("work")), Double.parseDouble(r.get("n")));
			}
		}

		network = NetworkUtils.readNetwork(networkPath.toString());
		population = PopulationUtils.readPopulation(input.toString());

		pb = new ProgressBar("Creating daily plans", population.getPersons().size());

		ParallelPersonAlgorithmUtils.run(population, 8, this);

		PopulationUtils.writePopulation(population, output.toString());

		log.info("Matched {} out of {} persons. Remaining were sampled.", counter.get(), population.getPersons().size());

		return 0;
	}

	/**
	 * Assigns zones to facilities.
	 */
	private Predicate<ActivityFacility> createFacilityFilter() {

		STRtree index = new STRtree();
		for (Map.Entry<String, Geometry> e : zones.entrySet()) {
			if (e.getKey().contains("_"))
				continue;

			index.insert(e.getValue().getEnvelopeInternal(), e);
		}
		index.build();

		return facility -> {
			Point point = MGC.coord2Point(facility.getCoord());
			List<Map.Entry<String, Geometry>> matches = index.query(point.getEnvelopeInternal());
			for (Map.Entry<String, Geometry> match : matches) {
				if (match.getValue().contains(point)) {
					facility.getAttributes().putAttribute("location", match.getKey());
				}
			}

			return true;
		};
	}

	/**
	 * Build map of zones to facilities.
	 */
	private Function<CSVRecord, Set<ActivityFacility>> createZoneSelector() {

		STRtree index = new STRtree();
		for (Map.Entry<String, Geometry> e : zones.entrySet()) {
			index.insert(e.getValue().getEnvelopeInternal(), e);
		}
		index.build();

		// Contains all activities for a zone
		Map<String, Set<ActivityFacility>> zoneFacilities = new HashMap<>();
		for (ActivityFacility facility : facilities.all.getFacilities().values()) {
			Point point = MGC.coord2Point(facility.getCoord());
			List<Map.Entry<String, Geometry>> matches = index.query(point.getEnvelopeInternal());
			for (Map.Entry<String, Geometry> match : matches) {
				if (match.getValue().contains(point)) {
					zoneFacilities.computeIfAbsent(match.getKey(), k -> new HashSet<>()).add(facility);
					break;
				}
			}
		}

		return row -> {
			String zone = getZone(row.get("location"), row.get("zone"));
			return zoneFacilities.getOrDefault(zone, Collections.emptySet());
		};
	}

	@Override
	public void run(Person person) {

		SplittableRandom rnd = initRandomNumberGenerator(person);

		Coord homeCoord = Attributes.getHomeCoord(person);

		Zone matched = Zone.FULL;
		String personId = matchPerson(rnd, createKey(person, Zone.FULL));
		if (personId == null) {
			matched = Zone.CITY;
			personId = matchPerson(rnd, createKey(person, Zone.CITY));
		}
		if (personId == null) {
			matched = Zone.NONE;
			personId = matchPerson(rnd, createKey(person, Zone.NONE));
		}

		if (personId == null) {
			log.warn("No matching person found for {}", createKey(person, Zone.NONE));
			return;
		}

		CSVRecord row = persons.get(personId);
		List<CSVRecord> activities = Objects.requireNonNull(this.activities.get(personId), "No activities found");

		String mobile = row.get("mobile_on_day");

		// Nothing to do for persons without activities
		if (mobile.equalsIgnoreCase("false")) {
			counter.incrementAndGet();
			pb.step();
			return;
		}

		Plan plan = RunActivitySampling.createPlan(homeCoord, activities, rnd, population.getFactory());

		boolean fullMatch = false;

		// Persons with high matching quality are added as reference persons
		// These can be used for mode choice calibration or analysis
		if (matched != Zone.NONE) {

			fullMatch = planBuilder.assignLocationsFromZones(activities, plan, homeCoord, false, rnd);

			// Match again, this time ignoring facility types
			// Facility information is too sparse in certain areas
			if (!fullMatch) {
				planBuilder.assignLocationsFromZones(activities, plan, homeCoord, true, rnd);
			}

			if (fullMatch) {
				String refModes = TripStructureUtils.getLegs(plan).stream().map(Leg::getMode).collect(Collectors.joining("-"));
				person.getAttributes().putAttribute(TripAnalysis.ATTR_REF_MODES, refModes);
				person.getAttributes().putAttribute(TripAnalysis.ATTR_REF_ID, personId);
				counter.incrementAndGet();
			}
		}

		// Sample suitable locations but only by distance
		if (!fullMatch) {
			sampleLocationsByDist(person, plan, rnd);
		}

		person.removePlan(person.getSelectedPlan());
		person.addPlan(plan);
		person.setSelectedPlan(plan);

		pb.step();
	}

	/**
	 * Select suitable locations with matching distances.
	 */
	private void sampleLocationsByDist(Person person, Plan plan, SplittableRandom rnd) {

		Coord homeCoord = Attributes.getHomeCoord(person);
		String homeZone = Objects.toString(person.getAttributes().getAttribute("city"));

		List<Activity> acts = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

		// Activities that only occur on one place per person
		Map<String, ActivityFacility> fixedLocations = new HashMap<>();

		// keep track of the current coordinate
		Coord lastCoord = homeCoord;

		for (Activity act : acts) {

			if (Attributes.isLinkUnassigned(act.getLinkId())) {

				String type = act.getType();

				act.setLinkId(null);
				ActivityFacility location = null;

				// target leg distance in meter
				double dist = (double) act.getAttributes().getAttribute("orig_dist") * 1000;

				if (fixedLocations.containsKey(type)) {
					location = fixedLocations.get(type);
				}

				if (location == null) {
					// Needed for lambda
					final Coord refCoord = lastCoord;

					// Unknown activity will use any work location
					STRtree index = facilities.index.containsKey(type) ? facilities.index.get(type) : facilities.index.get("work");

					// Distance should be within the bounds
					for (Double b : DoubleList.of(1, 1.2, 1.5)) {
						List<AttributedActivityFacility> query = index.query(MGC.coord2Point(lastCoord).buffer(dist * (b + 0.5) + 250).getEnvelopeInternal());
						List<AttributedActivityFacility> res = query.stream().filter(f -> checkDistanceBound(dist, refCoord, f.getCoord(), b)).toList();

						if (!res.isEmpty()) {
							if (type.equals("work")) {

								// Sample a location using the commuting as weight
								location = FacilityIndex.sampleWithGrouping(res,
									f -> Objects.requireNonNullElse(f.getLocation(), "na"),
									// Use a minimum weight of 1 so that all locations have a chance to be chosen
									e -> Math.max(1, commuter.getDouble(Pair.of(homeZone, e.getKey()))),
									rnd);
							} else
								location = res.get(rnd.nextInt(res.size()));

							break;
						}
					}
				}

				if (location == null) {
					// sample only coordinate if nothing else is possible
					Coord c = sampleLink(rnd, dist, lastCoord);
					act.setCoord(c);
					lastCoord = c;
					continue;
				}

				if (type.equals("work") || type.startsWith("edu"))
					fixedLocations.put(type, location);

				act.setFacilityId(location.getId());
			}

			if (act.getCoord() != null)
				lastCoord = act.getCoord();
			else if (act.getFacilityId() != null)
				lastCoord = facilities.all.getFacilities().get(act.getFacilityId()).getCoord();

		}

	}

	/**
	 * Sample a coordinate for which the associated link is not one of the ignored types.
	 */
	private Coord sampleLink(SplittableRandom rnd, double dist, Coord origin) {

		Coord coord = null;
		for (int i = 0; i < 500; i++) {
			coord = InitLocationChoice.rndCoord(rnd, dist, origin);
			Link link = NetworkUtils.getNearestLink(network, coord);
			if (!IGNORED_LINK_TYPES.contains(NetworkUtils.getType(link)))
				break;
		}

		return coord;
	}

	/**
	 * General logic to filter coordinate within target distance.
	 */
	private boolean checkDistanceBound(double target, Coord refCoord, Coord other, double factor) {
		double lower = target * 0.8 * (2 - factor) - 250;
		double upper = target * 1.15 * factor + 250;

		double dist = CoordUtils.calcEuclideanDistance(refCoord, other);
		return dist >= lower && dist <= upper;
	}

	/**
	 * Read zones ussd in the survey.
	 */
	private Map<String, Geometry> readZones(ShpOptions shp) {

		Map<String, Geometry> result = new HashMap<>();

		// Collect all zones of a city
		Map<String, List<Geometry>> cities = new HashMap<>();

		for (SimpleFeature feature : shp.readFeatures()) {

			String city = (String) feature.getAttribute("jichi_code");
			String zone = (String) feature.getAttribute("zip_pre") + feature.getAttribute("zip_mid");

			result.put(city + "_" + zone, (Geometry) feature.getDefaultGeometry());
			cities.computeIfAbsent(city, (k) -> new ArrayList<>()).add((Geometry) feature.getDefaultGeometry());
		}

		// Add city level zones
		cities.forEach((city, geoms) -> {
			Geometry cityGeom = geoms.get(0);
			for (int i = 1; i < geoms.size(); i++) {
				cityGeom = cityGeom.union(geoms.get(i));
			}
			result.put(city, cityGeom);
		});

		log.info("Read {} zones", result.size());

		return result;
	}

	/**
	 * Create subpopulations for sampling.
	 */
	private void readPersons(CSVParser csv) {

		int i = 0;
		int skipped = 0;

		for (CSVRecord r : csv) {

			String idx = r.get("p_id");
			String gender = r.get("gender");
			int age = Integer.parseInt(r.get("age"));

			if (!activities.containsKey(idx)) {
				skipped++;
				continue;
			}

			Stream<Key> keys = createKey(gender, age, r.get("location"), r.get("zone"));
			keys.forEach(key -> {
				groups.computeIfAbsent(key, (k) -> new ArrayList<>()).add(idx);

				// Alternative keys with different zones
				groups.computeIfAbsent(new Key(key.gender, key.age, r.get("location")), (k) -> new ArrayList<>()).add(idx);
				groups.computeIfAbsent(new Key(key.gender, key.age, null), (k) -> new ArrayList<>()).add(idx);

			});
			persons.put(idx, r);
			i++;
		}

		log.info("Read {} persons from csv. Skipped {} invalid persons", i, skipped);
	}

	/**
	 * Match person attributes with person from survey data.
	 *
	 * @return daily activities
	 */
	private String matchPerson(SplittableRandom rnd, Key key) {
		List<String> subgroup = groups.get(key);
		if (subgroup == null) {
			return null;
		}

		if (subgroup.size() < 5)
			return null;

		// TODO: needs to be weighted matching
		// weights can be preprocessed after reading in

		return subgroup.get(rnd.nextInt(subgroup.size()));
	}

	private Stream<Key> createKey(String gender, int age, String location, String zone) {

		String homeZone = getZone(location, zone);

		if (age <= 10) {
			return IntStream.rangeClosed(0, 10).mapToObj(i -> new Key(null, i, homeZone));
		}
		if (age < 18) {
			return IntStream.rangeClosed(11, 18).mapToObj(i -> new Key(gender, i, homeZone));
		}

		int min = Math.max(18, age - 6);
		int max = Math.min(65, age + 6);

		// larger groups for older people
		if (age > 65) {
			min = Math.max(66, age - 10);
			max = Math.min(105, age + 10);
		}

		return IntStream.rangeClosed(min, max).mapToObj(i -> new Key(gender, i, homeZone));
	}

	private Key createKey(Person person, Zone zone) {

		Integer age = PersonUtils.getAge(person);
		String gender = PersonUtils.getSex(person);
		if (age <= 10)
			gender = null;

		String homeZone = switch (zone) {
			case FULL -> person.getAttributes().getAttribute("city") + "_" + person.getAttributes().getAttribute("postal");
			case CITY -> Objects.toString(person.getAttributes().getAttribute("city"));
			case NONE -> null;
		};

		return new Key(gender, age, homeZone);
	}

	private enum Zone {
		FULL, CITY, NONE
	}

	/**
	 * Key used to match persons.
	 */
	public record Key(String gender, int age, String homeZone) {
	}

}
