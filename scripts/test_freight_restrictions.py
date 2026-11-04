import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

import freight_restrictions as rules

spec=importlib.util.spec_from_file_location('profile_audit',Path(__file__).with_name('audit-freight-profiles.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


def profile(clazz='truck'):
    return {'vehicleClass':clazz,'heightM':4,'widthM':2.55,'lengthM':10 if clazz=='truck' else 16.5,
            'actualMassKg':26000 if clazz=='truck' else 40000,'ratedGrossMassKg':26000 if clazz=='truck' else 40000,
            'curbMassKg':10000 if clazz=='truck' else 15000,'maxAxleLoadKg':None,'maxSpeedKmh':80}


class FreightRestrictionsTest(unittest.TestCase):
    def evaluate(self,tags,p=None,**kwargs):return rules.evaluate_tags(tags,p or profile(),**kwargs)

    def test_mass_units_and_exact_boundary(self):
        self.assertEqual('satisfied',self.evaluate({'maxweight':'26 t'})[0]['status'])
        self.assertEqual('violation',self.evaluate({'maxweight':'25999 kg'})[0]['status'])
        self.assertEqual(7500,rules.quantity('7.5','mass'))
        self.assertIsNone(rules.quantity('7,5','mass'))
        self.assertIsNone(rules.quantity('7.5;12','mass'))

    def test_actual_and_rated_mass_are_distinct(self):
        p=profile();p['actualMassKg']=10000
        events=self.evaluate({'maxweight':'12','maxweightrating':'12'},p)
        self.assertEqual(['satisfied','violation'],[e['status'] for e in events])

    def test_dimensions_and_imperial_units(self):
        self.assertEqual('violation',self.evaluate({'maxheight':'3.8 m'})[0]['status'])
        self.assertEqual('violation',self.evaluate({'maxwidth':'2'})[0]['status'])
        self.assertEqual('satisfied',self.evaluate({'maxlength':'1000 cm'})[0]['status'])
        self.assertAlmostEqual(3.9624,rules.quantity('13\'0"','length'))

    def test_unknown_values_are_not_unlimited(self):
        for value in ['default','none','unsigned','below_default','4 m; 3.8 m']:
            self.assertEqual('unknown',self.evaluate({'maxheight':value})[0]['status'])

    def test_axle_load_is_not_inferred_from_gross_mass(self):
        self.assertEqual('unknown',self.evaluate({'maxaxleload':'10'})[0]['status'])
        p=profile();p['maxAxleLoadKg']=11000
        self.assertEqual('violation',self.evaluate({'maxaxleload':'10'},p)[0]['status'])

    def test_directional_override_and_vehicle_scope(self):
        tags={'maxweight':'7.5','maxweight:forward':'30'}
        self.assertEqual(['satisfied'],[e['status'] for e in self.evaluate(tags)])
        self.assertEqual(['violation'],[e['status'] for e in self.evaluate(tags,backward=True)])
        self.assertEqual([],self.evaluate({'maxlength:hgv_articulated':'12'}))
        self.assertEqual('violation',self.evaluate({'maxlength:hgv_articulated':'12'},profile('trailer'))[0]['status'])

    def test_signage_metadata_is_not_a_restriction(self):
        self.assertEqual([],self.evaluate({'maxheight:signed':'no'}))
        self.assertEqual('unknown',self.evaluate({'maxheight:lanes':'3|4'})[0]['status'])

    def test_simple_weekly_window_and_overnight(self):
        berlin=ZoneInfo('Europe/Berlin')
        self.assertTrue(rules.condition_at('(Mo-Fr 07:00-16:00)',dt.datetime(2026,7,15,8,tzinfo=berlin)))
        self.assertFalse(rules.condition_at('(Mo-Fr 07:00-16:00)',dt.datetime(2026,7,18,8,tzinfo=berlin)))
        self.assertTrue(rules.condition_at('(Fr 22:00-06:00)',dt.datetime(2026,7,18,5,tzinfo=berlin)))
        self.assertFalse(rules.condition_at('(Fr 22:00-06:00)',dt.datetime(2026,7,17,5,tzinfo=berlin)))

    def test_changing_weather_holiday_and_missing_context_remain_unknown(self):
        a=dt.datetime(2026,7,15,21,59,tzinfo=ZoneInfo('Europe/Berlin'))
        self.assertIsNone(rules.condition_during('(22:00-06:00)',(a,a+dt.timedelta(minutes=2))))
        for condition in ['wet','PH','2024 Feb 03-2024 Jun 30']:
            self.assertIsNone(rules.condition_at(condition,a))
        self.assertEqual('unknown',self.evaluate({'maxweight:conditional':'7.5 @ (22:00-06:00)'})[0]['status'])

    def test_active_exception_overrides_static_limit(self):
        a=dt.datetime(2026,7,15,8,tzinfo=ZoneInfo('Europe/Berlin'))
        tags={'maxweight':'7.5','maxweight:conditional':'30 @ (Mo-Fr 07:00-16:00)'}
        self.assertEqual(['superseded','satisfied'],[e['status'] for e in self.evaluate(tags,window=(a,a+dt.timedelta(minutes=1)))])
        self.assertFalse(any(e['status']=='violation' for e in self.evaluate(tags)))
        a=a.replace(hour=20)
        self.assertEqual(['violation','inactive'],[e['status'] for e in self.evaluate(tags,window=(a,a+dt.timedelta(minutes=1)))])

    def test_speed_condition_is_not_an_access_ban(self):
        event=self.evaluate({'maxspeed:conditional':'80 @ wet'})[0]
        self.assertEqual('review',event['status'])
        self.assertEqual('speed-rule-not-access-ban',event['reason'])

    def test_access_exception_and_articulated_override_are_not_false_prohibitions(self):
        events=self.evaluate({'hgv':'no','hgv:conditional':'yes @ (delivery)'})
        self.assertFalse(any(e['status']=='violation' for e in events))
        self.assertTrue(any(e['status']=='unknown' for e in events))
        self.assertEqual([],self.evaluate({'hgv':'no','hgv_articulated':'yes'},profile('trailer')))
        self.assertEqual('violation',self.evaluate({'hgv':'no','hgv_articulated':'yes'})[0]['status'])

    def test_node_direction_without_orientation_stays_unknown(self):
        source=({'n':{'maxheight:backward':'3'}},{'1':{'tags':{},'nodes':['n']}},[])
        events=audit.edge_events('1',profile(),source)
        self.assertEqual('node-direction-not-resolved',events[0]['reason'])

    def test_timed_routes_account_for_missing_vehicle_and_type_mismatch(self):
        probes=[{'id':'p','vehicleClass':'truck','fromEdge':'1','toEdge':'2'}]
        source=({}, {'1':{'tags':{'maxheight':'4.2'},'nodes':[]},'2':{'tags':{'maxweight':'7.5'},'nodes':[]}}, [])
        routes=ET.fromstring('<routes><vehicle id="p" type="truck" depart="0"><route edges="1 2" exitTimes="5 10"/></vehicle></routes>')
        rows=audit.audit(routes,probes,{'truck':profile()},source,dt.datetime(2026,7,15,8,tzinfo=ZoneInfo('Europe/Berlin')))
        self.assertEqual('explicit-violation',rows[0]['status'])
        self.assertFalse(rows[0]['dimensionAccessCertified'])
        self.assertEqual('no-native-route',audit.audit(ET.fromstring('<routes/>'),probes,{'truck':profile()},source,dt.datetime.now(dt.timezone.utc))[0]['status'])
        routes.find('vehicle').set('type','trailer')
        with self.assertRaises(ValueError):audit.audit(routes,probes,{'truck':profile()},source,dt.datetime.now(dt.timezone.utc))

    def test_catalog_provenance_and_invalid_loads(self):
        catalog=json.loads((Path(__file__).parents[1]/'backend/src/main/resources/vehicle-types.json').read_text())
        policy=json.loads(Path(__file__).with_name('freight-vehicle-profiles-v1.json').read_text())
        resolved=rules.resolve_profiles(policy,catalog)
        self.assertEqual(40000,resolved['trailer']['actualMassKg'])
        self.assertIsNone(resolved['truck']['maxAxleLoadKg'])
        for mutate in ['version','overload','axles']:
            bad=copy.deepcopy(policy)
            if mutate=='version':bad['profiles']['truck']['catalogVersion']='missing'
            if mutate=='overload':bad['profiles']['truck']['actualMassKg']=50000
            if mutate=='axles':bad['profiles']['truck']['axleLoadsKg']=[10000,10000]
            with self.subTest(mutate=mutate),self.assertRaises(ValueError):rules.resolve_profiles(bad,catalog)

    @unittest.skipUnless(shutil.which('netconvert') and shutil.which('duarouter'),'optional native SUMO tools unavailable')
    def test_native_diamond_routes_truck_and_trailer_by_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            (p/'nodes.xml').write_text('<nodes>'+''.join('<node id="{}" x="{}" y="{}"/>'.format(i,x,y) for i,x,y in [('0',-100,0),('a',0,0),('b',100,0),('c',100,100),('d',200,0),('z',300,0)])+'</nodes>')
            edges=[('10','0','a'),('11','a','b'),('12','b','d'),('13','a','c'),('14','c','d'),('15','d','z')]
            (p/'edges.xml').write_text('<edges>'+''.join('<edge id="{}" from="{}" to="{}" speed="10" numLanes="1"/>'.format(*e) for e in edges)+'</edges>')
            completed=subprocess.run(['netconvert','--node-files',str(p/'nodes.xml'),'--edge-files',str(p/'edges.xml'),'--output-file',str(p/'base.net.xml')],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,completed.returncode,completed.stderr)
            source=({}, {e[0]:{'tags':{'maxweight':'30'} if e[0]=='11' else {},'nodes':[]} for e in edges}, [])
            profiles={c:profile(c) for c in ('truck','trailer')}
            closures=audit.profile_network(p/'base.net.xml',p/'profile.net.xml',profiles,source)
            self.assertNotIn('11',closures['blockedSourceEdgesByClass']['truck'])
            self.assertIn('11',closures['blockedSourceEdgesByClass']['trailer'])
            (p/'trips.xml').write_text('<routes><vType id="truck" vClass="truck"/><vType id="trailer" vClass="trailer"/><trip id="t" type="truck" depart="0" from="10" to="15"/><trip id="s" type="trailer" depart="0" from="10" to="15"/></routes>')
            completed=subprocess.run(['duarouter','--net-file',str(p/'profile.net.xml'),'--route-files',str(p/'trips.xml'),'--output-file',str(p/'routes.xml'),'--exit-times','true'],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,completed.returncode,completed.stderr)
            routed={v.get('type'):v.find('route') for v in ET.parse(p/'routes.xml').getroot().findall('vehicle')}
            self.assertIn('11',routed['truck'].get('edges').split())
            self.assertNotIn('11',routed['trailer'].get('edges').split())
            self.assertTrue(routed['truck'].get('exitTimes'))


if __name__=='__main__':unittest.main()
