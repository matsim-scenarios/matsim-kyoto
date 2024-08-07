#!/usr/bin/env python
# -*- coding: utf-8 -*-

import argparse
import os

import numpy as np
from matsim.scenariogen.data import TripMode, preparation, run_create_ref_data
from matsim.scenariogen.data.preparation import join_person_with_household, remove_persons_with_invalid_trips, \
    create_activities


def transform_persons(df):

    # Filter on the shape file for kyoto
    # "PREF" = 26  AND "CITY" IN (101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111)

    df = df[df.location.isin(("26101", "26102", "26103", "26104", "26105", "26106", "26107", "26108", "26109", "26110", "26111"))]

    df.loc[:, "age"] = preparation.cut(df.age, [0, 12, 18, 25, 35, 66, np.inf])

    return df


def trip_filter(df):
    # Motorcycles are counted as cars
    df.loc[df.main_mode == TripMode.MOTORCYCLE, "main_mode"] = TripMode.CAR

    # Other mode are ignored in the total share
    df = df[df.main_mode != TripMode.OTHER]

    return df


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Converter for survey data")

    parser.add_argument("-d", "--directory",
                        help="Directory with the kyoto survey data",
                        default=os.path.expanduser(
                            "~/Development/matsim-scenarios/shared-svn/projects/matsim-kyoto/data/survey_2010"))

    parser.add_argument("--output", default="table", help="Output prefix")

    args = parser.parse_args()

    result = run_create_ref_data.create(
        args.directory, transform_persons, trip_filter,
        invalid_trip_handling=run_create_ref_data.InvalidHandling.REMOVE_TRIPS,
        ref_groups=["age"]
    )

    print(result.share)

    hh, persons, trips = (result.all_hh, result.all_persons, result.all_trips)

    # Motorcycles are counted as cars
    trips.loc[trips.main_mode == TripMode.MOTORCYCLE, "main_mode"] = TripMode.CAR

    hh.to_csv(args.output + "-households.csv")
    trips.to_csv(args.output + "-trips.csv")

    df = join_person_with_household(persons, hh)
    df = remove_persons_with_invalid_trips(df, trips)

    df.to_csv(args.output + "-persons.csv", index=False)

    print("Written survey csvs with %d persons" % len(df))

    activities = create_activities(df, trips, include_person_context=False, use_index_as_pid=False, cut_groups=False)
    activities.to_csv(args.output + "-activities.csv", index=False)
