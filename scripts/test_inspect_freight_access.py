import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('access',Path(__file__).with_name('inspect-freight-access.py'))
access=importlib.util.module_from_spec(spec)
spec.loader.exec_module(access)


class FreightAccessTest(unittest.TestCase):
    def edge(self, **attributes):
        edge=ET.Element('edge')
        ET.SubElement(edge,'lane',**attributes)
        return edge

    def test_class_permissions(self):
        self.assertTrue(access.permits(self.edge(),'truck'))
        self.assertFalse(access.permits(self.edge(allow='passenger bus'),'truck'))
        self.assertFalse(access.permits(self.edge(disallow='all'),'trailer'))
        self.assertTrue(access.permits(self.edge(allow='truck trailer'),'trailer'))

    def test_source_rules_are_not_silently_cleared(self):
        self.assertEqual('hgv-prohibited',access.restriction({'hgv':'no'}))
        self.assertIsNone(access.restriction({'hgv':'no','hgv:forward':'yes'}))
        self.assertEqual('hgv-prohibited',access.restriction({'hgv':'no','hgv:forward':'yes'},True))
        for tags in [{'maxheight':'4'},{'hgv:conditional':'no @ (22:00-06:00)'},{'access':'private'},
                     {'hgv':'destination'},{'bridge':'yes'}]:
            with self.subTest(tags=tags):self.assertIsNotNone(access.restriction(tags))

    def test_segment_distance_in_metres(self):
        self.assertEqual(10,access.segment_distance((50,10),[(0,0),(100,0)]))
        self.assertEqual(5,access.segment_distance((3,4),[(0,0),(0,0)]))

    def test_native_evidence_keeps_unresolved_path_rules_visible(self):
        probes=[{'id':'p','siteId':'s','vehicleClass':'truck','direction':'in','fromEdge':'a','toEdge':'b'}]
        edges={k:{'xml':self.edge(allow='truck'),'restriction':None} for k in ('a','b')}
        routes=ET.fromstring('<routes><vehicle id="p" type="truck"><route edges="a b"/></vehicle></routes>')
        self.assertEqual('native-class-route-found',access.evaluate(probes,routes,edges)[0]['status'])
        edges['b']['restriction']='unresolved-dimension-or-conditional'
        result=access.evaluate(probes,routes,edges)[0]
        self.assertEqual('route-has-unresolved-source-rules',result['status'])
        self.assertFalse(result['physicalEntranceVerified'])
        self.assertEqual('no-native-route',access.evaluate(probes,ET.fromstring('<routes/>'),edges)[0]['status'])

    def test_wrong_native_type_endpoints_or_unknown_id_fail(self):
        probes=[{'id':'p','siteId':'s','vehicleClass':'truck','direction':'in','fromEdge':'a','toEdge':'b'}]
        edges={k:{'xml':self.edge(),'restriction':None} for k in ('a','b')}
        for xml in ['<routes><vehicle id="p" type="trailer"><route edges="a b"/></vehicle></routes>',
                    '<routes><vehicle id="p" type="truck"><route edges="b a"/></vehicle></routes>',
                    '<routes><vehicle id="other" type="truck"><route edges="a b"/></vehicle></routes>']:
            with self.subTest(xml=xml),self.assertRaises(ValueError):access.evaluate(probes,ET.fromstring(xml),edges)

    @unittest.skipUnless(importlib.util.find_spec('pyproj'), 'optional projection runtime unavailable')
    def test_conditional_relation_members_are_not_cleared(self):
        from pyproj import Proj
        projection='+proj=utm +zone=32 +datum=WGS84 +units=m +no_defs'
        x,y=Proj(projection)(7.5,51.5)
        net=ET.Element('net')
        ET.SubElement(net,'location',projParameter=projection,netOffset='0,0')
        for identifier,offset in [('1',1000),('2',0)]:
            edge=ET.SubElement(net,'edge',id=identifier)
            ET.SubElement(edge,'lane',allow='truck trailer',length='100',shape='{},{} {},{}'.format(x+offset,y,x+offset+100,y))
        osm=ET.fromstring('<osm><way id="1"><tag k="highway" v="primary"/></way><way id="2"><tag k="highway" v="residential"/></way><relation id="9"><member type="way" ref="2"/><tag k="type" v="restriction"/><tag k="restriction:conditional" v="no_left_turn @ (22:00-06:00)"/></relation></osm>')
        sites={'features':[{'geometry':{'type':'Point','coordinates':[7.5,51.5]},'properties':{'id':'s'}}]}
        rows,_,_,edges,_=access.inspect(sites,net,osm,1)
        self.assertEqual('unresolved-relation-rule',edges['2']['restriction'])
        self.assertEqual([],rows[0]['candidates']['truck'])
        osm.remove(osm.find('relation'))
        gate=ET.SubElement(osm,'node',id='77')
        ET.SubElement(gate,'tag',k='barrier',v='gate')
        ET.SubElement(osm.findall('way')[1],'nd',ref='77')
        _,_,_,edges,_=access.inspect(sites,net,osm,1)
        self.assertEqual('unresolved-node-control',edges['2']['restriction'])

    def test_streaming_readers_keep_permissions_controls_and_relations(self):
        with tempfile.TemporaryDirectory() as directory:
            net=Path(directory)/'network.xml'
            net.write_text('<net><location projParameter="!" netOffset="0,0"/><edge id="1"><lane allow="truck" shape="0,0 1,1" length="1"/></edge><edge id=":internal" function="internal"><lane/></edge><junction id="j"/></net>')
            compact=access.compact_network(net)
            self.assertEqual(['1'],[e.get('id') for e in compact.findall('edge')])
            self.assertEqual('truck',compact.find('edge/lane').get('allow'))
            osm=Path(directory)/'roads.osm'
            osm.write_text('<osm><node id="1"><tag k="barrier" v="gate"/></node><node id="2"/><way id="4"><nd ref="1"/><nd ref="2"/><tag k="hgv" v="no"/></way><relation id="7"><member type="way" ref="4"/><tag k="restriction:conditional" v="no_left_turn @ (Mo)"/></relation></osm>')
            compact=access.compact_osm(osm)
            self.assertEqual(['1'],[n.get('id') for n in compact.findall('node')])
            self.assertEqual(['1'],[n.get('ref') for n in compact.findall('way/nd')])
            self.assertEqual('no',compact.find('way/tag').get('v'))
            self.assertEqual('restriction:conditional',compact.find('relation/tag').get('k'))


if __name__=='__main__':unittest.main()
