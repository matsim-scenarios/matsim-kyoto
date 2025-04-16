package org.matsim.prepare;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.utils.TransitScheduleValidator;

public class ValidateTransitSchedule {

	public static void main(String[] args) {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		Network network = NetworkUtils.readNetwork("input/v1.0/kyoto-v1.0-pt-network.xml.gz");

		new TransitScheduleReader(scenario).readFile("train_schedule_new.xml");

		TransitScheduleValidator.ValidationResult result = TransitScheduleValidator.validateAll(scenario.getTransitSchedule(), network);

		for (TransitScheduleValidator.ValidationResult.ValidationIssue issue : result.getIssues()) {

			System.err.println(issue.getErrorCode());
			System.err.println(issue.getMessage());
			System.err.println("#####################");

		}
	}
}
