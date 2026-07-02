package de.synthesis.generation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory status of one generation run, polled by the frontend. */
public class GenerationJob {

    public enum State { QUEUED, RUNNING, DONE, FAILED }

    public static class StageInfo {
        public String name;
        public String status = "PENDING"; // PENDING | RUNNING | DONE | FAILED | SKIPPED
        public long durationMs;
        public String message;

        public StageInfo(String name) {
            this.name = name;
        }
    }

    public String scenarioId;
    public State state = State.QUEUED;
    public Instant startedAt;
    public Instant finishedAt;
    public String currentStage;
    public List<StageInfo> stages = new ArrayList<>();
    public List<String> log = new CopyOnWriteArrayList<>();
    public Map<String, Object> summary = new LinkedHashMap<>();
    public String error;

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
}
