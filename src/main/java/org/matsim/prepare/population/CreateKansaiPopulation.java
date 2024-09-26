package org.matsim.prepare.population;

import it.unimi.dsi.fastutil.doubles.Double2DoubleLinkedOpenHashMap;
import it.unimi.dsi.fastutil.doubles.Double2DoubleMap;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
import org.matsim.run.OpenKyotoScenario;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.*;

@CommandLine.Command(
	name = "kansai-population",
	description = "Create synthetic population for kansai region."
)
public class CreateKansaiPopulation implements MATSimAppCommand {

	// Define the tax brackets and corresponding rates
	private static final double[] TAX_BRACKETS = {1_950_000, 3_300_000, 6_950_000, 9_000_000, 18_000_000, 40_000_000};
	private static final double[] TAX_RATES = {0.05, 0.1, 0.2, 0.23, 0.33, 0.4, 0.45};

	private static final Logger log = LogManager.getLogger(CreateKansaiPopulation.class);

	@CommandLine.Option(names = "--input", description = "Path to input csv data", required = true)
	private Path input;

	@CommandLine.Mixin
	private FacilityOptions facilities = new FacilityOptions();

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--postal-shp", description = "Path to postal code shape file", required = true)
	private String postalShp;

	@CommandLine.Option(names = "--income", description = "Path to income csv", required = true)
	private String incomePath;

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	@CommandLine.Option(names = "--sample", description = "Sample size to generate", defaultValue = "0.1")
	private double sample;

	private Map<String, MultiPolygon> zones;
	private SplittableRandom rnd;
	private Population population;
	private ShpOptions.Index postalIndex;

	/**
	 * Map of city code to income distribution.
	 */
	private Int2ObjectMap<EnumeratedAttributeDistribution<Double>> income;
	private CoordinateTransformation ct;

	public static void main(String[] args) {
		new CreateKansaiPopulation().execute(args);
	}

	private static int parseInt(String s) {
		return s.equals("-") || s.isBlank() ? 0 : Integer.parseInt(s);
	}

	private static double parseDouble(String s) {
		return s.equals("-") || s.isBlank() ? 0.0 : Double.parseDouble(s);
	}

	/**
	 * Calculate income after tax, for one individual person. For household income this can not be applied.
	 */
	public static double calculateIncomeAfterTax(double income) {
		double tax = 0.0;
		double previousBracket = 0.0;

		for (int i = 0; i < TAX_BRACKETS.length; i++) {
			if (income > TAX_BRACKETS[i]) {
				tax += (TAX_BRACKETS[i] - previousBracket) * TAX_RATES[i];
				previousBracket = TAX_BRACKETS[i];
			} else {
				tax += (income - previousBracket) * TAX_RATES[i];
				return income - tax;
			}
		}

		// If income is above the highest bracket
		tax += (income - previousBracket) * TAX_RATES[TAX_RATES.length - 1];
		return income - tax;
	}

