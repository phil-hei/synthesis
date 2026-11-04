package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FreightProfileChecksTest {
    @TempDir Path temp;
    final FreightProfileChecks.Profile truck=new FreightProfileChecks.Profile("standard.heavyTruck.diesel","1","truck",10,2.55,4,26000,26000,80);
    final FreightProfileChecks.Profile trailer=new FreightProfileChecks.Profile("standard.articulatedTruck.diesel","1","trailer",16.5,2.55,4,40000,40000,80);
    List<FreightProfileChecks.Event> evaluate(Map<String,String> tags,FreightProfileChecks.Profile p) {
        return FreightProfileChecks.evaluate(tags,p,false,"way","10");
    }
    @Test void massDimensionsEqualityAndUnknowns() {
        assertTrue(evaluate(Map.of("maxweight","26","maxheight","400 cm"),truck).isEmpty());
        assertTrue(evaluate(Map.of("maxweight","30"),trailer).stream().anyMatch(e -> e.status().equals("violation")));
        assertEquals("unknown",evaluate(Map.of("maxaxleload","8"),truck).getFirst().status());
        assertEquals("unknown",evaluate(Map.of("maxheight","default"),truck).getFirst().status());
        assertEquals("unknown",evaluate(Map.of("maxwidth:lanes","2|3"),truck).getFirst().status());
        assertTrue(evaluate(Map.of("maxheight:backward","3"),truck).isEmpty());
        assertTrue(evaluate(Map.of("maxheight:signed","yes"),truck).isEmpty());
        assertTrue(evaluate(Map.of("maxweight:hgv","30"),truck).isEmpty());
        assertEquals("violation",evaluate(Map.of("maxweight","3.5","maxweight:hgv","30"),trailer).getFirst().status());
    }
    @Test void actualMassIsIndependentOfRatedGrossAndPhysicalClearanceStaysVisible() {
        var empty=new FreightProfileChecks.Profile("ref","1","truck",10,2.55,4,15000,26000,80);
        assertTrue(evaluate(Map.of("maxweight","20"),empty).isEmpty());
        assertEquals("violation",evaluate(Map.of("maxweightrating","20"),empty).getFirst().status());
        assertEquals("unknown",evaluate(Map.of("maxheight:physical","3.8"),empty).getFirst().status());
        assertEquals("unknown",evaluate(Map.of("hgv:lanes","no|yes"),empty).getFirst().status());
    }
    @Test void accessHierarchyConditionalExceptionsAndSpeed() {
        assertTrue(evaluate(Map.of("access","no","hgv","yes"),truck).isEmpty());
        assertEquals("unknown",evaluate(Map.of("hgv","delivery"),truck).getFirst().status());
        assertTrue(evaluate(Map.of("hgv","no","hgv:conditional","yes @ (delivery)"),truck).stream().noneMatch(e -> e.status().equals("violation")));
        assertTrue(evaluate(Map.of("maxheight","3.8","maxheight:conditional","4.2 @ (Mo-Fr 08:00-10:00)"),truck).stream().noneMatch(e -> e.status().equals("violation")));
        assertEquals("review",evaluate(Map.of("maxspeed:conditional","30 @ (wet)"),truck).getFirst().status());
        assertTrue(evaluate(Map.of("hgv","no","hgv_articulated","yes"),trailer).isEmpty());
        assertFalse(OsmFreightPermissions.hgvNo(Map.of("hgv","no","hgv_articulated","yes"),"trailer",false));
        assertFalse(OsmFreightPermissions.hgvNo(Map.of("hgv","no","hgv:conditional","yes @ (delivery)"),"truck",false));
        assertTrue(OsmFreightPermissions.hgvNo(Map.of("hgv","no","hgv:backward","yes"),"truck",false));
        assertFalse(OsmFreightPermissions.hgvNo(Map.of("hgv","no","hgv:backward","yes"),"truck",true));
    }
    Path file(String name,String text) throws Exception { var p=temp.resolve(name);Files.writeString(p,text);return p; }
    FreightProfileChecks fixture(String rule) throws Exception {
        return new FreightProfileChecks(file("source.osm","""
            <osm><node id="1"/><node id="2"/><node id="3"/><node id="4"/>
            <way id="10"><nd ref="1"/><nd ref="2"/></way>
            <way id="20"><nd ref="2"/><nd ref="3"/><tag k="maxweight" v="30"/></way>
            <way id="30"><nd ref="2"/><nd ref="4"/><tag k="access" v="destination"/></way>
            <relation id="99"><tag k="type" v="restriction"/><member type="way" role="from" ref="10"/><member type="way" role="to" ref="20"/><member type="node" role="via" ref="2"/>
            """+rule+"</relation></osm>"));
    }
    Path network() throws Exception {
        return file("net.xml","""
            <net><edge id="10" from="1" to="2"><lane index="0"/></edge>
            <edge id="20" from="2" to="3"><lane index="0" allow="truck trailer passenger"/></edge>
            <edge id="30" from="2" to="4"><lane index="0"/></edge></net>
            """);
    }
    @Test void preparationPreservesCarsAndOnlyBlocksViolatingProfile() throws Exception {
        var check=fixture("<tag k=\"restriction\" v=\"no_right_turn\"/>");var net=network();
        check.prepare(net,temp.resolve("permissions.json"));
        var edges=TrafficRoutes.elements(TrafficRoutes.read(net),"edge");
        assertEquals("passenger truck",TrafficRoutes.elements(edges.get(1),"lane").getFirst().getAttribute("allow"));
        assertFalse(TrafficRoutes.elements(edges.get(2),"lane").getFirst().hasAttribute("disallow"));
        assertEquals("source-turn-rule-violated",check.turnEvents(List.of("10","20"),"truck").getFirst().reason());
        assertTrue(check.turnEvents(List.of("10","30"),"truck").isEmpty());
    }
    @Test void uTurnBetweenDistinctWaysAndDirectionalNodesAreNotMissed() throws Exception {
        var check=fixture("<tag k=\"restriction\" v=\"no_u_turn\"/>");check.prepare(network(),temp.resolve("p.json"));
        assertEquals("violation",check.turnEvents(List.of("10","20"),"truck").getFirst().status());
        var osm=temp.resolve("source.osm");
        Files.writeString(osm,Files.readString(osm).replace("<node id=\"2\"/>","<node id=\"2\"><tag k=\"maxheight:backward\" v=\"3.8\"/></node>"));
        check=new FreightProfileChecks(osm);
        assertTrue(check.events("10",truck).stream().anyMatch(e -> e.reason().equals("node-position-or-direction-unresolved")));
    }
    @Test void onlyTurnExceptionAndConditionalRemainDistinct() throws Exception {
        var check=fixture("<tag k=\"restriction\" v=\"only_straight_on\"/>");check.prepare(network(),temp.resolve("p.json"));
        assertTrue(check.turnEvents(List.of("10","20"),"truck").isEmpty());
        assertEquals("violation",check.turnEvents(List.of("10","30"),"truck").getFirst().status());
        check=fixture("<tag k=\"restriction\" v=\"no_right_turn\"/><tag k=\"except\" v=\"hgv\"/>");check.prepare(network(),temp.resolve("p.json"));
        assertTrue(check.turnEvents(List.of("10","20"),"truck").isEmpty());
        check=fixture("<tag k=\"restriction:conditional\" v=\"no_right_turn @ (wet)\"/>");check.prepare(network(),temp.resolve("p.json"));
        assertEquals("unknown",check.turnEvents(List.of("10","20"),"truck").getFirst().status());
    }
    @Test void multipleOnlyRelationsPermitUnionOfTargets() throws Exception {
        var check=fixture("<tag k=\"restriction\" v=\"only_straight_on\"/>");
        var osm=temp.resolve("source.osm");
        Files.writeString(osm,Files.readString(osm).replace("</osm>","""
            <relation id="100"><tag k="type" v="restriction"/><tag k="restriction" v="only_left_turn"/>
            <member type="way" role="from" ref="10"/><member type="way" role="to" ref="30"/><member type="node" role="via" ref="2"/></relation></osm>
            """));
        check=new FreightProfileChecks(osm);check.prepare(network(),temp.resolve("p.json"));
        assertTrue(check.turnEvents(List.of("10","30"),"truck").isEmpty());
        assertTrue(check.turnEvents(List.of("10","20"),"truck").isEmpty());
    }
    @Test void flagDefaultsOffAndRejectsConflictingHeavyAllocations() {
        var scenario=FreightDemandGeneratorTest.scenario();assertFalse(scenario.freight.profileChecksEnabled);
        scenario.freight.profileChecksEnabled=true;assertDoesNotThrow(()->FreightDemandGenerator.validate(scenario));
        scenario.freight.enabled=false;assertThrows(IllegalArgumentException.class,()->FreightDemandGenerator.validate(scenario));
        scenario.freight.enabled=true;
        scenario.vehicleConfiguration=new de.synthesis.vehicle.VehicleConfiguration(
            de.synthesis.vehicle.VehicleConfiguration.CONTRACT,"sumoTraffic",false,List.of(),List.of(),List.of(),
            List.of(new de.synthesis.vehicle.VehicleConfiguration.TrafficAssignment("heavyTruck",new de.synthesis.vehicle.VehicleType.TypeRef("standard.heavyTruck.diesel","1"),1.0)),List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->FreightDemandGenerator.validate(scenario));
    }
    @Test void missingRoutesAreReportedBeforeFailingAndOutputRepeatable() throws Exception {
        var check=fixture("");check.prepare(network(),temp.resolve("p.json"));
        var trips=file("trips.xml","<routes><trip id=\"a\" type=\"heavyTruck\" from=\"10\" to=\"30\"/><trip id=\"b\" type=\"articulatedTruck\" from=\"10\" to=\"20\"/></routes>");
        var routes=file("routes.xml","<routes><vehicle id=\"a\" type=\"heavyTruck\"><route edges=\"10 30\"/></vehicle></routes>");
        Path report=temp.resolve("audit.json");
        assertThrows(java.io.IOException.class,()->check.audit(routes,trips,report));
        var json=new ObjectMapper().readTree(report.toFile());
        assertEquals(2,json.path("requested").asInt());
        assertEquals(1,json.path("routeStatuses").path("review-required").asInt());
        assertEquals(1,json.path("routeStatuses").path("no-native-route").asInt());
        assertFalse(json.path("accessCertified").asBoolean());
        var first=Files.readString(report);assertThrows(java.io.IOException.class,()->check.audit(routes,trips,report));
        assertEquals(first,Files.readString(report));
    }
}
