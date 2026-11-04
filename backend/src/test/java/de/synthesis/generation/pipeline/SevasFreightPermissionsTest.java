package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SevasFreightPermissionsTest {
    @TempDir Path temp;
    static JsonNode snapshot() throws Exception {
        return new ObjectMapper().readTree(SevasFreightPermissionsTest.class.getResourceAsStream("/sevas-simple.json"));
    }
    static ObjectNode properties(JsonNode data) { return (ObjectNode)data.path("features").get(0).path("properties"); }
    private JsonNode apply(JsonNode data, String coordinates) throws Exception {
        Files.writeString(temp.resolve("net.xml"),"<net><edge id='20' from='a' to='b'><lane allow='passenger truck trailer'/></edge><edge id='-20' from='b' to='a'><lane disallow='bus'/></edge></net>");
        Files.writeString(temp.resolve("nodes.xml"),"<nodes><node id='a' x='7.402' y='51.5'/><node id='b' x='7.404' y='51.5'/></nodes>");
        Files.writeString(temp.resolve("edges.xml"),"<edges><edge id='20' from='a' to='b' shape='"+coordinates+"'/><edge id='-20' from='b' to='a' shape='7.404,51.5 7.402,51.5'/></edges>");
        SevasFreightPermissions.apply(data,temp.resolve("net.xml"),temp.resolve("nodes.xml"),temp.resolve("edges.xml"),temp.resolve("report.json"));
        return new ObjectMapper().readTree(temp.resolve("report.json").toFile());
    }
    @Test void disabledSnapshotSurvivesScenarioJsonNullRoundtrip() throws Exception {
        var scenario = new ObjectMapper().readValue("{\"freight\":{\"sevasSnapshot\":null}}", de.synthesis.scenario.Scenario.class);
        assertDoesNotThrow(() -> FreightDemandGenerator.validate(scenario));
    }
    @Test void appliesDirectionAndPreservesOtherPermissions() throws Exception {
        JsonNode data=snapshot(); properties(data).put("fahrtri","1");
        var r=apply(data,"7.402,51.5 7.404,51.5");
        assertEquals(List.of("20"),new ObjectMapper().convertValue(r.path("restrictedEdges"),List.class));
        var edges=TrafficRoutes.elements(TrafficRoutes.read(temp.resolve("net.xml")),"edge");
        assertEquals("passenger",TrafficRoutes.elements(edges.getFirst(),"lane").getFirst().getAttribute("allow"));
        assertEquals("bus",TrafficRoutes.elements(edges.getLast(),"lane").getFirst().getAttribute("disallow"));
        properties(data).put("fahrtri","2"); r=apply(data,"7.402,51.5 7.404,51.5");
        assertEquals("-20",r.path("restrictedEdges").get(0).asText());
    }
    @Test void excludesExceptionsTemporalWeightAndUnknownSigns() throws Exception {
        for (var pair:List.of(new String[]{"vz_1026_35","true"},new String[]{"tage_grppe","2"},new String[]{"zeit1_von","08:00"},new String[]{"typ","262"},new String[]{"vz_future","false"})) {
            JsonNode data=snapshot();properties(data).put(pair[0],pair[1]);
            assertEquals(0,apply(data,"7.402,51.5 7.404,51.5").path("restrictedEdges").size());
        }
    }
    @Test void rejectsMissingExceptionFieldsDuplicateIdsAndSwappedCoordinates() throws Exception {
        JsonNode data=snapshot();properties(data).remove("vz_1026_35");assertThrows(IllegalArgumentException.class,()->SevasFreightPermissions.validate(data));
        var duplicate=snapshot();((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("features")).add(duplicate.path("features").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->SevasFreightPermissions.validate(duplicate));
        var swapped=snapshot(); ((com.fasterxml.jackson.databind.node.ArrayNode)swapped.path("features").get(0).path("geometry").path("coordinates").get(0)).set(0,new com.fasterxml.jackson.databind.node.DoubleNode(51.5));
        assertThrows(IllegalArgumentException.class,()->SevasFreightPermissions.validate(swapped));
    }
    @Test void neverBansWholeEdgeForPartialOrShiftedSource() throws Exception {
        var data=snapshot(); var coords=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("features").get(0).path("geometry").path("coordinates");
        ((com.fasterxml.jackson.databind.node.ArrayNode)coords.get(0)).set(0,new com.fasterxml.jackson.databind.node.DoubleNode(7.403));
        var r=apply(data,"7.402,51.5 7.404,51.5"); assertEquals(0,r.path("restrictedEdges").size());assertEquals("partial-edge",r.path("segments").get(0).path("status").asText());
        var shifted=snapshot(); ((ObjectNode)shifted.path("features").get(0).path("geometry")).set("coordinates",new ObjectMapper().readTree("[[7.402,51.501],[7.404,51.501]]"));
        assertEquals("geometry-mismatch",apply(shifted,"7.402,51.5 7.404,51.5").path("segments").get(0).path("status").asText());
    }
}
