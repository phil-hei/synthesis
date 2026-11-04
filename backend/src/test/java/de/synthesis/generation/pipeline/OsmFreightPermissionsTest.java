package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class OsmFreightPermissionsTest {
    @TempDir Path temp;
    @Test void respectsDirectionOverridesAndReportsConditionalRules() throws Exception {
        var osm=temp.resolve("network.osm"); var net=temp.resolve("net.xml");var report=temp.resolve("report.json");
        Files.writeString(osm,"""
            <osm><way id="20"><tag k="hgv" v="no"/><tag k="hgv:forward" v="yes"/>
            <tag k="hgv:conditional" v="no @ (Mo-Fr)"/></way></osm>
            """);
        Files.writeString(net,"""
            <net><edge id="20#0" from="a" to="b"><lane allow="passenger truck trailer"/></edge>
            <edge id="-20#1" from="b" to="a"><lane allow="passenger truck trailer"/></edge></net>
            """);
        OsmFreightPermissions.apply(osm,net,report);
        var edges=TrafficRoutes.elements(TrafficRoutes.read(net),"edge");
        assertTrue(TrafficRoutes.elements(edges.getFirst(),"lane").getFirst().getAttribute("allow").contains("truck"));
        assertEquals("passenger",TrafficRoutes.elements(edges.getLast(),"lane").getFirst().getAttribute("allow"));
        var result=new ObjectMapper().readTree(report.toFile());
        assertEquals(1,result.path("restrictedEdges").size());
        assertEquals(1,result.path("unsupportedHgvTags").size());
    }
}
