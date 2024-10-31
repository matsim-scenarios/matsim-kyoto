import pandas as pd
import xml.etree.ElementTree as ET

# Load CSV data for stop facilities and schedule
stop_facility_file = r'https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/jp/kyoto/data/public_transit/stop_data.csv'
schedule_file = r'https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/jp/kyoto/data/public_transit/schedule_data.csv'

# Function to create stop facilities
def create_stop_facilities(csv_file, root):
    transit_stops = ET.SubElement(root, 'transitStops')
    df = pd.read_csv(csv_file)

    for index, row in df.iterrows():
        stop_facility = ET.SubElement(transit_stops, 'stopFacility', {
            'id': str(row['Station_IDs']),
            'x': str(row['Coordinates'].split(',')[0]),  # Assuming "x,y" format
            'y': str(row['Coordinates'].split(',')[1]),
            'name': row['Station Name'],
            'linkRefId': f"pt_{row['Station_IDs']}",
            'isBlocking': 'false'
        })

# Function to create transit schedules
def create_transit_schedule(csv_file, root):
    df = pd.read_csv(csv_file)

    # Group by First Route Name and Last Route Name
    grouped = df.groupby(['First Route Name', 'Last Route Name'])

    for (first_route, last_route), group in grouped:
        line_id = f"{first_route}_{last_route}"
        transit_line = ET.SubElement(root, 'transitLine', {'id': line_id})

        for route_num, (route_id, route_data) in enumerate(group.iterrows(), start=1):
            route_type = route_data['Route Type Name']
            transit_route = ET.SubElement(transit_line, 'transitRoute', {'id': f"{line_id}_{route_num}_{route_type}"})
            ET.SubElement(transit_route, 'transportMode').text = "train"  # Modify as needed

            # Create routeProfile
            route_profile = ET.SubElement(transit_route, 'routeProfile')
            stops = eval(route_data['All Stops'])
            arrival_offsets = eval(route_data['arrivalOffset'])
            departure_offsets = eval(route_data['departureOffset'])

            # Handle the first stop
            ET.SubElement(route_profile, 'stop', {
                'refId': str(stops[0]),
                'arrivalOffset': '00:00:00',  # First stop arrival offset
                'departureOffset': departure_offsets[0],  # Use the first departure offset
                'awaitDeparture': 'true'
            })

            # Handle middle stops
            for i in range(1, len(stops) - 1):
                ET.SubElement(route_profile, 'stop', {
                    'refId': str(stops[i]),
                    'arrivalOffset': arrival_offsets[i - 1],  # Arrival from previous stop
                    'departureOffset': departure_offsets[i],  # Departure to the next stop
                    'awaitDeparture': 'true'
                })

            # Handle the last stop
            ET.SubElement(route_profile, 'stop', {
                'refId': str(stops[-1]),
                'arrivalOffset': arrival_offsets[-1],  # Arrival at the last stop
                'departureOffset': arrival_offsets[-1],  # Last stop has no departure offset, set it the same as arrival
                'awaitDeparture': 'true'
            })

            # Create route
            route = ET.SubElement(transit_route, 'route')
            for stop_id in stops:
                ET.SubElement(route, 'link', {'refId': f"pt_{stop_id}"})  # Modify as needed

            # Create departures
            departures = ET.SubElement(transit_route, 'departures')
            for dep_id, dep_time in zip(eval(route_data['Train ID']), eval(route_data['Departure time'])):
                ET.SubElement(departures, 'departure', {
                    'id': str(dep_id),
                    'departureTime': dep_time,
                    'vehicleRefId': f"pt_{line_id}_{route_num}_{dep_id}"
                })

# Main function to create XML structure
def create_transit_schedule_xml():
    root = ET.Element('transitSchedule')
    create_stop_facilities(stop_facility_file, root)
    create_transit_schedule(schedule_file, root)

    # Save the XML to a file
    tree = ET.ElementTree(root)
    tree.write(r'C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\transit_schedule.xml', encoding='utf-8', xml_declaration=True)

# Call the main function
create_transit_schedule_xml()

