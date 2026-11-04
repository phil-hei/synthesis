package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationJobTest {

    @TempDir
    Path dir;

    @Test
    void serializesSummaryExactlyOnceAndRoundTripsThroughTheArtifactFile() throws Exception {
        GenerationJob job = new GenerationJob("abc", List.of("Zonierung", "Matrizen"));
        job.state = GenerationJob.State.DONE;
        job.startedAt = Instant.parse("2026-11-04T10:00:00Z");
        job.finishedAt = Instant.parse("2026-11-04T10:00:16Z");
        job.summary.put("zones", 529);
        job.summary.put("matrixSource", "beeline-estimate");
        job.stage("Zonierung").status = "DONE";
        job.stage("Matrizen").status = "DONE";
        job.fallback("Matrizen", "Straßennetz nicht verfügbar");
        job.log("Generierung abgeschlossen");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(job);
        assertEquals(1, json.split("\"summary\"", -1).length - 1, "summary darf nur einmal erscheinen");
        assertTrue(json.contains("\"fallback\":true"));

        Path file = dir.resolve(GenerationJob.FILE);
        job.persist(file);
        GenerationJob restored = GenerationJob.read(file);
        assertEquals(GenerationJob.State.DONE, restored.state);
        assertEquals(529, restored.summary.get("zones"));
        assertEquals("beeline-estimate", restored.summary.get("matrixSource"));
        assertTrue(restored.stage("Matrizen").fallback);
        assertEquals("Straßennetz nicht verfügbar", restored.stage("Matrizen").message);
        assertEquals(job.startedAt, restored.startedAt);
        assertEquals(1, restored.log.size());
        assertNull(restored.error);
    }

    @Test
    void restoringAnActiveRunReportsItAsFailedInsteadOfPollingForever() throws Exception {
        GenerationJob job = new GenerationJob("abc", List.of("Zonierung", "Matrizen"));
        job.state = GenerationJob.State.RUNNING;
        job.startedAt = Instant.now();
        job.currentStage = "Matrizen";
        job.stage("Zonierung").status = "DONE";
        job.stage("Matrizen").status = "RUNNING";
        Path file = dir.resolve(GenerationJob.FILE);
        job.persist(file);

        GenerationJob restored = GenerationJob.read(file);
        assertEquals(GenerationJob.State.FAILED, restored.state);
        assertFalse(restored.active());
        assertEquals("FAILED", restored.stage("Matrizen").status);
        assertEquals("DONE", restored.stage("Zonierung").status);
        assertTrue(restored.error.contains("Backend wurde während des Laufs beendet"));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void snapshotIsSafeWhileAnotherThreadMutatesTheSummary() throws Exception {
        GenerationJob job = new GenerationJob("abc", List.of("Zonierung"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            started.countDown();
            for (int i = 0; i < 20_000; i++) {
                job.summary.put("key" + (i % 500), i);
            }
        });
        writer.start();
        started.await();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try {
            for (int i = 0; i < 200; i++) {
                Map<String, Object> snapshot = job.summarySnapshot();
                mapper.writeValueAsString(job);
                assertTrue(snapshot.size() <= 500);
            }
        } catch (Throwable t) {
            failure.set(t);
        }
        writer.join();
        assertNull(failure.get(), "Serialisierung darf nicht an nebenläufigen Änderungen scheitern");
    }
}
