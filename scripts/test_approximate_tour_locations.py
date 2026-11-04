import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('approx',Path(__file__).with_name('approximate-tour-locations.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ApproximateLocationsTest(unittest.TestCase):
 def test_nearest_number_is_local_and_marked(self):
  a=[dict(postcode='12345',street='Weg',house='14',lon=7,lat=51,id='14'),dict(postcode='12345',street='Weg',house='10',lon=7.01,lat=51,id='10'),dict(postcode='54321',street='Weg',house='11',lon=9,lat=53,id='11')]
  r=m.choose(dict(street='Weg 11 A',postalCode='12345'),a)
  self.assertEqual('10',r['osm_id']);self.assertIn('approximate-nearest',r['licence'])
 def test_missing_street_uses_labelled_locality_proxy_not_other_postcode(self):
  a=[dict(postcode='12345',street='Weg',house=str(i),lon=7+i*.001,lat=51,id=str(i)) for i in range(3)]
  r=m.choose(dict(street='Unknown 8',postalCode='12345'),a)
  self.assertEqual('1',r['osm_id']);self.assertIn('postcode-address-centroid',r['approximation'])
  self.assertIsNone(m.choose(dict(street='Weg 1',postalCode='99999'),a))
if __name__=='__main__':unittest.main()
