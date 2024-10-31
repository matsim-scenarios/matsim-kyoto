import xml.etree.ElementTree as ET
import xml.dom.minidom as minidom
import csv

# Define the namespaces
namespaces = {
    None: "http://www.matsim.org/files/dtd",  # Default namespace
    "xsi": "http://www.w3.org/2001/XMLSchema-instance"
}

# Create the root element with namespaces
root = ET.Element('vehicleDefinitions', {
    "xmlns": namespaces[None],
    "xmlns:xsi": namespaces["xsi"],
    "xsi:schemaLocation": "http://www.matsim.org/files/dtd http://www.matsim.org/files/dtd/vehicleDefinitions_v2.0.xsd"
})

# Function to add a new vehicle type based on data from the CSV file
def add_vehicle_type_from_csv(csv_file):
    with open(csv_file, newline='') as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            # Read parameters from the CSV row
            vehicle_id = row['vehicle_id']
            access_time = float(row['access_time'])
            door_operation = row['door_operation']
            egress_time = float(row['egress_time'])
            seats = int(row['seats'])
            standing_room = int(row['standing_room'])
            length = float(row['length'])
            width = float(row['width'])
            engine_info = row['engine_info'].lower() == 'true'  # Convert to boolean

            # Create vehicle type element
            vehicle_type = ET.Element('vehicleType', id=vehicle_id)

            # Attributes for vehicle type
            attributes = ET.SubElement(vehicle_type, 'attributes')
            ET.SubElement(attributes, 'attribute', attrib={"name": "accessTimeInSecondsPerPerson", "class": "java.lang.Double"}).text = str(access_time)
            ET.SubElement(attributes, 'attribute', attrib={"name": "doorOperationMode", "class": "org.matsim.vehicles.VehicleType$DoorOperationMode"}).text = door_operation
            ET.SubElement(attributes, 'attribute', attrib={"name": "egressTimeInSecondsPerPerson", "class": "java.lang.Double"}).text = str(egress_time)

            # Capacity, length, width, and other elements
            ET.SubElement(vehicle_type, 'capacity', seats=str(seats), standingRoomInPersons=str(standing_room))
            ET.SubElement(vehicle_type, 'length', meter=str(length))
            ET.SubElement(vehicle_type, 'width', meter=str(width))

            # Engine Information (optional)
            if engine_info:
                engine_information = ET.SubElement(vehicle_type, 'engineInformation')
                engine_attributes = ET.SubElement(engine_information, 'attributes')
                ET.SubElement(engine_attributes, 'attribute', attrib={"name": "HbefaEmissionsConcept", "class": "java.lang.String"}).text = "average"
                ET.SubElement(engine_attributes, 'attribute', attrib={"name": "HbefaSizeClass", "class": "java.lang.String"}).text = "average"
                ET.SubElement(engine_attributes, 'attribute', attrib={"name": "HbefaTechnology", "class": "java.lang.String"}).text = "average"
                ET.SubElement(engine_attributes, 'attribute', attrib={"name": "HbefaVehicleCategory", "class": "java.lang.String"}).text = "NON_HBEFA_VEHICLE"

            # Remaining elements
            ET.SubElement(vehicle_type, 'costInformation')
            ET.SubElement(vehicle_type, 'passengerCarEquivalents', pce="1.0")
            ET.SubElement(vehicle_type, 'networkMode', networkMode="car")
            ET.SubElement(vehicle_type, 'flowEfficiencyFactor', factor="1.0")

            # Append the completed vehicleType to root
            root.append(vehicle_type)

# Function to add vehicles from CSV data
def add_vehicles_from_csv(csv_file):
    with open(csv_file, newline='') as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            operator = row['operator']
            line_number = row['line_number']
            vehicle_count = int(row['vehicle_count'])
            vehicle_type = row['vehicle_type']

            for i in range(vehicle_count):
                vehicle_id = f"pt_{operator}-{line_number}-{i}"
                vehicle = ET.Element('vehicle', id=vehicle_id, type=vehicle_type)
                root.append(vehicle)

# Example usage:
# Load vehicle types and vehicles from CSV
add_vehicle_type_from_csv('/Users/mkreuschnervsp/Desktop/kyoto_vehicle_types.csv')
add_vehicles_from_csv('/Users/mkreuschnervsp/Desktop/kyoto_vehicles.csv')

# Function to prettify and save the XML
def save_pretty_xml(element, filename):
    xml_string = ET.tostring(element, encoding='utf-8')
    pretty_xml = minidom.parseString(xml_string).toprettyxml(indent="    ")
    with open(filename, "w", encoding="utf-8") as file:
        file.write(pretty_xml)

# Save the prettified XML to a file
save_pretty_xml(root, '/Users/mkreuschnervsp/Desktop/matsim-kyoto_transitVehicles_pretty.xml')