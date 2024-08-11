#!/usr/bin/env python
# -*- coding: utf-8 -*-

import pandas as pd
import swifter


def home_work_relation(x):
    """ Searches for home and work location of a person. """

    home = pd.NA
    work = pd.NA

    for t in x.itertuples():
        if t.type == "home":
            home = t.location
        elif t.type == "work":
            work = t.location

    return pd.Series(data={"home": home, "work": work, "n": x.a_weight.iloc[0]})


if __name__ == "__main__":
    df = pd.read_csv("table-activities.csv")
    df = df[df.type.isin(["work", "home"])]

    aggr = df.swifter.groupby("p_id").apply(home_work_relation)
    aggr = aggr.dropna()

    aggr.home = aggr.home.astype(int)
    aggr.work = aggr.work.astype(int)

    aggr = aggr.groupby(["home", "work"]).agg(n=("n", "sum"))

    aggr.to_csv("work-commuter.csv", columns=["n"], index=True)
