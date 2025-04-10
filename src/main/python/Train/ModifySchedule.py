## Modify transportMode
import xml.etree.ElementTree as ET

# Load the XML file
tree = ET.parse(r"C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\train_schedule.xml")
root = tree.getroot()

# Loop through each transitRoute
for route in root.findall(".//transitRoute"):
    route_id = route.attrib.get("id", "")
    if "叡山" in route_id or "嵐電" in route_id:
        transport_mode = route.find("transportMode")
        if transport_mode is not None:
            transport_mode.text = "train_short"

# Save the modified XML
tree.write(r"C:\Users\zhous\Desktop\zhouyh\berlin project\timetable\train_schedule_new.xml", encoding="utf-8", xml_declaration=True)