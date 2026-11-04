import importlib.util
from pathlib import Path
import tempfile
import json
import unittest
spec=importlib.util.spec_from_file_location('spatial',Path(__file__).with_name('prepare-toll-regional-od.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class SpatialTollTest(unittest.TestCase):
 def test_duplicate_perspective_conserved_and_geometry_centers_labelled(self):
  a=dict(ags_start='05913000',ags_ziel='05978036',name_start='A',name_ziel='B',land='x',monat=1782864000000,anzahl_befahrungen=100,fahrleistung_km=1000)
  def feature(direction,lo):return dict(properties=dict(a,richtung=direction),geometry=dict(type='Polygon',coordinates=[[[lo,51],[lo+.1,51],[lo+.1,51.1],[lo,51.1],[lo,51]]]))
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'od.json';p.write_text(json.dumps(dict(features=[feature(0,7.4),feature(1,7.2)])))
   od,report=m.convert([p],'2026-07')
   self.assertEqual(1,len(od['relations']));self.assertEqual(100,od['relations'][0]['trips'])
   self.assertEqual(31,od['periodDays']);self.assertEqual(1,report['duplicatePerspectivesRemoved'])
   self.assertFalse(report['completeRegionalCoverage']);self.assertIn('bounding-box',report['spatialAssumption'])
if __name__=='__main__':unittest.main()
