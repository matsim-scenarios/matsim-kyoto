package org.matsim.run;


import org.matsim.application.MATSimApplication;

/**
 * Run the {@link OpenKyotoScenario} with default configuration.
 */
public final class KNRunOpenKyotoScenario{

	private KNRunOpenKyotoScenario() {
	}

	public static void main(String[] args) {
		args = new String [] {
				"--config:network.inputNetworkFile=../../../../../shared-svn/projects/matsim-kyoto/data/input/kyoto-v1.0-network.xml.gz",
				"--config:plans.inputPlansFile=../../../../../shared-svn/projects/matsim-kyoto/data/input/kyoto-v1.0-1pct.plans-initial.xml.gz",
//				"--config:vehicles.vehiclesFile=../../../../../shared-svn/projects/matsim-kyoto/data/input/kyoto-v1.0-vehicleTypes.xml",
				"--config:facilities.inputFacilitiesFile=../../../../../shared-svn/projects/matsim-kyoto/data/input/kyoto-v1.0-facilities.xml.gz",
				"--iterations=0", "--1pct", "run"};
//		args = new String [] {"--help"};
		MATSimApplication.runWithDefaults(OpenKyotoScenario.class, args);

		// The following is taken from OpenBerlin; I do not know if it is correct for the Kyoto scenario.  kai, sep'24

		// (I think that this will in the end just do MATSimApplication.run( OpenBerlinScenario.class, args ).  kai, aug'24)

		// (That "run" will instantiate an instance of OpenBerlinScenario (*), then do some args consistency checking, then call the piccoli execute method.  kai, aug'24)

		// (The piccoli execute method will essentially call the "call" method of MATSimApplication. kai, aug'24)

		// (I think that in this execution path, this.config in that call method will be null.  (The ctor of MATSimApplication was called via reflection at (*); I think that it was called without a config argument.)
		// This then does:
		// * getCustomModules() (which is empty by default but can be overriden)
		// * ConfigUtils.loadConfig(...) _without_ passing on the args
		// * prepareConfig(...) (which is empty by default but is typically overridden, in this case in OpenBerlinScenario).  In our case, this sets the typical scoring params and the typical replanning strategies.
		// * next one can override the config from some yaml file provided as a commandline option
		// * next args is parsed and set
		// * then some standard CL options are detected and set
		// * then createScenario(config) is called (which can be overwritten but is not)
		// * then prepareScenario(scenario) is called (which can be overwritten but is not)
		// * then a standard controler is created from scenario
		// * then prepareControler is called which can be overwritten

	}

}
