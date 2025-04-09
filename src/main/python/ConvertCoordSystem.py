import xml.etree.ElementTree as ET
from pyproj import Transformer

# Load your XML file which is in wgs84
tree = ET.parse(r'C:\Users\zhous\Desktop\zhouyh\berlin project\bus\merged-KinkiBus.xml')
root = tree.getroot()

# Setup the transformer: WGS84 -> EPSG:32653 (UTM Zone 53N)
transformer = Transformer.from_crs("EPSG:4326", "EPSG:32653", always_xy=True)

# Loop through all stopFacility elements
for stop in root.findall(".//stopFacility"):
    lon = float(stop.attrib['x'])
    lat = float(stop.attrib['y'])

    # Transform coordinates
    x_new, y_new = transformer.transform(lon, lat)  # Note the (lon, lat) order

    # Update attributes with new coordinates
    stop.set('x', f"{x_new:.9f}")
    stop.set('y', f"{y_new:.8f}")

# Save updated XML
tree.write(r'C:\Users\zhous\Desktop\zhouyh\berlin project\bus\busGTFS_schedule_converted.xml', encoding="utf-8", xml_declaration=True)