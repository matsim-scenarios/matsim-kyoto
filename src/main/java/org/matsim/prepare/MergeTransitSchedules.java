package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicles;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "merge-transit-schedules",
	description = "Merge multiple transit schedules and vehicles together."
)
public class MergeTransitSchedules implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(MergeTransitSchedules.class);

	@CommandLine.Option(names = "--input-schedules", description = "Path to input schedules", arity = "2..*", required = true)
	private List<String> schedules;

	@CommandLine.Option(names = "--input-vehicles", description = "Path to input vehicles", arity = "1..*", required = true)
	private List<String> vehicles;

	@CommandLine.Option(names = "--output-schedule", description = "Path to output", required = true)
	private Path outputSchedule;

	@CommandLine.Option(names = "--output-vehicles", description = "Path to output", required = true)
	private Path outputVehicles;

	public static void main(String[] args) {
		new MergeTransitSchedules().execute(args);
	}

	@Override
	public Integer call() {

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);

		// Read all schedules
		for (String schedulePath : schedules) {
			log.info("Reading transit schedule file: {}", schedulePath);

			new TransitScheduleReader(scenario).readFile(schedulePath);
		}

		// Read all vehicles
		Vehicles mergedVehicles = scenario.getTransitVehicles();
		for (String vehiclePath : vehicles) {
			log.info("Reading transit vehicles file: {}", vehiclePath);

			new MatsimVehicleReader.VehicleReader(mergedVehicles).readFile(vehiclePath);
		}


		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(outputSchedule.toString());
		new MatsimVehicleWriter(mergedVehicles).writeFile(outputVehicles.toString());

		return 0;
	}

}
