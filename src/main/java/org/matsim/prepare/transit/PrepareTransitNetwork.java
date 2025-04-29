package org.matsim.prepare.transit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.CreatePseudoNetworkWithLoopLinks;
import org.matsim.pt.utils.TransitScheduleValidator;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@CommandLine.Command(
	name = "transit-network",
	description = "Prepare transit network from schedule."
)
public class PrepareTransitNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PrepareTransitNetwork.class);

	@CommandLine.Option(names = "--network", description = "Input network file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--schedule", description = "Input transit schedule file", required = true)
	private String scheduleFile;

	@CommandLine.Option(names = "--output-network", description = "Output network file with transit", required = true)
	private String outputNetworkFile;

	@CommandLine.Option(names = "--output-schedule", description = "Output transit schedule file", required = true)
	private String outputScheduleFile;

	public static void main(String[] args) {
		new PrepareTransitNetwork().execute(args);
	}

	/**
	 * Clean and correct timings of transit routes. This logic was copied from {@link org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs}.
	 */
	private static void cleanTransitLine(Network network, TransitLine line) {

		Set<TransitRoute> toRemove = new HashSet<>();

		for (TransitRoute route : line.getRoutes().values()) {

			// increase speed if current freespeed is lower.
			List<TransitRouteStop> routeStops = route.getStops();
			if (routeStops.size() < 2) {
				log.warn("TransitRoute with less than 2 stops found: line {}, route {}", line.getId(), route.getId());
				toRemove.add(route);
				continue;
			}

			if (route.getDepartures().isEmpty()) {
				log.warn("TransitRoute with no departures found: line {}, route {}", line.getId(), route.getId());
				toRemove.add(route);
				continue;
			}

			double lastDepartureOffset = route.getStops().get(0).getDepartureOffset().seconds();
			// min. time spend at a stop, useful especially for stops whose arrival and departure offset is identical,
			// so we need to add time for passengers to board and alight
			double minStopTime = 30.0;

			List<Id<Link>> routeIds = new LinkedList<>();
			routeIds.add(route.getRoute().getStartLinkId());
			routeIds.addAll(route.getRoute().getLinkIds());
			routeIds.add(route.getRoute().getEndLinkId());

			for (int i = 1; i < routeStops.size(); i++) {
				TransitRouteStop routeStop = routeStops.get(i);
				// if there is no departure offset set (or infinity), it is the last stop of the line,
				// so we don't need to care about the stop duration
				double stopDuration = routeStop.getDepartureOffset().isDefined() ?
					routeStop.getDepartureOffset().seconds() - routeStop.getArrivalOffset().seconds() : minStopTime;
				// ensure arrival at next stop early enough to allow for 30s stop duration -> time for passengers to board / alight
				// if link freespeed had been set such that the pt veh arrives exactly on time, but departure tiome is identical
				// with arrival time the pt vehicle would have been always delayed
				// Math.max to avoid negative values of travelTime
				double travelTime = Math.max(1, routeStop.getArrivalOffset().seconds() - lastDepartureOffset - 1.0 -
					(stopDuration >= minStopTime ? 0 : (minStopTime - stopDuration)));


				Id<Link> stopLink = routeStop.getStopFacility().getLinkId();
				List<Id<Link>> subRoute = new LinkedList<>();
				do {
					Id<Link> linkId = routeIds.removeFirst();
					subRoute.add(linkId);
				} while (!subRoute.contains(stopLink));

				List<? extends Link> links = subRoute.stream().map(network.getLinks()::get)
					.toList();

				double length = links.stream().mapToDouble(Link::getLength).sum();

				for (Link link : links) {
					increaseLinkFreespeedIfLower(link, length / travelTime);
				}
				lastDepartureOffset = routeStop.getDepartureOffset().seconds();
			}
		}

		toRemove.forEach(line::removeRoute);

	}

	private static void increaseLinkFreespeedIfLower(Link link, double newFreespeed) {
		if (link.getFreespeed() < newFreespeed) {
			link.setFreespeed(newFreespeed);
		}
	}

	@Override
	public Integer call() throws Exception {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		new TransitScheduleReader(scenario).readFile(scheduleFile);

		Network network = NetworkUtils.readNetwork(networkFile);

		new CreatePseudoNetworkWithLoopLinks(scenario.getTransitSchedule(), network,
			"pt", 0.1, 100000.0).createNetwork();

		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			cleanTransitLine(network, line);
		}

		TransitScheduleValidator.ValidationResult checkResult = TransitScheduleValidator.validateAll(scenario.getTransitSchedule(), network);
		List<String> warnings = checkResult.getWarnings();
		if (!warnings.isEmpty())
			log.warn("TransitScheduleValidator warnings: {}", String.join("\n", warnings));

		if (checkResult.isValid()) {
			log.info("TransitSchedule and Network valid according to TransitScheduleValidator");
		} else {
			log.error("TransitScheduleValidator errors: {}", String.join("\n", checkResult.getErrors()));
			throw new IllegalArgumentException("TransitSchedule and/or Network invalid");
		}

		// Write both network and schedule
		NetworkUtils.writeNetwork(network, outputNetworkFile);
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(outputScheduleFile);

		return 0;
	}

}
