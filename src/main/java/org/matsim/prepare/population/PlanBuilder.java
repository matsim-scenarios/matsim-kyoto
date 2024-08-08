package org.matsim.prepare.population;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacility;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * Utility class to build plans from activity data.
 */
public class PlanBuilder {

	private final Function<CSVRecord, Set<ActivityFacility>> zoneSelector;

	public PlanBuilder(Function<CSVRecord, Set<ActivityFacility>> zoneSelector) {
		this.zoneSelector = zoneSelector;
	}

	/**
	 * Assigns location from reference data to a person.
	 *
	 * @return whether the assignment was successful
	 */
	public boolean assignLocationsFromZones(List<CSVRecord> activities, Plan plan, Coord homeCoord, boolean ignoreTypes, SplittableRandom rnd) {

		List<Activity> existing = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

		// If activities don't match, this entry is skipped
		// this can happen if an end home activity has been added at the end
		if (activities.size() != existing.size())
			return false;

		ActLocation home = new ActLocation(null, homeCoord);

		List<List<ActLocation>> possibleLocations = new ArrayList<>();

		// Distances between activities in meter
		DoubleList dists = new DoubleArrayList();

		for (int i = 0; i < activities.size(); i++) {

			CSVRecord ref = activities.get(i);
			Activity activity = existing.get(i);

			String type = activity.getType();

			dists.add(Double.parseDouble(ref.get("leg_dist")) * 1000);

			if (type.equals("home")) {
				possibleLocations.add(List.of(home));
				continue;
			}

			Set<ActivityFacility> facilities = zoneSelector.apply(ref);
			List<ActivityFacility> subSet = facilities.stream().filter(f -> f.getActivityOptions().containsKey(type)).toList();

			List<ActLocation> candidates;
			if (subSet.isEmpty() || ignoreTypes) {
				// If there is no location with the correct type, choose from all possible coordinates
				candidates = facilities.stream().map(f -> new ActLocation(null, f.getCoord())).toList();
			} else {
				candidates = subSet.stream().map(f -> new ActLocation(f, f.getCoord())).toList();
			}

			if (candidates.isEmpty()) {
				return false;
			}

			possibleLocations.add(candidates);
		}

		List<ActLocation> chosen = sampleLocation(possibleLocations, dists, rnd);

		// No valid locations or matching error was too large
		if (chosen == null)
			return false;

		for (int i = 0; i < chosen.size(); i++) {
			ActLocation loc = chosen.get(i);
			Activity activity = existing.get(i);

			activity.setLinkId(null);
			if (loc.facility() != null) {
				activity.setFacilityId(loc.facility().getId());
			} else {
				activity.setCoord(loc.coord());
			}
		}


		return true;
	}

	/**
	 * Chooses from a list of possible locations such that difference to the references distances is minimized.
	 */
	private List<ActLocation> sampleLocation(List<List<ActLocation>> locations, DoubleList dists, SplittableRandom rnd) {

		double err = Double.POSITIVE_INFINITY;
		List<ActLocation> best = null;

		for (int k = 0; k < 100; k++) {
			List<ActLocation> current = new ArrayList<>();
			for (List<ActLocation> locs : locations) {
				current.add(locs.get(rnd.nextInt(locs.size())));
			}

			double currentErr = 0;
			for (int i = 1; i < current.size(); i++) {
				double dist = CoordUtils.calcEuclideanDistance(current.get(i - 1).coord(), current.get(i).coord());
				currentErr += Math.abs(dist - dists.getDouble(i));
			}

			if (currentErr < err || best == null) {
				err = currentErr;
				best = current;
			}
		}

		double total = dists.doubleStream().sum() / (locations.size() - 1);
		double perActErr = err / (locations.size() - 1);

		// threshold for deviation
		// survey has at least 500m inaccuracy
		if (perActErr > Math.max(500, total * 0.05))
			return null;

		return best;
	}

	private record ActLocation(ActivityFacility facility, Coord coord) {
	}

}
