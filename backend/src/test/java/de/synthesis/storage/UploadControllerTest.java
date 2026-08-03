package de.synthesis.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.BaCommuterAdapter;
import de.synthesis.generation.pipeline.RoadNetworkFile;
import de.synthesis.generation.pipeline.CommuterMatrix;
import de.synthesis.generation.pipeline.MunicipalityCentroidSource;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadControllerTest {

    @Test
    void roadNetworkUploadIsValidatedAndStoredUnderStableName(@TempDir Path tempDir) throws Exception {
        ScenarioRepository repository = repository(tempDir);
        repository.save(scenario());
        UploadController controller = controller(repository);
        MockMultipartFile upload = new MockMultipartFile("file", "extract.osm", "application/xml",
                networkXml().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        UploadController.RoadNetworkUploadSummary summary =
                controller.uploadRoadNetwork("test", upload);

        assertEquals(RoadNetworkFile.NAME, summary.name());
        assertEquals(1, summary.highwayWays());
        assertTrue(Files.isRegularFile(repository.uploadsDir("test").resolve(RoadNetworkFile.NAME)));
    }

    @Test
    void invalidReplacementKeepsPreviouslyAcceptedNetwork(@TempDir Path tempDir) throws Exception {
        ScenarioRepository repository = repository(tempDir);
        repository.save(scenario());
        UploadController controller = controller(repository);
        controller.uploadRoadNetwork("test", new MockMultipartFile("file", "valid.osm", "application/xml",
                networkXml().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path stored = repository.uploadsDir("test").resolve(RoadNetworkFile.NAME);
        String accepted = Files.readString(stored);

        MockMultipartFile invalid = new MockMultipartFile("file", "invalid.osm", "application/xml",
                "<osm version=\"0.6\"></osm>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(ResponseStatusException.class,
                () -> controller.uploadRoadNetwork("test", invalid));
        assertEquals(accepted, Files.readString(stored));
    }

    @Test
    void commuterUploadRequiresAHomeInsideScenarioRegion(@TempDir Path tempDir) throws Exception {
        ScenarioRepository repository = repository(tempDir);
        repository.save(scenario());
        UploadController controller = controller(repository);
        MockMultipartFile relevant = new MockMultipartFile("file", "commuters.csv", "text/csv", """
                homeLat;homeLon;workLat;workLon;commuters
                48.85;8.15;49.20;8.30;123
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        UploadController.CommuterUploadSummary summary =
                controller.uploadCommuters("test", relevant);

        assertEquals(1, summary.relationsWithHomeInRegion());
        assertEquals(123, summary.totalCommuters());
        assertTrue(Files.isRegularFile(repository.uploadsDir("test").resolve(CommuterMatrix.NAME)));

        MockMultipartFile outside = new MockMultipartFile("file", "outside.csv", "text/csv", """
                homeLat;homeLon;workLat;workLon;commuters
                50.0;10.0;51.0;11.0;10
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ResponseStatusException.class,
                () -> controller.uploadCommuters("test", outside));
    }

    @Test
    void officialBaExportIsResolvedThroughMunicipalityPoints(@TempDir Path tempDir)
            throws Exception {
        ScenarioRepository repository = repository(tempDir);
        repository.save(scenario());
        Map<String, MunicipalityCentroidSource.Point> points = Map.of(
                "08216043", new MunicipalityCentroidSource.Point("08216043", 48.85, 8.18),
                "08212000", new MunicipalityCentroidSource.Point("08212000", 49.01, 8.40),
                "08211000", new MunicipalityCentroidSource.Point("08211000", 48.76, 8.24));
        UploadController controller = new UploadController(repository, () -> points);
        MockMultipartFile ba = new MockMultipartFile("file", "statistik_pendler.csv", "text/csv", """
                \uFEFF"Land";"Baden-Württemberg"
                "Gebietsstand";"Juni 2025"
                "Datenstand";"Januar 2026"
                "Thema:";"Auspendler Gemeinden"
                "Wohnort";"Regionalschlüssel";"Arbeitsort";"Regionalschlüssel";"Insgesamt"
                "Rastatt, Stadt";"08216043";"Auspendler insgesamt";"0";"1.000"
                "Rastatt, Stadt";"08216043";"Karlsruhe, Stadt";"08212000";"700"
                "Rastatt, Stadt";"08216043";"Baden-Baden, Stadt";"08211000";"200"
                "Rastatt, Stadt";"08216043";"Übrige Kreise";"08xxx";"100"
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        UploadController.CommuterUploadSummary summary =
                controller.uploadCommuters("test", ba);

        assertEquals(BaCommuterAdapter.SOURCE, summary.source());
        assertEquals(2, summary.relations());
        assertEquals(900, summary.totalCommuters());
        assertEquals(1_000, summary.exportTotalCommuters());
        assertEquals(0.9, summary.coverage(), 1e-9);
        assertEquals(1, summary.skippedAggregateRows());
        Path stored = repository.uploadsDir("test").resolve(CommuterMatrix.NAME);
        assertEquals(BaCommuterAdapter.SOURCE, CommuterMatrix.source(stored));
        assertTrue(CommuterMatrix.parse(stored).flows().stream()
                .allMatch(flow -> flow.homeScope() == CommuterMatrix.HomeScope.EXTERNAL));
    }

    private static ScenarioRepository repository(Path tempDir) {
        SynthesisProperties properties = new SynthesisProperties();
        properties.setDataDir(tempDir.toString());
        return new ScenarioRepository(new ObjectMapper().findAndRegisterModules(), properties);
    }

    private static UploadController controller(ScenarioRepository repository) {
        return new UploadController(repository, Map::<String, MunicipalityCentroidSource.Point>of);
    }

    private static Scenario scenario() {
        Scenario scenario = new Scenario();
        scenario.id = "test";
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.8;
        scenario.region.minLon = 8.1;
        scenario.region.maxLat = 48.9;
        scenario.region.maxLon = 8.2;
        return scenario;
    }

    private static String networkXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6">
                  <node id="1" lat="48.7" lon="8.0"/>
                  <node id="2" lat="49.0" lon="8.3"/>
                  <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/></way>
                </osm>
                """;
    }
}
