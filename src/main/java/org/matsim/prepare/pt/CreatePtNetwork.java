package org.matsim.prepare.pt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.mapping.PTMapper;
import org.matsim.pt2matsim.osm.lib.OsmData;
import org.matsim.pt2matsim.osm.lib.OsmDataImpl;
import org.matsim.pt2matsim.osm.lib.OsmFileReader;
import org.matsim.pt2matsim.tools.ScheduleTools;
import org.matsim.run.OpenKyotoScenario;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "create-pt-network",
	description = "Creates a blank schedule file with just the lines and stations (but without departures or time offsets).",
	showDefaultValues = true)
public class CreatePtNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreatePtNetwork.class);

	@CommandLine.Option(names = "--osm", description = "Path to the .osm-file", required = true)
	private Path osmPath;

	@CommandLine.Option(names = "--network", description = "Path to the existing network without pt..", required = true)
	private Path inputNetworkPath;

	@CommandLine.Option(names = "--output-network", description = "Path to the output network containing pt.", required = true)
	private Path networkOutput;

	@CommandLine.Option(names = "--output-schedule", description = "Path to the output schedule.", required = true)
	private Path scheduleOutput;

	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions("WGS84", OpenKyotoScenario.CRS);

	public static void main(String[] args) {
		new CreatePtNetwork().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		// Start with the transitScheduleGeneration
		log.info("Starting with osm2transitSchedule transformation...");

		TransitSchedule schedule = createTransitSchedule(osmPath.toString());

		// Continue by mapping the schedule
		log.info("Starting with mapping...");

		Network network = NetworkUtils.readNetwork(inputNetworkPath.toString());

		new CreatePseudoNetwork(schedule, network, "pt_").createNetwork();


		TransitScheduleValidator.ValidationResult checkResult = TransitScheduleValidator.validateAll(schedule, network);
		List<String> warnings = checkResult.getWarnings();
		if (!warnings.isEmpty())
			log.warn("TransitScheduleValidator warnings: {}", String.join("\n", warnings));

		if (checkResult.isValid()) {
			log.info("TransitSchedule and Network valid according to TransitScheduleValidator");
		} else {
			log.error("TransitScheduleValidator errors: {}", String.join("\n", checkResult.getErrors()));
			throw new RuntimeException("TransitSchedule and/or Network invalid");
		}

		NetworkUtils.writeNetwork(network, networkOutput.toString());
		new TransitScheduleWriter(schedule).writeFile(scheduleOutput.toString());

		log.info("Finished!");
		return 0;
	}

	private TransitSchedule createTransitSchedule(String osmFile) {
		TransitSchedule schedule = ScheduleTools.createSchedule();
		CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(crs.getInputCRS(), crs.getTargetCRS());

		// load osm file
		OsmData osmData = new OsmDataImpl();
		new OsmFileReader(osmData).readFile(osmFile);

		// convert osm data
		new OsmTransitScheduleConverter(osmData).convert(schedule, ct);

		return schedule;
	}

	/**
	 * This is the mapping of pt2matsim, which we don't use at the moment.
	 * Instead {@link CreatePseudoNetwork} is used.
	 */
	private void mapNetwork(String configFile, Network network, TransitSchedule schedule) {
		// Load config
		PublicTransitMappingConfigGroup config = PublicTransitMappingConfigGroup.loadConfig(configFile);

		// Run PTMapper
		PTMapper.mapScheduleToNetwork(schedule, network, config);
	}

}
