package org.matsim.prepare.pt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.run.CreateDefaultPTMapperConfig;
import org.matsim.pt2matsim.run.Osm2TransitSchedule;
import org.matsim.pt2matsim.run.PublicTransitMapper;
import org.matsim.run.OpenKyotoScenario;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Creates a blank schedule file with just the lines and stations (but without departures or time offsets).
 * Basically just a wrapper method, uses functionality of the {@code pt2matsim} package.
 */
public class CreateBlankSchedule implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateBlankSchedule.class);

	@CommandLine.Option(names = "--osm", description = "Path to the .osm-file", required = true)
	private Path osmPath;

	@CommandLine.Option(names = "--network", description = "Path to the matsim-network to use", required = true)
	private Path networkPath;

	@CommandLine.Option(names = "--config", description = "Path to the mapper-config.xml Can be created using the pt2matsim " +
		"CreateDefaultPTMapperConfig.java. If no config is given, this code will automatically use the default-config for the kyoto-scenario.")
	private Path configPath;

	@CommandLine.Option(names = "--outSchedule", description = "Path, where to output the blank schedule file.", required = true)
	private Path scheduleOutPath;

	@CommandLine.Option(names = "--outNetwork", description = "Path, where to output the blank schedule file.", required = true)
	private Path networkOutPath;

	@CommandLine.Option(names = "--outCRS", description = "CRS in which to output the blank schedule file (default=EPSG:32653).", defaultValue = OpenKyotoScenario.CRS)
	private String CRSOut;


	public static void main(String[] args) {
		new CreateBlankSchedule().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		// First make sure, that the configuration stuff works
		log.info("Starting configuration preparation...");

		if (configPath == null){
			CreateDefaultPTMapperConfig.main(new String[]{"./input/ptGen/mapper-config2.xml"});
			configPath = Path.of("./input/ptGen/mapper-config.xml");
		}
		PublicTransitMappingConfigGroup config = PublicTransitMappingConfigGroup.loadConfig(configPath.toString());

		log.info("Finished configuration preparation!");

		// Start with the transitScheduleGeneration
		log.info("Starting with osm2transitSchedule transformation...");
		Osm2TransitSchedule.run(osmPath.toString(), "./schedule.tmp", CRSOut);
		log.info("Finished osm2transitSchedule transformation!");

		// Continue by mapping the schedule
		log.info("Starting with mapping...");
		PublicTransitMapper.run(configPath.toString());
		log.info("Finished mapping!");

		// Finish by replacing the pt2matsim (bus, rail, light_rail, ...) modes by standard-matsim-modes
		log.info("Removing pt2matsim modes...");
		Network network = NetworkUtils.readNetwork(config.getOutputNetworkFile());

		//TODO Add missing modes here (if there are any)
		for(Link l : network.getLinks().values()){
			if (l.getAllowedModes().contains("bus")||
					l.getAllowedModes().contains("tram")||
					l.getAllowedModes().contains("train")||
					l.getAllowedModes().contains("light_train")||
					l.getAllowedModes().contains("subway")||
					l.getAllowedModes().contains("artificial")){
				HashSet<String> allowedModes = new HashSet<>(l.getAllowedModes());
				List.of("bus", "tram", "rail", "light_rail", "subway").forEach(allowedModes::remove);
				allowedModes.add("pt");
				l.setAllowedModes(allowedModes);
			}
		}

		NetworkUtils.writeNetwork(network, networkOutPath.toString());

		// Delete the tmp-schedule file
		log.info("Cleaning up...");
		if (!Files.deleteIfExists(Path.of("./schedule.tmp"))){
			log.warn("Could not delete temporary schedule.tmp!");
		}

		log.info("Finished!");
		return 0;
	}
}
