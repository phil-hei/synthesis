package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-End-Smoke-Test der kompletten Generierungs-Pipeline – das Sicherheitsnetz
 * für Umbauten am {@link GenerationService}. Läuft bewusst OFFLINE (keine OSM-Daten,
 * Luftlinien-Matrizen, Template-Verhalten → kein Overpass/Gradle-actiTopp/LLM); nur
 * die logiTopp-Simulation nutzt das lokale Legacy-Projekt und wird übersprungen,
 * wenn es fehlt.
 */
class GenerationPipelineE2ETest {

    @Test
    void fullPipelineProducesArtifactsOffline() throws Exception {
        Path dataDir = Files.createTempDirectory("e2e-data");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repo = new ScenarioRepository(mapper, props);
        GenerationService service = new GenerationService(
                repo, new OverpassClient(props, mapper), props, mapper);

        boolean simAvailable = Files.exists(Path.of(props.getSimulation().getProjectDir(), "gradlew"));

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

        // Stufen-Status: nichts FAILED; SUMO/LLM sauber übersprungen (nicht gewählt)
        for (GenerationJob.StageInfo stage : job.stages) {
            assertTrue(!"FAILED".equals(stage.status), "Stufe fehlgeschlagen: " + stage.name);
        }
        assertEquals("SKIPPED", job.stage("SUMO (Verkehr)").status);
        assertEquals("SKIPPED", job.stage("LLM-Moduswahl").status);

        // logiTopp-Simulation: mit lokalem Legacy-Projekt muss sie DONE sein + KPIs liefern
        assumeTrue(simAvailable, "mobitopp-rastatt nicht vorhanden – Simulations-Asserts übersprungen");
        assertEquals("DONE", job.stage("Simulation (logiTopp)").status,
                "Simulation muss laufen; log=" + String.join(" | ", job.log));
        assertTrue(Files.exists(artifacts.resolve("simulation/kpis.json")), "kpis.json fehlt");
        assertTrue(Files.exists(artifacts.resolve("simulation/results/demandsimulationResult.csv")),
                "demandsimulationResult.csv fehlt");
    }

    /**
     * Dieselbe Offline-Pipeline mit INSPIRE-/EPSG:3035-Zonierung: beweist, dass die
     * gitterausgerichteten Zonen durch die ganze Kette (Population, Matrizen, Legacy-Export,
     * MobileCity) laufen und die zones.csv INSPIRE-Zell-IDs trägt.
     */
    @Test
    void fullPipelineWithInspireZoningOffline() throws Exception {
        Path dataDir = Files.createTempDirectory("e2e-inspire");
        SynthesisProperties props = new SynthesisProperties();
        props.setDataDir(dataDir.toString());
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repo = new ScenarioRepository(mapper, props);
        GenerationService service = new GenerationService(
                repo, new OverpassClient(props, mapper), props, mapper);

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
}
