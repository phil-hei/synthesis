package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.LogitoppDemandAdapter;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.generation.pipeline.ActitoppRunner;
import de.synthesis.generation.pipeline.GtaBehaviorRunner;
import de.synthesis.generation.pipeline.ActivityGenerator;
import de.synthesis.generation.pipeline.AttractivityCalculator;
import de.synthesis.generation.pipeline.CsvArtifacts;
import de.synthesis.generation.pipeline.CommuterMatrix;
import de.synthesis.generation.pipeline.CensusPopulationSource;
import de.synthesis.generation.pipeline.ExternalZones;
import de.synthesis.generation.pipeline.FeedbackMath;
import de.synthesis.generation.pipeline.GeoJson;
import de.synthesis.generation.pipeline.Epsg3035;
import de.synthesis.generation.pipeline.GridZoning;
import de.synthesis.generation.pipeline.InspireGridZoning;
import de.synthesis.generation.pipeline.LegacyExportGenerator;
import de.synthesis.generation.pipeline.LogisticsGenerator;
import de.synthesis.generation.pipeline.MatrixGenerator;
import de.synthesis.generation.pipeline.MobileCityExporter;
import de.synthesis.generation.pipeline.DistanceCalibrator;
import de.synthesis.generation.pipeline.DestinationChoiceParameterAdjuster;
import de.synthesis.generation.pipeline.ModeChoiceCalibrator;
import de.synthesis.generation.pipeline.ModeDistanceCalibrator;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.generation.pipeline.OsmDataSnapshot;
import de.synthesis.generation.pipeline.PopulationEstimator;
import de.synthesis.generation.pipeline.PopulationGenerator;
import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.generation.pipeline.RoutingEngine;
import de.synthesis.generation.pipeline.RoadNetworkFile;
import de.synthesis.generation.pipeline.SimulationResultIngestor;
import de.synthesis.generation.pipeline.SimulationRunner;
import de.synthesis.generation.pipeline.VisumMatrixWriter;
import de.synthesis.generation.pipeline.WorkplaceMargins;
import de.synthesis.generation.pipeline.LlmModeChoiceRunner;
import de.synthesis.generation.pipeline.GtfsSource;
import de.synthesis.generation.pipeline.SumoRunner;
import de.synthesis.generation.pipeline.TransitRunner;
import de.synthesis.generation.pipeline.Zone;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import de.synthesis.reference.Mid2017;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GenerationService {

    public static final List<String> STAGES = List.of(
            "Zonierung", "OSM-Daten (POIs)", "Attraktivitäten", "Bevölkerungssynthese",
            "Aktivitätenpläne", "Matrizen", "Logistik", "Legacy-Export", "MobileCity-Export",
            "Manifest", "Simulation (logiTopp)", "SUMO (Verkehr)", "Netz-Feedback", "LLM-Moduswahl");

    private final ScenarioRepository scenarios;
    private final OverpassClient overpass;
    private final CensusPopulationSource census;
    private final SynthesisProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    // Bewusst 1: die Simulationsstufe startet eine logiTopp-JVM mit bis zu 12 GB Heap und teilt
    // sich das Gradle-Projekt mobitopp-rastatt; parallele Läufe würden RAM und Arbeitsverzeichnis
    // kollidieren lassen. Weitere Jobs warten sichtbar als QUEUED.
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    public GenerationService(ScenarioRepository scenarios, OverpassClient overpass,
                             SynthesisProperties properties, ObjectMapper mapper) {
        this.scenarios = scenarios;
        this.overpass = overpass;
        this.census = new CensusPopulationSource(properties.getCensus());
        this.properties = properties;
        this.mapper = mapper;
    }

    public GenerationJob start(String scenarioId) {
        Scenario scenario = scenarios.findById(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Szenario nicht gefunden: " + scenarioId));
        GenerationJob existing = jobs.get(scenarioId);
        if (existing != null && (existing.state == GenerationJob.State.QUEUED
                || existing.state == GenerationJob.State.RUNNING)) {
            throw new IllegalStateException("Für dieses Szenario läuft bereits eine Generierung");
        }
        GenerationJob job = new GenerationJob(scenarioId, STAGES);
        jobs.put(scenarioId, job);
        executor.submit(() -> run(scenario, job));
        return job;
    }

    public Optional<GenerationJob> status(String scenarioId) {
        return Optional.ofNullable(jobs.get(scenarioId));
    }

    public record ZonePreview(List<Zone> zones, double cellSizeMeters, boolean autoAdjusted) {
    }

    public ZonePreview previewZones(Scenario.Region region, double cellSizeMeters) {
        return previewZones(region, cellSizeMeters, "grid");
    }

    /** Auto-raises the cell size to the finest resolution within the zone limit; honours the zoning mode. */
    public ZonePreview previewZones(Scenario.Region region, double cellSizeMeters, String mode) {
        return previewZones(region, cellSizeMeters, mode, null);
    }

    /** Preview with the scenario-specific limit; null retains the configured default. */
    public ZonePreview previewZones(Scenario.Region region, double cellSizeMeters, String mode,
                                    Integer requestedMaxZones) {
        int maxZones = effectiveMaxZones(requestedMaxZones);
        // LAEA-Modi nur im EPSG:3035-Gültigkeitsbereich (Europa) — außerhalb Bbox-Raster zeigen
        if (("inspire".equalsIgnoreCase(mode) || "epsg3035".equalsIgnoreCase(mode))
                && Epsg3035.covers(region.minLat, region.minLon, region.maxLat, region.maxLon)) {
            if ("inspire".equalsIgnoreCase(mode)) {
                int res = InspireGridZoning.pickResolution(region, cellSizeMeters, maxZones);
                return new ZonePreview(InspireGridZoning.generate(region, res, maxZones),
                        res, res != cellSizeMeters);
            }
            int res = InspireGridZoning.pickAlignedResolution(region, cellSizeMeters, maxZones);
            return new ZonePreview(InspireGridZoning.generateAligned(region, res, maxZones),
                    res, res != cellSizeMeters);
        }
        double effective = GridZoning.autoCellSize(region, cellSizeMeters, maxZones);
        return new ZonePreview(
                GridZoning.generate(region, effective, maxZones),
                effective, effective != cellSizeMeters);
    }

    private int effectiveMaxZones(Integer requestedMaxZones) {
        int maxZones = requestedMaxZones != null ? requestedMaxZones : properties.getMaxZones();
        if (maxZones < 100 || maxZones > properties.getMaxZonesHardLimit()) {
            throw new IllegalArgumentException("Maximale Zonenanzahl muss zwischen 100 und "
                    + properties.getMaxZonesHardLimit() + " liegen");
        }
        return maxZones;
    }

    public record RegionEstimate(long estimatedPopulation, String source, String sourceLabel,
                                 String referenceDate, String sourceUrl,
                                 List<PlaceInfo> places) {
        public record PlaceInfo(String name, String type, long population) {
        }
    }

    /** Amtliche Zensus-Schätzung für Deutschland, danach OSM-Siedlungsdaten (ohne Persistenz). */
    public RegionEstimate estimateRegion(Scenario.Region region) {
        Optional<CensusPopulationSource.Resolved> censusEstimate = census.resolve(region,
                censusCacheDir(), ignored -> { });
        if (censusEstimate.isPresent()) {
            CensusPopulationSource.Resolved resolved = censusEstimate.get();
            long total = Math.round(resolved.grid().totalPopulation());
            CensusPopulationSource.Provenance p = resolved.provenance();
            return new RegionEstimate(total, "zensus-2022", "Destatis Zensus 2022",
                    p.referenceDate(), p.url(), List.of());
        }
        if (!properties.getOverpass().isLiveDataEnabled()) {
            return new RegionEstimate(0, "none", "", "", "", List.of());
        }
        try {
            List<OverpassClient.Place> places = overpass.fetchPlaces(region);
            if (places.isEmpty()) {
                return new RegionEstimate(0, "none", "", "", "", List.of());
            }
            long estimate = PopulationEstimator.estimateForRegion(region, places);
            List<RegionEstimate.PlaceInfo> infos = places.stream()
                    .sorted((a, b) -> Long.compare(b.population(), a.population()))
                    .limit(8)
                    .map(p -> new RegionEstimate.PlaceInfo(p.name(), p.type(), p.population()))
                    .toList();
            return new RegionEstimate(estimate, "osm-places", "OSM-Siedlungsdaten",
                    "", properties.getOverpass().getEndpoint(), infos);
        } catch (Exception e) {
            return new RegionEstimate(0, "unavailable", "", "", "", List.of());
        }
    }

    private Path censusCacheDir() {
        return Path.of(properties.getDataDir()).resolve("census-cache");
    }

    private record PopulationProvenance(String source, String label, String version,
                                        String referenceDate, String url, String sha256,
                                        String license, String attribution) {
        static PopulationProvenance none() {
            return new PopulationProvenance("not-used", "", "", "", "", "", "", "");
        }
    }

    /**
     * Geteilter Zustand eines Pipeline-Laufs – explizit statt über Lambda-Captures.
     * Die Stufen lesen/schreiben hier ihre Übergaben (Zonen → Population → Aktivitäten …);
     * was eine Stufe befüllt, dokumentiert ihre Methode.
     */
    private static final class PipelineContext {
        final Scenario scenario;
        final GenerationJob job;
        final Path artifacts;
        final Random random;
        /** OSM-Straßennetz wird von Routing (Matrizen) UND SUMO gebraucht – einmal pro Lauf laden. */
        final AtomicReference<String> roadNetwork = new AtomicReference<>();

        List<Zone> zones;
        List<OverpassClient.Poi> pois = List.of();
        List<OverpassClient.Place> places = List.of();
        String populationSource;
        String populationSourceLabel;
        String distributionSource;
        PopulationProvenance populationProvenance = PopulationProvenance.none();
        int totalPersons;
        PopulationGenerator populationGenerator;
        PopulationGenerator.Result population;
        LogisticsGenerator logisticsGenerator;
        LogisticsGenerator.Result logistics;
        /** Pkw-Reisezeitmatrix (Minuten) aus der Matrizen-Stufe – Basis fürs Netz-Feedback. */
        float[][] carTravelTimeMin;
        /** Tatsächlich verwendete Matrizenquelle für Manifest und Diagnose. */
        String matrixSource = "beeline-estimate";
        /** Konkrete Netzprovenienz; bleibt "not-used", wenn kein Netz angefordert wurde. */
        String roadNetworkSource = "not-used";
        /** POI-/Siedlungsquelle und ihr Inhalts-Hash für reproduzierbare A/B-Läufe. */
        String osmDataSource = "not-used";
        String osmDataSha256 = "";
        String roadNetworkSha256 = "";
        /** Arbeitszielquelle: beobachtete OD-Relationen oder synthetisches Gravitationsmodell. */
        String commuterSource = "gravity-model";
        /** Regionaler Außenarbeitsplatz-Randwert; ohne Upload bleibt die Gravitation maßgeblich. */
        String workplaceMarginSource = "not-provided";
        boolean externalCommuterDistributionAvailable;

        PipelineContext(Scenario scenario, GenerationJob job, Path artifacts) {
            this.scenario = scenario;
            this.job = job;
            this.artifacts = artifacts;
            this.random = new Random(scenario.population.seed);
        }
    }

    /** Orchestriert die 13 Pipeline-Stufen; die Fachlogik liegt in den stage*-Methoden. */
    private void run(Scenario scenario, GenerationJob job) {
        job.state = GenerationJob.State.RUNNING;
        job.startedAt = Instant.now();
        PipelineContext ctx = new PipelineContext(scenario, job, scenarios.artifactsDir(scenario.id));
        try {
            ctx.zones = stage(job, "Zonierung", () -> stageZoning(ctx));
            stage(job, "OSM-Daten (POIs)", () -> {
                stageOsmData(ctx);
                return null;
            });
            preparePopulationDistribution(ctx);
            stage(job, "Attraktivitäten", () -> {
                stageAttractivities(ctx);
                return null;
            });
            ctx.population = stage(job, "Bevölkerungssynthese", () -> stagePopulationSynthesis(ctx));
            stage(job, "Aktivitätenpläne", () -> {
                stageActivityPlans(ctx);
                return null;
            });
            stage(job, "Matrizen", () -> {
                stageMatrices(ctx);
                return null;
            });
            ctx.logistics = stage(job, "Logistik", () -> stageLogistics(ctx));
            stage(job, "Legacy-Export", () -> {
                stageLegacyExport(ctx);
                return null;
            });
            stage(job, "MobileCity-Export", () -> {
                stageMobileCityExport(ctx);
                return null;
            });
            stage(job, "Manifest", () -> {
                stageManifest(ctx);
                return null;
            });
            stage(job, "Simulation (logiTopp)", () -> {
                stageSimulation(ctx);
                return null;
            });
            stage(job, "SUMO (Verkehr)", () -> {
                stageSumo(ctx);
                return null;
            });
            stage(job, "Netz-Feedback", () -> {
                stageNetworkFeedback(ctx);
                return null;
            });
            stage(job, "LLM-Moduswahl", () -> {
                stageLlmModeChoice(ctx);
                return null;
            });

            writeRunSummary(ctx);
            job.state = GenerationJob.State.DONE;
            job.log("Generierung abgeschlossen");
        } catch (Exception e) {
            job.state = GenerationJob.State.FAILED;
            job.error = e.getMessage();
            job.log("FEHLER: " + e.getMessage());
        } finally {
            job.finishedAt = Instant.now();
            job.currentStage = null;
        }
    }

    // --- Stufe 1: Zonierung -----------------------------------------------------

    /** Zellgröße wird bei Bedarf automatisch aufs Zonenlimit angehoben. Befüllt ctx.zones. */
    private List<Zone> stageZoning(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        int maxZones = effectiveMaxZones(scenario.zoning.maxZones);
        boolean inspire = "inspire".equalsIgnoreCase(scenario.zoning.mode);
        boolean aligned = "epsg3035".equalsIgnoreCase(scenario.zoning.mode);
        if (inspire || aligned) {
            Scenario.Region r = scenario.region;
            if (Epsg3035.covers(r.minLat, r.minLon, r.maxLat, r.maxLon)) {
                int res = inspire
                        ? InspireGridZoning.pickResolution(
                                scenario.region, scenario.zoning.cellSizeMeters, maxZones)
                        : InspireGridZoning.pickAlignedResolution(
                                scenario.region, scenario.zoning.cellSizeMeters, maxZones);
                List<Zone> result = inspire
                        ? InspireGridZoning.generate(scenario.region, res, maxZones)
                        : InspireGridZoning.generateAligned(scenario.region, res, maxZones);
                job.summary.put("cellSizeMeters", res);
                job.summary.put("zoningMode", inspire ? "inspire" : "epsg3035");
                String label = inspire ? "INSPIRE/EPSG:3035" : "EPSG:3035-ausgerichtet";
                job.log(result.size() + " Zonen erzeugt (" + label + ", " + res + " m Raster)");
                requireObservableDistanceCalibration(
                        scenario, result, logitoppSimulationAvailable());
                return result;
            }
            job.log("Region außerhalb des EPSG:3035-Gültigkeitsbereichs (Europa) – "
                    + "Fallback auf Bbox-Raster");
        }
        double effective = GridZoning.autoCellSize(
                scenario.region, scenario.zoning.cellSizeMeters, maxZones);
        if (effective != scenario.zoning.cellSizeMeters) {
            job.log("Zellgröße automatisch von " + Math.round(scenario.zoning.cellSizeMeters)
                    + " m auf " + Math.round(effective) + " m erhöht ("
                    + maxZones + "-Zonen-Limit)");
        }
        List<Zone> result = GridZoning.generate(scenario.region, effective, maxZones);
        job.summary.put("cellSizeMeters", Math.round(effective));
        job.summary.put("zoningMode", "grid");
        job.log(result.size() + " Zonen erzeugt (" + Math.round(effective) + " m Raster)");
        requireObservableDistanceCalibration(scenario, result, logitoppSimulationAvailable());
        return result;
    }

    private boolean logitoppSimulationAvailable() {
        return properties.getSimulation().isEnabled()
                && new SimulationRunner(properties.getSimulation()).isAvailable();
    }

    // --- Stufe 2: OSM-Daten -------------------------------------------------------

    /** POIs + Siedlungsdaten (automatische Quelle mit Fallback). Befüllt ctx.pois/ctx.places. */
    private void stageOsmData(PipelineContext ctx) {
        GenerationJob job = ctx.job;
        Path snapshot = scenarios.uploadsDir(ctx.scenario.id).resolve(OsmDataSnapshot.NAME);
        if (Files.isRegularFile(snapshot)) {
            try {
                OsmDataSnapshot.Content content = OsmDataSnapshot.read(snapshot, mapper);
                ctx.pois = content.pois();
                ctx.places = content.places();
                ctx.osmDataSource = "scenario-upload:" + OsmDataSnapshot.NAME;
                ctx.osmDataSha256 = content.sha256();
                job.summary.put("osmDataSource", ctx.osmDataSource);
                job.summary.put("osmDataSha256", ctx.osmDataSha256);
                job.log(content.pois().size() + " POIs und " + content.places().size()
                        + " Siedlungen aus OSM-Snapshot geladen (SHA-256 "
                        + content.sha256().substring(0, 12) + "…)");
                return;
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("OSM-Datensnapshot ist nicht mehr gültig: "
                        + e.getMessage(), e);
            }
        }
        if (!properties.getOverpass().isLiveDataEnabled()) {
            job.stage("OSM-Daten (POIs)").status = "SKIPPED";
            job.log("Live-Overpass zentral deaktiviert, nutze synthetische Daten");
            return;
        }
        ctx.osmDataSource = "overpass-live";
        try {
            ctx.pois = overpass.fetchPois(ctx.scenario.region);
            job.log(ctx.pois.size() + " POIs von Overpass geladen");
        } catch (Exception e) {
            job.stage("OSM-Daten (POIs)").message = "Overpass nicht erreichbar: " + e.getMessage();
            job.log("Overpass-POIs fehlgeschlagen (" + e.getMessage() + ") – Fallback auf synthetische Daten");
        }
        try {
            ctx.places = overpass.fetchPlaces(ctx.scenario.region);
            job.log(ctx.places.size() + " Siedlungen (place-Nodes mit Einwohnerzahl) geladen");
        } catch (Exception e) {
            job.log("Overpass-Places fehlgeschlagen (" + e.getMessage() + ") – Bevölkerung über Dichte/POIs");
        }
        writeOsmSnapshot(ctx);
    }

    /**
     * Schreibt die live geholten OSM-Daten als Snapshot-Artefakt und hinterlegt dessen SHA-256 im
     * Manifest. Overpass liefert für dieselbe Region nicht immer dieselbe Antwort; ohne
     * Fingerabdruck ist hinterher nicht feststellbar, ob zwei Läufe auf derselben Eingabe
     * beruhten (Befund vom 2026-08-16: abweichende Attraktivitäten bei gleicher Knotenzahl).
     * Die Datei ist zugleich der Einfrier-Pfad: nach {@code uploads/} kopiert, liest sie ein
     * anderes Szenario statt neu abzurufen.
     */
    private void writeOsmSnapshot(PipelineContext ctx) {
        if ((ctx.pois == null || ctx.pois.isEmpty()) && (ctx.places == null || ctx.places.isEmpty())) {
            return;
        }
        try {
            Path snapshot = ctx.artifacts.resolve(OsmDataSnapshot.NAME);
            ctx.osmDataSha256 = OsmDataSnapshot.write(snapshot,
                    ctx.pois == null ? List.of() : ctx.pois,
                    ctx.places == null ? List.of() : ctx.places,
                    mapper);
            ctx.job.summary.put("osmDataSource", ctx.osmDataSource);
            ctx.job.summary.put("osmDataSha256", ctx.osmDataSha256);
            ctx.job.log("OSM-Snapshot geschrieben (" + OsmDataSnapshot.NAME + ", SHA-256 "
                    + ctx.osmDataSha256.substring(0, 12) + "…)");
        } catch (IOException e) {
            ctx.job.log("OSM-Snapshot konnte nicht geschrieben werden (" + e.getMessage() + ")");
        }
    }

    // --- Zwischenschritt: Einwohnerzahl + räumliche Verteilung --------------------

    /**
     * Bestimmt Gesamteinwohner (Nutzerwert > Upload-Raster > Zensus 2022 > OSM > Dichte) und
     * verteilt sie auf die Zonen. Befüllt ctx.populationGenerator/totalPersons/
     * populationSource/distributionSource. Kein eigener Pipeline-Schritt (Logs erscheinen
     * zwischen den Stufen), muss aber vor den Attraktivitäten laufen (Private_* hängen davon ab).
     */
    private void preparePopulationDistribution(PipelineContext ctx) throws IOException {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;

        // Alternative/neuere Uploads gewinnen vor dem backendseitig versionierten Zensus 2022.
        double[] gridWeights = null;
        long gridPopulation = 0;
        String gridSource = null;
        Path gridFile = scenarios.uploadsDir(scenario.id).resolve("population-grid.csv");
        if (Files.exists(gridFile)) {
            PopulationGridParser.ParseResult grid = PopulationGridParser.parse(gridFile, scenario.region);
            gridWeights = new double[ctx.zones.size()];
            for (PopulationGridParser.GridPoint point : grid.points()) {
                int index = GridZoning.zoneIndexOf(ctx.zones, point.lat(), point.lon());
                if (index >= 0) {
                    gridWeights[index] += point.population();
                    gridPopulation += Math.round(point.population());
                }
            }
            if (gridPopulation > 0) {
                gridSource = "upload-grid";
                ctx.populationProvenance = new PopulationProvenance("scenario-upload:population-grid.csv",
                        "Hochgeladenes Bevölkerungsraster", "", "", "",
                        OsmDataSnapshot.sha256(gridFile), "", "");
                job.log("Bevölkerungsraster-Upload (" + grid.format() + "): " + gridPopulation
                        + " Einwohner in der Region");
            } else {
                gridWeights = null;
                job.log("Bevölkerungsraster-Upload enthält keine Punkte in der Region – wird ignoriert");
            }
        }

        if (gridWeights == null) {
            Optional<CensusPopulationSource.Resolved> resolved = census.resolve(
                    scenario.region, censusCacheDir(), job::log);
            if (resolved.isPresent()) {
                PopulationGridParser.ParseResult grid = resolved.get().grid();
                gridWeights = new double[ctx.zones.size()];
                for (PopulationGridParser.GridPoint point : grid.points()) {
                    int index = GridZoning.zoneIndexOf(ctx.zones, point.lat(), point.lon());
                    if (index >= 0) {
                        gridWeights[index] += point.population();
                        gridPopulation += Math.round(point.population());
                    }
                }
                if (gridPopulation > 0) {
                    CensusPopulationSource.Provenance p = resolved.get().provenance();
                    gridSource = "census-2022";
                    ctx.populationProvenance = new PopulationProvenance(p.source(),
                            "Destatis Zensus 2022", p.version(), p.referenceDate(), p.url(),
                            p.archiveSha256(), p.license(), p.attribution());
                    job.log("Amtliches Zensus-2022-Raster: " + gridPopulation
                            + " Einwohner in der Region (Stichtag " + p.referenceDate() + ")");
                } else {
                    gridWeights = null;
                }
            }
        }

        if (scenario.population.totalPersons != null) {
            ctx.totalPersons = scenario.population.totalPersons;
            ctx.populationSource = "user-defined";
            ctx.populationSourceLabel = "Manuelle Einwohnerzahl";
        } else if (gridWeights != null) {
            ctx.totalPersons = (int) gridPopulation;
            ctx.populationSource = gridSource;
            ctx.populationSourceLabel = "upload-grid".equals(gridSource)
                    ? "Hochgeladenes Bevölkerungsraster" : "Destatis Zensus 2022";
        } else if (!ctx.places.isEmpty()) {
            ctx.totalPersons = (int) PopulationEstimator.estimateForRegion(scenario.region, ctx.places);
            ctx.populationSource = "osm-places-estimate";
            ctx.populationSourceLabel = "OSM-Siedlungsdaten (Schätzung)";
            ctx.populationProvenance = new PopulationProvenance(ctx.osmDataSource,
                    ctx.populationSourceLabel, "", "", properties.getOverpass().getEndpoint(),
                    ctx.osmDataSha256, "ODbL", "OpenStreetMap contributors");
            job.log("Einwohner aus OSM-Siedlungsdaten geschätzt: " + ctx.totalPersons);
        } else {
            ctx.totalPersons = (int) Math.round(ctx.zones.stream().mapToDouble(z -> z.areaKm2).sum()
                    * scenario.population.personsPerKm2);
            ctx.populationSource = "density-estimate";
            ctx.populationSourceLabel = "Dichteannahme";
            job.log("Einwohner über Dichteannahme geschätzt: " + ctx.totalPersons);
        }
        ctx.populationGenerator = new PopulationGenerator(
                ctx.zones, ctx.random, scenario.population.seed);
        int[] poiCounts = countPoisPerZone(ctx.zones, ctx.pois);
        double[] weights = gridWeights != null
                ? gridWeights
                : ctx.places.isEmpty() ? null : PopulationEstimator.zoneWeights(ctx.zones, ctx.places);
        ctx.distributionSource = gridWeights != null ? gridSource
                : !ctx.places.isEmpty() ? "osm-places"
                : !ctx.pois.isEmpty() ? "poi-density" : "uniform-noise";
        ctx.populationGenerator.distributePopulation(
                ctx.totalPersons, ctx.pois.isEmpty() ? null : poiCounts, weights);
    }

    // --- Stufe 3: Attraktivitäten -------------------------------------------------

    /** Zell-Attraktivitäten (OSM oder synthetisch) + externe Zielzonen; schreibt zone-repository/. */
    private void stageAttractivities(PipelineContext ctx) throws IOException {
        GenerationJob job = ctx.job;
        if (ctx.pois.isEmpty()) {
            AttractivityCalculator.synthetic(ctx.zones, ctx.random);
        } else {
            AttractivityCalculator.fromPois(ctx.zones, ctx.pois);
        }
        // Außenbeziehungen: externe Zielzonen anhängen (ohne Einwohner, nur Attraktivität)
        if (ctx.scenario.population.externalTrips) {
            List<Zone> external = ExternalZones.create(ctx.zones);
            ctx.zones.addAll(external);
            job.log(String.format(Locale.ROOT,
                    "%d externe Zielzonen (~%.0f km Radius) für Außenbeziehungen ergänzt",
                    external.size(), ExternalZones.radiusKm()));
        }
        ctx.populationGenerator.freezeWorkGravity();
        prepareCommuterMapping(ctx);
        prepareWorkplaceMargin(ctx);
        Path zoneDir = ctx.artifacts.resolve("zone-repository");
        CsvArtifacts.writeZonesCsv(zoneDir.resolve("zones.csv"), ctx.zones);
        CsvArtifacts.writeAttractivitiesCsv(zoneDir.resolve("attractivities.csv"), ctx.zones);
        Files.writeString(zoneDir.resolve("zones.geojson"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        GeoJson.zonesToFeatureCollection(mapper, ctx.zones)));
        job.log("zones.csv + attractivities.csv geschrieben ("
                + (ctx.pois.isEmpty() ? "synthetisch" : "OSM-basiert") + ")");
    }

    /**
     * Maps an optional coordinate-based commuter upload after external zones exist
     * and before zones.csv/matrices are written. This lets observed external work
     * coordinates move the synthetic ring anchors to their real weighted centroids.
     */
    private void prepareCommuterMapping(PipelineContext ctx) throws IOException {
        Path file = scenarios.uploadsDir(ctx.scenario.id).resolve(CommuterMatrix.NAME);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            CommuterMatrix.ParseResult parsed = CommuterMatrix.parse(file);
            CommuterMatrix.Mapping mapping = CommuterMatrix.map(parsed, ctx.zones);
            if (mapping.mappedRows() == 0) {
                ctx.job.log("Pendler-Upload passt auf keine Wohnzone – Gravitationsmodell bleibt aktiv");
                return;
            }
            ctx.populationGenerator.useCommuterMapping(mapping);
            CommuterMatrix.writeMapped(ctx.artifacts.resolve("population/commuters.csv"),
                    mapping, ctx.zones);
            ctx.commuterSource = CommuterMatrix.source(file);
            ctx.job.summary.put("commuterSource", ctx.commuterSource);
            ctx.job.summary.put("commuterHomeZones", mapping.homeZones());
            ctx.job.summary.put("commuterRelations", mapping.mappedRows());
            ctx.job.summary.put("commuterExternalConditionalHomes",
                    mapping.externalCumulativeByHome().size());
            ctx.externalCommuterDistributionAvailable =
                    !mapping.externalCumulativeByHome().isEmpty();
            ctx.job.summary.put("externalCommuters", Math.round(mapping.externalCommuters()));
            ctx.job.log(String.format(Locale.ROOT,
                    "Pendler-Upload: %d/%d Relationen auf %d Wohnzonen verankert (%.0f externe Pendler)",
                    mapping.mappedRows(), mapping.inputRows(), mapping.homeZones(),
                    mapping.externalCommuters()));
        } catch (IllegalArgumentException e) {
            ctx.job.log("Pendler-Upload ungültig (" + e.getMessage()
                    + ") – Gravitationsmodell bleibt aktiv");
        }
    }

    /** Couples the 13111 outbound marginal to a separately supplied external OD distribution. */
    private void prepareWorkplaceMargin(PipelineContext ctx) throws IOException {
        Path file = scenarios.uploadsDir(ctx.scenario.id).resolve(WorkplaceMargins.NAME);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            WorkplaceMargins.Margin margin = WorkplaceMargins.readNormalized(file);
            if (!ctx.externalCommuterDistributionAvailable) {
                ctx.workplaceMarginSource = margin.source() + "-not-applied";
                ctx.job.log("Arbeitsplatz-Randwert vorhanden, aber ohne externe Pendler-OD nicht angewendet");
                return;
            }
            ctx.populationGenerator.useExternalWorkShare(margin.externalShare());
            ctx.workplaceMarginSource = margin.source();
            ctx.job.summary.put("workplaceMarginSource", ctx.workplaceMarginSource);
            ctx.job.summary.put("workplaceMarginDate", margin.date().toString());
            ctx.job.summary.put("residentWorkers", margin.residentWorkers());
            ctx.job.summary.put("outboundWorkers", margin.outboundWorkers());
            ctx.job.summary.put("externalWorkShareTarget", margin.externalShare());
            ctx.job.log(String.format(Locale.ROOT,
                    "Arbeitsplatz-Randwert %s: %.2f %% externe WORK-Ziele (%d/%d)",
                    margin.date(), margin.externalShare() * 100,
                    margin.outboundWorkers(), margin.residentWorkers()));
        } catch (IllegalArgumentException e) {
            ctx.workplaceMarginSource = "invalid-upload";
            ctx.job.log("Arbeitsplatz-Randwert ungültig (" + e.getMessage()
                    + ") – bisherige Zielwahl bleibt aktiv");
        }
    }

    // --- Stufe 4: Bevölkerungssynthese ---------------------------------------------

    /** Personen/Haushalte/Pkw/feste Ziele. Befüllt ctx.population. */
    private PopulationGenerator.Result stagePopulationSynthesis(PipelineContext ctx) throws IOException {
        PopulationGenerator.Result result =
                ctx.populationGenerator.generate(ctx.artifacts.resolve("population"), ctx.scenario);
        ctx.job.log(result.persons().size() + " Personen in " + result.householdCount()
                + " Haushalten, " + result.carCount() + " Pkw");
        ctx.job.summary.put("externalWorkDestinations", result.externalWorkDestinations());
        ctx.job.summary.put("externalWorkShareActual", result.persons().isEmpty() ? 0
                : (double) result.externalWorkDestinations() / result.persons().size());
        return result;
    }

    // --- Stufe 5: Aktivitätenpläne (Achse A) ---------------------------------------

    /** Verhaltensquelle actitopp | gta | template mit Fallback-Kette; schreibt activity.csv. */
    private void stageActivityPlans(PipelineContext ctx) throws IOException {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path populationDir = ctx.artifacts.resolve("population");
        Path activityCsv = populationDir.resolve("activity.csv");
        ActivityGenerator generator = new ActivityGenerator(ctx.random);
        long count = -1;
        String source = "Template";
        String behavior = scenario.population.behaviorSourceEffective();
        if ("gta".equals(behavior)) {
            GtaBehaviorRunner gta = new GtaBehaviorRunner(
                    properties.getLlm(), scenario.population.seed,
                    Path.of(properties.getDataDir(), "llm-cache"),
                    scenario.population.gtaWhatIf);
            if (properties.getLlm().isEnabled() && gta.isAvailable()) {
                try {
                    job.log("Starte GTA-Verhaltensquelle (lokales LLM "
                            + properties.getLlm().getModel() + ")…");
                    GtaBehaviorRunner.Result gtaResult = gta.run(
                            populationDir.resolve("person.csv"), job::log);
                    count = generator.generateFromGta(activityCsv, ctx.population.persons(),
                            p -> gtaResult.plans().get(p.id()));
                    source = "GTA (" + properties.getLlm().getModel() + ", "
                            + gtaResult.personas() + " Personas, " + gtaResult.personsCovered()
                            + "/" + gtaResult.personsTotal() + " abgedeckt)";
                    job.summary.put("gtaPersonas", gtaResult.personas());
                    job.summary.put("gtaCovered", gtaResult.personsCovered());
                    writeGtaPersonas(populationDir.resolve("gta-personas.json"),
                            scenario.population.gtaWhatIf, gtaResult.personaSummaries());
                } catch (Exception e) {
                    job.log("GTA fehlgeschlagen (" + e.getMessage() + ") – Fallback actiTopp");
                    behavior = "actitopp";
                }
            } else {
                job.log("GTA-LLM nicht erreichbar (" + properties.getLlm().getEndpoint()
                        + ") – Fallback actiTopp");
                behavior = "actitopp";
            }
        }
        if (count < 0 && "actitopp".equals(behavior)) {
            ActitoppRunner runner = new ActitoppRunner(properties.getActitopp());
            if (properties.getActitopp().isEnabled() && runner.isAvailable()) {
                try {
                    job.log("Starte KIT actiTopp (" + runner.projectDir().getFileName() + ")…");
                    ActitoppRunner.Result result = runner.run(
                            populationDir.resolve("person.csv"), populationDir.resolve("actitopp"));
                    count = generator.generateFromActitopp(
                            result.scheduleCsv(), ctx.population.persons(), activityCsv);
                    source = "actiTopp";
                } catch (Exception e) {
                    job.log("actiTopp fehlgeschlagen (" + e.getMessage() + ") – Fallback Template-Generator");
                    count = generator.generate(activityCsv, ctx.population.persons());
                }
            } else {
                job.log("actiTopp nicht verfügbar (" + runner.projectDir()
                        + ") – Template-Generator");
                count = generator.generate(activityCsv, ctx.population.persons());
            }
        }
        if (count < 0) {
            count = generator.generate(activityCsv, ctx.population.persons());
        }
        job.log(count + " Aktivitäten (Wochenpläne, " + source + ") geschrieben");
        job.summary.put("activitySource", source);
    }

    // --- Stufe 6: Matrizen ----------------------------------------------------------

    /** Reisezeit-/Distanzmatrizen (GraphHopper-Routing, Fallback Luftlinie). */
    private void stageMatrices(PipelineContext ctx) throws IOException {
        MatrixGenerator.Impedance routing = buildRouting(ctx);
        try {
            MatrixGenerator generator = new MatrixGenerator();
            List<String> files = generator.generate(ctx.artifacts.resolve("matrix"), ctx.zones, routing);
            ctx.carTravelTimeMin = generator.carTravelTimeMin();
            ctx.matrixSource = routing != null
                    ? "graphhopper-with-local-fallbacks" : "beeline-estimate";
            ctx.job.summary.put("matrixSource", ctx.matrixSource);
            ctx.job.log(files.size() + " Visum-Matrizen (" + ctx.zones.size() + "x" + ctx.zones.size()
                    + ") + Matrix-Konfigurationen geschrieben ("
                    + (routing != null ? "GraphHopper-Routing" : "Luftlinie") + ")");
        } finally {
            if (routing instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Graph-Aufräumen ist Best-Effort
                }
            }
        }
    }

    /**
     * Verhindert eine strukturell wirkungslose Distanz-Kalibrierung: {@code b_intrazonal}
     * verändert nur die Zahl intrazonaler Ziele. Liegt deren einziger Matrixwert bereits
     * oberhalb des {@code <1 km}-Zielbands, kann keine Iteration dieses Band beobachten.
     */
    static void requireObservableDistanceCalibration(
            Scenario scenario, List<Zone> zones, boolean logitoppSimulationAvailable) {
        if (!scenario.population.calibrateDistance
                || !scenario.targets().contains("logitopp")
                || !logitoppSimulationAvailable) {
            return;
        }
        double minimumIntrazonalCarKm = zones.stream()
                .filter(zone -> !zone.external)
                .mapToDouble(MatrixGenerator::intrazonalCarDistanceKm)
                .min()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Distanz-Kalibrierung benötigt mindestens eine interne Zone"));
        if (minimumIntrazonalCarKm >= 1.0) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Distanz-Kalibrierung nicht beobachtbar: kleinste intrazonale "
                            + "Pkw-Distanz %.2f km; b_intrazonal kann das Zielband <1 km "
                            + "bei dieser Zonierung nicht beeinflussen. Zellgröße verkleinern "
                            + "oder calibrateDistance deaktivieren.",
                    minimumIntrazonalCarKm));
        }
    }

    // --- Stufe 7: Logistik ------------------------------------------------------------

    /** Verteilzentren/Packstationen/Paketparameter. Befüllt ctx.logisticsGenerator/logistics. */
    private LogisticsGenerator.Result stageLogistics(PipelineContext ctx) throws IOException {
        ctx.logisticsGenerator = new LogisticsGenerator(ctx.zones, ctx.random);
        LogisticsGenerator.Result result = ctx.logisticsGenerator.generate(
                ctx.artifacts.resolve("logistics"), ctx.scenario,
                ctx.population.persons().size(), ctx.pois);
        ctx.job.log(result.depotCount() + " Verteilzentren"
                + (ctx.logisticsGenerator.sources().depotsFromOsm() ? " (aus OSM)" : "") + ", "
                + result.packstationCount() + " Packstationen"
                + (ctx.logisticsGenerator.sources().packstationsFromOsm() ? " (aus OSM)" : "")
                + ", Paketnachfrage-Parameter geschrieben");
        return result;
    }

    // --- Stufe 8: Legacy-Export ---------------------------------------------------------

    /** mobiTopp-0.3.x-/logiTopp-Layout unter legacy/. */
    private void stageLegacyExport(PipelineContext ctx) throws IOException {
        int files = new LegacyExportGenerator(ctx.zones, ctx.random).generate(ctx.artifacts);
        ctx.job.log(files + " Dateien im Legacy-Layout (mobiTopp 0.3.x/logiTopp) unter legacy/ geschrieben");
    }

    // --- Stufe 9: MobileCity-Export (Achse B) --------------------------------------------

    /** District-Config-Tabellen für Takomat – nur wenn Zielmodell gewählt. */
    private void stageMobileCityExport(PipelineContext ctx) throws IOException {
        GenerationJob job = ctx.job;
        if (!ctx.scenario.targets().contains("mobilecity")) {
            job.stage("MobileCity-Export").status = "SKIPPED";
            job.log("MobileCity-Export übersprungen (Zielmodell nicht ausgewählt)");
            return;
        }
        int files = new MobileCityExporter(ctx.zones).export(ctx.artifacts);
        job.log(files + " MobileCity-Config-Tabellen (REGdistricts/REGattractivity/"
                + "POPinhabitants/POPtrips/TECCarFleet) unter mobilecity/ geschrieben");
    }

    // --- Stufe 10: Manifest ----------------------------------------------------------------

    private void stageManifest(PipelineContext ctx) throws IOException {
        writeManifest(ctx.artifacts, ctx.scenario, ctx.job, ctx.zones.size(), ctx.population,
                ctx.logistics, !ctx.pois.isEmpty(), ctx.populationSource,
                ctx.populationSourceLabel, ctx.totalPersons,
                ctx.logisticsGenerator.sources(), ctx.distributionSource, ctx.matrixSource,
                ctx.roadNetworkSource, ctx.commuterSource, ctx.workplaceMarginSource,
                ctx.osmDataSource, ctx.osmDataSha256, ctx.roadNetworkSha256,
                ctx.populationProvenance);
    }

    // --- Stufe 11: Simulation logiTopp (Achse B) ----------------------------------------------

    /** Ergebnis der Zielwahl-Kalibrierung; der Parameterpfad kann in weitere Läufe eingehen. */
    private record DistanceCalibrationOutcome(
            Path parametersDir,
            SimulationResultIngestor.Summary summary) {
    }

    /** logiTopp-Wochensimulation, optional mit sequenziell kombinierbaren Kalibrierungen. */
    private void stageSimulation(PipelineContext ctx) throws Exception {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path artifacts = ctx.artifacts;
        if (!scenario.targets().contains("logitopp")) {
            job.stage("Simulation (logiTopp)").status = "SKIPPED";
            job.log("logiTopp-Simulation übersprungen (Zielmodell nicht ausgewählt)");
            return;
        }
        SimulationRunner runner = new SimulationRunner(properties.getSimulation());
        if (!properties.getSimulation().isEnabled() || !runner.isAvailable()) {
            job.stage("Simulation (logiTopp)").status = "SKIPPED";
            job.log("Simulation übersprungen ("
                    + (properties.getSimulation().isEnabled()
                    ? "Legacy-Projekt nicht gefunden: " + runner.projectDir()
                    : "deaktiviert") + ")");
            return;
        }
        job.log("Starte logiTopp-Wochensimulation (" + runner.projectDir().getFileName() + ")…");
        Path baseDestinationChoice = runner.projectDir().resolve("config/shared/parameters");
        Path destinationChoiceOverride = null;
        if (scenario.population.correctDistanceDecay) {
            destinationChoiceOverride = DestinationChoiceParameterAdjuster
                    .applyDistanceDecayCorrection(
                            baseDestinationChoice,
                            artifacts.resolve("simulation").resolve("distance-decay-adjusted"));
            baseDestinationChoice = destinationChoiceOverride;
            job.log("Distanz-Abklingen korrigiert: +0,06 auf b_tt_car_d, b_tt_car_p, "
                    + "b_tt_put, b_tt_ped und b_tt_bike");
            job.summary.put("distanceDecayCorrection", true);
            job.summary.put("distanceDecayOffset",
                    DestinationChoiceParameterAdjuster.DISTANCE_DECAY_OFFSET);
        }
        SimulationResultIngestor.Summary summary;
        if (scenario.population.calibrateModeDistance) {
            // Phase 1c: ÖV-Niveau (asc_put) und Fernweg-Schräge (b_tt_put) getrennt kalibrieren.
            if (scenario.population.calibrateDistance) {
                job.log("Kombinierte Kalibrierung: zuerst Distanzmarginale, danach ÖV-Niveau "
                        + "und ÖV-Distanzschräge");
                DistanceCalibrationOutcome distance = runDistanceCalibration(
                        scenario, job, artifacts, runner, baseDestinationChoice);
                destinationChoiceOverride = distance.parametersDir();
            }
            if (scenario.population.calibrateModeChoice) {
                job.log("Hinweis: Modus-Distanz-Kalibrierung ersetzt die reine "
                        + "Modal-Split-Kalibrierung");
            }
            Path baseMnl = runner.projectDir().resolve(
                    "config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt");
            Path calibFile = artifacts.resolve("simulation")
                    .resolve("mode_distance_calibrated.txt");
            SimulationResultIngestor.Summary[] last = {null};
            double targetOverall = scenario.population.calibrationTarget != null
                    ? scenario.population.calibrationTarget.getOrDefault(
                            "publicTransport", ModeChoiceCalibrator.MID_2017.get("publicTransport"))
                    : ModeChoiceCalibrator.MID_2017.get("publicTransport");
            double targetLong = Mid2017.MODAL_SPLIT_BY_DISTANCE_BAND.getLast()
                    .modalSplit().get("publicTransport");
            ModeDistanceCalibrator calibrator = new ModeDistanceCalibrator(
                    baseMnl,
                    calibFile,
                    targetOverall,
                    targetLong,
                    properties.getSimulation().getCalibrationIterations(),
                    properties.getSimulation().getCalibrationTolerance());
            job.log(String.format(
                    "Modus-Distanz-Kalibrierung: ÖV gesamt %.1f%%, ÖV >=20 km %.2f%% "
                            + "(max. %d Iterationen)…",
                    targetOverall * 100,
                    targetLong * 100,
                    properties.getSimulation().getCalibrationIterations()));
            Path calibratedDestinationChoice = destinationChoiceOverride;
            ModeDistanceCalibrator.Result cal = calibrator.calibrate(mnl -> {
                SimulationRunner.Result run = runner.run(
                        scenario, artifacts, job::log, mnl, calibratedDestinationChoice);
                last[0] = new SimulationResultIngestor(mapper)
                        .ingest(run.resultsDir(), artifacts, run.durationSeconds(),
                                scenario.logistics.returns);
                return SimulationResultIngestor.readModeDistanceMetrics(
                        run.resultsDir().resolve("demandsimulationResult.csv"));
            });
            cal.log().forEach(job::log);
            job.log(String.format(
                    "Modus-Distanz-Kalibrierung beendet: %d Iterationen, "
                            + "Zwei-Ziel-RMSE %.4f → %.4f, Joint-RMSE %.4f → %.4f%s",
                    cal.iterations(),
                    cal.startError(),
                    cal.finalError(),
                    cal.startMetrics().jointRmseToMid2017(),
                    cal.finalMetrics().jointRmseToMid2017(),
                    cal.converged() ? "" : " (Toleranz nicht erreicht)"));
            job.summary.put("modeDistanceCalibrationIterations", cal.iterations());
            job.summary.put("modeDistanceCalibrationConverged", cal.converged());
            job.summary.put("modeDistanceCalibrationRmse",
                    Math.round(cal.finalError() * 10_000) / 10_000.0);
            job.summary.put("modeDistanceCalibrationJointRmse",
                    Math.round(cal.finalMetrics().jointRmseToMid2017() * 10_000) / 10_000.0);
            job.summary.put("modeDistanceCalibrationAscPut",
                    Math.round(cal.finalAscPut() * 10_000) / 10_000.0);
            job.summary.put("modeDistanceCalibrationBttPut",
                    Math.round(cal.finalBttPut() * 10_000) / 10_000.0);
            summary = last[0];
        } else if (scenario.population.calibrateDistance) {
            summary = runDistanceCalibration(
                    scenario, job, artifacts, runner, baseDestinationChoice).summary();
        } else if (scenario.population.calibrateModeChoice) {
            // Phase 1: iterative ASC-Kalibrierung gegen MiD-Modal-Split (mehrere Sim-Läufe)
            Path baseMnl = runner.projectDir().resolve(
                    "config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt");
            Path calibFile = artifacts.resolve("simulation").resolve("mode_choice_calibrated.txt");
            SimulationResultIngestor.Summary[] last = {null};
            Map<String, Double> target = scenario.population.calibrationTarget != null
                    && !scenario.population.calibrationTarget.isEmpty()
                    ? scenario.population.calibrationTarget : ModeChoiceCalibrator.MID_2017;
            ModeChoiceCalibrator calibrator = new ModeChoiceCalibrator(baseMnl, calibFile, target,
                    properties.getSimulation().getCalibrationIterations(),
                    properties.getSimulation().getCalibrationTolerance());
            job.log("Modal-Split-Kalibrierung gegen Ziel " + target + " (max. "
                    + properties.getSimulation().getCalibrationIterations() + " Iterationen)…");
            Path correctedDestinationChoice = destinationChoiceOverride;
            ModeChoiceCalibrator.Result cal = calibrator.calibrate(mnl -> {
                SimulationRunner.Result run = runner.run(
                        scenario, artifacts, job::log, mnl, correctedDestinationChoice);
                last[0] = new SimulationResultIngestor(mapper)
                        .ingest(run.resultsDir(), artifacts, run.durationSeconds(),
                                scenario.logistics.returns);
                return readModalSplit(artifacts.resolve("simulation").resolve("kpis.json"));
            });
            cal.log().forEach(job::log);
            job.log(String.format("Kalibrierung beendet: %d Iterationen, RMSE %.3f → %.3f",
                    cal.iterations(), cal.startRmse(), cal.finalRmse()));
            job.summary.put("calibrationIterations", cal.iterations());
            job.summary.put("calibrationRmse", Math.round(cal.finalRmse() * 1000) / 1000.0);
            summary = last[0];
        } else {
            SimulationRunner.Result run = runner.run(
                    scenario, artifacts, job::log, null, destinationChoiceOverride);
            job.log("Simulation beendet nach " + run.durationSeconds() + " s, werte Ergebnisse aus…");
            summary = new SimulationResultIngestor(mapper)
                    .ingest(run.resultsDir(), artifacts, run.durationSeconds(),
                            scenario.logistics.returns);
        }
        job.log(summary.trips() + " Wege, " + summary.deliveries() + " Zustellaufträge ("
                + summary.deliveredDeliveries() + " zugestellt), "
                + summary.customerReturns() + " Kundenretouren ("
                + summary.collectedReturns() + " eingesammelt), "
                + summary.tours() + " Touren – KPIs unter simulation/, "
                + "normalisierte Nachfrage unter demand/");
        job.summary.put("simulatedTrips", summary.trips());
        job.summary.put("trafficDemandContract", TravelDemand.CONTRACT);
        job.summary.put("simulatedParcels", summary.parcels());
        job.summary.put("deliveredParcels", summary.delivered());
        job.summary.put("simulatedDeliveries", summary.deliveries());
        job.summary.put("deliveredDeliveries", summary.deliveredDeliveries());
        job.summary.put("simulatedCustomerReturns", summary.customerReturns());
        job.summary.put("collectedCustomerReturns", summary.collectedReturns());
        job.summary.put("simulatedTours", summary.tours());
    }

    /**
     * Kalibriert die Zielwahl auf den Nahdistanz-Anteil und liefert den exakt simulierten
     * Parameterstand zurück. Derselbe Stand kann anschließend mit einer MNL-Kalibrierung
     * kombiniert werden; so arbeiten beide Regler auf derselben Distanzmarginale.
     */
    private DistanceCalibrationOutcome runDistanceCalibration(
            Scenario scenario,
            GenerationJob job,
            Path artifacts,
            SimulationRunner runner,
            Path baseParams) throws Exception {
        Path dcDir = artifacts.resolve("simulation").resolve("dc-calibrated");
        SimulationResultIngestor.Summary[] last = {null};
        double targetNear = properties.getSimulation().getDistanceCalibrationTargetNearShare();
        DistanceCalibrator calibrator = new DistanceCalibrator(
                baseParams,
                dcDir,
                targetNear,
                properties.getSimulation().getCalibrationIterations(),
                properties.getSimulation().getDistanceCalibrationTolerance());
        job.log(String.format(
                "Distanz-Kalibrierung: Ziel <1km-Anteil %.0f%% (max. %d Iterationen)…",
                targetNear * 100,
                properties.getSimulation().getCalibrationIterations()));
        DistanceCalibrator.Result cal = calibrator.calibrate(dc -> {
            SimulationRunner.Result run = runner.run(scenario, artifacts, job::log, null, dc);
            last[0] = new SimulationResultIngestor(mapper)
                    .ingest(run.resultsDir(), artifacts, run.durationSeconds(),
                            scenario.logistics.returns);
            return readNearDistanceShare(run.resultsDir());
        });
        cal.log().forEach(job::log);
        job.log(String.format(
                "Distanz-Kalibrierung beendet: %d Iterationen, <1km %.1f%% → %.1f%% (Ziel %.0f%%)",
                cal.iterations(),
                cal.startNearShare() * 100,
                cal.finalNearShare() * 100,
                targetNear * 100));
        job.summary.put("distanceCalibrationIterations", cal.iterations());
        job.summary.put("distanceCalibrationNearShare",
                Math.round(cal.finalNearShare() * 1000) / 1000.0);
        return new DistanceCalibrationOutcome(cal.calibratedDir(), last[0]);
    }

    // --- Stufe 12: SUMO (Achse B) ------------------------------------------------------------

    /** Mikroskopische Verkehrssimulation der PKW-Trips + logiTopp-Zustelltouren. */
    private void stageSumo(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path artifacts = ctx.artifacts;
        if (!scenario.targets().contains("sumo")) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (Zielmodell nicht ausgewählt)");
            return;
        }
        SumoRunner runner = new SumoRunner(properties.getSumo());
        Path demandResult = artifacts.resolve("simulation").resolve("results")
                .resolve("demandsimulationResult.csv");
        if (!properties.getSumo().isEnabled() || !runner.isAvailable()) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)");
            return;
        }
        if (!Files.exists(demandResult)) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (benötigt logiTopp-Trips – bitte Zielmodell logiTopp mitwählen)");
            return;
        }
        try {
            String osmXml = roadNetworkOsm(ctx);
            job.log("Baue SUMO-Netz und simuliere Verkehr…");
            Path plannedTours = artifacts.resolve("simulation").resolve("results")
                    .resolve("planned-tours.csv");
            SumoRunner.PtProvider ptProvider = buildPtProvider(ctx);
            SumoRunner.Result sumo = runner.run(artifacts, osmXml, demandResult,
                    plannedTours, ptProvider, job::log);
            writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
            job.log(String.format("SUMO: %d Fahrzeuge (%d PKW, %d Rad, %d Lieferverkehr) + %d Fuß "
                            + "+ %d ÖV-Fahrgäste, Ø %.1f min / %.1f km / %.0f km/h, CO₂ %.1f kg (%d s)",
                    sumo.vehicles(), sumo.carTrips(), sumo.bikeTrips(), sumo.deliveryTrips(),
                    sumo.walkTrips(), sumo.ptRiders(), sumo.meanDurationSec() / 60,
                    sumo.meanRouteLengthM() / 1000, sumo.meanSpeedMs() * 3.6, sumo.co2Kg(),
                    sumo.durationSeconds()));
            job.summary.put("sumoVehicles", sumo.vehicles());
        } catch (Exception e) {
            // FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster Abwahl unterscheiden können
            job.stage("SUMO (Verkehr)").status = "FAILED";
            job.stage("SUMO (Verkehr)").message = String.valueOf(e);
            job.log("SUMO fehlgeschlagen: " + e);
        }
    }

    /**
     * Baut den ÖV-Provider für den SUMO-Lauf (Phase C): sobald das Netz steht, wird der GTFS-Feed
     * beschafft (Auto-Fetch nach Region, Upload-Fallback), zugeschnitten und via gtfs2pt.py in die
     * Fahrplan-Infrastruktur überführt. {@code null} = ohne ÖV (Flag aus oder venv/gtfs2pt fehlt).
     */
    private SumoRunner.PtProvider buildPtProvider(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        if (!scenario.population.publicTransport || !properties.getTransit().isEnabled()) {
            return null;
        }
        TransitRunner transit = new TransitRunner(properties.getTransit(),
                properties.getSumo().getSumoHome());
        if (!transit.isAvailable()) {
            job.log("ÖV übersprungen (Python-venv/gtfs2pt nicht konfiguriert – synthesis.transit.venv-python)");
            return null;
        }
        GtfsSource gtfs = new GtfsSource(properties.getTransit());
        Path transitDir = ctx.artifacts.resolve("sumo").resolve("transit");
        Path rawCache = Path.of(properties.getDataDir()).resolve("gtfs-cache");
        Path croppedFeed = transitDir.resolve("feed-cropped.zip");
        Path uploaded = scenario.population.gtfsUploadPath != null
                && !scenario.population.gtfsUploadPath.isBlank()
                ? Path.of(scenario.population.gtfsUploadPath) : null;
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return net -> {
            Files.createDirectories(transitDir);
            GtfsSource.Resolved resolved = gtfs.resolve(scenario.region, uploaded, rawCache,
                    croppedFeed, job::log);
            if (resolved == null) {
                return null;
            }
            return transit.build(net, resolved.croppedFeed(), date, transitDir, job::log);
        };
    }

    // --- Stufe 13: Netz-Feedback (Nachfrage ↔ Netz) --------------------------------------------

    /**
     * Gleichgewichts-Schleife Nachfrage ↔ Netz: je Iteration werden die Stau-Faktoren je
     * Zonenpaar (staugewichtete / freie Routenzeit auf dem SUMO-Netz, 1–3) per MSA gedämpft
     * in die Pkw-Matrix gemischt (Basis = GraphHopper-Zeiten), logiTopp entscheidet neu und
     * SUMO simuliert die neue Nachfrage – bis die mittlere Faktor-Änderung unter die Toleranz
     * fällt oder das Iterationslimit erreicht ist. Die Dämpfung verhindert das Oszillieren
     * ungedämpfter Rückkopplungen (Stau → alle weichen aus → kein Stau → …).
     */
    private void stageNetworkFeedback(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path artifacts = ctx.artifacts;
        if (!scenario.population.sumoFeedback) {
            job.stage("Netz-Feedback").status = "SKIPPED";
            job.log("Netz-Feedback übersprungen (nicht aktiviert)");
            return;
        }
        if (!"DONE".equals(job.stage("SUMO (Verkehr)").status)
                || !Files.exists(artifacts.resolve("sumo").resolve("edgedata.xml"))) {
            job.stage("Netz-Feedback").status = "SKIPPED";
            job.log("Netz-Feedback übersprungen (benötigt erfolgreichen SUMO-Lauf)");
            return;
        }
        if (!scenario.targets().contains("logitopp") || ctx.carTravelTimeMin == null) {
            job.stage("Netz-Feedback").status = "SKIPPED";
            job.log("Netz-Feedback übersprungen (benötigt logiTopp-Ziel und Pkw-Matrix aus diesem Lauf)");
            return;
        }
        int maxZones = properties.getSumo().getFeedbackMaxZones();
        if (ctx.zones.size() > maxZones) {
            job.stage("Netz-Feedback").status = "SKIPPED";
            job.log("Netz-Feedback übersprungen (" + ctx.zones.size() + " Zonen > Limit " + maxZones
                    + " – OD-Routing wäre n²)");
            return;
        }
        try {
            SumoRunner runner = new SumoRunner(properties.getSumo());
            SimulationRunner simRunner = new SimulationRunner(properties.getSimulation());
            Path simulationDir = artifacts.resolve("simulation");
            Path modeChoiceOverride = modeChoiceCalibrationOverride(scenario, simulationDir);
            Path destinationChoiceOverride =
                    destinationChoiceOverride(scenario, simulationDir);
            Path demandResult = artifacts.resolve("simulation").resolve("results")
                    .resolve("demandsimulationResult.csv");
            Path plannedTours = artifacts.resolve("simulation").resolve("results")
                    .resolve("planned-tours.csv");
            int maxIterations = Math.max(1, properties.getSumo().getFeedbackIterations());
            double tolerance = properties.getSumo().getFeedbackTolerance();
            int n = ctx.zones.size();
            double[][] smoothed = new double[n][n];
            for (double[] row : smoothed) {
                java.util.Arrays.fill(row, 1);
            }
            Map<String, Double> initialSplit = readModalSplit(
                    artifacts.resolve("simulation").resolve("kpis.json"));
            job.log("Gleichgewichts-Schleife: max. " + maxIterations
                    + " Iterationen, MSA-Dämpfung, Konvergenz bei Δ<" + tolerance);

            int iteration = 0;
            double delta = Double.MAX_VALUE;
            SumoRunner.OdFactors od = null;
            while (iteration < maxIterations) {
                iteration++;
                od = runner.computeOdFactors(artifacts.resolve("sumo"), ctx.zones, job::log);
                if (od.routedPairs() == 0) {
                    job.stage("Netz-Feedback").status = "SKIPPED";
                    job.log("Netz-Feedback übersprungen (keine routbaren Zonenrelationen)");
                    return;
                }
                delta = FeedbackMath.msaUpdate(smoothed, od.factors(), iteration);
                job.log(String.format(
                        "Iteration %d: %d Relationen, Ø Faktor %.2f (max %.2f), Δ nach Dämpfung %.4f",
                        iteration, od.routedPairs(), od.meanFactor(), od.maxFactor(), delta));
                if (iteration > 1 && delta < tolerance) {
                    job.log(String.format("Konvergiert nach %d Iterationen (Δ %.4f < %.2f) – "
                            + "Nachfrage und Netz sind im Gleichgewicht", iteration, delta, tolerance));
                    break;
                }
                float[][] scaled = new float[n][n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        scaled[i][j] = (float) (ctx.carTravelTimeMin[i][j] * smoothed[i][j]);
                    }
                }
                VisumMatrixWriter.write(
                        artifacts.resolve("matrix").resolve("travel_times").resolve("CAR_TTC.mtx.bz2"),
                        ctx.zones, scaled);
                VisumMatrixWriter.write(
                        artifacts.resolve("legacy").resolve("matrices").resolve("travel_times")
                                .resolve("CAR_TTC.mtx"),
                        ctx.zones, scaled);
                job.log("logiTopp entscheidet neu auf gedämpften Stauzeiten (Iteration " + iteration + ")…");
                SimulationRunner.Result run = simRunner.run(
                        scenario,
                        artifacts,
                        job::log,
                        modeChoiceOverride,
                        destinationChoiceOverride);
                new SimulationResultIngestor(mapper)
                        .ingest(run.resultsDir(), artifacts, run.durationSeconds(),
                                scenario.logistics.returns);
                // SUMO auf der neuen Nachfrage – liefert die Faktoren der nächsten Iteration
                // und hält CO₂/Netzlast-Artefakte konsistent zur finalen Nachfrage
                String osmXml = roadNetworkOsm(ctx);
                SumoRunner.Result sumo = runner.run(artifacts, osmXml, demandResult,
                        plannedTours, job::log);
                writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
                job.summary.put("sumoVehicles", sumo.vehicles());
            }

            Map<String, Double> finalSplit = readModalSplit(
                    artifacts.resolve("simulation").resolve("kpis.json"));
            job.log(String.format(
                    "Netz-Feedback (%d Iterationen): Pkw %.0f%%→%.0f%%, Fuß %.0f%%→%.0f%%, "
                            + "Rad %.0f%%→%.0f%%, ÖV %.0f%%→%.0f%%",
                    iteration,
                    initialSplit.getOrDefault("car", 0.0) * 100, finalSplit.getOrDefault("car", 0.0) * 100,
                    initialSplit.getOrDefault("pedestrian", 0.0) * 100,
                    finalSplit.getOrDefault("pedestrian", 0.0) * 100,
                    initialSplit.getOrDefault("bike", 0.0) * 100, finalSplit.getOrDefault("bike", 0.0) * 100,
                    initialSplit.getOrDefault("publicTransport", 0.0) * 100,
                    finalSplit.getOrDefault("publicTransport", 0.0) * 100));
            job.summary.put("feedbackIterations", iteration);
            job.summary.put("feedbackDelta", Math.round(delta * 10000) / 10000.0);
            if (od != null) {
                job.summary.put("feedbackMeanFactor", Math.round(od.meanFactor() * 100) / 100.0);
                job.summary.put("feedbackRelations", od.routedPairs());
            }
        } catch (Exception e) {
            job.stage("Netz-Feedback").status = "FAILED";
            job.stage("Netz-Feedback").message = String.valueOf(e);
            job.log("Netz-Feedback fehlgeschlagen: " + e);
        }
    }

    /**
     * Wählt nur ein im aktuellen Szenario aktiviertes MNL-Kalibrierartefakt. So kann ein
     * liegengebliebener Stand aus einem früheren Lauf nicht unbemerkt reaktiviert werden.
     */
    static Path modeChoiceCalibrationOverride(Scenario scenario, Path simulationDir) {
        Path modeDistance = simulationDir.resolve("mode_distance_calibrated.txt");
        if (scenario.population.calibrateModeDistance && Files.exists(modeDistance)) {
            return modeDistance;
        }
        Path modeChoice = simulationDir.resolve("mode_choice_calibrated.txt");
        if (scenario.population.calibrateModeChoice && Files.exists(modeChoice)) {
            return modeChoice;
        }
        return null;
    }

    /** Wählt nur den im aktuellen Szenario aktiven Zielwahl-Parameterstand. */
    static Path destinationChoiceOverride(Scenario scenario, Path simulationDir) {
        Path destinationChoice = simulationDir.resolve("dc-calibrated");
        if (scenario.population.calibrateDistance && Files.isDirectory(destinationChoice)) {
            return destinationChoice;
        }
        Path corrected = simulationDir.resolve("distance-decay-adjusted");
        return scenario.population.correctDistanceDecay && Files.isDirectory(corrected)
                ? corrected : null;
    }

    // --- Stufe 14: LLM-Moduswahl (GTA 3c) ------------------------------------------------------

    /** Alternativer Modal-Split aus dem LLM, geerdet in Reisezeiten; Vergleich zu MNL/MiD. */
    private void stageLlmModeChoice(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path artifacts = ctx.artifacts;
        if (!scenario.population.llmModeChoice) {
            job.stage("LLM-Moduswahl").status = "SKIPPED";
            job.log("LLM-Moduswahl übersprungen (nicht aktiviert)");
            return;
        }
        Path demandResult = artifacts.resolve("simulation").resolve("results")
                .resolve("demandsimulationResult.csv");
        LlmModeChoiceRunner runner = new LlmModeChoiceRunner(
                properties.getLlm(), scenario.population.seed);
        if (!properties.getLlm().isEnabled() || !runner.isAvailable()) {
            job.stage("LLM-Moduswahl").status = "SKIPPED";
            job.log("LLM-Moduswahl übersprungen (LLM nicht erreichbar)");
            return;
        }
        if (!Files.exists(demandResult)) {
            job.stage("LLM-Moduswahl").status = "SKIPPED";
            job.log("LLM-Moduswahl übersprungen (benötigt logiTopp-Trips)");
            return;
        }
        try {
            job.log("LLM-Moduswahl je Distanzband…");
            LlmModeChoiceRunner.Result r = runner.run(demandResult, job::log);
            Map<String, Double> mid = scenario.population.calibrationTarget != null
                    ? scenario.population.calibrationTarget : ModeChoiceCalibrator.MID_2017;
            Map<String, Double> mnl = readModalSplit(
                    artifacts.resolve("simulation").resolve("kpis.json"));
            writeLlmModeChoice(artifacts.resolve("simulation").resolve("llm-mode-choice.json"),
                    r, mid, mnl);
            Map<String, Double> ms = r.modalSplit();
            job.log(String.format("LLM-Modal-Split: Fuß %.0f%% Rad %.0f%% Pkw %.0f%% Mitf. %.0f%% "
                            + "ÖV %.0f%% (%d Trips, RMSE→MiD %.3f, →MNL %.3f)",
                    ms.get("pedestrian") * 100, ms.get("bike") * 100, ms.get("car") * 100,
                    ms.get("passenger") * 100, ms.get("publicTransport") * 100, r.trips(),
                    rmse(ms, mid), rmse(ms, mnl)));
        } catch (Exception e) {
            job.stage("LLM-Moduswahl").status = "FAILED";
            job.stage("LLM-Moduswahl").message = String.valueOf(e);
            job.log("LLM-Moduswahl fehlgeschlagen: " + e);
        }
    }

    // --- Lauf-Zusammenfassung -------------------------------------------------------------------

    private void writeRunSummary(PipelineContext ctx) {
        GenerationJob job = ctx.job;
        job.summary.put("zones", ctx.zones.size());
        job.summary.put("populationSource", ctx.populationSource);
        job.summary.put("populationSourceLabel", ctx.populationSourceLabel);
        if (!"user-defined".equals(ctx.populationSource)
                && !ctx.populationProvenance.referenceDate().isBlank()) {
            job.summary.put("populationReferenceDate", ctx.populationProvenance.referenceDate());
        }
        if (!"user-defined".equals(ctx.populationSource)
                && !ctx.populationProvenance.url().isBlank()) {
            job.summary.put("populationSourceUrl", ctx.populationProvenance.url());
        }
        job.summary.put("populationDistribution", ctx.distributionSource);
        job.summary.put("persons", ctx.population.persons().size());
        job.summary.put("households", ctx.population.householdCount());
        job.summary.put("cars", ctx.population.carCount());
        job.summary.put("depots", ctx.logistics.depotCount());
        job.summary.put("osmDataUsed", !ctx.pois.isEmpty());
        job.summary.put("roadNetworkSource", ctx.roadNetworkSource);
        job.summary.put("commuterSource", ctx.commuterSource);
        job.summary.put("workplaceMarginSource", ctx.workplaceMarginSource);
    }

    /**
     * Builds a GraphHopper routing impedance from the region's OSM road network, or
     * {@code null} when routing is disabled or the network cannot be fetched/built
     * (the matrix generator then falls back to beeline estimates).
     */
    private MatrixGenerator.Impedance buildRouting(PipelineContext ctx) {
        if (!ctx.scenario.population.useRealRouting) {
            return null;
        }
        try {
            String osmXml = roadNetworkOsm(ctx);
            RoutingEngine engine = RoutingEngine.fromOsmXml(osmXml);
            ctx.job.log("GraphHopper-Graph aufgebaut – Matrizen werden geroutet");
            return engine;
        } catch (Exception e) {
            ctx.job.log("Routing nicht verfügbar (" + e.getMessage() + ") – Fallback auf Luftlinie");
            return null;
        }
    }

    /** OSM-Straßennetz einmal pro Lauf laden (Routing + SUMO teilen sich den Abruf). */
    private String roadNetworkOsm(PipelineContext ctx) throws Exception {
        String cached = ctx.roadNetwork.get();
        if (cached != null) {
            ctx.job.log("OSM-Straßennetz aus diesem Lauf wiederverwendet");
            return cached;
        }
        Path scenarioFile = scenarios.uploadsDir(ctx.scenario.id).resolve(RoadNetworkFile.NAME);
        if (Files.isRegularFile(scenarioFile)) {
            RoadNetworkFile.Inspection inspection =
                    RoadNetworkFile.inspectForRegion(scenarioFile, ctx.scenario.region);
            ctx.roadNetworkSource = "scenario-upload:" + RoadNetworkFile.NAME + "@"
                    + inspection.sha256().substring(0, 12);
        } else if (overpass.roadNetworkSource().equals("Overpass")) {
            ctx.roadNetworkSource = "overpass-live";
        } else {
            ctx.roadNetworkSource = overpass.roadNetworkSource();
        }
        ctx.job.summary.put("roadNetworkSource", ctx.roadNetworkSource);
        ctx.job.log("Lade OSM-Straßennetz (" + ctx.roadNetworkSource + ")…");
        String osmXml = overpass.fetchRoadNetworkOsm(ctx.scenario.region, scenarioFile);
        ctx.roadNetwork.set(osmXml);
        persistRoadNetwork(ctx, osmXml, scenarioFile);
        return osmXml;
    }

    /**
     * Legt das tatsächlich verwendete Straßennetz als Artefakt ab und hinterlegt seinen SHA-256
     * im Manifest. Der Upload-Pfad war bereits geprüft und gehasht, der Live-Abruf nicht: Das
     * XML lebte nur im Speicher des Laufs. Damit war weder belegbar, dass zwei Läufe dasselbe
     * Netz benutzten, noch ließ es sich für einen A/B-Vergleich einfrieren — und gerade dieser
     * Abruf kippt bei Zeitüberschreitung still auf Luftlinien-Matrizen (siehe
     * „Fallback auf Luftlinie"), was historisch eine ganze Kampagne kontaminiert hat.
     */
    private void persistRoadNetwork(PipelineContext ctx, String osmXml, Path scenarioFile) {
        try {
            Path target = ctx.artifacts.resolve(RoadNetworkFile.NAME);
            if (Files.isRegularFile(scenarioFile)) {
                // Upload-Pfad: Der Hash steckt bereits in roadNetworkSource; nicht neu schreiben.
                ctx.roadNetworkSha256 = RoadNetworkFile.inspect(scenarioFile).sha256();
                return;
            }
            ctx.roadNetworkSha256 = RoadNetworkFile.persist(target, osmXml).sha256();
            ctx.job.summary.put("roadNetworkSha256", ctx.roadNetworkSha256);
            ctx.job.log("Straßennetz gesichert (" + RoadNetworkFile.NAME + ", SHA-256 "
                    + ctx.roadNetworkSha256.substring(0, 12) + "…)");
        } catch (Exception e) {
            ctx.job.log("Straßennetz konnte nicht gesichert werden (" + e.getMessage() + ")");
        }
    }

    private void writeSumoSummary(Path file, SumoRunner.Result sumo) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("vehicles", sumo.vehicles());
        node.put("meanDurationMin", Math.round(sumo.meanDurationSec() / 60 * 10) / 10.0);
        node.put("meanRouteLengthKm", Math.round(sumo.meanRouteLengthM() / 1000 * 100) / 100.0);
        node.put("meanSpeedKmh", Math.round(sumo.meanSpeedMs() * 3.6 * 10) / 10.0);
        node.put("runtimeSeconds", sumo.durationSeconds());
        node.put("carTrips", sumo.carTrips());
        node.put("bikeTrips", sumo.bikeTrips());
        node.put("walkTrips", sumo.walkTrips());
        node.put("deliveryTrips", sumo.deliveryTrips());
        node.put("ptRiders", sumo.ptRiders());
        node.put("ptVehicles", sumo.ptVehicles());
        node.put("co2Kg", sumo.co2Kg());
        node.set("co2KgByType", mapper.valueToTree(sumo.co2KgByType()));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }

    /** GTA 3d: Personas + LLM-Begründungen (und ggf. Was-wäre-wenn-Kontext) für das Frontend. */
    private void writeGtaPersonas(Path file, String whatIf,
                                  List<GtaBehaviorRunner.PersonaSummary> summaries) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        if (whatIf != null && !whatIf.isBlank()) {
            node.put("whatIf", whatIf.trim());
        }
        node.set("personas", mapper.valueToTree(summaries));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }

    private void writeLlmModeChoice(Path file, LlmModeChoiceRunner.Result r,
                                    Map<String, Double> mid, Map<String, Double> mnl) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("trips", r.trips());
        node.set("modalSplit", mapper.valueToTree(r.modalSplit()));
        node.set("mid2017", mapper.valueToTree(mid));
        node.set("logitoppMnl", mapper.valueToTree(mnl));
        node.put("rmseToMid", Math.round(rmse(r.modalSplit(), mid) * 1000) / 1000.0);
        node.put("rmseToMnl", Math.round(rmse(r.modalSplit(), mnl) * 1000) / 1000.0);
        ArrayNode bands = node.putArray("bands");
        for (LlmModeChoiceRunner.BandResult b : r.bands()) {
            ObjectNode bn = bands.addObject();
            bn.put("band", b.band());
            bn.put("trips", b.trips());
            bn.put("meanKm", b.meanKm());
            bn.set("travelTimesMin", mapper.valueToTree(b.times()));
            bn.set("modalSplit", mapper.valueToTree(b.split()));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }

    /** Wurzel der mittleren quadratischen Abweichung zweier Modal-Splits (gemeinsame Modi). */
    private static double rmse(Map<String, Double> a, Map<String, Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (String mode : LlmModeChoiceRunner.MODES) {
            double diff = a.getOrDefault(mode, 0.0) - b.getOrDefault(mode, 0.0);
            sum += diff * diff;
            n++;
        }
        return Math.sqrt(sum / n);
    }

    /** Liest den simulierten Modal-Split (Modus → Anteil) aus kpis.json. */
    private Map<String, Double> readModalSplit(Path kpisJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode split =
                    mapper.readTree(kpisJson.toFile()).path("demand").path("modalSplit");
            Map<String, Double> result = new java.util.HashMap<>();
            split.properties().forEach(e -> result.put(e.getKey(), e.getValue().asDouble()));
            return result;
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** Anteil vollständiger Wege &lt; 1 km aus dem gemeinsamen Demand-Modell. */
    private double readNearDistanceShare(Path resultsDir) throws IOException {
        Path csv = resultsDir.resolve("demandsimulationResult.csv");
        TravelDemand demand = new LogitoppDemandAdapter().read(csv);
        return demand.nearTripShare();
    }

    private interface StageWork<T> {
        T run() throws Exception;
    }

    private <T> T stage(GenerationJob job, String name, StageWork<T> work) throws Exception {
        GenerationJob.StageInfo info = job.stage(name);
        job.currentStage = name;
        info.status = "RUNNING";
        long start = System.currentTimeMillis();
        try {
            T result = work.run();
            if (!info.status.equals("SKIPPED") && !info.status.equals("FAILED")) {
                info.status = "DONE";
            }
            return result;
        } catch (Exception e) {
            info.status = "FAILED";
            info.message = e.getMessage();
            throw e;
        } finally {
            info.durationMs = System.currentTimeMillis() - start;
        }
    }

    private int[] countPoisPerZone(List<Zone> zones, List<OverpassClient.Poi> pois) {
        int[] counts = new int[zones.size()];
        for (OverpassClient.Poi poi : pois) {
            int index = GridZoning.zoneIndexOf(zones, poi.lat(), poi.lon());
            if (index >= 0) {
                counts[index]++;
            }
        }
        return counts;
    }

    private void writeManifest(Path artifacts, Scenario scenario, GenerationJob job, int zoneCount,
                               PopulationGenerator.Result population, LogisticsGenerator.Result logistics,
                               boolean osmUsed, String populationSource, String populationSourceLabel,
                               int totalPersons,
                               LogisticsGenerator.Sources logisticsSources,
                               String distributionSource, String matrixSource,
                               String roadNetworkSource, String commuterSource,
                               String workplaceMarginSource, String osmDataSource,
                               String osmDataSha256, String roadNetworkSha256,
                               PopulationProvenance populationProvenance) throws IOException {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("scenarioId", scenario.id);
        manifest.put("scenarioName", scenario.name);
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("seed", scenario.population.seed);
        manifest.set("scenario", mapper.valueToTree(scenario));

        ObjectNode counts = manifest.putObject("counts");
        counts.put("zones", zoneCount);
        counts.put("persons", population.persons().size());
        counts.put("households", population.householdCount());
        counts.put("cars", population.carCount());
        counts.put("distributionCenters", logistics.depotCount());

        ObjectNode quality = manifest.putObject("dataQuality");
        quality.put("zoning", "synthetic-grid");
        quality.put("attractivities", osmUsed ? "osm-pois" : "synthetic");
        quality.put("populationTotal", populationSource);
        quality.put("populationDistribution", distributionSource);
        quality.put("populationStructure", scenario.population.useKitPopulation
                ? "kit-population-sample" : "synthetic");
        quality.put("matrices", matrixSource);
        quality.put("roadNetwork", roadNetworkSource);
        quality.put("commuters", commuterSource);
        quality.put("workplaceMargins", workplaceMarginSource);
        quality.put("depots", !scenario.logistics.depots.isEmpty() ? "user-defined"
                : logisticsSources.depotsFromOsm() ? "osm-pois" : "auto-placed");
        quality.put("packstations", logisticsSources.packstationsFromOsm() ? "osm-pois" : "synthetic");
        ObjectNode populationEstimate = manifest.putObject("populationEstimate");
        populationEstimate.put("totalPersons", totalPersons);
        populationEstimate.put("source", populationSource);
        populationEstimate.put("sourceLabel", populationSourceLabel);
        if (!"user-defined".equals(populationSource)
                && !populationProvenance.referenceDate().isBlank()) {
            populationEstimate.put("referenceDate", populationProvenance.referenceDate());
        }

        ObjectNode inputs = manifest.putObject("inputProvenance");
        ObjectNode populationInput = inputs.putObject("population");
        populationInput.put("source", populationProvenance.source());
        if (!populationProvenance.version().isBlank()) {
            populationInput.put("version", populationProvenance.version());
        }
        if (!populationProvenance.referenceDate().isBlank()) {
            populationInput.put("referenceDate", populationProvenance.referenceDate());
        }
        if (!populationProvenance.url().isBlank()) {
            populationInput.put("url", populationProvenance.url());
        }
        if (!populationProvenance.sha256().isBlank()) {
            populationInput.put("sha256", populationProvenance.sha256());
        }
        if (!populationProvenance.license().isBlank()) {
            populationInput.put("license", populationProvenance.license());
        }
        if (!populationProvenance.attribution().isBlank()) {
            populationInput.put("attribution", populationProvenance.attribution());
        }
        ObjectNode osmData = inputs.putObject("osmData");
        osmData.put("source", osmDataSource);
        if (!osmDataSha256.isBlank()) {
            osmData.put("sha256", osmDataSha256);
        }
        ObjectNode roadNetwork = inputs.putObject("roadNetwork");
        roadNetwork.put("source", roadNetworkSource);
        if (!roadNetworkSha256.isBlank()) {
            roadNetwork.put("sha256", roadNetworkSha256);
        }
        Path attractivities = artifacts.resolve("zone-repository/attractivities.csv");
        ObjectNode attractivityInput = inputs.putObject("attractivities");
        attractivityInput.put("path", "zone-repository/attractivities.csv");
        attractivityInput.put("sha256", OsmDataSnapshot.sha256(attractivities));

        var notes = manifest.putArray("notes");
        notes
                .add("Formate validiert (2026-06-10) gegen einen erfolgreichen Short-Term-Lauf des "
                        + "mobiTopp-Tutorials (main-Branch); Details in docs/05-validierung.md.")
                .add("Matrizen liegen im Visum-$V;D3-Textformat (.mtx.bz2) vor; Distanzen in Metern; "
                        + "Distanzmatrizen unter other/ (Tutorial-Layout).");
        if ("graphhopper-with-local-fallbacks".equals(matrixSource)) {
            notes.add("Interzonale Binnenbeziehungen werden mit GraphHopper geroutet; intrazonale, "
                    + "externe oder nicht routbare Beziehungen verwenden die dokumentierten "
                    + "Luftlinien-Heuristiken.");
        } else {
            notes.add("Matrizen sind Luftlinien-Schätzungen (Umwegfaktor); für realistische Werte "
                    + "GraphHopper/R5-Routing einsetzen.");
        }
        notes.add("activity.csv nutzt numerische Aktivitätscodes (LegacyActivityType); das "
                        + "Aktivitätsset des Zielprojekts muss die Codes 31/32/33/41/42/51/52 enthalten.")
                .add("fixedDestination-logitopp.csv enthält zusätzlich PICK_UP_PARCEL-Ziele für "
                        + "logiTopp (Legacy-Stack); fixedDestination.csv ist Tutorial-kompatibel.")
                .add("Choice-Model-Parameter stammen aus kit-ifv/logitopp (GPL-3.0).");

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(artifacts.resolve("manifest.json").toFile(), manifest);
        job.log("manifest.json geschrieben");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
