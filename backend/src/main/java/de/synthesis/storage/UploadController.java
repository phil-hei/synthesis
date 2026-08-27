package de.synthesis.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.generation.pipeline.BaCommuterAdapter;
import de.synthesis.generation.pipeline.CommuterMatrix;
import de.synthesis.generation.pipeline.MunicipalityCentroidSource;
import de.synthesis.generation.pipeline.OsmDataSnapshot;
import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.generation.pipeline.RoadNetworkFile;
import de.synthesis.generation.pipeline.WorkplaceMargins;
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
import java.util.Map;
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
    public static final String OSM_DATA = OsmDataSnapshot.NAME;

    public record UploadSummary(String name, String format, int points, long totalPopulation,
                                long skippedLines) {
    }

    public record UploadInfo(String name, long sizeBytes) {
    }

    public record RoadNetworkUploadSummary(String name, long nodes, long ways, long highwayWays,
                                           double minLat, double minLon, double maxLat, double maxLon,
                                           String sha256) {
    }

    public record OsmDataUploadSummary(String name, int elements, int pois, int places,
                                       String sha256) {
    }

    public record CommuterUploadSummary(String name, int relations, long totalCommuters,
                                        int relationsWithHomeInRegion, String source,
                                        long exportTotalCommuters, double coverage,
                                        int skippedAggregateRows, int unresolvedMunicipalities,
                                        WorkplaceMarginUploadSummary workplaceMargin) {
    }

    public record WorkplaceMarginUploadSummary(String name, String source, String date,
                                               int municipalities, long residentWorkers,
                                               long outboundWorkers, double externalShare,
                                               int unresolvedMunicipalities) {
    }

    private final ScenarioRepository scenarios;
    private final MunicipalityCentroidSource municipalityCentroids;
    private final ObjectMapper mapper;

    public UploadController(ScenarioRepository scenarios,
                            MunicipalityCentroidSource municipalityCentroids,
                            ObjectMapper mapper) {
        this.scenarios = scenarios;
        this.municipalityCentroids = municipalityCentroids;
        this.mapper = mapper;
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

    @PostMapping("/osm-data")
    public OsmDataUploadSummary uploadOsmData(@PathVariable String id,
                                              @RequestParam("file") MultipartFile file)
            throws IOException {
        scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null
                || !originalName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte einen Overpass-JSON-Snapshot (.json) hochladen");
        }
        Path dir = scenarios.uploadsDir(id);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, "osm-data-", ".json");
        try {
            file.transferTo(temporary.toAbsolutePath());
            OsmDataSnapshot.Content content = OsmDataSnapshot.read(temporary, mapper);
            moveReplacing(temporary, dir.resolve(OSM_DATA));
            return new OsmDataUploadSummary(OSM_DATA, content.elements(), content.pois().size(),
                    content.places().size(), content.sha256());
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
        Path normalizedMargin = null;
        WorkplaceMarginUploadSummary automaticMargin = null;
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
                String homeAgs = export.relations().getFirst().homeAgs();
                var margin = WorkplaceMargins.bundledBaMargin(homeAgs);
                if (margin.isPresent()) {
                    WorkplaceMargins.Margin value = margin.get();
                    normalizedMargin = Files.createTempFile(dir,
                            "workplace-margins-automatic-", ".csv");
                    WorkplaceMargins.writeNormalized(normalizedMargin, value);
                    automaticMargin = summary(value);
                }
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
            Path marginTarget = dir.resolve(WorkplaceMargins.NAME);
            if (normalizedMargin != null) {
                moveReplacing(normalizedMargin, marginTarget);
            } else {
                removeAutomaticMargin(marginTarget);
            }
            Files.deleteIfExists(temporary);
            long total = Math.round(parsed.totalCommuters());
            return new CommuterUploadSummary(CommuterMatrix.NAME, parsed.flows().size(),
                    total, relevant, source, exportTotal,
                    exportTotal <= 0 ? 0 : (double) total / exportTotal,
                    aggregateRows, unresolved, automaticMargin);
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            if (normalizedMargin != null) {
                Files.deleteIfExists(normalizedMargin);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResponseStatusException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            if (normalizedMargin != null) {
                Files.deleteIfExists(normalizedMargin);
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            if (normalized != null) {
                Files.deleteIfExists(normalized);
            }
            if (normalizedMargin != null) {
                Files.deleteIfExists(normalizedMargin);
            }
            throw e;
        }
    }

    @PostMapping("/workplace-margins")
    public WorkplaceMarginUploadSummary uploadWorkplaceMargins(@PathVariable String id,
                                                               @RequestParam("file") MultipartFile file)
            throws IOException {
        Scenario scenario = scenarios.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null
                || !originalName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte einen Flat-File-CSV-Export der Regionalstatistik-Tabelle 13111-08-02-5-B hochladen");
        }
        if (scenario.region == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Szenario hat keine Region für die Gemeindeauswahl");
        }

        Path dir = scenarios.uploadsDir(id);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, "workplace-margins-", ".csv");
        Path normalized = Files.createTempFile(dir, "workplace-margins-normalized-", ".csv");
        try {
            file.transferTo(temporary.toAbsolutePath());
            WorkplaceMargins.Export export = WorkplaceMargins.parseOfficial(temporary);
            Map<String, MunicipalityCentroidSource.Point> points;
            try {
                points = municipalityCentroids.fetch();
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "BKG-Gemeindepunkte konnten nicht geladen werden: " + e.getMessage(), e);
            }
            WorkplaceMargins.Margin margin = WorkplaceMargins.selectLatestForRegion(export,
                    code -> {
                        MunicipalityCentroidSource.Point point = points.get(code);
                        return point != null && contains(scenario.region, point.lat(), point.lon());
                    }, points::containsKey);
            WorkplaceMargins.writeNormalized(normalized, margin);
            moveReplacing(normalized, dir.resolve(WorkplaceMargins.NAME));
            Files.deleteIfExists(temporary);
            return summary(margin);
        } catch (IllegalArgumentException e) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(normalized);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResponseStatusException e) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(normalized);
            throw e;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(normalized);
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

    @DeleteMapping("/osm-data")
    public ResponseEntity<Void> deleteOsmData(@PathVariable String id) throws IOException {
        Files.deleteIfExists(scenarios.uploadsDir(id).resolve(OSM_DATA));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/commuters")
    public ResponseEntity<Void> deleteCommuters(@PathVariable String id) throws IOException {
        Path dir = scenarios.uploadsDir(id);
        Files.deleteIfExists(dir.resolve(CommuterMatrix.NAME));
        removeAutomaticMargin(dir.resolve(WorkplaceMargins.NAME));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/workplace-margins")
    public ResponseEntity<Void> deleteWorkplaceMargins(@PathVariable String id) throws IOException {
        Files.deleteIfExists(scenarios.uploadsDir(id).resolve(WorkplaceMargins.NAME));
        return ResponseEntity.noContent().build();
    }

    private static WorkplaceMarginUploadSummary summary(WorkplaceMargins.Margin margin) {
        return new WorkplaceMarginUploadSummary(WorkplaceMargins.NAME,
                margin.source(), margin.date().toString(), margin.municipalities(),
                margin.residentWorkers(), margin.outboundWorkers(), margin.externalShare(),
                margin.unresolvedMunicipalities());
    }

    /** Removes only an automatically coupled BA margin; explicit legacy uploads are preserved. */
    private static void removeAutomaticMargin(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            if (WorkplaceMargins.BA_SOURCE.equals(WorkplaceMargins.readNormalized(file).source())) {
                Files.deleteIfExists(file);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed or unknown explicit file is not silently deleted by another upload.
        }
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
