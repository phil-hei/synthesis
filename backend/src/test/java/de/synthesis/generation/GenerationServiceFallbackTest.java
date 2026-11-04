package de.synthesis.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ein Overpass-Ausfall ist ein dokumentierter Fallback: die Stufe bleibt DONE, trägt aber die
 * Fallback-Markierung, und das Manifest nennt die tatsächlich verwendete Quelle. Vorher stand
 * nach einem Ausfall "overpass-live" in der Provenienz (Befund 2026-11-04).
 */
class GenerationServiceFallbackTest {

    @TempDir
    Path temp;

    @Test
    void overpassOutageIsVisibleAsFallbackAndProvenanceNamesTheSyntheticSource() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var props = new SynthesisProperties();
        props.setDataDir(temp.toString());
        props.getCensus().setEnabled(false);
        props.getSimulation().setEnabled(false);
        var client = new OverpassClient(props, mapper) {
            @Override
            public List<Poi> fetchPois(Scenario.Region r) throws Exception {
                throw new IOException("Tunnel failed, got: 403");
            }

            @Override
            public List<Place> fetchPlaces(Scenario.Region r, boolean external) throws Exception {
                throw new IOException("Tunnel failed, got: 403");
            }
        };
        var repo = new ScenarioRepository(mapper, props);
        var scenario = new Scenario();
        scenario.id = "overpass-outage";
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.83;
        scenario.region.maxLat = 48.84;
        scenario.region.minLon = 8.2;
        scenario.region.maxLon = 8.21;
        scenario.population.totalPersons = 12;
        scenario.population.behaviorSource = "template";
        scenario.population.useKitPopulation = false;
        scenario.population.externalTrips = true;
        scenario.population.useRealRouting = false;
        scenario.targetModels = new ArrayList<>(List.of("logitopp"));
        repo.save(scenario);
        var service = new GenerationService(repo, client, props, mapper);
        try {
            var job = service.start(scenario.id);
            await(job);
            assertEquals(GenerationJob.State.DONE, job.state, job.error);

            var osm = job.stage("OSM-Daten (POIs)");
            assertEquals("DONE", osm.status);
            assertTrue(osm.fallback, "Overpass-Ausfall muss als Fallback markiert sein");
            assertTrue(osm.message.contains("Overpass nicht erreichbar"));
            assertEquals("synthetic-fallback", job.summary.get("osmDataSource"));
            long internal = ((Number) job.summary.get("zonesInternal")).longValue();
            assertTrue(internal > 0);
            assertEquals(8L, job.summary.get("zonesExternal"));
            assertEquals(internal + 8, ((Number) job.summary.get("zones")).longValue());

            Path artifacts = repo.artifactsDir(scenario.id);
            JsonNode manifest = mapper.readTree(artifacts.resolve("manifest.json").toFile());
            assertEquals("synthetic-fallback", manifest.path("inputProvenance").path("osmData").path("source").asText());
            assertEquals("synthetic", manifest.path("dataQuality").path("attractivities").asText());

            // Der Status überlebt den Prozess: ein frischer Service liest job.json statt 404.
            Path statusFile = artifacts.resolve(GenerationJob.FILE);
            assertTrue(Files.isRegularFile(statusFile));
            var restarted = new GenerationService(repo, client, props, mapper);
            try {
                var restored = restarted.status(scenario.id).orElseThrow();
                assertEquals(GenerationJob.State.DONE, restored.state);
                assertFalse(restored.active());
                assertNotNull(restored.finishedAt);
                assertTrue(restored.stage("OSM-Daten (POIs)").fallback);
                assertEquals("synthetic-fallback", restored.summary.get("osmDataSource"));
                assertTrue(restarted.status("unknown/../id").isEmpty(), "Ungültige IDs liefern kein Status-Objekt");
            } finally {
                restarted.shutdown();
            }
        } finally {
            service.shutdown();
        }
    }

    private static void await(GenerationJob job) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (job.finishedAt == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(job.finishedAt, "Pipeline worker must publish a finished state");
    }
}
