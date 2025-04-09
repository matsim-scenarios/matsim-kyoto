import random
import string
import gzip
import xml.etree.ElementTree as ET
import pandas as pd
import numpy as np
from datetime import timedelta, datetime

# === CONFIGURATION ===
NUM_AGENTS = 500  # Number of tourist agents to generate
CSV_FILE = "kyoto_points_of_interest.csv"  # Input CSV with POI locations
OUTPUT_FILE = "kyoto_tourists_population.xml.gz"  # Output MATSim population file

# Attribute distributions (using normal distribution or predefined options)
AGE_MEAN, AGE_STD = 35, 10
INCOME_MEAN, INCOME_STD = 2500, 800
HOUSEHOLD_SIZE_OPTIONS = [1, 2, 3, 4]
SEX_OPTIONS = ['m', 'f']
EMPLOYMENT_OPTIONS = ['job_full_time', 'job_part_time', 'unemployed']

# === UTILITY FUNCTIONS ===
def generate_agent_id():
    random_part = ''.join(random.choices(string.ascii_letters + string.digits, k=8))
    return f"tourist_{random_part}"

def generate_start_time():
    hour = random.randint(8, 14)
    minute = random.choice([0, 15, 30, 45])
    return timedelta(hours=hour, minutes=minute)

def format_time(td):
    return str(td)

def choose_weighted_locations(pois_df, count=3):
    weighted = pois_df.sample(n=count, weights='gravity')
    return weighted.to_dict('records')

# === LOAD POIs ===
pois_df = pd.read_csv(CSV_FILE)
hotels_or_stations = pois_df[pois_df['type'].isin(['hotel', 'kyoto_station'])]
other_pois = pois_df[~pois_df['type'].isin(['hotel', 'kyoto_station'])]

# === CREATE XML STRUCTURE ===
population = ET.Element("population")

for _ in range(NUM_AGENTS):
    person_id = generate_agent_id()
    person = ET.SubElement(population, "person", attrib={"id": person_id})

    # === ATTRIBUTES ===
    attributes = ET.SubElement(person, "attributes")
    def add_attr(name, clazz, value):
        ET.SubElement(attributes, "attribute", name=name, attrib={"class": clazz}).text = str(value)

    age = int(np.random.normal(AGE_MEAN, AGE_STD))
    income = round(np.random.normal(INCOME_MEAN, INCOME_STD), 2)

    add_attr("age", "java.lang.Integer", age)
    add_attr("sex", "java.lang.String", random.choice(SEX_OPTIONS))
    add_attr("income", "java.lang.Double", income)
    add_attr("employment", "java.lang.String", random.choice(EMPLOYMENT_OPTIONS))
    add_attr("employed", "java.lang.Boolean", "true")
    add_attr("carAvail", "java.lang.String", "always")
    add_attr("bikeAvail", "java.lang.String", "always")
    add_attr("ptAboAvail", "java.lang.String", "never")
    add_attr("hasLicense", "java.lang.String", "yes")
    add_attr("economic_status", "java.lang.String", "high")
    add_attr("restricted_mobility", "java.lang.Boolean", "false")
    add_attr("household_size", "java.lang.Integer", random.choice(HOUSEHOLD_SIZE_OPTIONS))
    add_attr("household_equivalent_size", "java.lang.Double", round(random.uniform(1.0, 2.5), 1))
    add_attr("household_type", "java.lang.String", "multi_wo_children")
    add_attr("gem", "java.lang.Integer", 12065136)
    add_attr("ars", "java.lang.Long", 120650136136)
    add_attr("RegioStaR7", "java.lang.Integer", 3)
    add_attr("subpopulation", "java.lang.String", "person")

    vehicle_id_base = person_id[-8:].lower()
    vehicle_str = {"car": f"bb_{vehicle_id_base}_car", "truck": f"bb_{vehicle_id_base}_truck",
                   "freight": f"bb_{vehicle_id_base}_freight", "ride": f"bb_{vehicle_id_base}_ride",
                   "bike": f"bb_{vehicle_id_base}_bike"}
    add_attr("vehicles", "org.matsim.vehicles.PersonVehicles", str(vehicle_str).replace("'", '"'))

    # === PLAN ===
    plan = ET.SubElement(person, "plan", attrib={"selected": "yes"})

    start_time = generate_start_time()
    end_time = start_time

    # 1. Start at hotel or station
    start_loc = hotels_or_stations.sample(1).iloc[0]
    ET.SubElement(plan, "activity", attrib={
        "type": start_loc['type'],
        "x": str(start_loc['x_coord']),
        "y": str(start_loc['y_coord']),
        "end_time": format_time(end_time + timedelta(minutes=15))
    })
    ET.SubElement(plan, "leg", attrib={"mode": "walk"})

    # 2. Visit 3 POIs
    visits = choose_weighted_locations(other_pois, 3)
    for visit in visits:
        duration = timedelta(minutes=int(visit['duration']))
        end_time += timedelta(minutes=15) + duration
        ET.SubElement(plan, "activity", attrib={
            "type": visit['type'],
            "x": str(visit['x_coord']),
            "y": str(visit['y_coord']),
            "end_time": format_time(end_time)
        })
        ET.SubElement(plan, "leg", attrib={"mode": "walk"})

    # 3. End again at hotel or station
    end_loc = hotels_or_stations.sample(1).iloc[0]
    ET.SubElement(plan, "activity", attrib={
        "type": end_loc['type'],
        "x": str(end_loc['x_coord']),
        "y": str(end_loc['y_coord'])
    })

# === WRITE TO FILE ===
tree = ET.ElementTree(population)
with gzip.open(OUTPUT_FILE, 'wb') as f:
    tree.write(f, encoding='utf-8', xml_declaration=True)

print(f"Generated {NUM_AGENTS} tourist agents in '{OUTPUT_FILE}'")
