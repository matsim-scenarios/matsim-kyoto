import os
import xml.etree.ElementTree as ET

def merge_transit_schedules(input_folder, output_file):
    # Create a new root for the merged document
    merged_root = ET.Element("transitSchedule")

    # Create placeholders for unique stops and lines
    transitStops = ET.Element("transitStops")
    merged_root.append(transitStops)
    stop_ids = set()

    transitLines = ET.Element("transitLines")
    merged_root.append(transitLines)
    line_ids = set()

    # Function to merge stops
    def merge_stops(root, stop_ids, transitStops):
        for stop in root.findall(".//stopFacility"):
            stop_id = stop.get("id")

            if stop_id not in stop_ids:
                stop_ids.add(stop_id)
                transitStops.append(stop)
            else:
                # Add a numerical suffix to duplicate stop IDs
                count = 1
                new_stop_id = f"{stop_id}+{count}"
                while new_stop_id in stop_ids:
                    count += 1
                    new_stop_id = f"{stop_id}+{count}"
                stop_ids.add(new_stop_id)

                # Update the stop ID
                stop.set("id", new_stop_id)
                transitStops.append(stop)


    def merge_lines(root, line_ids, transitLines, filename):
        for line in root.findall(".//transitLine"):
            line_id = line.get("id")

            # Append the filename to the line ID
            # to distinguish the same line ID from different agencies
            # new_line_id = f"{line_id}_{os.path.splitext(filename)[0]}"
            if line_id not in line_ids:
                line_ids.add(line_id)
                # line.set("id", new_line_id)  # Update the line ID with the filename
                transitLines.append(line)
            else:
                # If the line already exists, merge its routes
                existing_line = [l for l in transitLines if l.get("id") == line_id][0]
                route_ids = {r.get("id") for r in existing_line.findall(".//transitRoute")}

                for route in line.findall("transitRoute"):
                    route_id = route.get("id")
                    if route_id not in route_ids:
                        route_ids.add(route_id)
                        existing_line.append(route)
                # # If the line already exists, add a numerical suffix
                # count = 1
                # unique_line_id = f"{new_line_id}_{count}"
                # while unique_line_id in line_ids:
                #     count += 1
                #     unique_line_id = f"{new_line_id}_{count}"
                # line_ids.add(unique_line_id)

                # # Update the line ID
                # line.set("id", unique_line_id)
                # transitLines.append(line)


    # Process all XML files in the input folder
    for filename in os.listdir(input_folder):
        if filename.endswith(".xml"):
            file_path = os.path.join(input_folder, filename)
            tree = ET.parse(file_path)
            root = tree.getroot()

            # Copy attributes from the first file (if available)
            if merged_root.find("attributes") is None:
                attributes = root.find("attributes")
                if attributes is not None:
                    merged_root.append(attributes)

            # Merge stops and lines
            merge_stops(root, stop_ids, transitStops)
            merge_lines(root, line_ids, transitLines, filename)

    # Save the merged XML to the output file
    merged_tree = ET.ElementTree(merged_root)
    merged_tree.write(output_file, encoding="utf-8", xml_declaration=True)
    print(f"Merge complete. Output saved to {output_file}")

# Paths to your input folder and output file
input_folder = r"C:\Users\zhous\Desktop\zhouyh\berlin project\bus\KinkiBusXML"
output_file = r"C:\Users\zhous\Desktop\zhouyh\berlin project\bus\merged-KinkiBus.xml"

merge_transit_schedules(input_folder, output_file)