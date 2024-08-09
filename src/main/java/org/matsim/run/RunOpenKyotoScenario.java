package org.matsim.run;


import org.matsim.application.MATSimApplication;

/**
 * Run the {@link OpenKyotoScenario} with default configuration.
 */
public final class RunOpenKyotoScenario {

	private RunOpenKyotoScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.runWithDefaults(OpenKyotoScenario.class, args);
	}

}
