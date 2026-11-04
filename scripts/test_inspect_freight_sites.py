import copy
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('sites', Path(__file__).with_name('inspect-freight-sites.py'))
sites = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sites)
fetch_spec = importlib.util.spec_from_file_location('fetch_sites', Path(__file__).with_name('fetch-overture-freight-sites.py'))
fetch = importlib.util.module_from_spec(fetch_spec)
fetch_spec.loader.exec_module(fetch)


class FreightSiteInventoryTest(unittest.TestCase):
    def setUp(self):
        self.row = {'id': 'stable-id', 'bbox': {'xmin': 7.5, 'xmax': 7.5, 'ymin': 51.5, 'ymax': 51.5},
                    'geometry': {'type': 'Point', 'coordinates': [7.5, 51.5]},
                    'taxonomy': {'primary': 'warehouse', 'hierarchy': ['business', 'warehouse']},
                    'names': {'primary': 'Example'}, 'sources': [{'dataset': 'example', 'license': 'CC0-1.0'}]}
        self.bbox = [7.3, 51.4, 7.85, 51.65]

    def run_inventory(self, rows, categories=None):
        return sites.inspect(rows, self.bbox, {'warehouse'} if categories is None else categories)

    def test_provenance_and_unknown_status_preserved_without_demand(self):
        result, report = self.run_inventory([self.row])
        self.assertEqual(self.row['sources'], result[0]['sources'])
        self.assertEqual('unknown', result[0]['operatingStatus'])
        self.assertTrue(result[0]['reviewRequired'])
        self.assertFalse(result[0]['truckAccessVerified'])
        self.assertFalse(report['simulationReady'])
        self.assertNotIn('trips', result[0])

    def test_primary_category_must_match_explicitly(self):
        result, report = self.run_inventory([self.row], {'business'})
        self.assertEqual([], result)
        self.assertEqual({'warehouse': 1}, report['primaryCategoryCounts'])
        self.assertEqual({'categoryNotSelected': 1}, report['rejectedRecords'])

    def test_duplicates_are_rejected_not_counted_twice(self):
        with self.assertRaisesRegex(ValueError, 'unique'):
            self.run_inventory([self.row, self.row])

    def test_invalid_outside_nonpoint_closed_and_missing_source_are_balanced(self):
        rows = []
        for i in range(5):
            row = copy.deepcopy(self.row)
            row['id'] = str(i)
            rows.append(row)
        rows[0]['geometry']['coordinates'][0] = float('nan')
        rows[1]['geometry']['coordinates'][0] = 8
        rows[2]['geometry']['type'] = 'Polygon'
        rows[3]['operating_status'] = 'permanently_closed'
        rows[4]['sources'] = []
        result, report = self.run_inventory(rows)
        self.assertEqual([], result)
        self.assertEqual(5, sum(report['rejectedRecords'].values()))
        self.assertEqual(1, report['rejectedRecords']['closed'])

    def test_legacy_schema_and_boundary_points(self):
        row = copy.deepcopy(self.row)
        row['categories'] = row.pop('taxonomy')
        row['geometry']['coordinates'][0] = self.bbox[0]
        self.assertEqual(1, len(self.run_inventory([row])[0]))

    def test_rounded_bbox_never_replaces_exact_point(self):
        row = copy.deepcopy(self.row)
        row['bbox'] = {'xmin': 7.49, 'xmax': 7.51, 'ymin': 51.49, 'ymax': 51.51}
        self.assertEqual([7.5, 51.5], self.run_inventory([row])[0][0]['point'])

    def test_bad_bbox_fails(self):
        for bbox in [[7, 51, 6, 52], [7, 51, 8, float('nan')], [0, -91, 1, 1]]:
            with self.assertRaises(ValueError):
                sites.inspect([], bbox, set())

    def test_wkb_point_preserves_coordinates_in_both_endiannesses(self):
        for endian, marker in [('<', 1), ('>', 0)]:
            wkb = bytes([marker]) + struct.pack(endian + 'Idd', 1, 7.512345678, 51.512345678)
            self.assertEqual({'type': 'Point', 'coordinates': [7.512345678, 51.512345678]}, fetch.decode_point(wkb))
        with self.assertRaises(ValueError):
            fetch.decode_point(b'not a point')
        with self.assertRaises(ValueError):
            fetch.decode_point(bytes([1]) + struct.pack('<Idd', 2, 7.5, 51.5))

    def test_municipality_holes_and_ambiguity_remain_unassigned(self):
        result, _ = self.run_inventory([self.row])
        polygon = {'type': 'Feature', 'properties': {'ags_ziel': '05913000'},
                   'geometry': {'type': 'Polygon', 'coordinates': [[[7,51],[8,51],[8,52],[7,52],[7,51]]]}}
        collection = {'type': 'FeatureCollection', 'features': [polygon]}
        self.assertEqual({'05913000': 1}, sites.assign_municipalities(result, collection))
        duplicate = copy.deepcopy(polygon)
        duplicate['properties']['ags_ziel'] = '05978036'
        collection['features'].append(duplicate)
        self.assertEqual({'ambiguous': 1}, sites.assign_municipalities(result, collection))
        self.assertIsNone(result[0]['municipalityAgs'])
        collection['features'] = [polygon]
        polygon['geometry']['coordinates'].append([[7.4,51.4],[7.6,51.4],[7.6,51.6],[7.4,51.6],[7.4,51.4]])
        self.assertEqual({'unassigned': 1}, sites.assign_municipalities(result, collection))


if __name__ == '__main__':
    unittest.main()
