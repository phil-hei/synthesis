package de.synthesis.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Side-by-side comparison of several scenarios: bundles each scenario's name,
 * a few headline parameters and — if it has been simulated — the KPI and
 * depot-statistics artifacts, so the frontend can render a comparison table.
 */
@RestController
@RequestMapping("/api/comparison")
public class ComparisonController {

    private final ScenarioRepository repository;
    private final ObjectMapper mapper;

    public ComparisonController(ScenarioRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @GetMapping
    public ArrayNode compare(@RequestParam("ids") List<String> ids) {
        ArrayNode result = mapper.createArrayNode();
        for (String id : ids) {
            repository.findById(id).ifPresent(scenario -> result.add(entry(scenario)));
        }
        return result;
    }

    private ObjectNode entry(Scenario scenario) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", scenario.id);
        node.put("name", scenario.name);

        ObjectNode params = node.putObject("params");
        params.put("cellSizeMeters", scenario.zoning.cellSizeMeters);
        params.put("samplingFraction", scenario.population.samplingFraction);
        params.put("useRealRouting", scenario.population.useRealRouting);
        params.put("correctDistanceDecay", scenario.population.correctDistanceDecay);
        params.put("parcelsPerPersonWeek", scenario.logistics.parcelsPerPersonWeek);
        params.put("returnsEnabled", scenario.logistics.returns.enabled);
        params.put("returnShare", scenario.logistics.returns.shareOfPrivateOrders);
        params.put("depots", scenario.logistics.depots.size());

        Path simulation = repository.artifactsDir(scenario.id).resolve("simulation");
        JsonNode kpis = readJson(simulation.resolve("kpis.json"));
        node.set("kpis", kpis);
        node.set("depotStats", readJson(simulation.resolve("depot-stats.json")));
        // SUMO-Kennzahlen (Fahrzeuge, Lieferverkehr, CO₂ je Typ) für den Emissionsvergleich
        node.set("sumo", readJson(repository.artifactsDir(scenario.id)
                .resolve("sumo").resolve("summary.json")));
        node.put("simulated", !kpis.isNull());
        return node;
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(Path file) {
        if (!Files.exists(file)) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            return mapper.nullNode();
        }
    }
}
