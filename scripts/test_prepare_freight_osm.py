import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('osm_prepare',Path(__file__).with_name('prepare-freight-osm.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class OsmSitePolicyTest(unittest.TestCase):
    def test_explicit_classes_only(self):
        self.assertEqual('warehouse',prepare.category({'building':'warehouse'}))
        self.assertEqual('grocery_store',prepare.category({'shop':'supermarket'}))
        self.assertEqual('freight_and_cargo_service',prepare.category({'office':'logistics'}))
        self.assertIsNone(prepare.category({'name':'Logistics Centre'}))
        self.assertIsNone(prepare.category({'landuse':'industrial'}))


@unittest.skipUnless(importlib.util.find_spec('osmium') and importlib.util.find_spec('shapely'),
                     'optional pilot pyosmium/shapely runtime unavailable')
class OsmExtractIntegrationTest(unittest.TestCase):
    def test_nodes_polygons_relations_and_native_road_import(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            osm=ET.Element('osm',version='0.6')
            points={1:(7.001,51.001),2:(7.002,51.001),3:(7.003,51.001),20:(7.005,51.005)}
            points.update({4:(7.004,51.004),5:(7.006,51.004),6:(7.006,51.006),7:(7.004,51.006)})
            points.update({8:(7.007,51.007),9:(7.009,51.007),10:(7.009,51.009),11:(7.007,51.009)})
            for identifier,(lon,lat) in sorted(points.items()):
                node=ET.SubElement(osm,'node',id=str(identifier),lon=str(lon),lat=str(lat),version='1')
                if identifier==20:
                    ET.SubElement(node,'tag',k='shop',v='supermarket')
            for identifier,nodes,tags in [(10,[1,2],{'highway':'residential'}),(11,[2,3],{'highway':'residential'}),
                                          (30,[4,5,6,7,4],{'building':'warehouse'}),(41,[8,9,10,11,8],{})]:
                way=ET.SubElement(osm,'way',id=str(identifier),version='1')
                for node in nodes:ET.SubElement(way,'nd',ref=str(node))
                for k,v in tags.items():ET.SubElement(way,'tag',k=k,v=v)
            area=ET.SubElement(osm,'relation',id='40',version='1')
            ET.SubElement(area,'member',type='way',ref='41',role='outer')
            for k,v in {'type':'multipolygon','building':'warehouse'}.items():ET.SubElement(area,'tag',k=k,v=v)
            restriction=ET.SubElement(osm,'relation',id='99',version='1')
            for kind,ref,role in [('way','10','from'),('way','11','to'),('node','2','via')]:
                ET.SubElement(restriction,'member',type=kind,ref=ref,role=role)
            for k,v in {'type':'restriction','restriction':'no_straight_on'}.items():ET.SubElement(restriction,'tag',k=k,v=v)
            source=root/'input.osm'
            ET.ElementTree(osm).write(source)
            report=prepare.extract(source,[7,51,7.01,51.01],root/'extract')
            self.assertEqual(3,report['siteRecords'])
            self.assertEqual(2,report['copiedRoadObjects']['ways'])
            self.assertEqual(1,report['copiedRoadObjects']['restrictionRelations'])
            roads=ET.parse(root/'extract/roads.osm').getroot()
            ids={n.attrib['id'] for n in roads.findall('node')}
            self.assertTrue(all(n.attrib['ref'] in ids for w in roads.findall('way') for n in w.findall('nd')))
            if shutil.which('netconvert'):
                process=subprocess.run(['netconvert','--osm-files',str(root/'extract/roads.osm'),
                                        '--output-file',str(root/'network.net.xml')],capture_output=True,text=True,timeout=30)
                self.assertEqual(0,process.returncode,process.stderr)
                self.assertTrue(ET.parse(root/'network.net.xml').getroot().findall('edge'))


if __name__=='__main__':unittest.main()
