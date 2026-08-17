package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsmDataSnapshotTest {

    @Test
    void parsesPoiAndSettlementFromOneOverpassSnapshot(@TempDir Path tempDir) throws Exception {
        Path snapshot = tempDir.resolve("osm.json");
        Files.writeString(snapshot, json());

        OsmDataSnapshot.Content content = OsmDataSnapshot.read(snapshot, new ObjectMapper());

        assertEquals(3, content.elements());
        assertEquals(1, content.pois().size());
        assertEquals("supermarket", content.pois().getFirst().tags().get("shop"));
        assertEquals(1, content.places().size());
        assertEquals(50_000, content.places().getFirst().population());
        assertEquals(64, content.sha256().length());
    }

    @Test
    void rejectsSnapshotWithoutRelevantElements(@TempDir Path tempDir) throws Exception {
        Path snapshot = tempDir.resolve("empty.json");
        Files.writeString(snapshot, "{\"elements\":[{\"type\":\"node\",\"lat\":48.8,\"lon\":8.1}]}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OsmDataSnapshot.read(snapshot, new ObjectMapper()));

        assertTrue(error.getMessage().contains("weder verwertbare POIs"));
    }

    private static String json() {
        return """
                {"version":0.6,"elements":[
                  {"type":"node","id":1,"lat":48.85,"lon":8.15,"tags":{"shop":"supermarket"}},
                  {"type":"node","id":2,"lat":48.86,"lon":8.16,"tags":{"place":"town","name":"Teststadt","population":"50.000"}},
                  {"type":"way","id":3,"tags":{"highway":"primary"}}
                ]}
                """;
    }
}
