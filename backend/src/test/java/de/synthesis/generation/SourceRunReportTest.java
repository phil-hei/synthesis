package de.synthesis.generation;

import de.synthesis.data.SourcePlan;
import de.synthesis.data.importing.ImportSupport;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceRunReportTest {
    @TempDir Path directory;

    @Test void completedReportBindsPlanAmountsRoundingAndOnlyCurrentTargetExecution() throws Exception {
        try (var f = new SourcePlanTrafficFixture(directory, false, 42, false)) {
            configure(f);
            // A prior target report must never become evidence for this skipped target stage.
            Files.createDirectories(f.out().resolve("sumo"));
            Files.writeString(f.out().resolve("sumo/traffic-execution.json"), "{\"status\":\"complete\",\"old\":true}");
            var job = f.run(); var report = read(f);
            assertEquals(SourceRunReport.CONTRACT, report.path("contract").asText());
            assertEquals("DONE", report.at("/run/state").asText());
            assertEquals(job.startedAt.toString(), report.at("/run/startedAt").asText());
            assertEquals(job.finishedAt.toString(), report.at("/run/finishedAt").asText());
            assertEquals(f.scenario.id, report.at("/run/scenarioId").asText());
            for (String name : List.of("plan", "lock")) assertEquals(ImportSupport.hash(Files.readAllBytes(f.out().resolve("sources/" + name + ".json"))), report.at("/run/" + name + "Sha256").asText());
            assertEquals(8, report.at("/freight/generatedVehicleTrips").asInt());
            assertEquals("uniform-noise", report.at("/population/distributionSource").asText());
            assertEquals("synthetic", report.at("/workplaces/provider").asText());
            assertEquals(f.json.readTree(f.out().resolve("population/household-rounding.json").toFile()), report.path("populationSynthesis"));
            assertEquals("SKIPPED", report.at("/targets/sumo/stageStatus").asText());
            assertFalse(report.at("/targets/sumo").has("execution"));
            assertTrue(report.path("failures").isEmpty());
            Path proof = Path.of("target/source-run-report/completed.json"); Files.createDirectories(proof.getParent());
            Files.copy(f.out().resolve("sources/resolution.json"), proof, StandardCopyOption.REPLACE_EXISTING);
            // A new run has a new identity even with identical effective sources and seed.
            f.run(); assertNotEquals(report.at("/run/id"), read(f).at("/run/id"));
            var disabled = f.repo.findById(f.scenario.id).orElseThrow(); disabled.dataPlan.enabled = false; f.repo.save(disabled); f.run();
            assertFalse(Files.exists(f.out().resolve("sources/resolution.json")));
        }
    }

    @Test void runningAndFailedRunsReplaceSuccessAndRetainOnlyTheirOwnPartialLedgers() throws Exception {
        try (var f = new SourcePlanTrafficFixture(directory, false, 42, false)) {
            configure(f); f.run(); String completedId = read(f).at("/run/id").asText();
            f.props.getOverpass().setLiveDataEnabled(true);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var client = new OverpassClient(f.props, f.json) {
                @Override public List<Poi> fetchPois(Scenario.Region region) throws Exception {
                    entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release missing"); return List.of();
                }
                @Override public List<Place> fetchPlaces(Scenario.Region r, boolean external) { return List.of(); }
            };
            var s = f.repo.findById(f.scenario.id).orElseThrow();
            s.dataPlan.policy("opportunities.work").providers = List.of(new de.synthesis.data.SourceProfiles.Candidate("osm-pois", false, "fail"));
            f.repo.save(s);
            var generation = new GenerationService(f.repo, client, f.props, f.json);
            try {
                var job = generation.start(s.id); assertTrue(entered.await(10, TimeUnit.SECONDS));
                var running = read(f); assertEquals("RUNNING", running.at("/run/state").asText());
                assertNotEquals(completedId, running.at("/run/id").asText()); assertTrue(running.at("/run/finishedAt").isNull());
                assertFalse(running.has("freight")); assertTrue(running.path("workplaces").isEmpty());
                release.countDown(); DataSourcesTest.await(job); assertEquals(GenerationJob.State.FAILED, job.state);
                var failed = read(f); assertEquals("FAILED", failed.at("/run/state").asText());
                assertEquals(running.at("/run/id"), failed.at("/run/id"));
                assertFalse(failed.at("/run/error").asText().isBlank()); assertTrue(failed.path("failures").has("workplace"));
                assertTrue(failed.has("population")); assertFalse(failed.has("populationSynthesis")); assertFalse(failed.has("freight"));
                assertEquals(failed.at("/population/totalSource"), failed.path("populationSource"));
                assertEquals(failed.at("/population/distributionSource"), failed.path("populationDistribution"));
                assertTrue(failed.path("workplaces").isEmpty()); assertFalse(failed.at("/targets/sumo").has("execution"));
                Path proof = Path.of("target/source-run-report/failed.json"); Files.createDirectories(proof.getParent()); Files.copy(f.out().resolve("sources/resolution.json"), proof, StandardCopyOption.REPLACE_EXISTING);
            } finally { release.countDown(); generation.shutdown(); }
        }
    }
    private void configure(SourcePlanTrafficFixture f) throws Exception {
        f.select("dataset", f.a.id()); var s = f.repo.findById(f.scenario.id).orElseThrow();
        for (var entry : Map.of("population.distribution", "uniform", "opportunities.work", "synthetic").entrySet()) {
            var p = new SourcePlan.Policy(); p.source = "builtins"; p.providers = List.of(new de.synthesis.data.SourceProfiles.Candidate(entry.getValue(), false, "fail")); s.dataPlan.aspects.put(entry.getKey(), p);
        }
        f.repo.save(s);
    }
    private com.fasterxml.jackson.databind.JsonNode read(SourcePlanTrafficFixture f) throws Exception { return f.json.readTree(f.out().resolve("sources/resolution.json").toFile()); }
}
