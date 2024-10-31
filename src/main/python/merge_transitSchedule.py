import xml.etree.ElementTree as ET

def merge_transit_schedules(file1, file2, output_file):
    # Parse the XML files
    tree1 = ET.parse(file1)
    root1 = tree1.getroot()

    tree2 = ET.parse(file2)
    root2 = tree2.getroot()

    # Create a new root for the merged document
    merged_root = ET.Element("transitSchedule")

    # Copy <attributes> from the first file
    attributes1 = root1.find("attributes")
    if attributes1 is not None:
        merged_root.append(attributes1)

    # Merge <transitStops>
    transitStops = ET.Element("transitStops")
    merged_root.append(transitStops)
    stop_ids = set()

    def merge_stops(root, stop_ids, transitStops):
        for stop in root.findall(".//stopFacility"):
            stop_id = stop.get("id")
            if stop_id not in stop_ids:
                stop_ids.add(stop_id)
                transitStops.append(stop)

    # Apply merging of stops from both files
    merge_stops(root1, stop_ids, transitStops)
    merge_stops(root2, stop_ids, transitStops)

    # Merge <transitLines>
    transitLines = ET.Element("transitLines")
    merged_root.append(transitLines)
    line_ids = set()

    def merge_lines(root, line_ids, transitLines):
        for line in root.findall(".//transitLine"):
            line_id = line.get("id")
            if line_id not in line_ids:
                line_ids.add(line_id)
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

    # Apply merging of lines and routes from both files
    merge_lines(root1, line_ids, transitLines)
    merge_lines(root2, line_ids, transitLines)

    # Save the merged XML to the output file
    merged_tree = ET.ElementTree(merged_root)
    merged_tree.write(output_file, encoding="utf-8", xml_declaration=True)
    print(f"Merge complete. Output saved to {output_file}")

# Paths to your input files and output file
file1 = "/Users/mkreuschnervsp/git/matsim-kyoto/input/kyotobus-transitSchedule.xml"
file2 = "/Users/mkreuschnervsp/git/matsim-kyoto/input/kyotocitybus-transitSchedule.xml"
output_file = "/Users/mkreuschnervsp/git/matsim-kyoto/input/mergedKyoto-transitSchedule.xml"

merge_transit_schedules(file1, file2, output_file)



