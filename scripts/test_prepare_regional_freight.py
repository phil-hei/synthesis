import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('regional', Path(__file__).with_name('prepare-regional-freight.py'))
regional = importlib.util.module_from_spec(spec)
spec.loader.exec_module(regional)


class RegionalFreightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        p = Path(self.temp.name)/'net.xml'
        p.write_text('''<net>
          <edge id="isolated"><lane allow="truck"/></edge>
          <edge id="car"><lane allow="passenger"/></edge>
          <edge id="a"><lane allow="truck trailer"/></edge>
          <edge id="b"><lane allow="truck trailer"/></edge>
          <connection from="a" to="b"/><connection from="b" to="a"/>
          </net>''')
        shapes = {'isolated': [[7.01,51.01],[7.09,51.01]], 'car': [[7.01,51.02],[7.09,51.02]],
                  'a': [[7.01,51.03],[7.09,51.03]], 'b': [[7.09,51.04],[7.01,51.04]]}
        geo = {'features':[{'properties':{'edgeId':k},'geometry':{'coordinates':v}} for k,v in shapes.items()]}
        self.box = [7,51,7.1,51.1]
        self.network = regional.Network(p, geo, self.box)

    def demand(self, relations, **extra):
        return dict(source='Synthetic test only', referenceDate='2026-09-29', relations=relations, **extra)

    def test_all_four_movements_and_outside_nonintersection(self):
        relations = [dict(id='internal', **{'from':[7.02,51.03], 'to':[7.08,51.04]}, trips=2),
                     dict(id='inbound', **{'from':[6.9,51.03], 'to':[7.08,51.04]}, trips=3),
                     dict(id='outbound', **{'from':[7.02,51.03], 'to':[7.2,51.04]}, trips=4),
                     dict(id='through', **{'from':[6.9,51.03], 'to':[7.2,51.04]}, trips=5),
                     dict(id='irrelevant', **{'from':[6,50], 'to':[6.1,50.1]}, trips=10)]
        rows, report = regional.prepare(self.demand(relations), self.network, self.box)
        self.assertEqual(dict(internal=2,inbound=3,outbound=4,through=5),report['byMovement'])
        self.assertEqual(14, sum(r['trips'] for r in rows))
        self.assertEqual(10, report['unmappedPeriodTrips'])
        self.assertFalse(report['calibrated'])
        self.assertEqual('additive; overlaps ignored; no subtraction',report['companyToursPolicy'])
        for row in rows:
            self.assertTrue(regional.inside([row['fromLon'],row['fromLat']],self.box))
            self.assertTrue(regional.inside([row['toLon'],row['toLat']],self.box))

    def test_nearest_connected_pair_ignores_closer_island_and_car_road(self):
        a,b = self.network.snap_pair([7.02,51.01],[7.08,51.04],'truck')
        self.assertEqual(('a','b'),(a[1],b[1]))
        self.assertGreater(a[0],1000)

    def test_conserves_rounded_monthly_total_and_order_independence(self):
        rs=[dict(id=str(i), **{'from':[7.02,51.03], 'to':[7.08,51.04]}, trips=10) for i in range(3)]
        x=regional.prepare(self.demand(rs,periodDays=31),self.network,self.box)
        y=regional.prepare(self.demand(list(reversed(rs)),periodDays=31),self.network,self.box)
        self.assertEqual(x,y)
        self.assertEqual(1,sum(r['trips'] for r in x[0]))
        self.assertEqual(3,len(x[1]['assignments']))

    def test_distinct_edges_at_one_junction_must_not_emit_identical_endpoints(self):
        # A coarse OD can snap to a common junction on two different edges.
        # The platform requires two distinct endpoint coordinates as well as IDs.
        self.network.shapes={'a': [((7.01,51.03),(7.05,51.03))],
                             'b': [((7.05,51.03),(7.09,51.03))]}
        with self.assertRaises(ValueError):
            self.network.snap_pair((7.05,51.02),(7.05,51.04),'truck')
        self.network.shapes['c']=[((7.01,51.032),(7.09,51.032))]
        self.network.permissions['c']={'truck'}
        self.network.links['a'].append('c')
        self.network.reach_cache.clear()
        f,t=self.network.snap_pair((7.05,51.02),(7.05,51.04),'truck')
        self.assertNotEqual(f[2],t[2])

    def test_bad_inputs_rejected(self):
        r=dict(id='x', **{'from':[7.02,51.03], 'to':[7.08,51.04]}, trips=10)
        for change in ({'trips':float('nan')},{'vehicleClass':'bus'},{'beginHour':25},{'id':'unsafe;id'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                regional.prepare(self.demand([dict(r,**change)]),self.network,self.box)
        with self.assertRaises(ValueError):
            regional.prepare(self.demand([r,r]),self.network,self.box)

    def test_tangent_and_same_point_are_not_invented_trips(self):
        self.assertIsNone(regional.clip([6,50],[7,51],self.box))
        rows,report=regional.prepare(self.demand([dict(id='same',**{'from':[7.02,51.03],'to':[7.02,51.03]},trips=5)]),self.network,self.box)
        self.assertEqual([],rows)
        self.assertEqual(5,report['unmappedPeriodTrips'])

if __name__ == '__main__': unittest.main()
