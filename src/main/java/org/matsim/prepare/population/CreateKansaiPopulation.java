package org.matsim.prepare.population;

import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.MultiPolygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.run.OpenBerlinScenario;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.*;

@CommandLine.Command(
	name = "kansai-population",
	description = "Create synthetic population for kansai region."
)
public class CreateKansaiPopulation implements MATSimAppCommand {

	private static final NumberFormat FMT = NumberFormat.getInstance(Locale.GERMAN);

	private static final Logger log = LogManager.getLogger(CreateBerlinPopulation.class);
	private final CoordinateTransformation ct = new GeotoolsTransformation("EPSG:32653", "EPSG:32653");

	@CommandLine.Option(names = "--input", description = "Path to input csv data", required = true)
	private Path input;

	@CommandLine.Mixin
	private FacilityOptions facilities = new FacilityOptions();

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	@CommandLine.Option(names = "--sample", description = "Sample size to generate", defaultValue = "0.1")
	private double sample;

	private Map<String, MultiPolygon> zones;
	private SplittableRandom rnd;
	private Population population;

	public static void main(String[] args) {
		new CreateKansaiPopulation().execute(args);
	}

	private static int parseInt(String s) {
		return s.equals("-") || s.isBlank() ? 0 : Integer.parseInt(s);
	}

	private static double parseDouble(String s) {
		return s.equals("-") || s.isBlank() ? 0.0 : Double.parseDouble(s);
	}

