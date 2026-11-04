import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('counts', pathlib.Path(__file__).with_name('compare-freight-counts.py'))
counts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(counts)


class CounterComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.p = pathlib.Path(self.temp.name)
        (self.p/'net.xml').write_text('<net><edge id="approach"/><edge id="north"/><edge id="south"/><edge id="exit"/></net>')
        (self.p/'routes.xml').write_text('<routes><vType id="H" vClass="truck"/><vType id="B" vClass="bus"/><vehicle id="h" type="H"><route edges="approach north south north exit"/></vehicle><vehicle id="b" type="B"><route edges="approach north exit"/></vehicle></routes>')
        (self.p/'tripinfo.xml').write_text('<tripinfos><tripinfo id="h" arrival="100"/><tripinfo id="b" arrival="110"/></tripinfos>')
        (self.p/'statistics.xml').write_text('<statistics><teleports total="0"/></statistics>')
        self.obs = {'observations':[dict(station='1',sgv=100,month='2026-07',period='Mo-So',unit='vehicles/calendar-day')]}
        self.mapping = dict(networkSha256=hashlib.sha256((self.p/'net.xml').read_bytes()).hexdigest(),
                            month='2026-07',assumption='Partial modeled accesses; no calibration',
                            stations=[dict(station='1',status='reviewed-geometric-match',officialTypeCode='01',
                                           edges=[dict(edgeId='north',distanceMetres=5),dict(edgeId='south',distanceMetres=6)])])

    def run_comparison(self):
        (self.p/'obs.json').write_text(json.dumps(self.obs))
        (self.p/'mapping.json').write_text(json.dumps(self.mapping))
        return counts.compare(self.p/'routes.xml',self.p/'net.xml',self.p/'tripinfo.xml',self.p/'obs.json',self.p/'mapping.json',self.p/'statistics.xml')

    def test_bus_excluded_and_repeated_passages_counted(self):
        report = self.run_comparison()
        self.assertEqual(1,report['completedHeavyVehicles'])
        self.assertEqual(3,report['stations'][0]['completedHeavyCrossings'])
        self.assertEqual(.03,report['stations'][0]['partialToObservedRatio'])
        self.assertFalse(report['calibrated'])

    def test_teleported_routes_are_not_proven_passages(self):
        (self.p/'statistics.xml').write_text('<statistics><teleports total="1"/></statistics>')
        with self.assertRaisesRegex(ValueError,'teleports'):self.run_comparison()

    def test_unfinished_route_not_counted(self):
        (self.p/'tripinfo.xml').write_text('<tripinfos><tripinfo id="h" arrival="-1"/><tripinfo id="b" arrival="110"/></tripinfos>')
        report = self.run_comparison()
        self.assertEqual(0,report['completedHeavyVehicles'])
        self.assertEqual(1,report['routesWithoutCompletedTripinfo'])

    def test_unresolved_identity_has_no_comparison(self):
        self.mapping['stations'][0].update(status='unresolved',reason='manual station identity')
        row=self.run_comparison()['stations'][0]
        self.assertEqual('not_comparable',row['status'])
        self.assertNotIn('partialToObservedRatio',row)

    def test_wrong_network_period_manual_type_or_edge_rejected(self):
        for target,key,value in [(self.mapping,'networkSha256','wrong'),(self.obs['observations'][0],'period','Mo-Fr'),
                                  (self.mapping['stations'][0],'officialTypeCode','03'),
                                  (self.mapping['stations'][0]['edges'][0],'edgeId','absent'),
                                  (self.mapping['stations'][0]['edges'][0],'distanceMetres',float('nan'))]:
            previous=target[key];target[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.run_comparison()
            target[key]=previous

    def test_counter_at_start_or_end_is_not_a_proven_passage(self):
        for route in ('north south exit', 'approach south north'):
            (self.p/'routes.xml').write_text('<routes><vType id="H" vClass="truck"/><vehicle id="h" type="H"><route edges="'+route+'"/></vehicle></routes>')
            (self.p/'tripinfo.xml').write_text('<tripinfos><tripinfo id="h" arrival="100"/></tripinfos>')
            with self.assertRaisesRegex(ValueError,'passage unknown'):self.run_comparison()

    def test_separate_type_definitions_and_missing_type_rejected(self):
        (self.p/'routes.xml').write_text('<routes><vehicle id="h" type="H"><route edges="approach north exit"/></vehicle></routes>')
        (self.p/'tripinfo.xml').write_text('<tripinfos><tripinfo id="h" arrival="100"/></tripinfos>')
        with self.assertRaisesRegex(ValueError,'unknown vehicle'):self.run_comparison()
        (self.p/'types.xml').write_text('<routes><vType id="H" vClass="trailer"/></routes>')
        report=counts.compare(self.p/'routes.xml',self.p/'net.xml',self.p/'tripinfo.xml',self.p/'obs.json',self.p/'mapping.json',self.p/'statistics.xml',self.p/'types.xml')
        self.assertEqual(1,report['stations'][0]['completedHeavyCrossings'])


if __name__=='__main__':unittest.main()
