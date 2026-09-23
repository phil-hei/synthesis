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
            if (config.appliesToSumo() && !scenario.targets().contains("sumo")) throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Zuerst SUMO als Szenario-Zielmodell auswählen oder application=catalogOnly verwenden");
            scenario.vehicleConfiguration = config;
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
