package org.matsim.prepare.population;

import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import picocli.CommandLine;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@CommandLine.Command(
	name = "create-daily-plans",
	description = "Create daily plans for population."
)
public class CreateDailyPlans implements MATSimAppCommand, PersonAlgorithm {

	private static final Logger log = LogManager.getLogger(CreateDailyPlans.class);
	private final Map<String, CSVRecord> persons = new HashMap<>();
	private final Map<Key, List<String>> groups = new HashMap<>();
	@CommandLine.Option(names = "--input", description = "Path to input population.")
	private Path input;
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;
	@CommandLine.Option(names = "--persons", description = "Path to person table", required = true)
	private Path personsPath;
	@CommandLine.Option(names = "--activities", description = "Path to activity table", required = true)
	private Path activityPath;
	@CommandLine.Option(names = "--facilities", description = "Path to facilities file", required = true)
	private Path facilityPath;
	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private Path networkPath;
	@CommandLine.Option(names = "--seed", description = "Seed used to sample locations", defaultValue = "1")
	private long seed;
	@CommandLine.Mixin
	private ShpOptions shp;
	private Population population;
	private Map<String, List<CSVRecord>> activities;
	private ProgressBar pb;

	public static void main(String[] args) {
		new CreateDailyPlans().execute(args);
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

		// TODO: map zones to shape file

		activities = RunActivitySampling.readActivities(activityPath);

		// Remove activities with missing leg duration
		activities.values().removeIf(
			rows -> rows.stream().anyMatch(act -> act.get("leg_duration").isBlank() || act.get("duration").isBlank())
		);

		log.info("Got {} persons after cleaning", activities.size());

		try (CSVParser csv = CSVParser.parse(personsPath, StandardCharsets.UTF_8,
			CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
			readPersons(csv);
		}

		population = PopulationUtils.readPopulation(input.toString());

		pb = new ProgressBar("Creating daily plans", population.getPersons().size());

		ParallelPersonAlgorithmUtils.run(population, 8, this);

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	@Override
	public void run(Person person) {

		SplittableRandom rnd = initRandomNumberGenerator(person);

		Coord homeCoord = Attributes.getHomeCoord(person);

		String personId = matchPerson(rnd, createKey(person, Zone.FULL));
		if (personId == null) {
			personId = matchPerson(rnd, createKey(person, Zone.CITY));
		}
		if (personId == null) {
			personId = matchPerson(rnd, createKey(person, Zone.NONE));
		}

		if (personId == null) {
			log.warn("No matching person found for {}", createKey(person, Zone.NONE));
			return;
		}

		List<CSVRecord> activities = Objects.requireNonNull(this.activities.get(personId), "No activities found");

		RunActivitySampling.createPlan(homeCoord, activities, rnd, population.getFactory());

		// TODO: select locations

		// TODO: add reference modes and weights for fully matched persons

		pb.step();
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

		String homeZone;
		if (zone.isBlank() || zone.equals(location))
			homeZone = location;
		else
			// The last two digits of the postal code are not known
			homeZone = location + "_" + zone.substring(0, zone.length() - 2);

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
