package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.data.SourceResolver;
import de.synthesis.data.importing.ImportSupport;
import de.synthesis.scenario.Scenario;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Latest source run, including partial failures. Never infers execution from generated amounts. */
final class SourceRunReport {
    static final String CONTRACT = "synthesis-source-run-resolution-v1";
    private SourceRunReport() { }

    static void write(Path artifacts, ObjectMapper mapper, Scenario scenario, GenerationJob job,
                      SourceResolver.Inputs inputs, Instant finishedAt) throws IOException {
        if (scenario.dataPlan == null || !scenario.dataPlan.enabled) return;
        var run = new LinkedHashMap<String, Object>();
        run.put("id", scenario.id + "/" + job.startedAt);
        run.put("scenarioId", scenario.id); run.put("state", job.state);
        run.put("startedAt", job.startedAt.toString()); run.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        run.put("error", job.error); run.put("seed", scenario.population.seed);
        run.put("referenceYear", scenario.referenceYear);
        for (String name : List.of("plan", "lock"))
            run.put(name + "Sha256", ImportSupport.hash(Files.readAllBytes(artifacts.resolve("sources/" + name + ".json"))));
        var report = new LinkedHashMap<String, Object>();
        report.put("contract", CONTRACT); report.put("run", run);
        report.put("plan", scenario.dataPlan);
        // Queue-time decisions remain distinct from the actual per-aspect application ledgers.
        report.put("decisions", inputs.decisions());
        report.put("workplaces", job.summary.getOrDefault("workplaceDataset", Map.of()));
        report.put("populationSource", job.summary.getOrDefault("populationSource", "not-resolved"));
        report.put("populationDistribution", job.summary.getOrDefault("populationDistribution", "not-resolved"));
        if (job.stage("Bevölkerungssynthese").status.equals("DONE"))
            report.put("populationSynthesis", mapper.readTree(artifacts.resolve("population/household-rounding.json").toFile()));
        if (job.summary.get("populationSources") instanceof Map<?, ?> population) {
            report.put("population", population);
            report.put("populationSource", population.get("totalSource"));
            report.put("populationDistribution", population.get("distributionSource"));
        }
        if (job.summary.containsKey("freightSources")) report.put("freight", job.summary.get("freightSources"));
        var failures = new LinkedHashMap<String, Object>();
        for (String aspect : List.of("population", "workplace")) {
            Path file = artifacts.resolve("sources/" + aspect + "-failure.json");
            if (Files.isRegularFile(file)) failures.put(aspect, mapper.readTree(file.toFile()));
        }
        report.put("failures", failures);
        var sumo = new LinkedHashMap<String, Object>();
        sumo.put("stageStatus", job.stage("SUMO (Verkehr)").status);
        // Earlier runs may have left execution files. Only this run's successful stage may bind them.
        if (job.stage("SUMO (Verkehr)").status.equals("DONE")) {
            Path executionFile = artifacts.resolve("sumo/traffic-execution.json");
            var execution = mapper.readTree(executionFile.toFile());
            var summary = mapper.createObjectNode();
            // The per-trip request ledger can be large. Bind its hash and retain only aggregate evidence.
            for (String field : List.of("contract", "status", "routingVerified", "demandRetained", "vehicles",
                    "persons", "nativeVehicles", "nativeVehicleTeleports", "teleportAccountingComplete", "groups"))
                if (execution.has(field)) summary.set(field, execution.get(field));
            sumo.put("execution", summary);
            sumo.put("executionReport", "sumo/traffic-execution.json");
            sumo.put("executionSha256", ImportSupport.hash(Files.readAllBytes(executionFile)));
        }
        report.put("targets", Map.of("sumo", sumo, "logitopp",
                Map.of("stageStatus", job.stage("Simulation (logiTopp)").status)));
        Path file = artifacts.resolve("sources/resolution.json");
        Path temporary = Files.createTempFile(file.getParent(), ".resolution-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
