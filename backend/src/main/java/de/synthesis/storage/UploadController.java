package de.synthesis.storage;

import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.scenario.ScenarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Per-scenario data uploads. Currently one slot: a point-based population grid
 * (Zensus 2022 CSV or generic lat/lon/population), stored as
 * uploads/population-grid.csv and picked up by the generation pipeline as the
 * highest-quality population source.
 */
@RestController
@RequestMapping("/api/scenarios/{id}/uploads")
public class UploadController {

    public static final String POPULATION_GRID = "population-grid.csv";

    public record UploadSummary(String name, String format, int points, long totalPopulation,
                                long skippedLines) {
    }

    public record UploadInfo(String name, long sizeBytes) {
    }

    private final ScenarioRepository scenarios;

    public UploadController(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @PostMapping("/population-grid")
    public UploadSummary uploadPopulationGrid(@PathVariable String id,
                                              @RequestParam("file") MultipartFile file) throws IOException {
        scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
        Path dir = scenarios.uploadsDir(id);
        Files.createDirectories(dir);
        Path target = dir.resolve(POPULATION_GRID);
        file.transferTo(target.toAbsolutePath());
        try {
            PopulationGridParser.ParseResult result = PopulationGridParser.parse(target);
            if (result.points().isEmpty()) {
                Files.deleteIfExists(target);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Keine verwertbaren Datenpunkte in der Datei gefunden");
            }
            return new UploadSummary(POPULATION_GRID, result.format(), result.points().size(),
                    Math.round(result.totalPopulation()), result.skippedLines());
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(target);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public List<UploadInfo> list(@PathVariable String id) {
        Path dir = scenarios.uploadsDir(id);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> {
                        try {
                            return new UploadInfo(f.getFileName().toString(), Files.size(f));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DeleteMapping("/population-grid")
    public ResponseEntity<Void> deletePopulationGrid(@PathVariable String id) throws IOException {
        Files.deleteIfExists(scenarios.uploadsDir(id).resolve(POPULATION_GRID));
        return ResponseEntity.noContent().build();
    }
}
