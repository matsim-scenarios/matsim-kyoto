package org.matsim.prepare.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.SplittableRandom;

@CommandLine.Command(
	name = "create-daily-plans",
	description = "Create daily plans for population."
)
public class CreateDailyPlans implements MATSimAppCommand, PersonAlgorithm {

	private static final Logger log = LogManager.getLogger(CreateDailyPlans.class);

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

	private ThreadLocal<SplittableRandom> rnd;

	public static void main(String[] args) {
		new CreateDailyPlans().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (!shp.isDefined()) {
			log.error("Shape file with zones is required.");
			return 2;
		}

		Population population = PopulationUtils.readPopulation(input.toString());

		rnd = ThreadLocal.withInitial(() -> new SplittableRandom(seed));

		ParallelPersonAlgorithmUtils.run(population, 8, this);

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	@Override
	public void run(Person person) {

		// TODO: map zones to shape file

	}
}