	@Override
	@SuppressWarnings("IllegalCatch")
	public Integer call() throws Exception {

		if (!shp.isDefined()) {
			log.error("Shape file with LOR zones is required.");
			return 2;
		}

		List<SimpleFeature> fts = shp.readFeatures();

		rnd = new SplittableRandom(0);
		zones = new HashMap<>();
		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		// Collect all LORs
		for (SimpleFeature ft : fts) {
			zones.put((String) ft.getAttribute("KEY_CODE"), (MultiPolygon) ft.getDefaultGeometry());
		}

		log.info("Found {} zones", zones.size());

		CSVFormat.Builder format = CSVFormat.DEFAULT.builder().setDelimiter(',').setHeader().setSkipHeaderRecord(true);

		try (CSVParser reader = new CSVParser(Files.newBufferedReader(input), format.build())) {

			for (CSVRecord row : ProgressBar.wrap(reader.getRecords(), "Zones")) {
				try {
					processZone(row);
				} catch (RuntimeException e) {
					log.error("Error processing lor", e);
					log.error(row.toString());
				}
			}
		}

		log.info("Generated {} persons", population.getPersons().size());

		PopulationUtils.sortPersons(population);

		ProjectionUtils.putCRS(population, OpenBerlinScenario.CRS);
		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private EnumeratedAttributeDistribution<AgeGroup> buildAgeDist(CSVRecord row) {

		// Number of entries in the age columns
		int total_age = parseInt(row.get("total_age"));

		Map<AgeGroup, Double> p = new HashMap<>();

		p.put(new AgeGroup(0, 4), parseDouble(row.get("0~4")) / total_age);
		p.put(new AgeGroup(5, 9), parseDouble(row.get("5~9")) / total_age);
		p.put(new AgeGroup(10, 14), parseDouble(row.get("10~14")) / total_age);
		p.put(new AgeGroup(15, 19), parseDouble(row.get("15~19")) / total_age);
		p.put(new AgeGroup(20, 24), parseDouble(row.get("20~24")) / total_age);
		p.put(new AgeGroup(25, 29), parseDouble(row.get("25~29")) / total_age);
		p.put(new AgeGroup(30, 34), parseDouble(row.get("30~34")) / total_age);
		p.put(new AgeGroup(35, 39), parseDouble(row.get("35~39")) / total_age);
		p.put(new AgeGroup(40, 44), parseDouble(row.get("40~44")) / total_age);
		p.put(new AgeGroup(45, 49), parseDouble(row.get("45~49")) / total_age);
		p.put(new AgeGroup(50, 54), parseDouble(row.get("50~54")) / total_age);
		p.put(new AgeGroup(55, 59), parseDouble(row.get("55~59")) / total_age);
		p.put(new AgeGroup(60, 64), parseDouble(row.get("60~64")) / total_age);
		p.put(new AgeGroup(65, 69), parseDouble(row.get("65~69")) / total_age);
		p.put(new AgeGroup(70, 74), parseDouble(row.get("70~74")) / total_age);
		p.put(new AgeGroup(75, 79), parseDouble(row.get("75~79")) / total_age);
		p.put(new AgeGroup(80, 84), parseDouble(row.get("80~84")) / total_age);
		p.put(new AgeGroup(85, 89), parseDouble(row.get("85~89")) / total_age);
		p.put(new AgeGroup(90, 94), parseDouble(row.get("90~94")) / total_age);
		p.put(new AgeGroup(95, 99), parseDouble(row.get("95~99")) / total_age);
		p.put(new AgeGroup(100, Integer.MAX_VALUE), parseDouble(row.get("100~")) / total_age);

		return new EnumeratedAttributeDistribution<>(p);
	}

	private void processZone(CSVRecord row) throws ParseException {

		String zone = row.get("citytown code").strip();

		// The census contains aggregated zonal information as well
		// These zones are not contained in the shape file
		if (!zones.containsKey(zone)) {
			return;
		}

		// Row with no data
		if (row.get("total_gender").equals("-") || row.get("total_gender").equals("X"))
			return;

		// TODO: some row have inhabitants but no age or employment data

		int n = parseInt(row.get("total_gender")) + parseInt(row.get("foreigners"));

		// x women for 100 men
		double women = parseDouble(row.get("female"));
		double men = parseDouble(row.get("male"));
		double womenQuota = women / (men + women);

		double unemployed = parseDouble(row.get("unemployed")) / (parseDouble(row.get("total_em")));

		EnumeratedAttributeDistribution<String> sex = new EnumeratedAttributeDistribution<>(Map.of("f", womenQuota, "m", 1 - womenQuota));
		EnumeratedAttributeDistribution<Boolean> employment = new EnumeratedAttributeDistribution<>(Map.of(true, 1 - unemployed, false, unemployed));
		EnumeratedAttributeDistribution<AgeGroup> ageGroup = buildAgeDist(row);

		MultiPolygon geom = zones.get(zone);

		PopulationFactory f = population.getFactory();

		double inhabitants = n * sample;

		while (inhabitants > 0) {

			// If the number of inhabitants is less than 1, we use it as a probability
			if (inhabitants < 1) {
				if (rnd.nextDouble() > inhabitants)
					break;
			} else
				inhabitants--;

			Person person = f.createPerson(CreateBerlinPopulation.generateId(population, "", rnd));
			PersonUtils.setSex(person, sex.sample());
			PopulationUtils.putSubpopulation(person, "person");

			AgeGroup group = ageGroup.sample();

			int age = group.max == Integer.MAX_VALUE ? 100 : rnd.nextInt(group.min, group.max + 1);


			PersonUtils.setAge(person, age);
			if (age >= 15)
				PersonUtils.setEmployed(person, employment.sample());

			Coord coord = ct.transform(CreateBerlinPopulation.sampleHomeCoordinate(geom, "EPSG:32653", facilities, rnd));

			person.getAttributes().putAttribute(Attributes.HOME_X, coord.getX());
			person.getAttributes().putAttribute(Attributes.HOME_Y, coord.getY());

			person.getAttributes().putAttribute("city", parseInt(row.get("city code")));

			Plan plan = f.createPlan();
			plan.addActivity(f.createActivityFromCoord("home", coord));

			person.addPlan(plan);
			person.setSelectedPlan(plan);

			population.addPerson(person);
		}
	}

	/**
	 * Age group where both min and max are inclusive.
	 */
	record AgeGroup(int min, int max) {
	}

}
