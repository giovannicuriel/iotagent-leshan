from xml.dom import minidom
import defusedxml.ElementTree as ET
from pprint import pprint
from optparse import OptionParser
import json

parser = OptionParser()
parser.add_option("-i", "--input", dest="input_file",
                  help="input xml file", metavar="FILE")
parser.add_option("-o", "--output", dest="output_file",
                  help="output json file", metavar="FILE", default="out.json")


def gettype(op):
    if op == "RW" or op == "E":
        return "actuator"
    if op == "R":
        return "static"

def get_attribute(item, resource_id, resource_name):
    attr_id = item.attrib['ID']
    label = item.findall("Name")[0].text
    op = item.findall("Operations")[0].text
    attr_type = item.findall("Type")[0].text
    units = item.findall("Units")[0].text
    attr = {
        "label": resource_name + ": " + label,
        "type" : gettype(op),
        "value_type": attr_type.lower(),
        "metadata": [
            {"label": "oi", "static_value": "/{}/0/{}".format(resource_id, attr_id), "type": "lwm2m", "value_type": "string"}
        ]
    }

    if units is not None:
        attr['metadata'].append({"label": "unit", "static_value": units, "type": "meta", "value_type": "string" })

    if attr["type"] == "static":
        attr["static_value"] = ""

    return attr

def gettemplate(root):
    root_object = root.findall("Object")[0]
    object_id = root_object.findall("ObjectID")[0].text
    resources = root_object.findall("Resources")[0]
    name = root_object.findall("Name")[0].text
    items = resources.findall("Item")
    attrs = [get_attribute(item, object_id, name) for item in items]

    template = {
        "label": name,
        "attrs": attrs
    }

    return template


(options, args) = parser.parse_args()

tree = ET.parse(options.input_file)
root = tree.getroot()
template = gettemplate(root)
pprint(template)

with open(options.output_file, "w+") as f:
    json.dump(template, f, indent=2, separators=(',', ': '))

# print(xmldoc.getElementsByTagName('Name'))
#
# itemlist = xmldoc.getElementsByTagName('Item')
#
# name = xmldoc.getElementsByTagName('Name')[0]

#
# print(len(itemlist))
# for p in itemlist:

#
#
# print(p.attributes['ID'].value)
# print(p)