import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as E

spec=importlib.util.spec_from_file_location('turns',Path(__file__).with_name('check-freight-turns.py'))
turns=importlib.util.module_from_spec(spec);spec.loader.exec_module(turns)


class FreightTurnTest(unittest.TestCase):
    def inspect(self,rule='no_right_turn',extra='',second='',connected='20'):
        osm=E.fromstring('<osm><relation id="1"><tag k="type" v="restriction"/>'
            '<member type="way" role="from" ref="10"/><member type="way" role="to" ref="20"/>'
            '<member type="node" role="via" ref="2"/><tag k="restriction" v="'+rule+'"/>'+extra+'</relation>'+second+'</osm>')
        net=E.fromstring('<net><edge id="10" from="1" to="2"><lane index="0"/></edge>'
            '<edge id="20" from="2" to="3"><lane index="0"/></edge><edge id="30" from="2" to="4"><lane index="0"/></edge>'
            '<connection from="10" to="'+connected+'" fromLane="0" toLane="0"/></net>')
        routes=E.fromstring('<routes><vehicle id="a" type="truck"><route edges="10 '+connected+'"/></vehicle></routes>')
        return turns.inspect(osm,net,routes)
    def test_detects_native_forbidden_connection_and_used_route(self):
        r=self.inspect();self.assertEqual(2,r['relationClassStatuses']['transfer-mismatch']);self.assertEqual(1,r['routesWithTurnViolation'])
    def test_u_turn_between_distinct_ways_does_not_require_opposite_id_sign(self):
        self.assertEqual(2,self.inspect('no_u_turn')['relationClassStatuses']['transfer-mismatch'])
    def test_no_rule_does_not_forbid_other_turn(self):
        self.assertEqual(2,self.inspect(connected='30')['relationClassStatuses']['verified-simple-transfer'])
    def test_only_rule_forbids_other_turn(self):
        self.assertEqual(2,self.inspect('only_straight_on',connected='30')['relationClassStatuses']['transfer-mismatch'])
    def test_only_rules_allow_union_of_targets(self):
        second='<relation id="2"><tag k="type" v="restriction"/><tag k="restriction" v="only_left_turn"/><member type="way" role="from" ref="10"/><member type="way" role="to" ref="30"/><member type="node" role="via" ref="2"/></relation>'
        self.assertEqual(4,self.inspect('only_straight_on',second=second,connected='30')['relationClassStatuses']['verified-simple-transfer'])
    def test_conditional_is_not_claimed_verified(self):
        r=self.inspect(extra='<tag k="restriction:conditional" v="no_right_turn @ (wet)"/>')
        self.assertEqual(2,r['relationClassStatuses']['unresolved'])
    def test_hgv_exception_and_other_mode(self):
        self.assertEqual(2,self.inspect(extra='<tag k="except" v="hgv"/>')['relationClassStatuses']['class-exempt'])
    def test_no_positive_reference_is_unresolved(self):
        # A native connection is required to certify a simple rule's transfer.
        osm=E.fromstring('<osm><relation id="1"><tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/><member type="way" role="from" ref="10"/><member type="way" role="to" ref="20"/><member type="node" role="via" ref="2"/></relation></osm>')
        net=E.fromstring('<net><edge id="10" from="1" to="2"><lane index="0"/></edge><edge id="20" from="2" to="3"><lane index="0"/></edge></net>')
        self.assertEqual(2,turns.inspect(osm,net,E.fromstring('<routes/>'))['relationClassStatuses']['unresolved'])


if __name__=='__main__':unittest.main()
