package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsmDataSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

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

    // --- Schreibpfad: Live-Abruf nachprüfbar machen -------------------------------
    // Ohne write() bekommt nur ein hochgeladener Snapshot einen Fingerabdruck; ein
    // Overpass-Lauf blieb unbelegt. Genau daran scheiterte die Seed-43-Auswertung
    // am 2026-08-16 (E24).

    @Test
    void roundTripPreservesPoisAndPlaces(@TempDir Path dir) throws Exception {
        List<OverpassClient.Poi> pois = List.of(
                new OverpassClient.Poi(48.9, 8.4, Map.of("shop", "bakery")),
                new OverpassClient.Poi(48.91, 8.41, Map.of("amenity", "school")));
        List<OverpassClient.Place> places = List.of(
                new OverpassClient.Place("Rastatt", "town", 48.85, 8.2, 50000));

        Path file = dir.resolve(OsmDataSnapshot.NAME);
        String sha = OsmDataSnapshot.write(file, pois, places, mapper);
        OsmDataSnapshot.Content back = OsmDataSnapshot.read(file, mapper);

        assertEquals(2, back.pois().size(), "beide POIs müssen zurückgelesen werden");
        assertEquals(1, back.places().size(), "die Siedlung muss zurückgelesen werden");
        assertEquals("Rastatt", back.places().getFirst().name());
        assertEquals(50000, back.places().getFirst().population());
        assertEquals(sha, back.sha256(), "read() muss denselben Fingerabdruck melden wie write()");
    }

    @Test
    void nodeThatIsBothPoiAndSettlementIsNotDuplicated(@TempDir Path dir) throws Exception {
        // Ein Knoten mit shop- UND place-Tag darf beim Zurücklesen genau einmal als POI und
        // einmal als Siedlung erscheinen — nicht doppelt, weil er über zwei Listen geschrieben wird.
        List<OverpassClient.Poi> pois = List.of(
                new OverpassClient.Poi(48.85, 8.2, Map.of("shop", "supermarket")));
        List<OverpassClient.Place> places = List.of(
                new OverpassClient.Place("Rastatt", "town", 48.85, 8.2, 50000));

        Path file = dir.resolve(OsmDataSnapshot.NAME);
        OsmDataSnapshot.write(file, pois, places, mapper);
        OsmDataSnapshot.Content back = OsmDataSnapshot.read(file, mapper);

        assertEquals(1, back.elements(), "beide Rollen gehören auf denselben Knoten");
        assertEquals(1, back.pois().size());
        assertEquals(1, back.places().size());
    }

    @Test
    void differingDataYieldsDifferingFingerprint(@TempDir Path dir) throws Exception {
        List<OverpassClient.Poi> pois = List.of(
                new OverpassClient.Poi(48.9, 8.4, Map.of("shop", "bakery")));
        List<OverpassClient.Place> two = List.of(
                new OverpassClient.Place("Rastatt", "town", 48.85, 8.2, 50000),
                new OverpassClient.Place("Kuppenheim", "town", 48.83, 8.26, 8000));
        List<OverpassClient.Place> one = List.of(two.getFirst());

        String shaTwo = OsmDataSnapshot.write(dir.resolve("a.json"), pois, two, mapper);
        String shaOne = OsmDataSnapshot.write(dir.resolve("b.json"), pois, one, mapper);
        String shaTwoAgain = OsmDataSnapshot.write(dir.resolve("c.json"), pois, two, mapper);

        assertNotEquals(shaTwo, shaOne,
                "eine fehlende Siedlung muss den Fingerabdruck ändern — genau der Fall aus E24");
        assertEquals(shaTwo, shaTwoAgain, "gleiche Daten müssen denselben Fingerabdruck liefern");
        assertTrue(shaTwo.matches("[0-9a-f]{64}"), "SHA-256 als Hex erwartet");
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
