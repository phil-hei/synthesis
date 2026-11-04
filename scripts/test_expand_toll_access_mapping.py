import copy
import contextlib
import io
import json
import tempfile
from unittest.mock import patch
import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('expand',pathlib.Path(__file__).with_name('expand-toll-access-mapping.py'))
expand=importlib.util.module_from_spec(spec)
spec.loader.exec_module(expand)


class AccessDistributionTest(unittest.TestCase):
    def setUp(self):
        self.a,self.b='00000001','00000002'
        self.records={(self.a,self.b):{'anzahl_befahrungen':100},(self.a,self.a):{'anzahl_befahrungen':50}}
        self.catalog={'assumption':'Equal access-pair prior is modeled','classes':{'truck':.3,'trailer':.7},'departureHours':[6,20],
            'municipalities':{self.a:[dict(id='a1',point=[0,0],weight=1,edges=[{'edgeId':'a1'}]),dict(id='a2',point=[.2,0],weight=2,edges=[{'edgeId':'a2'}])],self.b:[dict(id='b',point=[2,0],weight=1,edges=[{'edgeId':'b'}])]}}
        self.net=ET.fromstring('<net><edge id="a1"><lane length="100" speed="10"/></edge><edge id="a2"><lane length="100" speed="10"/></edge><edge id="b"><lane length="100" speed="10"/></edge><edge id="bad#0"><lane length="100" speed="10"/></edge></net>')
        self.geo={'features':[{'properties':{'edgeId':eid},'geometry':{'coordinates':[[x,0],[x+.0001,0]]}} for eid,x in [('a1',0),('a2',.2),('b',2)]]}
        self.polys={'features':[{'properties':{'ags_ziel':ags},'geometry':{'type':'Polygon','coordinates':[[[left,-1],[right,-1],[right,1],[left,1],[left,-1]]]}} for ags,left,right in [(self.a,-1,1),(self.b,1,3)]]}

    def candidates(self):return expand.build_candidates(self.records,self.catalog,self.net,self.geo,self.polys)

    def routes(self,candidates):
        root=ET.Element('routes')
        for c in candidates:ET.SubElement(ET.SubElement(root,'vehicle',id=c['id'],type=c['vehicleClass']),'route',edges=c['fromEdge']+' '+c['toEdge'])
        return root

    def test_conserves_observed_od_and_modeled_class_shares(self):
        cs=self.candidates();mapping,report=expand.distribute(self.records,self.catalog,cs,self.routes(cs),self.net,set())
        self.assertEqual(4,len(cs))
        self.assertAlmostEqual(1,sum(r['share'] for r in mapping['relations']))
        self.assertAlmostEqual(.3,sum(r['share'] for r in mapping['relations'] if r['vehicleClass']=='truck'))
        self.assertEqual(100,report['mappedMonthlyEntries'])
        self.assertEqual(50,report['intramunicipalMonthlyEntriesExcluded'])
        self.assertFalse(report['sectionCountsUsedAsEntryWeights'])
        self.assertEqual(self.candidates(),cs)

    def test_excludes_unsupported_paths_and_reports_renormalization(self):
        cs=self.candidates();rs=self.routes(cs)
        for v in rs:
            if cs[int(v.attrib['id'].split('_')[-1])]['originNode']=='a1':v.find('route').set('edges','a1 bad#0 b')
        mapping,report=expand.distribute(self.records,self.catalog,cs,rs,self.net,{'bad'})
        self.assertEqual(2,report['routedCandidatesRejectedForUnsupportedHgv'])
        self.assertTrue(all(r['originNode']=='a2' for r in mapping['relations']))
        self.assertAlmostEqual(1,sum(r['share'] for r in mapping['relations']))

    def test_missing_positive_class_is_not_silently_reassigned(self):
        cs=self.candidates();rs=self.routes([c for c in cs if c['vehicleClass']=='truck'])
        with self.assertRaisesRegex(ValueError,'no complete OD-class'):expand.distribute(self.records,self.catalog,cs,rs,self.net,set())

    def test_wrong_municipality_edge_or_class_rejected(self):
        for change in ['municipality','edge','class','weight']:
            catalog=copy.deepcopy(self.catalog)
            if change=='municipality':catalog['municipalities'][self.a][0]['point']=[2,0]
            if change=='edge':catalog['municipalities'][self.a][0]['edges']=[{'edgeId':'absent'}]
            if change=='class':catalog['classes']['truck']=.5
            if change=='weight':catalog['municipalities'][self.a][0]['weight']=float('nan')
            with self.subTest(change=change),self.assertRaises(ValueError):expand.build_candidates(self.records,catalog,self.net,self.geo,self.polys)

    def test_polygon_hole_excluded(self):
        geometry={'type':'Polygon','coordinates':[[[-1,-1],[1,-1],[1,1],[-1,1],[-1,-1]],[[-.1,-.1],[.1,-.1],[.1,.1],[-.1,.1],[-.1,-.1]]]}
        self.assertFalse(expand.in_geometry([0,0],geometry));self.assertTrue(expand.in_geometry([.5,0],geometry))

    def test_phase_one_invalidates_old_derived_mapping(self):
        with tempfile.TemporaryDirectory() as temp:
            p=pathlib.Path(temp);output=p/'output';output.mkdir()
            for name in ['mapping.json','distribution-report.json']:(output/name).write_text('old')
            catalog=copy.deepcopy(self.catalog);catalog['month']='2026-07'
            for name,obj in [('catalog',catalog),('geo',self.geo),('municipalities',self.polys),('permissions',{'unsupportedHgvTags':[]})]:(p/(name+'.json')).write_text(json.dumps(obj))
            (p/'network.xml').write_text(ET.tostring(self.net,encoding='unicode'))
            observation=dict(monat=1782864000000,richtung=0,land='x',ags_start=self.a,ags_ziel=self.b,name_start='A',name_ziel='B',anzahl_befahrungen=100,fahrleistung_km=100)
            (p/'od.json').write_text(json.dumps({'features':[{'attributes':observation}]}))
            argv=['expand','--month','2026-07','--catalog',str(p/'catalog.json'),'--network',str(p/'network.xml'),'--network-geo',str(p/'geo.json'),'--municipalities',str(p/'municipalities.json'),'--permissions',str(p/'permissions.json'),'--output',str(output),str(p/'od.json')]
            with patch('sys.argv',argv),contextlib.redirect_stdout(io.StringIO()):expand.main()
            self.assertFalse((output/'mapping.json').exists());self.assertFalse((output/'distribution-report.json').exists())
            self.assertTrue((output/'candidate-report.json').exists())

    def test_mismatched_native_route_type_rejected(self):
        cs=self.candidates();rs=self.routes(cs);rs[0].set('type','passenger')
        with self.assertRaisesRegex(ValueError,'vehicle type mismatch'):expand.distribute(self.records,self.catalog,cs,rs,self.net,set())

    def test_mismatched_native_route_endpoints_rejected(self):
        cs=self.candidates();rs=self.routes(cs);rs[0].find('route').set('edges','b a1')
        with self.assertRaisesRegex(ValueError,'endpoints'):expand.distribute(self.records,self.catalog,cs,rs,self.net,set())


if __name__=='__main__':unittest.main()
