import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('toll', pathlib.Path(__file__).with_name('prepare-toll-freight.py'))
toll = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toll)


class TollPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / 'source.json'
        self.a = dict(monat=1782864000000, richtung=0, land='x', ags_start='05913000',
                      ags_ziel='05978036', name_start='Dortmund', name_ziel='Unna',
                      anzahl_befahrungen=100, fahrleistung_km=1800)

    def normal(self, values, **extra):
        self.path.write_text(json.dumps(dict(features=[{'attributes': x} for x in values], **extra)))
        return toll.normalize([self.path], '2026-07')

    def mapping(self, shares=(1,)):
        return dict(assumption='Explicit modeled accesses and assumed truck fleet, uniform all-day',
                    relations=[dict(id=f'route_{i}', ags_start='05913000', ags_ziel='05978036',
                                    vehicleClass='truck', movement='internal', fromLon=7.5,
                                    fromLat=51.5, toLon=7.7, toLat=51.5,
                                    fromEdge='edge_a', toEdge='edge_b', beginHour=0,
                                    endHour=24, share=share) for i, share in enumerate(shares)])

    def test_perspectives_deduplicated_not_summed(self):
        records, report = self.normal([self.a, dict(self.a, richtung=1, objectid=999)])
        self.assertEqual(100, report['monthlyObservedEntries'])
        self.assertEqual(1, report['duplicatePerspectivesRemoved'])
        self.assertFalse(report['dailyDemandGenerated'])

    def test_conflicting_duplicate_rejected(self):
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            self.normal([self.a, dict(self.a, richtung=1, anzahl_befahrungen=110)])

    def test_incomplete_response_rejected(self):
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.normal([self.a], exceededTransferLimit=True)

    def test_wrong_month_aggregate_and_suppression_rejected(self):
        for change in ({'monat': 0}, {'land': 'DE'}, {'anzahl_befahrungen': None},
                       {'anzahl_befahrungen': -1}, {'fahrleistung_km': float('nan')}, {'ags_ziel': '000'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.normal([dict(self.a, **change)])

    def test_calendar_day_largest_remainder_and_partial_coverage(self):
        records, report = self.normal([self.a, dict(self.a, ags_ziel='05913000', anzahl_befahrungen=200)])
        rows = toll.mapped_day(records, report, self.mapping((.5, .5)))
        self.assertEqual([2, 1], [x['trips'] for x in rows])
        self.assertEqual(200, report['unmappedMonthlyEntries'])
        self.assertFalse(report['mappingValidatedAgainstNetwork'])
        self.assertFalse(report['calibrated'])
        self.assertEqual(self.mapping((.5, .5)), report['mapping'])
        self.assertEqual(64, len(report['mappingSha256']))

    def test_no_overallocation_or_implicit_mapping(self):
        records, report = self.normal([self.a])
        for mapping in (self.mapping((.7, .7)), dict(self.mapping(), assumption=''),
                        dict(self.mapping(), relations=[])):
            with self.assertRaises(ValueError):
                toll.mapped_day(records, dict(report), mapping)

    def test_nonfinite_and_identical_edges_rejected(self):
        records, report = self.normal([self.a])
        for change in ({'share': float('nan')}, {'fromLon': float('inf')},
                       {'toEdge': 'edge_a'}, {'endHour': 25}):
            mapping = self.mapping()
            mapping['relations'][0].update(change)
            with self.assertRaises(ValueError):
                toll.mapped_day(records, dict(report), mapping)


if __name__ == '__main__':
    unittest.main()
