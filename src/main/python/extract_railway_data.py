## extract all the required info from json file and calculate the offset

from datetime import timedelta # type: ignore
import json
import pandas as pd
from datetime import datetime
import numpy as np
import os
import ast

# Load file paths for json input

## filtered files
# Express
train_file_path = r'C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\filtered_trainUnion_local.json'
station_file_path = r'C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\master.json'


# Load the JSON files
with open(train_file_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

with open(station_file_path, 'r', encoding='utf-8') as f:
    station_data = json.load(f)

# Create mapping from station IDs to names
station_id_to_name = {station['c']: station['n'] for station in station_data['master']['st']}
routetype_id_to_name = {route['c']: route['n'] for route in station_data['master']['tt']}

# Function to convert timestamp to formatted time
def format_time(timestamp):
    return datetime.fromtimestamp(timestamp).strftime('%H:%M:%S')

# Extract relevant information from each train trip
train_stations = []
for train in data['trains']:
    train_id = train['summary']['tid']  # Train ID
    sections = train['trainSectionList']

    # Prepare to store information for the current train
    train_info = {
        'Train ID': train_id,
    }

    # Extract route type from summary
    for list_item in train['summary']['ls']:
        route_typeID = list_item['tt']
        train_info['Route Type ID'] = route_typeID

    if sections:
        # Extract the first section details
        first_section = sections[0]
        start_stop_ID = first_section['f']
        train_info.update({
            'First Route Name': first_section['rn'],  # Route name of first section
        })

        # Extract the last section details
        last_section = sections[-1]
        end_stop_ID = last_section['t']  # Assign end stop name to a variable
        train_info.update({
            'Last Route Name': last_section['rn'],  # Route name of last section
        })

        # Collect all section times (fts and tts) and stops
        all_departure_times = [format_time(section['fts']) for section in sections]
        all_arrival_times = [format_time(section['tts']) for section in sections]
        all_departure_stops = [section['f'] for section in sections]

        # Combine all departure stops with the end stop
        all_stops = all_departure_stops + [end_stop_ID]

        # Extract the first coordinate from each section's 's' field and the last coordinate of the last section
        first_coordinates = []
        last_coordinates = []
        for i, section in enumerate(sections):
            coordinates = section['s'].split(' ')  # Split by space to get coordinate pairs
            if coordinates:
                first_coordinates.append(coordinates[0])  # Add the first coordinate of this section
                if i == len(sections) - 1:  # If this is the last section
                    last_coordinates.append(coordinates[-1])  # Add the last coordinate of the last section

        all_coord = first_coordinates + last_coordinates


        # Store these lists in the train_info
        train_info.update({
            'All Departure Times': all_departure_times,
            'All Arrival Times': all_arrival_times,
            'All Stops': all_stops,
            'All Coordinate' : all_coord,
        })

        # Map start and end station names to their IDs
        train_info['Start Station Name'] = station_id_to_name.get(start_stop_ID)
        train_info['End Station Name'] = station_id_to_name.get(end_stop_ID)

        # Append the train_info dictionary to the list
        train_stations.append(train_info)

# Function to calculate time difference in HH:MM:SS format
def calculate_time_difference(earlier_time, later_time):
    # Convert string times to datetime objects
    earlier_time_obj = datetime.strptime(earlier_time, '%H:%M:%S')
    later_time_obj = datetime.strptime(later_time, '%H:%M:%S')

    # If later_time is earlier than earlier_time, assume it's on the next day
    if later_time_obj < earlier_time_obj:
        later_time_obj += timedelta(days=1)

    # Calculate the time difference
    time_diff = later_time_obj - earlier_time_obj

    # Return the difference in HH:MM:SS format
    return str(time_diff)

# Add new columns for departureOffset and arrivalOffset
offsets = []

for row in train_stations:
    departure_times = row['All Departure Times']
    arrival_times = row['All Arrival Times']

    # Initialize offsets list for each train
    train_offsets = {
        'departureOffset': [],
        'arrivalOffset': [],
        'waitingtime': []
    }

    # First stop: departure offset is 0
    train_offsets['departureOffset'].append('00:00:00')

    # Calculate the offsets for all other stops
    for i in range(1, len(departure_times)):
        # Departure offset for this stop: departure - arrival of the previous stop
        waiting_offset = calculate_time_difference(arrival_times[i-1], departure_times[i])
        departure_offset = calculate_time_difference(departure_times[0], departure_times[i])
        train_offsets['waitingtime'].append(waiting_offset)
        train_offsets['departureOffset'].append(departure_offset)

    for k in range(len(arrival_times)):
        arrival_offset = calculate_time_difference(departure_times[0], arrival_times[k])
        train_offsets['arrivalOffset'].append(arrival_offset)

    # Append the offsets to the list
    offsets.append(train_offsets)

# Create a DataFrame from the extracted train information
train_stations_df = pd.DataFrame(train_stations)

# Add the offsets to the DataFrame
train_stations_df['departureOffset'] = [offset['departureOffset'] for offset in offsets]
train_stations_df['arrivalOffset'] = [offset['arrivalOffset'] for offset in offsets]
train_stations_df['waitingtime'] = [offset['waitingtime'] for offset in offsets]

# Map route type IDs to their names
train_stations_df['Route Type Name'] = train_stations_df['Route Type ID'].map(routetype_id_to_name)
# Extract the first element from 'Column A'
train_stations_df['Departure time'] = train_stations_df['All Departure Times'].apply(lambda x: x[0] if x else None)

######################################
# #NEXT STEP
# group by "All stops" and average time offsets
# Function to convert time string (HH:MM:SS) to total seconds
def time_to_seconds(time_str):
    h, m, s = map(int, time_str.split(':'))
    return h * 3600 + m * 60 + s

# Function to convert total seconds back to HH:MM:SS format
def seconds_to_time(seconds):
    return str(timedelta(seconds=int(seconds)))

# Convert 'All Stops' from string to list (if it's in string format)
train_stations_df['All Stops'] = train_stations_df['All Stops'].apply(lambda x: ast.literal_eval(x) if isinstance(x, str) else x)

# Convert "All Stops" to tuples (since tuples are hashable and can be used for grouping)
train_stations_df['All Stops'] = train_stations_df['All Stops'].apply(tuple)

# Convert 'departureOffset' and 'arrivalOffset' to total seconds
# train_stations_df['departureOffset'] = train_stations_df['departureOffset'].apply(lambda x: [time_to_seconds(t) for t in x])
# train_stations_df['arrivalOffset'] = train_stations_df['arrivalOffset'].apply(lambda x: [time_to_seconds(t) for t in x])

# Convert 'departureOffset' and 'arrivalOffset' to total seconds
train_stations_df['departureOffset'] = train_stations_df['departureOffset'].apply(lambda x: [time_to_seconds(t) for t in eval(x) if isinstance(x, str)])
train_stations_df['arrivalOffset'] = train_stations_df['arrivalOffset'].apply(lambda x: [time_to_seconds(t) for t in eval(x) if isinstance(x, str)])


# Group by "All Stops" and average time offsets
grouped_df = train_stations_df.groupby('All Stops').agg({
    'Train ID': lambda x: list(x),
    'Departure time': lambda x: list(x),
    'All Coordinate': 'first',
    'Start Station Name': 'first',
    'End Station Name': 'first',
    'Route Type ID': 'first',
    'Route Type Name': 'first',
    'First Route Name':'first',
    'Last Route Name':'first',
    'departureOffset': lambda x: np.mean(np.vstack(x), axis=0).tolist(),
    'arrivalOffset': lambda x: np.mean(np.vstack(x), axis=0).tolist()
}).reset_index()

# Convert the averaged departureOffset and arrivalOffset back to HH:MM:SS format
grouped_df['departureOffset'] = grouped_df['departureOffset'].apply(lambda x: [seconds_to_time(t) for t in x])
grouped_df['arrivalOffset'] = grouped_df['arrivalOffset'].apply(lambda x: [seconds_to_time(t) for t in x])

# Convert 'All Stops' back to list format (for consistency with other columns)
grouped_df['All Stops'] = grouped_df['All Stops'].apply(list)

# Show the result
grouped_df.head()
# save schdule data in csv
grouped_df.to_csv('schedule_data.csv', index=False)


######################################
# #NEXT STEP
# creat a list of stations as the input for stop facility
# Define the file path
file_path = r'C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\schedule_data.csv'

# Read the CSV file into a pandas DataFrame
df = pd.read_csv(file_path)

# Convert 'All Stops' and 'All Coordinate' columns from string to list
df['All Stops'] = df['All Stops'].apply(lambda x: ast.literal_eval(x) if isinstance(x, str) else x)
df['All Coordinate'] = df['All Coordinate'].apply(lambda x: ast.literal_eval(x) if isinstance(x, str) else x)

# Function to match IDs with coordinates and remove duplicates
def match_and_remove_duplicates(row):
    # Zip Station IDs with their corresponding Coordinates
    id_coord_pairs = list(zip(row['All Stops'], row['All Coordinate']))

    # Remove duplicate pairs by converting to dict (this will remove duplicates based on IDs)
    unique_pairs = dict(id_coord_pairs)

    # Separate back into IDs and Coordinates
    unique_ids = list(unique_pairs.keys())
    unique_coords = list(unique_pairs.values())

    return pd.Series([unique_ids, unique_coords])

# Apply the function to each row and save the result in a new DataFrame
new_df = df.apply(match_and_remove_duplicates, axis=1)

# Rename the columns for clarity
new_df.columns = ['Station_IDs', 'Coordinates']

# Explode the lists into individual rows
df_exploded = new_df.explode(['Station_IDs', 'Coordinates'])

# Drop duplicate Station ID and Coordinate pairs
df_unique = df_exploded.drop_duplicates().reset_index(drop=True)

# add name by ID using master file
# Express
station_file_path = r'C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\express\okyw-timetable-union-express\master.json'

with open(station_file_path, 'r', encoding='utf-8') as f:
    station_data = json.load(f)

station_id_to_name = {station['c']: station['n'] for station in station_data['master']['st']}

# Map the Station ID to Station Name using the dictionary
df_unique['Station Name'] = df_unique['Station_IDs'].map(station_id_to_name)

# save stop data in csv
df_unique.to_csv('stop_data.csv', index=False)