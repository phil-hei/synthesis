package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.generation.pipeline.GeoJson;
import de.synthesis.scenario.Scenario;
import de.synthesis.storage.ArtifactService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class GenerationController {

    public record ZonePreviewRequest(Scenario.Region region, double cellSizeMeters, String mode) {
    }

    private final GenerationService generation;
    private final ArtifactService artifacts;
    private final ObjectMapper mapper;

    public GenerationController(GenerationService generation, ArtifactService artifacts, ObjectMapper mapper) {
        this.generation = generation;
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    @PostMapping("/scenarios/{id}/generate")
    public ResponseEntity<GenerationJob> generate(@PathVariable String id) {
        try {
            return ResponseEntity.accepted().body(generation.start(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/scenarios/{id}/generation")
    public GenerationJob status(@PathVariable String id) {
        return generation.status(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Keine Generierung für Szenario " + id));
    }

    @PostMapping("/region/estimate")
    public GenerationService.RegionEstimate estimateRegion(@RequestBody Scenario.Region region) {
        return generation.estimateRegion(region);
    }

    @PostMapping("/preview/zones")
    public ObjectNode previewZones(@RequestBody ZonePreviewRequest request) {
        try {
            GenerationService.ZonePreview preview =
                    generation.previewZones(request.region(), request.cellSizeMeters(),
                        request.mode() != null ? request.mode() : "grid");
            ObjectNode collection = GeoJson.zonesToFeatureCollection(mapper, preview.zones());
            collection.put("cellSizeMeters", preview.cellSizeMeters());
            collection.put("autoAdjusted", preview.autoAdjusted());
            return collection;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/scenarios/{id}/artifacts")
    public List<ArtifactService.ArtifactInfo> listArtifacts(@PathVariable String id) {
        return artifacts.list(id);
    }

    @GetMapping("/scenarios/{id}/artifacts/{*path}")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String path) {
        Resource file = artifacts.load(id, path);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        MediaType mediaType = filename.endsWith(".json") || filename.endsWith(".geojson")
                ? MediaType.APPLICATION_JSON
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(file);
    }
}
