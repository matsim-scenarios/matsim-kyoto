package org.matsim.contrib.gtfs;

import java.time.LocalDate;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;

import com.conveyal.gtfs.GTFSFeed;

import java.io.File;



/**
 * @author NKuehnel
 * modified by YiheZHOU
 */
public class RunGTFS2MATSimKyoto {

    /**
     * Starts the conversion.
     *
     * @param fromFile path of input file
     * @param toFile path to write to
     * @param startDate start date (inclusive) to check for transit data
     * @param endDate end date (inclusive) to check for transit data
     * @param coordinateTransformation coordination transformation for stops
     * @param useExtendedRouteTypes transfer extended route types to MATSim schedule
     * @param mergeStops create one TransitStopFacility per track or merge to one TransitStopFacility per station
     */
    public static void convertGtfs(String fromFile, String toFile, LocalDate startDate, LocalDate endDate, CoordinateTransformation coordinateTransformation, boolean useExtendedRouteTypes, GtfsConverterKyoto.MergeGtfsStops mergeStops) {

        // MAIN MODIFICATION: Extract prefix from filename
        String prefix = new File(fromFile).getName();
        prefix = prefix.substring(0, prefix.lastIndexOf('.')) + "-";   //removing the extension
        System.out.println("prefix: "+prefix);

        GTFSFeed feed = GTFSFeed.fromFile(fromFile);

        feed.feedInfo.values().stream().findFirst().ifPresent(feedInfo -> {
            System.out.println("Feed start date: " + feedInfo.feed_start_date);
            System.out.println("Feed end date: " + feedInfo.feed_end_date);
        });

        System.out.println("Parsed trips: "+feed.trips.size());
        System.out.println("Parsed routes: "+feed.routes.size());
        System.out.println("Parsed stops: "+feed.stops.size());

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

        GtfsConverterKyoto converter = GtfsConverterKyoto.newBuilder()
                .setScenario(scenario)
                .setTransform(coordinateTransformation)
                .setFeed(feed)
                .setStartDate(startDate)
                .setEndDate(endDate)
                .setUseExtendedRouteTypes(useExtendedRouteTypes)
                .setMergeStops(mergeStops)
                .setPrefix(prefix) // Pass the extracted prefix here
                .build();

        converter.convert();

        System.out.println("Converted stops: " + scenario.getTransitSchedule().getFacilities().size());

        TransitScheduleWriter writer = new TransitScheduleWriter(scenario.getTransitSchedule());
        writer.writeFile(toFile);

        System.out.println("Done.");
    }

    /**
     * Starts the conversion.
     *
     * @param gtfsZip path of input file
     * @param scenario scenario
     * @param startDate start date (inclusive) to check for transit data
     * @param endDate end date (inclusive) to check for transit data
     * @param coordinateTransformation coordination transformation for stops
     * @param createNetworkAndVehicles determine whether a transit network and vehicles should also be created
     * @param copyEarlyAndLateDepartures
     * @param useExtendedRouteTypes transfer extended route types to MATSim schedule
     * @param mergeStops create one TransitStopFacility per track or merge to one TransitStopFacility per station
     */
    public static void convertGTFSandAddToScenario(Scenario scenario, String gtfsZip, LocalDate startDate, LocalDate endDate, CoordinateTransformation coordinateTransformation, boolean createNetworkAndVehicles, boolean copyEarlyAndLateDepartures, boolean useExtendedRouteTypes, GtfsConverterKyoto.MergeGtfsStops mergeStops)
    {
        GTFSFeed feed = GTFSFeed.fromFile(gtfsZip);
        feed.feedInfo.values().stream().findFirst().ifPresent((feedInfo) -> {
            System.out.println("Feed start date: " + feedInfo.feed_start_date);
            System.out.println("Feed end date: " + feedInfo.feed_end_date);
        });
        GtfsConverterKyoto converter = GtfsConverterKyoto.newBuilder()
                .setScenario(scenario)
                .setTransform(coordinateTransformation)
                .setFeed(feed)
                .setStartDate(startDate)
                .setEndDate(endDate)
                .setUseExtendedRouteTypes(useExtendedRouteTypes)
                .setMergeStops(mergeStops)
                .build();

        converter.convert();
        if (copyEarlyAndLateDepartures) {
            TransitSchedulePostProcessTools.copyLateDeparturesToStartOfDay(scenario.getTransitSchedule(), 86400.0, "copied", false);
            TransitSchedulePostProcessTools.copyEarlyDeparturesToFollowingNight(scenario.getTransitSchedule(), 21600.0, "copied");
        }
        if (createNetworkAndVehicles) {
            (new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), "pt_")).createNetwork();
            (new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles())).run();
        }
    }


    public static void main(String[] args) {
        String inputFolder = args[0];
        String outputFolder = args[1];
        boolean useExtendedRouteTypes = Boolean.parseBoolean(args[2]);

        File folder = new File(inputFolder);

        if (!folder.isDirectory()) {
            System.err.println("The specified input path is not a folder.");
            return;
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null || files.length == 0) {
            System.err.println("No GTFS zip files found in the folder: " + inputFolder);
            return;
        }

        for (File file : files) {
            try {
                System.out.println("Processing file: " + file.getName());

                // Extract filename and set as prefix
                String fileName = file.getName();
                String prefix = fileName.substring(0, fileName.lastIndexOf('.')); // Remove file extension

                // Load the GTFS feed to determine the date range
                GTFSFeed feed = GTFSFeed.fromFile(file.getAbsolutePath());
                LocalDate earliestDate = feed.feedInfo.values().stream()
                        .map(info -> info.feed_start_date)
                        .min(LocalDate::compareTo)
                        .orElseThrow(() -> new IllegalArgumentException("No start date found in GTFS feed for " + file.getName()));

                LocalDate latestDate = feed.feedInfo.values().stream()
                        .map(info -> info.feed_end_date)
                        .max(LocalDate::compareTo)
                        .orElseThrow(() -> new IllegalArgumentException("No end date found in GTFS feed for " + file.getName()));

                System.out.println("Earliest date in feed: " + earliestDate);
                System.out.println("Latest date in feed: " + latestDate);

                // Find the first Weekday e.g.,Monday/Tuesday within the GTFS period
                LocalDate Weekday = earliestDate;
                while (Weekday.getDayOfWeek() != java.time.DayOfWeek.TUESDAY) {
                    Weekday = Weekday.plusDays(1);
                }

                if (Weekday.isAfter(latestDate)) {
                    throw new IllegalArgumentException("No eekday exists within the GTFS period for " + file.getName());
                }

                System.out.println("First Weekday in feed: " + Weekday);

                // Set the output file name
                String outputFileName = file.getName().replace(".zip", ".xml");
                String outputFilePath = new File(outputFolder, outputFileName).getAbsolutePath();

                // Convert the GTFS file to MATSim schedule format for the first Monday
                convertGtfs(file.getAbsolutePath(), outputFilePath, Weekday, Weekday, new IdentityTransformation(), useExtendedRouteTypes, GtfsConverterKyoto.MergeGtfsStops.doNotMerge);

                System.out.println("Conversion completed for file: " + file.getName());
            } catch (Exception e) {
                System.err.println("Failed to process file: " + file.getName());
                e.printStackTrace();
            }
        }
    }


}


