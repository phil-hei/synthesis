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
import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

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
        return new Scenario();
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
        Scenario.Region r = scenario.region;
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte zuerst eine Region auf der Karte festlegen");
        }
        if (r.minLat >= r.maxLat || r.minLon >= r.maxLon) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige Region (minLat/minLon müssen kleiner als maxLat/maxLon sein)");
        }
        if (scenario.zoning.cellSizeMeters < 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zellgröße muss mindestens 100 m betragen");
        }
    }
}
