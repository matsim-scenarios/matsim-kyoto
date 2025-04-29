package org.matsim.prepare.transit;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import picocli.CommandLine;

import java.util.Map;

@CommandLine.Command(
	name = "transit-vehicles",
	description = "Create transit vehicles from schedule file."
)
public final class PrepareTransitVehicles implements MATSimAppCommand {

	@CommandLine.Option(names = "--schedule", description = "Input transit schedule file", required = true)
	private String scheduleFile;

	@CommandLine.Option(names = "--transit-vehicles", description = "Input transit vehicles file", required = true)
	private String transitVehiclesFile;

	@CommandLine.Option(names = "--output", description = "Output transit vehicles file", required = true)
	private String outputFile;

	@CommandLine.Option(names = "--mode-mapping", description = "Mapping from transport mode to vehicle type", required = true)
	private Map<String, String> modeMapping;

	public static void main(String[] args) {
		new PrepareTransitVehicles().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Scenario scenario = ScenarioUtils.loadScenario(ConfigUtils.createConfig());

		// Read existing vehicles if any
		Vehicles vehicles = scenario.getTransitVehicles();
		if (transitVehiclesFile != null) {
			new MatsimVehicleReader(vehicles).readFile(transitVehiclesFile);
		}

		// Read schedule
		TransitSchedule schedule = scenario.getTransitSchedule();
		new TransitScheduleReader(scenario).readFile(scheduleFile);

		// Create vehicles for all transit lines that don't have one yet
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				String transportMode = route.getTransportMode();
				String vehicleTypeId = modeMapping.get(transportMode);
				
				if (vehicleTypeId == null) {
					throw new IllegalArgumentException("No vehicle type mapping found for transport mode: " + transportMode);
				}

				VehicleType vehicleType = vehicles.getVehicleTypes().get(Id.create(vehicleTypeId, VehicleType.class));
				if (vehicleType == null) {
					throw new IllegalArgumentException("Vehicle type not found: " + vehicleTypeId);
				}

				for (Departure departure : route.getDepartures().values()) {
					Id<Vehicle> vehicleId = departure.getVehicleId();
					if (vehicles.getVehicles().get(vehicleId) == null) {
						VehicleUtils.createVehicle(vehicleId, vehicleType);
					}
				}
			}
		}

		// Write the vehicles
		new MatsimVehicleWriter(vehicles).writeFile(outputFile);

		return 0;
	}
}
