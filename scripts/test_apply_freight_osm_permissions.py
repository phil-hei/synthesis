import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('permissions',Path(__file__).with_name('apply-freight-osm-permissions.py'))
permissions=importlib.util.module_from_spec(spec)
spec.loader.exec_module(permissions)


class OsmFreightTransferTest(unittest.TestCase):
    def test_directional_override_preserves_other_classes_and_native_structure(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            (p/'roads.osm').write_text('<osm><way id="12"><tag k="hgv" v="no"/><tag k="hgv:forward" v="yes"/></way></osm>')
            (p/'net.xml').write_text('<net><edge id="12#0"><lane allow="passenger truck trailer"/></edge><edge id="-12#0"><lane allow="passenger truck trailer"/></edge><connection from="12#0" to="-12#0"/></net>')
            report=permissions.apply(p/'roads.osm',p/'net.xml',p/'result.xml')
            result=ET.parse(p/'result.xml').getroot()
            self.assertEqual(['-12#0'],report['restrictedEdges'])
            self.assertEqual('passenger',result.findall('edge')[1].find('lane').get('allow'))
            self.assertEqual('passenger truck trailer',result.findall('edge')[0].find('lane').get('allow'))
            self.assertIsNotNone(result.find('connection'))

    def test_allow_empty_and_disallow_rules(self):
        self.assertEqual({'disallow':'all'},permissions.restrict_lane({'allow':'truck trailer'}))
        self.assertEqual({'disallow':'bus trailer truck'},permissions.restrict_lane({'disallow':'bus'}))
        with self.assertRaises(ValueError):permissions.restrict_lane({'allow':'all'})

    def test_conditional_prohibition_not_reinterpreted_as_unconditional(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'roads.osm'
            path.write_text('<osm><way id="1"><tag k="hgv:conditional" v="no @ (Mo)"/></way></osm>')
            self.assertEqual({},permissions.rules(path))


if __name__=='__main__':unittest.main()
