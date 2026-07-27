package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-End-Smoke-Test der kompletten Generierungs-Pipeline – das Sicherheitsnetz
 * für Umbauten am {@link GenerationService}. Läuft bewusst OFFLINE (keine OSM-Daten,
 * Luftlinien-Matrizen, Template-Verhalten, deaktivierte Simulation → kein
 * Overpass/Gradle/LLM). Der echte logiTopp-Lauf bleibt ein separat gegateter Integrationstest.
 */
class GenerationPipelineE2ETest {

    private final List<Path> tempDirs = new ArrayList<>();
    private final List<GenerationService> services = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        services.forEach(GenerationService::shutdown);
        for (Path dir : tempDirs) {
            deleteRecursively(dir);
        }
    }

    @Test
    void fullPipelineProducesArtifactsOffline() throws Exception {
        Path dataDir = tempDirectory("e2e-data");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        props.getSimulation().setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repo = new ScenarioRepository(mapper, props);
        GenerationService service = new GenerationService(
                repo, new OverpassClient(props, mapper), props, mapper);
        services.add(service);

        Scenario scenario = new Scenario();
        scenario.id = "e2e-smoke"; // die ID vergibt sonst der Controller
        scenario.name = "E2E-Smoke";
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.85;
        scenario.region.minLon = 8.19;
        scenario.region.maxLat = 48.87;
        scenario.region.maxLon = 8.22;
        scenario.population.totalPersons = 250;
        scenario.population.useOsmData = false;      // offline: synthetische POIs
        scenario.population.useRealRouting = false;  // offline: Luftlinien-Matrizen
        scenario.population.behaviorSource = "template"; // kein Gradle/LLM nötig
        scenario.population.calibrateModeChoice = false;
        scenario.population.llmModeChoice = false;
        scenario.targetModels = new java.util.ArrayList<>(List.of("logitopp", "mobilecity"));
        scenario = repo.save(scenario);

        GenerationJob job = service.start(scenario.id);
        long deadline = System.currentTimeMillis() + 6 * 60_000;
        while (job.state == GenerationJob.State.QUEUED || job.state == GenerationJob.State.RUNNING) {
            assertTrue(System.currentTimeMillis() < deadline, "Pipeline-Timeout (6 min)");
            Thread.sleep(1000);
        }
        assertEquals(GenerationJob.State.DONE, job.state,
                "Pipeline muss durchlaufen; error=" + job.error + ", log=" + String.join(" | ", job.log));

        // Kern-Artefakte jeder Stufe
        Path artifacts = repo.artifactsDir(scenario.id);
        for (String file : List.of(
                "zone-repository/zones.csv", "zone-repository/attractivities.csv",
                "population/person.csv", "population/household.csv", "population/activity.csv",
                "matrix/travel_times/CAR_TTC.mtx.bz2", "matrix/other/CAR_DIS.mtx.bz2",
                "legacy/demand-data/person.csv", "legacy/matrices/matrix-configuration.yaml",
                "mobilecity/REGdistricts.csv", "mobilecity/summary.json",
                "mobilecity/districts.shp", "mobilecity/districts.shx",
                "mobilecity/districts.dbf", "mobilecity/districts.prj",
                "manifest.json")) {
            assertTrue(Files.exists(artifacts.resolve(file)), "Artefakt fehlt: " + file);
        }
        assertEquals("Template", job.summary.get("activitySource"), "Template-Verhalten erwartet");
        assertEquals("beeline-estimate",
                mapper.readTree(artifacts.resolve("manifest.json").toFile())
                        .path("dataQuality").path("matrices").asText());

        // Stufen-Status: nichts FAILED; SUMO/LLM sauber übersprungen (nicht gewählt)
        for (GenerationJob.StageInfo stage : job.stages) {
            assertTrue(!"FAILED".equals(stage.status), "Stufe fehlgeschlagen: " + stage.name);
        }
        assertEquals("SKIPPED", job.stage("SUMO (Verkehr)").status);
        assertEquals("SKIPPED", job.stage("LLM-Moduswahl").status);
        assertEquals("SKIPPED", job.stage("Simulation (logiTopp)").status);
    }

    /**
     * Dieselbe Offline-Pipeline mit INSPIRE-/EPSG:3035-Zonierung: beweist, dass die
     * gitterausgerichteten Zonen durch die ganze Kette (Population, Matrizen, Legacy-Export,
     * MobileCity) laufen und die zones.csv INSPIRE-Zell-IDs trägt.
     */
    @Test
    void fullPipelineWithInspireZoningOffline() throws Exception {
        Path dataDir = tempDirectory("e2e-inspire");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        props.getSimulation().setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repo = new ScenarioRepository(mapper, props);
        GenerationService service = new GenerationService(
                repo, new OverpassClient(props, mapper), props, mapper);
        services.add(service);

        Scenario scenario = new Scenario();
        scenario.id = "e2e-inspire";
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.85;
        scenario.region.minLon = 8.19;
        scenario.region.maxLat = 48.87;
        scenario.region.maxLon = 8.22;
        scenario.zoning.mode = "inspire";            // ← INSPIRE/EPSG:3035-Gitter
        scenario.population.totalPersons = 250;
        scenario.population.useOsmData = false;
        scenario.population.useRealRouting = false;
        scenario.population.behaviorSource = "template";
        scenario.targetModels = new java.util.ArrayList<>(List.of("logitopp", "mobilecity"));
        scenario = repo.save(scenario);

        GenerationJob job = service.start(scenario.id);
        long deadline = System.currentTimeMillis() + 6 * 60_000;
        while (job.state == GenerationJob.State.QUEUED || job.state == GenerationJob.State.RUNNING) {
            assertTrue(System.currentTimeMillis() < deadline, "Pipeline-Timeout (6 min)");
            Thread.sleep(1000);
        }
        assertEquals(GenerationJob.State.DONE, job.state,
                "INSPIRE-Pipeline muss durchlaufen; error=" + job.error);
        for (GenerationJob.StageInfo stage : job.stages) {
            assertTrue(!"FAILED".equals(stage.status), "Stufe fehlgeschlagen: " + stage.name);
        }
        assertEquals("inspire", job.summary.get("zoningMode"));
        String zonesCsv = Files.readString(
                repo.artifactsDir(scenario.id).resolve("zone-repository/zones.csv"));
        assertTrue(zonesCsv.contains("CRS3035RES"),
                "zones.csv muss INSPIRE-Zell-IDs enthalten");
    }

    @Test
    void localRoutingIsReportedTruthfullyInManifest() throws Exception {
        Path fixture = Path.of("../validation/rastatt-test.osm").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(fixture), "Lokales Rastatt-OSM-Fixture fehlt");

        Path dataDir = tempDirectory("e2e-routing-manifest");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        props.getOverpass().setRoadNetworkFile(fixture.toString());
        props.getSimulation().setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repo = new ScenarioRepository(mapper, props);
        GenerationService service = new GenerationService(
                repo, new OverpassClient(props, mapper), props, mapper);
        services.add(service);

        Scenario scenario = new Scenario();
        scenario.id = "e2e-routing";
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.85;
        scenario.region.minLon = 8.19;
        scenario.region.maxLat = 48.87;
        scenario.region.maxLon = 8.22;
        scenario.population.totalPersons = 50;
        scenario.population.useOsmData = false;
        scenario.population.useRealRouting = true;
        scenario.population.externalTrips = false;
        scenario.population.behaviorSource = "template";
        scenario.targetModels = new ArrayList<>(List.of("mobilecity"));
        scenario = repo.save(scenario);

        GenerationJob job = service.start(scenario.id);
        long deadline = System.currentTimeMillis() + 6 * 60_000;
        while (job.state == GenerationJob.State.QUEUED || job.state == GenerationJob.State.RUNNING) {
            assertTrue(System.currentTimeMillis() < deadline, "Routing-Pipeline-Timeout (6 min)");
            Thread.sleep(100);
        }
        assertEquals(GenerationJob.State.DONE, job.state, "Routing-Pipeline muss durchlaufen");
        assertEquals("graphhopper-with-local-fallbacks", job.summary.get("matrixSource"));

        var manifest = mapper.readTree(repo.artifactsDir(scenario.id).resolve("manifest.json").toFile());
        assertEquals("graphhopper-with-local-fallbacks",
                manifest.path("dataQuality").path("matrices").asText());
        assertTrue(manifest.path("notes").toString().contains("GraphHopper"));
        assertTrue(!manifest.path("notes").toString().contains("Roadmap Phase 1"));
    }

    /** Außerhalb des EPSG:3035-Gültigkeitsbereichs (Europa) fällt "inspire" aufs Bbox-Raster zurück. */
    @Test
    void inspireRequestOutsideEuropeFallsBackToGrid() throws Exception {
        Path dataDir = tempDirectory("e2e-guard");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        props.getSimulation().setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        GenerationService service = new GenerationService(
                new ScenarioRepository(mapper, props), new OverpassClient(props, mapper), props, mapper);
        services.add(service);

        Scenario.Region newYork = new Scenario.Region();
        newYork.minLat = 40.70;
        newYork.minLon = -74.02;
        newYork.maxLat = 40.75;
        newYork.maxLon = -73.95;
        GenerationService.ZonePreview preview = service.previewZones(newYork, 1000, "inspire");
        assertTrue(preview.zones().size() > 1, "Fallback muss Zonen liefern");
        for (var zone : preview.zones()) {
            assertEquals(0, zone.laeaRes, "außerhalb Europas entstehen Bbox-Raster-Zonen (kein LAEA)");
        }
    }

    private Path tempDirectory(String prefix) throws java.io.IOException {
        Path dir = Files.createTempDirectory(prefix);
        tempDirs.add(dir);
        return dir;
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best effort in test cleanup.
                }
            });
        } catch (Exception ignored) {
            // Nothing to clean up.
        }
    }
}
