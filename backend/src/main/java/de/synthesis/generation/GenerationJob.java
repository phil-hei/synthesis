package de.synthesis.generation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Status of one generation run. The worker thread mutates it while HTTP threads serialize it
 * for polling, therefore every shared structure is either immutable after construction,
 * volatile, or copied under a lock before serialization. The same JSON shape is persisted as
 * {@code artifacts/job.json}, so the status survives a backend restart.
 */
public class GenerationJob {

    public static final String FILE = "job.json";

    public enum State { QUEUED, RUNNING, DONE, FAILED }

    public static class StageInfo {
        public String name;
        public volatile String status = "PENDING"; // PENDING | RUNNING | DONE | FAILED | SKIPPED
        public volatile long durationMs;
        public volatile String message;
        /** DONE, but with a documented fallback (e.g. Overpass unreachable, beeline matrices). */
        public volatile boolean fallback;

        StageInfo() {
        }

        public StageInfo(String name) {
            this.name = name;
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    public String scenarioId;
    public volatile State state = State.QUEUED;
    public volatile Instant startedAt;
    public volatile Instant finishedAt;
    public volatile String currentStage;
    public List<StageInfo> stages = new ArrayList<>();
    public List<String> log = new CopyOnWriteArrayList<>();
    /** Mutated by the worker; serialized through {@link #summarySnapshot()} only. */
    @JsonIgnore
    public final Map<String, Object> summary = Collections.synchronizedMap(new LinkedHashMap<>());
    public volatile String error;

    GenerationJob() {
    }

    public GenerationJob(String scenarioId, List<String> stageNames) {
        this.scenarioId = scenarioId;
        stageNames.forEach(name -> stages.add(new StageInfo(name)));
    }

    public StageInfo stage(String name) {
        return stages.stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    public void log(String message) {
        log.add(Instant.now().toString().substring(11, 19) + "  " + message);
    }

    /** Marks a finished stage as degraded without changing its DONE/SKIPPED/FAILED status. */
    public void fallback(String stageName, String message) {
        StageInfo info = stage(stageName);
        info.fallback = true;
        info.message = info.message == null || info.message.isBlank() ? message : info.message + "; " + message;
    }

    @JsonProperty("summary")
    public Map<String, Object> summarySnapshot() {
        synchronized (summary) {
            return new LinkedHashMap<>(summary);
        }
    }

    @JsonProperty("summary")
    void setSummary(Map<String, Object> values) {
        synchronized (summary) {
            summary.clear();
            if (values != null) summary.putAll(values);
        }
    }

    public boolean active() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    /**
     * Atomically writes the current status; a failure to persist never fails the run itself.
     * The temporary file lives next to the artifacts directory, not inside it: readers that walk
     * the artifacts (ZIP export, tests) must never see a half-written or vanishing entry.
     */
    public void persist(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path scratch = file.getParent().getParent() != null && Files.isDirectory(file.getParent().getParent())
                    ? file.getParent().getParent() : file.getParent();
            Path temporary = Files.createTempFile(scratch, ".job-", ".json");
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), this);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            log("Jobstatus konnte nicht gespeichert werden (" + e.getMessage() + ")");
        }
    }

    /**
     * Restores a persisted status. A run that was still active when the backend stopped is
     * reported as FAILED, otherwise clients would poll forever for a worker that no longer exists.
     */
    public static GenerationJob read(Path file) throws IOException {
        GenerationJob job = JSON.readValue(file.toFile(), GenerationJob.class);
        if (job.active()) {
            job.state = State.FAILED;
            job.error = "Backend wurde während des Laufs beendet; Lauf erneut starten";
            job.currentStage = null;
            job.stages.stream()
                    .filter(stage -> "RUNNING".equals(stage.status))
                    .forEach(stage -> {
                        stage.status = "FAILED";
                        stage.message = job.error;
                    });
            if (job.finishedAt == null) job.finishedAt = Instant.now();
        }
        return job;
    }
}
