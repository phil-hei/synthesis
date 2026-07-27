package de.synthesis.scenario;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private static final Set<String> BEHAVIOR_SOURCES = Set.of("actitopp", "gta", "template");
    private static final Set<String> TARGET_MODELS = Set.of("logitopp", "sumo", "mobilecity");

    private final ScenarioRepository repository;

    public ScenarioController(ScenarioRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Scenario> list() {
        return repository.findAll();
    }

    @GetMapping("/defaults")
    public Scenario defaults() {
        Scenario scenario = new Scenario();
        // Neuanlagen starten auf dem INSPIRE-/Zensus-Gitter (Europa-Projektfokus). Der
        // FELD-Default in Scenario.Zoning bleibt "grid", damit persistierte Alt-Szenarien
        // ohne mode-Key ihr Bbox-Raster behalten; außerhalb Europas fällt die Zonierungs-
        // Stufe ohnehin auf das Bbox-Raster zurück (Epsg3035.covers).
        scenario.zoning.mode = "inspire";
        return scenario;
    }

    @GetMapping("/{id}")
    public Scenario get(@PathVariable String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
    }

    @PostMapping
    public ResponseEntity<Scenario> create(@RequestBody Scenario scenario) {
        scenario.id = UUID.randomUUID().toString().substring(0, 8);
        scenario.createdAt = Instant.now();
        validate(scenario);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(scenario));
    }

    @PutMapping("/{id}")
    public Scenario update(@PathVariable String id, @RequestBody Scenario scenario) {
        Scenario existing = get(id);
        scenario.id = existing.id;
        scenario.createdAt = existing.createdAt;
        validate(scenario);
        return repository.save(scenario);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        repository.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * GTFS-Upload (Phase-C-Fallback): speichert einen Feed für das Szenario und verankert den
     * Pfad in {@code population.gtfsUploadPath}. Greift, wenn der Auto-Fetch keinen Regionsfeed
     * findet oder der Feed zu groß ist.
     */
    @PostMapping("/{id}/gtfs")
    public Scenario uploadGtfs(@PathVariable String id,
                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Scenario scenario = get(id);
        if (file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte eine GTFS-ZIP-Datei hochladen");
        }
        try {
            java.nio.file.Path uploads = repository.uploadsDir(id);
            java.nio.file.Files.createDirectories(uploads);
            java.nio.file.Path dest = uploads.resolve("gtfs.zip");
            file.transferTo(dest.toAbsolutePath());
            scenario.population.gtfsUploadPath = dest.toString();
            return repository.save(scenario);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GTFS-Upload fehlgeschlagen: " + e.getMessage());
        }
    }

    private void validate(Scenario scenario) {
        if (scenario.zoning == null) {
            throw badRequest("Zonierungsparameter fehlen");
        }
        if (scenario.population == null) {
            throw badRequest("Bevölkerungsparameter fehlen");
        }
        if (scenario.logistics == null) {
            throw badRequest("Logistikparameter fehlen");
        }
        Scenario.Region r = scenario.region;
        if (r == null) {
            throw badRequest("Bitte zuerst eine Region auf der Karte festlegen");
        }
        if (!Double.isFinite(r.minLat) || !Double.isFinite(r.minLon)
                || !Double.isFinite(r.maxLat) || !Double.isFinite(r.maxLon)
                || r.minLat < -90 || r.maxLat > 90 || r.minLon < -180 || r.maxLon > 180) {
            throw badRequest("Region enthält ungültige Koordinaten");
        }
        if (r.minLat >= r.maxLat || r.minLon >= r.maxLon) {
            throw badRequest("Ungültige Region (minLat/minLon müssen kleiner als maxLat/maxLon sein)");
        }
        if (!Double.isFinite(scenario.zoning.cellSizeMeters) || scenario.zoning.cellSizeMeters < 100) {
            throw badRequest("Zellgröße muss mindestens 100 m betragen");
        }
        if (!"grid".equals(scenario.zoning.mode) && !"inspire".equals(scenario.zoning.mode)) {
            throw badRequest("Unbekannter Zonierungsmodus");
        }
        String behaviorSource = scenario.population.behaviorSourceEffective();
        if (!BEHAVIOR_SOURCES.contains(behaviorSource)) {
            throw badRequest("Unbekannte Verhaltensquelle");
        }
        if (scenario.targetModels != null
                && scenario.targetModels.stream()
                .anyMatch(target -> target == null || !TARGET_MODELS.contains(target))) {
            throw badRequest("Unbekanntes Zielmodell");
        }
        double samplingFraction = scenario.population.samplingFraction;
        if (!Double.isFinite(samplingFraction) || samplingFraction <= 0 || samplingFraction > 1) {
            throw badRequest("Stichprobenanteil muss größer 0 und höchstens 1 sein");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