	@Override
	@SuppressWarnings("IllegalCatch")
	public Integer call() throws Exception {

		if (!shp.isDefined()) {
			log.error("Shape file with zones is required.");
			return 2;
		}

		List<SimpleFeature> fts = shp.readFeatures();

		rnd = new SplittableRandom(0);
		zones = new HashMap<>();
		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		ct = shp.createInverseTransformation(OpenKyotoScenario.CRS);

		// Collect all LORs
		for (SimpleFeature ft : fts) {
			zones.put((String) ft.getAttribute("KEY_CODE"), (MultiPolygon) ft.getDefaultGeometry());
		}

		log.info("Found {} zones", zones.size());

		postalIndex = new ShpOptions(postalShp, null, null).createIndex(OpenKyotoScenario.CRS, "_");
		income = readIncomeDistribution(incomePath);

		CSVFormat.Builder format = CSVFormat.DEFAULT.builder().setDelimiter(',').setHeader().setSkipHeaderRecord(true);

		try (CSVParser reader = new CSVParser(Files.newBufferedReader(input), format.build())) {

			for (CSVRecord row : ProgressBar.wrap(reader.getRecords(), "Zones")) {
				try {
					processZone(row);
				} catch (RuntimeException e) {
					log.error("Error processing zone", e);
					log.error(row.toString());
				}
			}
		}

		log.info("Generated {} persons", population.getPersons().size());

		PopulationUtils.sortPersons(population);

		ProjectionUtils.putCRS(population, OpenKyotoScenario.CRS);
		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	/**
	 * Read income distribution from csv file.
	 */
	private Int2ObjectMap<EnumeratedAttributeDistribution<Double>> readIncomeDistribution(String incomePath) throws IOException {

		Int2ObjectMap<EnumeratedAttributeDistribution<Double>> result = new Int2ObjectOpenHashMap<>();

		CSVFormat.Builder format = CSVFormat.DEFAULT.builder().setDelimiter(',').setHeader().setSkipHeaderRecord(true);

		try (CSVParser csv = format.build().parse(Files.newBufferedReader(Path.of(incomePath)))) {
			for (CSVRecord row : csv) {

				DoubleList incomes = new DoubleArrayList();

				for (int i = 2; i < row.size(); i++) {
					String value = row.get(i).replace(",", "");
					incomes.add(value.equals("-") ? 0 : parseDouble(value));
				}

				double total = incomes.doubleStream().sum();

				// probabilities
				Double2DoubleMap p = new Double2DoubleLinkedOpenHashMap();

				// Group data depends on groups in the input
				for (int i = 0; i < incomes.size(); i++) {
					double income = 250_000 + 500_000 * (i + 1);

					// number of people in the group
					double n = incomes.getDouble(i);

					// yearly income is converted to monthly income
					p.put(income / 12, n / total);
				}

				EnumeratedAttributeDistribution<Double> dist = new EnumeratedAttributeDistribution<>(p);
				result.put(Integer.parseInt(row.get("city town code")), dist);
			}
		}

		return result;
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


		// Employment is not used, because it can not be used from survey data
		//		double unemployed = parseDouble(row.get("unemployed")) / (parseDouble(row.get("total_em")));
//		EnumeratedAttributeDistribution<Boolean> employment = new EnumeratedAttributeDistribution<>(Map.of(true, 1 - unemployed, false, unemployed));

		EnumeratedAttributeDistribution<AgeGroup> ageGroup = buildAgeDist(row);
		EnumeratedAttributeDistribution<String> sex = new EnumeratedAttributeDistribution<>(Map.of("f", womenQuota, "m", 1 - womenQuota));

		MultiPolygon geom = zones.get(zone);

		PopulationFactory f = population.getFactory();

		double inhabitants = n * sample;

		int tries = 0;

		while (inhabitants > 0) {

			// If the number of inhabitants is less than 1, we use it as a probability
			if (inhabitants < 1) {
				if (rnd.nextDouble() > inhabitants)
					break;
			} else
				inhabitants--;

			Person person = f.createPerson(CreateBerlinPopulation.generateId(population, "p", rnd));
			PersonUtils.setSex(person, sex.sample());
			PopulationUtils.putSubpopulation(person, "person");

			AgeGroup group = ageGroup.sample();

			int age = group.max == Integer.MAX_VALUE ? 100 : rnd.nextInt(group.min, group.max + 1);

			PersonUtils.setAge(person, age);

			Coord coord = ct.transform(CreateBerlinPopulation.sampleHomeCoordinate(geom, OpenKyotoScenario.CRS, facilities, rnd, 100));

			person.getAttributes().putAttribute(Attributes.HOME_X, coord.getX());
			person.getAttributes().putAttribute(Attributes.HOME_Y, coord.getY());

			int cityCode = parseInt(row.get("city code"));
			person.getAttributes().putAttribute("city", cityCode);

			// Some city codes are only available as aggregated data
			if (!income.containsKey(cityCode)) {
				cityCode = (cityCode / 10) * 10;
			}
			if (!income.containsKey(cityCode)) {
				cityCode = (cityCode / 100) * 100;
			}

			EnumeratedAttributeDistribution<Double> incomeDist = Objects.requireNonNull(income.get(cityCode));

			PersonUtils.setIncome(person, incomeDist.sample());

			SimpleFeature ft = postalIndex.queryFeature(coord);

			// Skip and generate another person if no postal code is found
			if (ft == null && tries++ < 10) {
				inhabitants++;
				continue;
			} else {
				String postal = ft == null ? "NA" : Objects.toString(ft.getAttribute("zip_pre")) + ft.getAttribute("zip_mid");
				person.getAttributes().putAttribute("postal", postal);
			}
			tries = 0;


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
