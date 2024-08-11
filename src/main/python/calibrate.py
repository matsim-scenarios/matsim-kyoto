#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd

from matsim.calibration import create_calibration, ASCCalibrator, utils

# %%

modes = ["walk", "car", "pt", "bike", "ride"]
fixed_mode = "walk"
initial = {
    "bike": -1.4,
    "pt": 0.6,
    "car": -1,
    "ride": -1.4
}

# Modal split according to survey of milt
# The modal split is only calibrated for persons living in kyoto
target = {
    "walk": 0.239023,
    "bike": 0.232813,
    "pt": 0.236254,
    "car": 0.217186,
    "ride": 0.074724
}

region = gpd.read_file("../input/area.gpkg")


def filter_persons(persons):
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))
    df = gpd.sjoin(persons.set_crs("EPSG:32653"), region, how="inner", predicate="intersects")

    print("Filtered %s persons" % len(df))
    return df


def filter_modes(df):
    return df[df.main_mode.isin(modes)]


study, obj = create_calibration(
    "calib",
    ASCCalibrator(modes, initial, target, lr=utils.linear_scheduler(start=0.3, interval=15)),
    "matsim-kyoto-1.0-SNAPSHOT.jar",
    "../input/v1.0/kyoto-v1.0-10pct.config.xml",
    args="--10pct",
    jvm_args="-Xmx48G -Xms48G -XX:+AlwaysPreTouch -XX:+UseParallelGC",
    transform_persons=filter_persons,
    transform_trips=filter_modes,
    chain_runs=utils.default_chain_scheduler, debug=False
)

# %%

study.optimize(obj, 6)
