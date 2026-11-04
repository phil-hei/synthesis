import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('osm_addresses', Path(__file__).with_name('prepare-osm-tour-addresses.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class OsmTourAddressTest(unittest.TestCase):
    def test_absent_postcode_requires_explicit_policy_and_exact_municipality(self):
        self.assertIsNone(prepare.address_key('Weg', '4', '', 'Teststadt'))
        self.assertEqual(('weg', '4', 'city:teststadt'), prepare.address_key('Weg', '4', '', 'Teststadt', True))
        self.assertIsNone(prepare.address_key('Weg', '4', 'broken', 'Teststadt', True))
        draft = {'tours': [{'stops': [{'location': {'street': 'Weg 4', 'postalCode': '01234', 'city': 'Teststadt, Region'}}]}]}
        wanted = prepare.wanted_queries(draft, {}, {}, True, {prepare.nrw.normal('Teststadt, Region'): 'Teststadt'})
        self.assertIn(prepare.address_key('Weg', '4', '', 'Teststadt', True), wanted)
        self.assertNotIn(prepare.address_key('Weg', '4', '99999', 'Teststadt', True), wanted)
        self.assertNotIn(prepare.address_key('Weg', '4', '', 'Andere Stadt', True), wanted)

    def test_requires_exact_house_and_postcode_and_preserves_existing_candidates(self):
        self.assertNotEqual(prepare.address_key('Weg', '70-72', '01234'), prepare.address_key('Weg', '7072', '01234'))
        self.assertIsNone(prepare.address_key('Weg', '1', ''))
        draft = {'tours': [{'stops': [{'location': {'street': 'Teststr. 1', 'postalCode': '01234', 'city': 'Stadt'}},
                                      {'location': {'street': 'Teststr. 2', 'postalCode': '01234', 'city': 'Stadt'}}]}]}
        query = 'Teststr. 1, 01234 Stadt'
        index = {query: [], 'Teststr. 2, 01234 Stadt': [{'fixture': 'retained'}]}
        wanted = prepare.wanted_queries(draft, index, {})
        self.assertEqual({query}, wanted[prepare.address_key('Teststraße', '1', '01234')])
        self.assertNotIn(prepare.address_key('Teststraße', '2', '01234'), wanted)
        with self.assertRaises(ValueError):
            prepare.wanted_queries(draft, index, {'unknown': {'street': 'Weg 1', 'reason': 'Review'}})

    def test_real_osm_parser_keeps_ambiguity_and_marks_explicit_corrections(self):
        try:
            import osmium
            import shapely
        except ImportError:
            self.skipTest('Optional local OSM preparation runtime unavailable')
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'addresses.osm'
            source.write_text('''<osm version="0.6">
              <node id="1" lat="51" lon="8"><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/><tag k="addr:postcode" v="01234"/></node>
              <node id="2" lat="51.001" lon="8"><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/><tag k="addr:postcode" v="01234"/></node>
              <node id="3" lat="51" lon="9"><tag k="addr:street" v="Teststraße"/><tag k="addr:housenumber" v="1"/><tag k="addr:postcode" v="99999"/></node>
            </osm>''')
            draft = {'tours': [{'stops': [{'location': {'street': 'Tsetstraße 1', 'postalCode': '01234', 'city': 'Stadt'}}]}]}
            query = 'Tsetstraße 1, 01234 Stadt'
            index = {query: []}
            prepare.supplement([source], draft, index, {})
            self.assertEqual([], index[query])
            prepare.supplement([source], draft, index, {query: {'street': 'Teststraße 1', 'reason': 'Reviewed model assumption'}})
            self.assertEqual(2, len(index[query]))
            self.assertTrue(all('Modellannahme' in c['licence'] for c in index[query]))
            self.assertTrue(all(float(c['lon']) == 8 for c in index[query]))


if __name__ == '__main__':
    unittest.main()
