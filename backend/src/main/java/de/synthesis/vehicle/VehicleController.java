package de.synthesis.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VehicleController {
    private final ScenarioRepository scenarios;
    public VehicleController(ScenarioRepository scenarios) { this.scenarios = scenarios; }

    @GetMapping("/vehicle-types")
    public List<VehicleType> standards() { return VehicleCatalog.standards(); }

    @GetMapping("/vehicle-configurations/schema")
    public JsonNode schema() throws IOException { return resource("/vehicles/schema.json"); }

    @GetMapping("/vehicle-configurations/example")
    public JsonNode example() throws IOException { return resource("/vehicles/example.json"); }

    @PostMapping(value = "/vehicle-configurations/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VehicleCatalog.Report validate(@RequestBody String json) throws IOException {
        return VehicleCatalog.validate(parse(json));
    }

    public record OperationsPreview(boolean valid, List<VehicleCatalog.Issue> errors,
            VehicleConfiguration vehicleConfiguration, VehicleOperationsConfiguration vehicleOperations,
            String vehicleConfigurationSha256, String operationsSha256) { }

    @GetMapping("/vehicle-operations/example")
    public JsonNode operationsExample() throws IOException { return resource("/vehicles/operations-example.json"); }

    @GetMapping("/vehicle-operations/freight-example")
    public JsonNode freightOperationsExample() throws IOException { return resource("/vehicles/operations-freight-example.json"); }

    @PostMapping(value = "/vehicle-operations/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public OperationsPreview validateOperations(@RequestBody String json) throws IOException {
        if (json.length() > 2_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Betriebskonfiguration maximal 2 MB");
        JsonNode input = VehicleJson.MAPPER.readTree(json);
        if (input == null || !input.isObject() || input.size() != 2 || !input.has("vehicleConfiguration") || !input.has("vehicleOperations"))
            throw new IOException("vehicleConfiguration und vehicleOperations erforderlich");
        var vehicles = VehicleJson.read(input.path("vehicleConfiguration").toString());
        var operations = VehicleJson.MAPPER.readValue(input.path("vehicleOperations").toString(), VehicleOperationsConfiguration.class);
        var report = VehicleCatalog.validate(vehicles);
        Scenario scenario = new Scenario(); scenario.targetModels = List.of("sumo");
        scenario.vehicleConfiguration = vehicles; scenario.vehicleOperations = operations;
        var errors = new java.util.ArrayList<>(report.errors()); errors.addAll(VehicleOperationsConfiguration.validate(scenario));
        return new OperationsPreview(errors.isEmpty(), List.copyOf(errors), vehicles, operations, report.sha256(), VehicleJson.hash(operations));
    }

    @GetMapping("/scenarios/{id}/vehicle-configuration")
    public ResponseEntity<VehicleConfiguration> get(@PathVariable String id) {
        Scenario scenario = scenario(id);
        return ResponseEntity.ok().eTag(etag(scenario.vehicleConfiguration)).body(scenario.vehicleConfiguration);
    }

    @PutMapping(value = "/scenarios/{id}/vehicle-configuration", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> put(@PathVariable String id, @RequestBody String json,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) throws IOException {
        VehicleConfiguration config = parse(json);
        VehicleCatalog.Report report = VehicleCatalog.validate(config);
        if (!report.valid()) return ResponseEntity.unprocessableEntity().body(report);
        // Same lock as all repository writes; the ETag check and replacement form one operation.
        synchronized (scenarios) {
            Scenario scenario = scenario(id);
            if (ifMatch == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "Zuerst Konfiguration laden und ETag als If-Match senden");
            if (!etag(scenario.vehicleConfiguration).equals(ifMatch)) throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Fahrzeugkonfiguration wurde zwischenzeitlich geändert; erneut laden");
            scenario.vehicleConfiguration = config;
            var operationErrors = VehicleOperationsConfiguration.validate(scenario);
            if (!operationErrors.isEmpty()) return ResponseEntity.unprocessableEntity().body(Map.of("errors", operationErrors));
            scenarios.save(scenario);
            return ResponseEntity.ok().eTag(etag(config)).body(config);
        }
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> invalidJson(IOException error) {
        return ResponseEntity.badRequest().body(Map.of("errors", List.of(
                new VehicleCatalog.Issue("$", "Ungültiges Fahrzeug-JSON: " + error.getMessage()))));
    }
    private VehicleConfiguration parse(String json) throws IOException {
        if (json.length() > 1_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Fahrzeugkonfiguration maximal 1 MB");
        return VehicleJson.read(json);
    }
    private static JsonNode resource(String path) throws IOException {
        try (var stream = VehicleController.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Fahrzeugressource fehlt: " + path);
            return VehicleJson.MAPPER.readTree(stream);
        }
    }
    private Scenario scenario(String id) {
        return scenarios.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
    }
    private String etag(VehicleConfiguration config) { return "\"" + VehicleJson.hash(config) + "\""; }
}
