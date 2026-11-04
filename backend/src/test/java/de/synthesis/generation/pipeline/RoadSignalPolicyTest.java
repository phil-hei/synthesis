package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadSignalPolicyTest {
    @TempDir Path dir;
    @Test void transferredSignalsMayCrossSplitNodesButEveryBranchMustRemainControlled()throws Exception {
        Files.writeString(dir.resolve("network.osm"),"<osm><node id='2'><tag k='railway' v='signal'/><tag k='highway' v='traffic_signals'/></node></osm>");
        var p=RoadSignalPolicy.prepare(dir,true);
        String nodes="<junction id='2' type='priority'/><junction id='split' type='priority'/><junction id='light' type='traffic_light'/><junction id='uncontrolled' type='priority'/>";
        String first="<edge id='a' from='2' to='split'><lane length='8.05'/></edge>";
        for(String tail:List.of(
                "<edge id='b' from='split' to='light'><lane length='1.46'/></edge>",
                "<edge id='b' from='split' to='light'><lane length='16.95'/></edge>")) {
            Files.writeString(dir.resolve("net.xml"),"<net>"+nodes+first+tail+"</net>");assertTrue(p.verify(dir.resolve("net.xml")));
        }
        for(String tail:List.of(
                "<edge id='b' from='split' to='light'><lane length='17'/></edge>",
                "<edge id='b' from='split' to='2'><lane length='1'/></edge>",
                "<edge id='b' from='split' to='light'><lane length='1'/></edge><edge id='c' from='split' to='uncontrolled'><lane length='1'/></edge>",
                "")) {
            Files.writeString(dir.resolve("net.xml"),"<net>"+nodes+first+tail+"</net>");assertThrows(java.io.IOException.class,()->p.verify(dir.resolve("net.xml")));
        }
    }
    @Test void defaultAndDualSignalTagsProduceIdPreservingImportArguments()throws Exception {
        String source="<osm><node id='1'><tag k='railway' v='signal'/></node><node id='2'><tag k='railway' v='signal'/><tag k='highway' v='traffic_signals'/></node><node id='3'><tag k='railway:signal:main' v='DE-BOStrab:f0'/><tag k='highway' v='traffic_signals'/></node><node id='4'><tag k='railway' v='level_crossing'/><tag k='railway:signal:main' v='DE-BOStrab:h0'/><tag k='highway' v='traffic_signals'/></node></osm>";
        Files.writeString(dir.resolve("network.osm"),source);
        var policy=RoadSignalPolicy.prepare(dir,true);
        assertEquals(List.of("--railway.signals.discard","--tls.set","2,3"),policy.arguments());
        assertEquals(source,Files.readString(dir.resolve("network.osm")),"Original OSM stays untouched");
        Files.writeString(dir.resolve("net.xml"),"<net><junction id='1' type='priority'/><junction id='2' type='traffic_light'/><junction id='3' type='traffic_light'/><junction id='4' type='rail_crossing'/></net>");policy.verify(dir.resolve("net.xml"));
        var report=new ObjectMapper().readTree(dir.resolve(RoadSignalPolicy.REPORT).toFile());assertEquals("applied",report.path("status").asText());
        assertEquals(List.of(),RoadSignalPolicy.prepare(dir,false).arguments());
        assertFalse(((SumoRunner.PtProvider)(net->null)).roadVehiclesOnly());
        assertTrue(SumoRunner.PtProvider.roadOnly(net->null).roadVehiclesOnly());
    }
    @Test void unexpectedNativeControlIsFailureAndMissingSourceIdIsVisible()throws Exception {
        Files.writeString(dir.resolve("network.osm"),"<osm><node id='2'><tag k='railway' v='signal'/><tag k='highway' v='traffic_signals'/></node></osm>");
        var p=RoadSignalPolicy.prepare(dir,true);Files.writeString(dir.resolve("net.xml"),"<net><junction id='2' type='priority'/></net>");assertThrows(java.io.IOException.class,()->p.verify(dir.resolve("net.xml")));
        Files.writeString(dir.resolve("net.xml"),"<net><junction id='3' type='rail_signal'/></net>");assertThrows(java.io.IOException.class,()->p.verify(dir.resolve("net.xml")));
        Files.writeString(dir.resolve("net.xml"),"<net><edge id='approach' from='2' to='cluster'><lane length='10'/></edge><junction id='2' type='priority'/><junction id='cluster' type='traffic_light'/></net>");assertTrue(p.verify(dir.resolve("net.xml")));
        Files.writeString(dir.resolve("net.xml"),"<net><edge id='approach' from='2' to='cluster'><lane length='100'/></edge><junction id='2' type='priority'/><junction id='cluster' type='traffic_light'/></net>");assertThrows(java.io.IOException.class,()->p.verify(dir.resolve("net.xml")));
        Files.writeString(dir.resolve("net.xml"),"<net/>");p.verify(dir.resolve("net.xml"));
        var report=new ObjectMapper().readTree(dir.resolve(RoadSignalPolicy.REPORT).toFile());assertEquals("applied-source-ids-require-review",report.path("status").asText());assertEquals("2",report.path("sourceRoadLightIdsNotRetainedOrMerged").get(0).asText());
    }
    @Test void declaredRoadTransitRejectsRailAndUnresolvedTypeReferences()throws Exception {
        Path types=dir.resolve("types.xml"),vehicles=dir.resolve("vehicles.xml");
        Files.writeString(dir.resolve("stops.xml"),"<additional/>");
        var pt=new TransitRunner.PtInfrastructure(types,dir.resolve("stops.xml"),vehicles,0,1);
        Files.writeString(types,"<additional><vType id='bus' vClass='bus'/></additional>");Files.writeString(vehicles,"<routes><vehicle id='1' type='bus'/><trip id='2'/><flow id='3' type='bus'/></routes>");RoadSignalPolicy.requireRoadVehicles(pt);
        for(String invalid:List.of("<routes><vehicle id='1' type='DEFAULT_RAILTYPE'/></routes>","<routes><flow id='1' type='tram'/></routes>","<routes><vTypeDistribution id='mixed'/></routes>","<routes><include href='rail.xml'/></routes>","<routes><vehicle id='1' type='bus'><include href='rail.xml'/></vehicle></routes>")) {
            Files.writeString(vehicles,invalid);assertThrows(java.io.IOException.class,()->RoadSignalPolicy.requireRoadVehicles(pt));
        }
        Files.writeString(vehicles,"<routes><vehicle id='1' type='bus'/></routes>");
        for(String invalid:List.of("<additional><vType id='bus' vClass='tram'/></additional>","<additional><vTypeDistribution id='bus'><vType id='train' vClass='rail'/></vTypeDistribution></additional>","<additional><vType id='bus'/></additional>")) {
            Files.writeString(types,invalid);assertThrows(java.io.IOException.class,()->RoadSignalPolicy.requireRoadVehicles(pt));
        }
        Files.writeString(types,"<additional><vType id='bus' vClass='bus'/></additional>");
        for(Path file:List.of(types,pt.stops())) {
            String original=Files.readString(file);
            Files.writeString(file,"<additional><include href='rail.xml'/></additional>");
            assertThrows(java.io.IOException.class,()->RoadSignalPolicy.requireRoadVehicles(pt));
            Files.writeString(file,original);
        }
        Files.writeString(pt.stops(),"<additional><vType id='train' vClass='rail'/></additional>");
        assertThrows(java.io.IOException.class,()->RoadSignalPolicy.requireRoadVehicles(pt));

    }
}
