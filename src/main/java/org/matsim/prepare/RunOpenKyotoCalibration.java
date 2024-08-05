package org.matsim.prepare;

import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.network.params.ApplyNetworkParams;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.prepare.choices.ComputePlanChoices;
import org.matsim.prepare.choices.ComputeTripChoices;
import org.matsim.prepare.facilities.CreateMATSimFacilities;
import org.matsim.prepare.facilities.ExtractFacilityGeoPkg;
import org.matsim.prepare.opt.ExtractPlanIndexFromType;
import org.matsim.prepare.opt.RunCountOptimization;
import org.matsim.prepare.opt.SelectPlansFromIndex;
import org.matsim.prepare.population.*;
import org.matsim.run.OpenKyotoScenario;
import picocli.CommandLine;

@CommandLine.Command(header = ":: Open Kyoto Calibration ::", version = OpenKyotoScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
	MergePopulations.class, CreateKyotoPopulation.class,
	ExtractFacilityGeoPkg.class, DownSamplePopulation.class,
	CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class,
	CleanNetwork.class, CreateMATSimFacilities.class, InitLocationChoice.class, FilterRelevantAgents.class,
	RunActivitySampling.class, MergePlans.class, SplitActivityTypesDuration.class, CleanPopulation.class, CleanAttributes.class,
	RunCountOptimization.class, SelectPlansFromIndex.class, ExtractPlanIndexFromType.class, AssignReferencePopulation.class,
	ExtractRelevantFreightTrips.class, CheckCarAvailability.class, FixSubtourModes.class, ComputeTripChoices.class, ComputePlanChoices.class,
	ApplyNetworkParams.class, SetCarAvailabilityByAge.class
})
public class RunOpenKyotoCalibration extends MATSimApplication {

	public static void main(String[] args) {
		MATSimApplication.run(RunOpenKyotoCalibration.class, args);
	}

}
