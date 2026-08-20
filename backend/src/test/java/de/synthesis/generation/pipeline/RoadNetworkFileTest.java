package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNetworkFileTest {

    @Test
    void inspectsHighwayNetworkAndCoverage(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("network.osm");
        Files.writeString(file, networkXml());

        RoadNetworkFile.Inspection inspection = RoadNetworkFile.inspectForRegion(file, region());

        assertEquals(2, inspection.nodes());
        assertEquals(1, inspection.ways());
        assertEquals(1, inspection.highwayWays());
        assertEquals(64, inspection.sha256().length());
        assertTrue(inspection.covers(region()));
    }

    @Test
    void rejectsNetworkThatDoesNotCoverRegion(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("network.osm");
        Files.writeString(file, networkXml());
        Scenario.Region outside = region();
        outside.maxLon = 9.0;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RoadNetworkFile.inspectForRegion(file, outside));

        assertTrue(error.getMessage().contains("nicht vollständig"));
    }

    @Test
    void rejectsXmlWithoutHighwayWays(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("not-a-network.osm");
        Files.writeString(file, """
                <?xml version="1.0"?>
                <osm version="0.6"><node id="1" lat="48.8" lon="8.1"/></osm>
                """);

        assertThrows(IllegalArgumentException.class, () -> RoadNetworkFile.inspect(file));
    }

    // --- Schreibpfad: Live-Abruf belegbar machen ----------------------------------
    // Der Upload-Pfad war immer geprüft und gehasht; ein Overpass-Abruf lebte nur im
    // Speicher des Laufs und war hinterher nicht mehr nachweisbar.

    @Test
    void persistWritesNetworkAndReportsSameFingerprintAsInspect(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("sub").resolve(RoadNetworkFile.NAME);

        RoadNetworkFile.Inspection persisted = RoadNetworkFile.persist(target, networkXml());

        assertTrue(Files.isRegularFile(target), "Zielverzeichnis muss angelegt werden");
        assertEquals(networkXml(), Files.readString(target), "Netz muss unverändert abgelegt werden");
        assertEquals(RoadNetworkFile.inspect(target).sha256(), persisted.sha256(),
                "persist() muss denselben Fingerabdruck melden wie eine spätere Prüfung");
        assertEquals(1, persisted.highwayWays());
    }

    @Test
    void persistedNetworkIsReusableAsScenarioUpload(@TempDir Path dir) throws Exception {
        // Das gesicherte Artefakt muss als Upload eines zweiten Szenarios taugen — sonst
        // gibt es keinen Weg, die Eingabe für einen A/B-Vergleich einzufrieren.
        Path produced = dir.resolve("run1").resolve(RoadNetworkFile.NAME);
        RoadNetworkFile.Inspection first = RoadNetworkFile.persist(produced, networkXml());

        Path reused = dir.resolve("run2").resolve(RoadNetworkFile.NAME);
        Files.createDirectories(reused.getParent());
        Files.copy(produced, reused);

        assertEquals(first.sha256(), RoadNetworkFile.inspectForRegion(reused, region()).sha256(),
                "kopiertes Netz muss denselben Fingerabdruck und die Regionsdeckung behalten");
    }

    private static Scenario.Region region() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.8;
        region.minLon = 8.1;
        region.maxLat = 48.9;
        region.maxLon = 8.2;
        return region;
    }

    static String networkXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6">
                  <node id="1" lat="48.7" lon="8.0"/>
                  <node id="2" lat="49.0" lon="8.3"/>
                  <way id="10">
                    <nd ref="1"/><nd ref="2"/>
                    <tag k="highway" v="primary"/>
                  </way>
                </osm>
                """;
    }
}
