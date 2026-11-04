import copy
import importlib.util
import pathlib
import json
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('site_compare', pathlib.Path(__file__).with_name('compare-freight-sites.py'))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class FreightSiteComparisonTest(unittest.TestCase):
    def site(self, identifier, name='ACME GmbH', lon=7.5, category='warehouse'):
        return compare.read_sites({'features':[{'geometry':{'type':'Point','coordinates':[lon,51.5]},
                                    'properties':{'id':identifier,'name':name,'category':category}}]})[0]

    def test_same_name_nearby_is_review_not_merge(self):
        pairs, _, report = compare.compare([self.site('a')], [self.site('b', 'Acme-GmbH', 7.5002)])
        self.assertEqual('same-name-nearby', pairs[0]['classification'])
        self.assertTrue(pairs[0]['reviewRequired'])
        self.assertEqual(0, report['automaticMerges'])
        self.assertFalse(report['coverageImprovementProven'])

    def test_far_branches_and_unnamed_sites_not_name_matches(self):
        self.assertEqual([], compare.compare([self.site('a')], [self.site('b', lon=7.6)])[0])
        pairs = compare.compare([self.site('a', None)], [self.site('b', None)])[0]
        self.assertEqual('proximity-only', pairs[0]['classification'])

    def test_nearby_different_names_are_not_deduplicated(self):
        pairs, within, _ = compare.compare([self.site('a')], [self.site('b', 'Other business')])
        self.assertEqual('proximity-only', pairs[0]['classification'])
        self.assertEqual({'left':[], 'right':[]}, within)

    def test_same_name_category_conflict_remains_visible(self):
        pairs = compare.compare([self.site('a')], [self.site('b', category='grocery_store')])[0]
        self.assertFalse(pairs[0]['sameCategoryGroup'])

    def test_within_source_pair_once_and_deterministic(self):
        data = [self.site('b'), self.site('a')]
        a = compare.compare(data, [])
        self.assertEqual(1, len(a[1]['left']))
        self.assertEqual(a, compare.compare(list(reversed(data)), []))

    def test_duplicate_ids_and_invalid_coordinates_rejected(self):
        feature = {'geometry':{'type':'Point','coordinates':[7.5,51.5]}, 'properties':{'id':'same'}}
        with self.assertRaises(ValueError):
            compare.read_sites({'features':[feature, feature]})
        feature = copy.deepcopy(feature)
        feature['geometry']['coordinates'][0] = float('nan')
        with self.assertRaises(ValueError):
            compare.read_sites({'features':[feature]})

    def test_global_inputs_do_not_silently_use_regional_index(self):
        with self.assertRaisesRegex(ValueError,'regional spatial index'):
            compare.compare([self.site('a')],[self.site('b',lon=12)])

    def test_cli_scopes_both_sources_and_preserves_source_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            for source in ('left','right'):
                features=[]
                for suffix,lon in [('inside',7.5),('outside',12)]:
                    features.append({'type':'Feature','geometry':{'type':'Point','coordinates':[lon,51.5]},
                                     'properties':{'id':source+suffix,'name':'Warehouse','category':'warehouse','sources':[{'dataset':source}]}})
                (root/(source+'.json')).write_text(json.dumps({'features':features}))
            (root/'municipalities.json').write_text(json.dumps({'features':[{'properties':{'ags_ziel':'05913000'},'geometry':{'type':'Polygon','coordinates':[[[7.4,51.4],[7.6,51.4],[7.6,51.6],[7.4,51.6],[7.4,51.4]]]}}]}))
            result=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('compare-freight-sites.py')),
                                   '--left',str(root/'left.json'),'--right',str(root/'right.json'),
                                   '--municipalities',str(root/'municipalities.json'),'--output',str(root/'output')],
                                  capture_output=True,text=True,timeout=20)
            self.assertEqual(0,result.returncode,result.stderr)
            report=json.loads((root/'output/report.json').read_text())
            self.assertEqual(1,report['leftSites'])
            self.assertEqual(1,report['rightSites'])
            self.assertEqual({'left':2,'right':2},report['inputSitesBeforeSpatialScope'])
            scoped=json.loads((root/'output/left-scoped.geojson').read_text())
            self.assertEqual([{'dataset':'left'}],scoped['features'][0]['properties']['sources'])


if __name__ == '__main__':
    unittest.main()
