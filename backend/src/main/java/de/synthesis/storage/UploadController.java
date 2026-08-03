package de.synthesis.storage;

import de.synthesis.generation.pipeline.BaCommuterAdapter;
import de.synthesis.generation.pipeline.CommuterMatrix;
import de.synthesis.generation.pipeline.MunicipalityCentroidSource;
import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.generation.pipeline.RoadNetworkFile;
import de.synthesis.scenario.Scenario;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Per-scenario data uploads. Stable filenames form the contract with the
 * generation pipeline; uploaded source filenames never become filesystem paths.
 */
@RestController
@RequestMapping("/api/scenarios/{id}/uploads")
public class UploadController {

    public static final String POPULATION_GRID = "population-grid.csv";
    public static final String ROAD_NETWORK = RoadNetworkFile.NAME;

    public record UploadSummary(String name, String format, int points, long totalPopulation,
                                long skippedLines) {
    }

    public record UploadInfo(String name, long sizeBytes) {
    }

    public record RoadNetworkUploadSummary(String name, long nodes, long ways, long highwayWays,
                                           double minLat, double minLon, double maxLat, double maxLon,
                                           String sha256) {
    }

    public record CommuterUploadSummary(String name, int relations, long totalCommuters,
                                        int relationsWithHomeInRegion, String source,
                                        long exportTotalCommuters, double coverage,
                                        int skippedAggregateRows, int unresolvedMunicipalities) {
    }

    private final ScenarioRepository scenarios;
    private final MunicipalityCentroidSource municipalityCentroids;

    public UploadController(ScenarioRepository scenarios,
                            MunicipalityCentroidSource municipalityCentroids) {
        this.scenarios = scenarios;
        this.municipalityCentroids = municipalityCentroids;
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

    @PostMapping("/road-network")
    public RoadNetworkUploadSummary uploadRoadNetwork(@PathVariable String id,
                                                       @RequestParam("file") MultipartFile file)
            throws IOException {
        Scenario scenario = scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null
                || !(originalName.toLowerCase(Locale.ROOT).endsWith(".osm")
                || originalName.toLowerCase(Locale.ROOT).endsWith(".xml"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte ein OSM-Straßennetz im XML-Format (.osm oder .xml) hochladen");
        }
        if (scenario.region == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Szenario hat keine Region für die Netzabdeckungsprüfung");
        }

        Path dir = scenarios.uploadsDir(id);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, "road-network-", ".osm");
        try {
            file.transferTo(temporary.toAbsolutePath());
            RoadNetworkFile.Inspection inspection =
                    RoadNetworkFile.inspectForRegion(temporary, scenario.region);
            moveReplacing(temporary, dir.resolve(ROAD_NETWORK));
            return new RoadNetworkUploadSummary(ROAD_NETWORK, inspection.nodes(), inspection.ways(),
                    inspection.highwayWays(), inspection.minLat(), inspection.minLon(),
                    inspection.maxLat(), inspection.maxLon(), inspection.sha256());
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(temporary);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    @PostMapping("/commuters")
    public CommuterUploadSummary uploadCommuters(@PathVariable String id,
                                                 @RequestParam("file") MultipartFile file)
            throws IOException {
        Scenario scenario = scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null
                || !originalName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte eine normalisierte OD-Matrix oder einen BA-Auspendlerexport als CSV hochladen");
        }
        Path dir = scenarios.uploadsDir(id);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, "commuters-", ".csv");
        Path normalized = null;
        try {
            file.transferTo(temporary.toAbsolutePath());
            CommuterMatrix.ParseResult parsed;
            String source;
            long exportTotal;
            int aggregateRows;
            int unresolved;
            if (BaCommuterAdapter.isBaExport(temporary)) {
                BaCommuterAdapter.Export export = BaCommuterAdapter.parse(temporary);
                BaCommuterAdapter.Adapted adapted;
                try {
                    adapted = BaCommuterAdapter.adapt(export, municipalityCentroids.fetch());
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "BKG-Gemeindepunkte konnten nicht geladen werden: " + e.getMessage(), e);
                }
                normalized = Files.createTempFile(dir, "commuters-normalized-", ".csv");
                CommuterMatrix.writeNormalized(normalized, adapted.matrix(),
                        BaCommuterAdapter.SOURCE);
                parsed = adapted.matrix();
                source = BaCommuterAdapter.SOURCE;
                exportTotal = adapted.exportTotalCommuters();
                aggregateRows = adapted.aggregateRows();
                unresolved = adapted.unresolvedMunicipalities();
            } else {
                parsed = CommuterMatrix.parse(temporary);
                source = CommuterMatrix.source(temporary);
                exportTotal = Math.round(parsed.totalCommuters());
                aggregateRows = 0;
                unresolved = 0;
            }
            int relevant = (int) parsed.flows().stream()
                    .filter(flow -> contains(scenario.region, flow.homeLat(), flow.homeLon()))
                    .count();
            if (relevant == 0) {
                throw new IllegalArgumentException(
                        "Pendlermatrix enthält keinen Wohnort in der Szenarioregion");
            }
            moveReplacing(normalized == null ? temporary : normalized,
                    dir.resolve(CommuterMatrix.NAME));
            Files.deleteIfExists(temporary);
            long total = Math.round(parsed.totalCommuters());
            return new CommuterUploadSummary(CommuterMatrix.NAME, parsed.flows().size(),
                    total, relevant, source, exportTotal,
                    exportTotal <= 0 ? 0 : (double) total / exportTotal,
                    aggregateRows, unresolved);
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResponseStatusException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            throw e;
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

    @DeleteMapping("/road-network")
    public ResponseEntity<Void> deleteRoadNetwork(@PathVariable String id) throws IOException {
        Files.deleteIfExists(scenarios.uploadsDir(id).resolve(ROAD_NETWORK));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/commuters")
    public ResponseEntity<Void> deleteCommuters(@PathVariable String id) throws IOException {
        Files.deleteIfExists(scenarios.uploadsDir(id).resolve(CommuterMatrix.NAME));
        return ResponseEntity.noContent().build();
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean contains(Scenario.Region region, double lat, double lon) {
        return region != null && lat >= region.minLat && lat <= region.maxLat
                && lon >= region.minLon && lon <= region.maxLon;
    }
}
