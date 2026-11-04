package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.LogitoppDemandAdapter;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.generation.demand.TrafficDemand;
import de.synthesis.generation.demand.TrafficDemandArtifacts;
import de.synthesis.generation.demand.ParcelTourAdapter;
import de.synthesis.generation.pipeline.ActitoppRunner;
import de.synthesis.generation.pipeline.GtaBehaviorRunner;
import de.synthesis.generation.pipeline.ActivityGenerator;
import de.synthesis.generation.pipeline.AttractivityCalculator;
import de.synthesis.generation.pipeline.CsvArtifacts;
import de.synthesis.generation.pipeline.CommuterMatrix;
import de.synthesis.generation.pipeline.CensusPopulationSource;
import de.synthesis.generation.pipeline.CensusStructureSource;
import de.synthesis.generation.pipeline.ExternalZones;
import de.synthesis.generation.pipeline.FeedbackMath;
import de.synthesis.generation.pipeline.FreightDemandGenerator;
import de.synthesis.generation.pipeline.GeoJson;
import de.synthesis.generation.pipeline.Epsg3035;
import de.synthesis.generation.pipeline.GridZoning;
import de.synthesis.generation.pipeline.InspireGridZoning;
import de.synthesis.generation.pipeline.LegacyExportGenerator;
import de.synthesis.generation.pipeline.LogisticsGenerator;
import de.synthesis.generation.pipeline.MatrixGenerator;
import de.synthesis.generation.pipeline.MobileCityExporter;
import de.synthesis.generation.pipeline.MobileCityGtfsAdapter;
import de.synthesis.generation.pipeline.MobileCitySurroundingSource;
import de.synthesis.generation.pipeline.MobileCityValidationRunner;
import de.synthesis.generation.pipeline.DistanceCalibrator;
import de.synthesis.generation.pipeline.DestinationChoiceParameterAdjuster;
import de.synthesis.generation.pipeline.ModeChoiceCalibrator;
import de.synthesis.generation.pipeline.ModeDistanceCalibrator;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.generation.pipeline.EnergyInfrastructure;
import de.synthesis.generation.pipeline.OsmDataSnapshot;
import de.synthesis.generation.pipeline.OsmExternalDestinationCandidates;
import de.synthesis.generation.pipeline.PopulationEstimator;
import de.synthesis.generation.pipeline.PopulationGenerator;
import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.generation.pipeline.RoutingEngine;
import de.synthesis.generation.pipeline.RoadNetworkFile;
import de.synthesis.generation.pipeline.RoadLocations;
import de.synthesis.generation.pipeline.SimulationResultIngestor;
import de.synthesis.generation.pipeline.SimulationRunner;
import de.synthesis.generation.pipeline.VisumMatrixWriter;
import de.synthesis.generation.pipeline.WorkplaceMargins;
import de.synthesis.generation.pipeline.LlmModeChoiceRunner;
import de.synthesis.generation.pipeline.GtfsSource;
import de.synthesis.generation.pipeline.SumoRunner;
import de.synthesis.vehicle.VehicleCatalog;
import de.synthesis.vehicle.VehicleArtifacts;
import de.synthesis.vehicle.VehicleConfiguration;
import de.synthesis.generation.pipeline.TransitRunner;
import de.synthesis.generation.pipeline.Zone;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import de.synthesis.reference.Mid2017;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Comparator;
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
            "Zonierung", "OSM-Daten (POIs)", "Attraktivitäten", "Matrizen", "Bevölkerungssynthese",
            "Aktivitätenpläne", "Logistik", "Legacy-Export", "MobileCity-Export",
            "MobileCity-Validierung", "Manifest", "Simulation (logiTopp)", "SUMO (Verkehr)",
            "Netz-Feedback", "LLM-Moduswahl");

    private final ScenarioRepository scenarios;
    private final OverpassClient overpass;
    private final CensusPopulationSource census;
    private final SynthesisProperties properties;
    private final ObjectMapper mapper;
    private final de.synthesis.data.importing.ImportRegistry importRegistry;
    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    // Bewusst 1: die Simulationsstufe startet eine logiTopp-JVM mit bis zu 12 GB Heap und teilt
    // sich das Gradle-Projekt mobitopp-rastatt; parallele Läufe würden RAM und Arbeitsverzeichnis
    // kollidieren lassen. Weitere Jobs warten sichtbar als QUEUED.
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    public GenerationService(ScenarioRepository scenarios, OverpassClient overpass,
                             SynthesisProperties properties, ObjectMapper mapper) {
        this(scenarios, overpass, properties, mapper, de.synthesis.data.importing.ImportRegistry.builtins());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GenerationService(ScenarioRepository scenarios, OverpassClient overpass,
                             SynthesisProperties properties, ObjectMapper mapper,
                             de.synthesis.data.importing.ImportRegistry importRegistry) {
        this.scenarios = scenarios;
        this.overpass = overpass;
        this.census = new CensusPopulationSource(properties.getCensus());
        this.properties = properties;
        this.mapper = mapper;
        this.importRegistry = importRegistry;
    }

    public GenerationJob start(String scenarioId) {
        Scenario scenario = scenarios.findById(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Szenario nicht gefunden: " + scenarioId));
        VehicleCatalog.Report vehicleReport = VehicleCatalog.validate(scenario.vehicleConfiguration);
        if (!vehicleReport.valid()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Fahrzeugkonfiguration: " + vehicleReport.errors().getFirst());
        }
        final de.synthesis.data.SourceResolver.Inputs sourceInputs;
        try {
            sourceInputs = de.synthesis.data.SourceResolver.prepare(scenario, scenarios, mapper, importRegistry);
            FreightDemandGenerator.validateDemand(scenario);
            if (scenario.freight.enabled && scenario.freight.relations.isEmpty())
                throw new IllegalArgumentException("Bitte Güterverkehrsbeziehungen hochladen");
        } catch (IllegalArgumentException | IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        final de.synthesis.companytour.CompanyTourExecution.Batch companyInput;
        try {
            companyInput = de.synthesis.companytour.CompanyTourImportService.loadForGeneration(scenarios, mapper, scenario);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unternehmenstouren: " + e.getMessage());
        }
        validateMobileCityPreflight(scenario);
        if(scenario.population.roadLocatedActivities && !scenario.population.useRealRouting)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Straßennahe Wohn- und Aktivitätsorte benötigen reales Routing");
        GenerationJob existing = jobs.get(scenarioId);
        if (existing != null && (existing.state == GenerationJob.State.QUEUED
                || existing.state == GenerationJob.State.RUNNING)) {
            throw new IllegalStateException("Für dieses Szenario läuft bereits eine Generierung");
        }
        GenerationJob job = new GenerationJob(scenarioId, STAGES);
        jobs.put(scenarioId, job);
        executor.submit(() -> run(scenario, job, companyInput, sourceInputs));
        return job;
    }

    /** Fail before queueing when a complete MobileCity package cannot be produced at all. */
    private void validateMobileCityPreflight(Scenario scenario) {
        if (!scenario.targets().contains("mobilecity")
                || (!scenario.mobileCity.regionalExport && !scenario.mobileCity.useTemplate)) {
            return;
        }
        if (scenario.mobileCity.regionalExport
                && !scenario.mobileCity.useRegionalFleet
                && scenario.mobileCity.electricShare == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "MobileCity-Regionalpaket benötigt regionale KBA-Daten oder eine explizite BEV-Annahme; für einen rudimentären Export ist 0 zulässig");
        }
        Path manifest = Path.of(properties.getDataDir()).resolve("mobilecity")
                .resolve(scenario.mobileCity.templateId).resolve("template-manifest.json")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "MobileCity-Modellprofil '" + scenario.mobileCity.templateId + "' fehlt unter "
                            + manifest.getParent() + "; mit scripts/prepare-mobilecity.py vorbereiten");
        }
    }

    /** In-memory status first; after a backend restart the persisted {@code artifacts/job.json}. */
    public Optional<GenerationJob> status(String scenarioId) {
        GenerationJob job = jobs.get(scenarioId);
        if (job != null) {
            return Optional.of(job);
        }
        try {
            Path file = scenarios.artifactsDir(scenarioId).resolve(GenerationJob.FILE);
            return Files.isRegularFile(file) ? Optional.of(GenerationJob.read(file)) : Optional.empty();
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
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
        final VehicleCatalog.Report vehicles;
        de.synthesis.companytour.CompanyTourExecution.Batch companyTours = de.synthesis.companytour.CompanyTourExecution.Batch.empty();
        List<FreightDemandGenerator.Trip> freightTrips = List.of();
        TrafficDemand trafficDemand;
        de.synthesis.data.SourceResolver.Inputs sourceInputs = de.synthesis.data.SourceResolver.Inputs.empty();
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
        RoadLocations roadLocations;
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
            this.vehicles = VehicleCatalog.validate(scenario.vehicleConfiguration);
        }
    }

    /** Orchestriert die 15 Pipeline-Stufen; die Fachlogik liegt in den stage*-Methoden. */
    private void run(Scenario scenario, GenerationJob job) {
        run(scenario, job, de.synthesis.companytour.CompanyTourExecution.Batch.empty());
    }
    private void run(Scenario scenario, GenerationJob job, de.synthesis.companytour.CompanyTourExecution.Batch companyTours) {
        try { run(scenario, job, companyTours, de.synthesis.data.SourceResolver.prepare(scenario, scenarios, mapper, importRegistry)); }
        catch (Exception e) { job.state=GenerationJob.State.FAILED; job.error=e.getMessage(); job.finishedAt=Instant.now(); }
    }
    private void run(Scenario scenario, GenerationJob job, de.synthesis.companytour.CompanyTourExecution.Batch companyTours,
                     de.synthesis.data.SourceResolver.Inputs sourceInputs) {
        job.state = GenerationJob.State.RUNNING;
        job.startedAt = Instant.now();
        PipelineContext ctx = new PipelineContext(scenario, job, scenarios.artifactsDir(scenario.id));
        ctx.companyTours = companyTours;
        ctx.sourceInputs = sourceInputs;
        job.persist(ctx.artifacts.resolve(GenerationJob.FILE));
        try {
            Path sourceDir=ctx.artifacts.resolve("sources");
            for(String name:List.of("plan.json","lock.json","resolution.json","population-failure.json","workplace-failure.json","population-grid.csv"))Files.deleteIfExists(sourceDir.resolve(name));
            if(scenario.dataPlan!=null&&scenario.dataPlan.enabled){
                Files.createDirectories(sourceDir);
                mapper.writerWithDefaultPrettyPrinter().writeValue(sourceDir.resolve("plan.json").toFile(),scenario.dataPlan);
                mapper.writerWithDefaultPrettyPrinter().writeValue(sourceDir.resolve("lock.json").toFile(),sourceInputs);
                SourceRunReport.write(ctx.artifacts, mapper, scenario, job, sourceInputs, null);
            }
            for (String name : List.of("input.json", "routing.json", "execution.json", "regional-projection.json", "network-paths.json")) Files.deleteIfExists(ctx.artifacts.resolve("company-tours").resolve(name));
            if (companyTours.active()) { de.synthesis.vehicle.VehicleArtifacts.write(ctx.artifacts.resolve("company-tours/input.json"), companyTours);
                de.synthesis.companytour.CompanyTourRegion.writeReport(ctx.artifacts,companyTours,de.synthesis.companytour.CompanyTourRegion.project(companyTours));
            }
            for (String name : List.of("person-trips.csv", "legs.csv", "zone-flows.csv", "od-flows.csv", "metadata.json",
                    "traffic-demand.json", "traffic-summary.json", "movements.csv", "tours.json", "planned-connections.geojson"))
                Files.deleteIfExists(ctx.artifacts.resolve("demand").resolve(name));
            Files.deleteIfExists(ctx.artifacts.resolve(RoadLocations.FILE));
            Files.deleteIfExists(ctx.artifacts.resolve("sumo/traffic-routes.geojson"));
            Files.deleteIfExists(ctx.artifacts.resolve("sumo/traffic-categories.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("sumo/freight-network.geojson"));
            Files.deleteIfExists(ctx.artifacts.resolve("sumo/freight-permissions.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("sumo/sevas-permissions.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("freight/execution.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("freight/edge-mapping.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("freight/route-checks.json"));
            Files.deleteIfExists(ctx.artifacts.resolve("freight/profile-permissions.json"));
            VehicleArtifacts.snapshot(ctx.artifacts, scenario.vehicleConfiguration, ctx.vehicles);
            EnergyInfrastructure.clear(ctx.artifacts);
            de.synthesis.generation.pipeline.VehicleContinuity.clear(ctx.artifacts);
            if (scenario.vehicleConfiguration.appliesToSumo()) job.log(
                    "Fahrzeugkatalog für SUMO-Verkehr aktiv (" + ctx.vehicles.sha256()
                    + "); bestehende Nachfrage, Fahrzeugzuordnungen separat über vehicleOperations");
            ctx.zones = stage(job, "Zonierung", () -> stageZoning(ctx));
            stage(job, "OSM-Daten (POIs)", () -> {
                stageOsmData(ctx);
                if (scenario.energyInfrastructure != null && scenario.energyInfrastructure.enabled) {
                    job.stage("OSM-Daten (POIs)").status = "RUNNING";
                    var energy = EnergyInfrastructure.generate(scenario, ctx.artifacts,
                            scenarios.uploadsDir(scenario.id), () -> {
                                if (!properties.getOverpass().isLiveDataEnabled())
                                    throw new IOException("Live-OSM deaktiviert; Stationssnapshot hochladen oder OSM-Stationsquelle abwählen");
                                return overpass.fetchEnergyStations(scenario.region);
                            }, job::log);
                    job.summary.put("energyInfrastructure", energy);
                }
                return null;
            });
            preparePopulationDistribution(ctx);
            stage(job, "Attraktivitäten", () -> {
                stageAttractivities(ctx);
                return null;
            });
            stage(job, "Matrizen", () -> {
                stageMatrices(ctx);
                return null;
            });
            ctx.populationGenerator.useDestinationTravelTimes(ctx.carTravelTimeMin);
            job.summary.put("fixedDestinationImpedance", "initial-car-travel-time");
            job.summary.put("fixedDestinationTimeScaleMinutes", PopulationGenerator.DESTINATION_TIME_SCALE_MIN);
            job.log("Feste Arbeits-/Bildungsziele verwenden die initiale Pkw-Reisematrix; Pendlerdaten haben Vorrang");
            ctx.population = stage(job, "Bevölkerungssynthese", () -> stagePopulationSynthesis(ctx));
            stage(job, "Aktivitätenpläne", () -> {
                stageActivityPlans(ctx);
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
            stage(job, "MobileCity-Validierung", () -> {
                stageMobileCityValidation(ctx);
                return null;
            });
            stage(job, "Manifest", () -> {
                stageManifest(ctx);
                return null;
            });
            stage(job, "Simulation (logiTopp)", () -> {
                stageSimulation(ctx);
                publishTrafficDemand(ctx, !job.stage("Simulation (logiTopp)").status.equals("SKIPPED"));
                updateTrafficManifest(ctx);
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
        } catch (Error error) {
            // ExecutorService.submit captures Errors in an unobserved Future. Publish a terminal
            // state first so a resource/VM failure cannot leave a finished job RUNNING forever.
            job.state = GenerationJob.State.FAILED;
            job.error = error.toString();
            job.log("FEHLER: " + job.error);
            throw error;
        } finally {
            Instant finishedAt = Instant.now();
            try {
                SourceRunReport.write(ctx.artifacts, mapper, scenario, job, sourceInputs, finishedAt);
            } catch (IOException reportError) {
                job.state = GenerationJob.State.FAILED;
                if (job.error == null) job.error = "Quellenbericht konnte nicht gespeichert werden: " + reportError.getMessage();
                job.log("FEHLER: Quellenbericht konnte nicht gespeichert werden: " + reportError.getMessage());
            }
            job.finishedAt = finishedAt;
            job.currentStage = null;
            job.persist(ctx.artifacts.resolve(GenerationJob.FILE));
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
        boolean poisLoaded = false;
        boolean placesLoaded = false;
        try {
            ctx.pois = overpass.fetchPois(ctx.scenario.region);
            poisLoaded = true;
            job.log(ctx.pois.size() + " POIs von Overpass geladen");
        } catch (Exception e) {
            job.fallback("OSM-Daten (POIs)", "Overpass nicht erreichbar, synthetische Attraktivitäten: " + e.getMessage());
            job.log("Overpass-POIs fehlgeschlagen (" + e.getMessage() + ") – Fallback auf synthetische Daten");
        }
        try {
            ctx.places = overpass.fetchPlaces(ctx.scenario.region,
                    ctx.scenario.population.externalTrips);
            placesLoaded = true;
            job.log(ctx.places.size() + " Siedlungen (place-Nodes mit Einwohnerzahl) geladen");
        } catch (Exception e) {
            job.fallback("OSM-Daten (POIs)", "Overpass-Siedlungen nicht erreichbar, Bevölkerung über Dichte/POIs");
            job.log("Overpass-Places fehlgeschlagen (" + e.getMessage() + ") – Bevölkerung über Dichte/POIs");
        }
        // Die Herkunft beschreibt, was tatsächlich verwendet wurde – nicht, was versucht wurde.
        // Vorher stand nach einem Overpass-Ausfall "overpass-live" im Manifest, obwohl die
        // Attraktivitäten synthetisch waren (Befund 2026-11-04).
        ctx.osmDataSource = poisLoaded && placesLoaded ? "overpass-live"
                : poisLoaded || placesLoaded ? "overpass-live-partial" : "synthetic-fallback";
        job.summary.put("osmDataSource", ctx.osmDataSource);
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
        if(ctx.scenario.dataPlan!=null&&ctx.scenario.dataPlan.enabled
                &&(ctx.scenario.dataPlan.policy("population.distribution").source.equals("builtins")||ctx.sourceInputs.chains().containsKey("population.distribution")||ctx.sourceInputs.datasets().containsKey("population.distribution")&&ctx.sourceInputs.datasets().get("population.distribution").data().schemaId().equals(de.synthesis.data.MaskedCells.POP))){
            preparePopulationProviders(ctx);return;
        }
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        // Zusätzliche Fernsiedlungen sind Diagnosedaten. Für Bevölkerung und Verteilung
        // gilt auch beim Snapshot-Replay weiterhin der bisherige lokale Abfrageausschnitt.
        List<OverpassClient.Place> populationPlaces =
                OverpassClient.populationPlaces(scenario.region, ctx.places);

        // Alternative/neuere Uploads gewinnen vor dem backendseitig versionierten Zensus 2022.
        double[] gridWeights = null;
        double gridPopulation = 0;
        String gridSource = null;
        Path gridFile = scenarios.uploadsDir(scenario.id).resolve("population-grid.csv");
        var populationDataset=ctx.sourceInputs.datasets().get("population.distribution");
        if(populationDataset!=null){
            gridWeights=new double[ctx.zones.size()];
            for(var row:populationDataset.data().rows()){
                var values=row.values();
                int index=GridZoning.zoneIndexOf(ctx.zones,((Number)values.get("lat")).doubleValue(),((Number)values.get("lon")).doubleValue());
                if(index>=0){double amount=((Number)values.get("value")).doubleValue();gridWeights[index]+=amount;gridPopulation+=amount;}
            }
            if(gridPopulation<=0)throw new IllegalArgumentException("Ausgewähltes Bevölkerungsraster enthält keine positive Bevölkerung in den Modellzonen");
            gridSource="dataset:"+populationDataset.id();
            var meta=populationDataset.data().metadata();
            ctx.populationProvenance=new PopulationProvenance(gridSource,meta.label(),populationDataset.data().contract(),meta.referenceDate(),"",populationDataset.data().sourceHash(),"",meta.source());
            job.log("Bevölkerungsrevision "+populationDataset.id().substring(0,12)+": "+gridPopulation+" Personen auf Modellzonen zugeordnet");
        }
        if (gridWeights == null && Files.exists(gridFile)) {
            PopulationGridParser.ParseResult grid = PopulationGridParser.parse(gridFile, scenario.region);
            gridWeights = new double[ctx.zones.size()];
            for (PopulationGridParser.GridPoint point : grid.points()) {
                int index = GridZoning.zoneIndexOf(ctx.zones, point.lat(), point.lon());
                if (index >= 0) {
                    gridWeights[index] += point.population();
                    gridPopulation += point.population();
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
                        gridPopulation += point.population();
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
            ctx.totalPersons = (int) Math.round(gridPopulation);
            ctx.populationSource = gridSource;
            ctx.populationSourceLabel = populationDataset!=null ? populationDataset.data().metadata().label() : "upload-grid".equals(gridSource)
                    ? "Hochgeladenes Bevölkerungsraster" : "Destatis Zensus 2022";
        } else if (!populationPlaces.isEmpty()) {
            ctx.totalPersons = (int) PopulationEstimator.estimateForRegion(scenario.region, populationPlaces);
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
                ctx.zones, ctx.random, scenario.population.seed, scenario.referenceYear);
        int[] poiCounts = countPoisPerZone(ctx.zones, ctx.pois);
        double[] weights = gridWeights != null
                ? gridWeights
                : populationPlaces.isEmpty() ? null : PopulationEstimator.zoneWeights(ctx.zones, populationPlaces);
        ctx.distributionSource = gridWeights != null ? gridSource
                : !populationPlaces.isEmpty() ? "osm-places"
                : !ctx.pois.isEmpty() ? "poi-density" : "uniform-noise";
        ctx.populationGenerator.distributePopulation(
                ctx.totalPersons, ctx.pois.isEmpty() ? null : poiCounts, weights, gridWeights == null);
        if (gridWeights != null) job.log("Bevölkerungsraster ohne zusätzliche Glättung: bekannte 1–10-km-Zellen gleichmäßig auf 100-m-Zuordnungspunkte verteilt; unbekannte Auflösung bleibt punktbasiert");
    }

    /** First migrated native provider family; legacy execution above remains byte compatible. */
    private void preparePopulationProviders(PipelineContext ctx)throws IOException {
        var s=ctx.scenario;var policy=s.dataPlan.policy("population.distribution");
        var osmInfo=new de.synthesis.data.PopulationSources.Info(ctx.osmDataSource,"OSM-basierte Verteilung","osm-population-v1","",properties.getOverpass().getEndpoint(),ctx.osmDataSha256,"ODbL","OpenStreetMap contributors");
        final de.synthesis.data.PopulationSources.Resolution resolution;
        try{
            var providers=de.synthesis.data.PopulationSources.builtins(s,ctx.zones,ctx.pois,ctx.places,osmInfo,
                    scenarios.uploadsDir(s.id).resolve("population-grid.csv"),ctx.sourceInputs.populationUploadHash(),
                    ctx.artifacts.resolve("sources/population-grid.csv"),census,censusCacheDir(),ctx.job::log);
            var mixed=ctx.sourceInputs.chains().get("population.distribution");
            var dataset=ctx.sourceInputs.datasets().get("population.distribution");
            resolution=mixed!=null?de.synthesis.data.PopulationSources.resolveMixed(mixed,providers,ctx.zones,s)
                    :dataset!=null?de.synthesis.data.PopulationSources.resolveRevision(dataset,s,ctx.zones,providers):de.synthesis.data.PopulationSources.resolve(policy.providers,providers);
        }catch(de.synthesis.data.PopulationSources.ResolutionException failure){
            mapper.writerWithDefaultPrettyPrinter().writeValue(ctx.artifacts.resolve("sources/population-failure.json").toFile(),
                    Map.of("status",failure.candidates().stream().anyMatch(c->"invalid".equals(c.get("status")))?"invalid":"insufficient-input","message",failure.getMessage(),"candidates",failure.candidates()));
            throw failure;
        }
        var value=resolution.value();var info=value.info();
        ctx.totalPersons=s.population.totalPersons!=null?s.population.totalPersons:(int)Math.round(value.estimatedTotal());
        if(ctx.totalPersons<=0)throw new IllegalArgumentException("Bevölkerungsquelle liefert keine positive Gesamtzahl");
        ctx.populationSource=s.population.totalPersons!=null?"user-defined":value.totalSource();
        ctx.populationSourceLabel=s.population.totalPersons!=null?"Manuelle Einwohnerzahl":value.totalSource().equals("density-estimate")?"Dichteannahme":info.label();
        ctx.populationProvenance=new PopulationProvenance(info.source(),info.label(),info.version(),info.referenceDate(),info.url(),info.sha256(),info.license(),info.attribution());
        ctx.distributionSource=value.distribution();
        ctx.populationGenerator=new PopulationGenerator(ctx.zones,ctx.random,s.population.seed,s.referenceYear);
        int[] counts=value.distribution().equals("poi-density")?countPoisPerZone(ctx.zones,ctx.pois):null;
        ctx.populationGenerator.distributePopulation(ctx.totalPersons,counts,value.weights(),value.smooth());
        var report=de.synthesis.data.PopulationSources.report(resolution,s,ctx.zones,ctx.totalPersons);
        ctx.job.summary.put("populationSources",report);
        ctx.job.log("Bevölkerungsquellen: "+ctx.distributionSource+" für die Verteilung; "+ctx.populationSource+" für "+ctx.totalPersons+" Personen");
    }

    // --- Stufe 3: Attraktivitäten -------------------------------------------------

    /** Zell-Attraktivitäten (OSM oder synthetisch) + externe Zielzonen; schreibt zone-repository/. */
    private void stageAttractivities(PipelineContext ctx) throws IOException {
        GenerationJob job = ctx.job;
        Files.deleteIfExists(ctx.artifacts.resolve("population")
                .resolve(OsmExternalDestinationCandidates.NAME));
        if (ctx.pois.isEmpty()) {
            AttractivityCalculator.synthetic(ctx.zones, ctx.random);
        } else {
            AttractivityCalculator.fromPois(ctx.zones, ctx.pois);
        }
        Map<String,Object> workplaceReport;
        if(ctx.scenario.dataPlan!=null&&ctx.scenario.dataPlan.enabled&&(ctx.scenario.dataPlan.policy("opportunities.work").source.equals("builtins")||ctx.sourceInputs.chains().containsKey("opportunities.work")||ctx.scenario.dataPlan.policy("opportunities.work").gaps.equals("providers"))){
            try{
                workplaceReport=ctx.scenario.dataPlan.policy("opportunities.work").source.equals("builtins")
                    ?de.synthesis.data.WorkplaceSources.apply(ctx.scenario.dataPlan.policy("opportunities.work").providers,ctx.zones,ctx.pois,ctx.scenario.population.seed,ctx.osmDataSource,ctx.osmDataSha256)
                    :de.synthesis.data.WorkplaceComposition.apply(ctx.sourceInputs,ctx.scenario.dataPlan,ctx.zones,ctx.pois,ctx.scenario.population.seed,ctx.osmDataSource,ctx.osmDataSha256);
            }catch(de.synthesis.data.ProviderChain.Failure failure){
                mapper.writerWithDefaultPrettyPrinter().writeValue(ctx.artifacts.resolve("sources/workplace-failure.json").toFile(),
                    Map.of("status",failure.candidates().stream().anyMatch(c->"invalid".equals(c.get("status")))?"invalid":"insufficient-input","message",failure.getMessage(),"candidates",failure.candidates()));
                throw failure;
            }
        }else workplaceReport=de.synthesis.data.SourceResolver.applyWorkplaces(ctx.sourceInputs,ctx.scenario.dataPlan,ctx.zones);
        if(!workplaceReport.isEmpty())ctx.job.summary.put("workplaceDataset",workplaceReport);
        // Außenbeziehungen: externe Zielzonen anhängen (ohne Einwohner, nur Attraktivität)
        if (ctx.scenario.population.externalTrips) {
            Double surroundingBuffer = ctx.scenario.mobileCity.regionalExport
                    && ctx.scenario.mobileCity.autoSurrounding
                    ? ctx.scenario.mobileCity.surroundingBufferKm : null;
            List<Zone> external = ExternalZones.create(ctx.zones, surroundingBuffer);
            ctx.zones.addAll(external);
            job.log(surroundingBuffer==null?String.format(Locale.ROOT,
                    "%d externe Zielzonen (~%.0f km Radius) für Außenbeziehungen ergänzt",
                    external.size(), ExternalZones.radiusKm()):String.format(Locale.ROOT,
                    "%d Richtungsanker in einem %.1f-km-MobileCity-Umlandpuffer ergänzt",
                    external.size(),surroundingBuffer));
            prepareOsmExternalDestinationCandidates(ctx);
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
     * Writes the preregistered OSM fallback candidate without changing the active
     * destination choice. If an official commuter upload exists, the artifact also
     * contains the promotion metrics against that reference distribution.
     */
    private void prepareOsmExternalDestinationCandidates(PipelineContext ctx) {
        if (ctx.places == null || ctx.places.isEmpty()) {
            ctx.job.log("OSM-Außenzielkandidat nicht gebildet (keine Siedlungen mit Einwohnerzahl)");
            return;
        }
        CommuterMatrix.ParseResult official = null;
        Path commuterFile = scenarios.uploadsDir(ctx.scenario.id).resolve(CommuterMatrix.NAME);
        if (Files.isRegularFile(commuterFile)) {
            try {
                official = CommuterMatrix.parse(commuterFile);
            } catch (IOException | IllegalArgumentException e) {
                ctx.job.log("OSM-Außenzielkandidat ohne OD-Vergleich (" + e.getMessage() + ")");
            }
        }
        try {
            OsmExternalDestinationCandidates.Result result =
                    OsmExternalDestinationCandidates.build(ctx.scenario.region, ctx.places, official);
            if (result.candidates().isEmpty()) {
                ctx.job.log("OSM-Außenzielkandidat nicht gebildet (keine Siedlung außerhalb der Region)");
                return;
            }
            Path file = ctx.artifacts.resolve("population")
                    .resolve(OsmExternalDestinationCandidates.NAME);
            String sha256 = OsmExternalDestinationCandidates.write(file, result, mapper);
            ctx.job.summary.put("externalDestinationCandidateSource",
                    OsmExternalDestinationCandidates.SOURCE);
            ctx.job.summary.put("externalDestinationCandidateCount", result.candidates().size());
            ctx.job.summary.put("externalDestinationCandidateSha256", sha256);
            if (result.evaluation() != null) {
                var evaluation = result.evaluation();
                ctx.job.summary.put("externalDestinationDirectionShareMae",
                        evaluation.directionShareMae());
                ctx.job.summary.put("externalDestinationMeanDistanceRelativeError",
                        evaluation.meanDistanceRelativeError());
                ctx.job.summary.put("externalDestinationShareBeyond20KmDifference",
                        evaluation.shareBeyond20KmDifference());
                ctx.job.log(String.format(Locale.ROOT,
                        "OSM-Außenzielkandidat gegen Pendler-OD: Richtungs-MAE %.3f, "
                                + "Distanzfehler %.1f %%, >=20-km-Abweichung %.1f Prozentpunkte",
                        evaluation.directionShareMae(),
                        evaluation.meanDistanceRelativeError() * 100,
                        evaluation.shareBeyond20KmDifference() * 100));
            } else {
                ctx.job.log(result.candidates().size()
                        + " OSM-Außenzielkandidaten als Diagnoseartefakt geschrieben");
            }
        } catch (IOException e) {
            ctx.job.log("OSM-Außenzielkandidat konnte nicht geschrieben werden ("
                    + e.getMessage() + ")");
        }
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
        if (ctx.scenario.population.useCensusStructure) {
            var structure = new CensusStructureSource(properties.getCensus()).resolve(
                    ctx.scenario.region, ctx.zones, censusCacheDir().resolve("structure"), ctx.job::log);
            ctx.populationGenerator.useCensusStructure(structure);
        }
        PopulationGenerator.Result result;
        try { result=ctx.populationGenerator.useRoadLocations(ctx.roadLocations).generate(ctx.artifacts.resolve("population"),ctx.scenario); }
        finally { if(ctx.roadLocations!=null)ctx.roadLocations.write(ctx.artifacts.resolve(RoadLocations.FILE)); }
        if (ctx.scenario.population.useCensusStructure) {
            var report = ctx.populationGenerator.censusStructureReport();
            ctx.job.summary.put("censusStructureStatus", report.get("status"));
            ctx.job.summary.put("censusStructureWeightedZones", report.get("weightedZones"));
            ctx.job.summary.put("censusStructureFallbackZones", report.get("fitFallbackZones"));
            ctx.job.log("Zensus-Struktur: " + report.get("weightedZones") + " Zonen gewichtet, "
                    + report.get("fitFallbackZones") + " Zonen ohne ausreichende Anpassung; Details: population/census-structure.json");
            if (report.get("unavailableReason") != null) ctx.job.log("Zensus-Struktur-Fallback: " + report.get("unavailableReason"));
        }
        ctx.job.log(result.persons().size() + " Personen in " + result.householdCount()
                + " Haushalten, " + result.carCount() + " Pkw");
        var rounding = ctx.populationGenerator.householdRoundingReport();
        ctx.job.summary.put("populationTargetPersons", rounding.get("targetPersons"));
        ctx.job.summary.put("populationRoundingDifference", rounding.get("difference"));
        ctx.job.log("Vollständige Haushalte: Ziel " + rounding.get("targetPersons")
                + ", tatsächlich " + rounding.get("actualPersons") + ", Differenz "
                + rounding.get("difference") + "; Details: population/household-rounding.json");
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
                    job.fallback("Aktivitätenpläne", "GTA fehlgeschlagen, actiTopp verwendet: " + e.getMessage());
                    job.log("GTA fehlgeschlagen (" + e.getMessage() + ") – Fallback actiTopp");
                    behavior = "actitopp";
                }
            } else {
                job.fallback("Aktivitätenpläne", "GTA-LLM nicht erreichbar, actiTopp verwendet");
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
                            populationDir.resolve("person.csv"), populationDir.resolve("household.csv"),
                            populationDir.resolve("car.csv"), scenario.population.areaType, populationDir.resolve("actitopp"));
                    count = generator.generateFromActitopp(
                            result.scheduleCsv(), ctx.population.persons(), activityCsv);
                    source = "actiTopp";
                    job.summary.put("actitoppHouseholds", result.households());
                    job.summary.put("actitoppCars", result.cars());
                    job.summary.put("actitoppAreaType", scenario.population.areaType);
                    job.log("actiTopp: " + result.households() + " vollständige Haushaltskontexte, "
                            + result.cars() + " tatsächliche Pkw; Raumtyp " + scenario.population.areaType
                            + " (Szenarioannahme)");
                } catch (Exception e) {
                    job.fallback("Aktivitätenpläne", "actiTopp fehlgeschlagen, Template-Generator verwendet: " + e.getMessage());
                    job.log("actiTopp fehlgeschlagen (" + e.getMessage() + ") – Fallback Template-Generator");
                    count = generator.generate(activityCsv, ctx.population.persons());
                }
            } else {
                job.fallback("Aktivitätenpläne", "actiTopp nicht verfügbar, Template-Generator verwendet");
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
            if(ctx.scenario.population.roadLocatedActivities) {
                if(!(routing instanceof RoutingEngine engine))throw new IOException("Straßennahe Orte benötigen einen erfolgreich aufgebauten Straßengraphen");
                ctx.roadLocations=engine.roadLocations(ctx.zones);
                ctx.roadLocations.write(ctx.artifacts.resolve(RoadLocations.FILE));
                ctx.job.summary.put("roadLocatedActivities",true);
                ctx.job.log("Straßenmodell für synthetische interne Wohn- und Aktivitätsorte vorbereitet; Zonen und Zufallsfolge bleiben erhalten");
            }
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

    /** Compose once before exports, refresh only when a current logiTopp result exists. */
    private void publishTrafficDemand(PipelineContext ctx, boolean personResultsAvailable) throws IOException {
        Path results = ctx.artifacts.resolve("simulation/results");
        var persons = personResultsAvailable ? new LogitoppDemandAdapter().read(results.resolve("demandsimulationResult.csv"))
                : new TravelDemand(List.of(), 0);
        var parcels = personResultsAvailable ? ParcelTourAdapter.readAll(results.resolve("planned-tours.csv")) : List.<ParcelTourAdapter.Leg>of();
        ctx.trafficDemand = new TrafficDemand(TrafficDemand.CONTRACT, personResultsAvailable ? "available" : "not-generated",
                persons, ctx.scenario.freight.day, ctx.freightTrips, parcels, ctx.companyTours, ctx.vehicles.resolved(),
                ctx.scenario.vehicleOperations.enabled() ? ctx.scenario.vehicleOperations.bindings() : List.of(),
                personResultsAvailable && Files.exists(results.resolve("planned-tours.csv")) ? "available" : "not-generated");
        TrafficDemandArtifacts.write(ctx.artifacts, ctx.trafficDemand, mapper);
        ctx.job.summary.put("trafficDemand", mapper.readTree(ctx.artifacts.resolve(TrafficDemandArtifacts.SUMMARY).toFile()));
        ctx.job.log("Gemeinsame Nachfrage: " + ctx.trafficDemand.movements().size() + " Bewegungen; Personenstatus "
                + ctx.trafficDemand.personDemandStatus() + "; exportunabhängig unter demand/");
    }
    private void updateTrafficManifest(PipelineContext ctx) throws IOException {
        Path path = ctx.artifacts.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode)mapper.readTree(path.toFile());
        ObjectNode traffic = manifest.putObject("trafficDemand");
        traffic.put("contract", TrafficDemand.CONTRACT); traffic.put("input", TrafficDemandArtifacts.INPUT);
        traffic.put("movements", "demand/movements.csv"); traffic.put("tours", "demand/tours.json");
        traffic.put("plannedConnections", "demand/planned-connections.geojson");
        traffic.set("summary", mapper.readTree(ctx.artifacts.resolve(TrafficDemandArtifacts.SUMMARY).toFile()));
        VehicleArtifacts.write(path, manifest);
    }

    // --- Stufe 7: Logistik ------------------------------------------------------------

    /** Verteilzentren/Packstationen/Paketparameter. Befüllt ctx.logisticsGenerator/logistics. */
    private LogisticsGenerator.Result stageLogistics(PipelineContext ctx) throws IOException {
        ctx.freightTrips = FreightDemandGenerator.generate(ctx.scenario);
        FreightDemandGenerator.write(ctx.artifacts, ctx.scenario, mapper, ctx.freightTrips);
        if(ctx.scenario.dataPlan!=null&&ctx.scenario.dataPlan.enabled)
            ctx.job.summary.put("freightSources",de.synthesis.data.FreightSources.report(ctx.sourceInputs,ctx.scenario,ctx.freightTrips));
        publishTrafficDemand(ctx, false);
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
        int files;
        try { files=new LegacyExportGenerator(ctx.zones,ctx.random).useRoadLocations(ctx.roadLocations).generate(ctx.artifacts); }
        finally { if(ctx.roadLocations!=null)ctx.roadLocations.write(ctx.artifacts.resolve(RoadLocations.FILE)); }
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
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("populationSource", ctx.populationSource);
        sources.put("populationSourceLabel", ctx.populationSourceLabel);
        sources.put("distributionSource", ctx.distributionSource);
        sources.put("matrixSource", ctx.matrixSource);
        sources.put("roadNetworkSource", ctx.roadNetworkSource);
        sources.put("osmDataSource", ctx.osmDataSource);
        if (ctx.populationProvenance != null) {
            sources.put("populationReferenceDate", ctx.populationProvenance.referenceDate());
            sources.put("populationSourceSha256", ctx.populationProvenance.sha256());
            sources.put("populationSourceUrl", ctx.populationProvenance.url());
        }
        MobileCityGtfsAdapter.Feed transitFeed = null;
        MobileCitySurroundingSource.Result surrounding = null;
        Path transitWork = null;
        if (ctx.scenario.mobileCity.regionalExport && ctx.scenario.mobileCity.autoSurrounding) {
            surrounding = new MobileCitySurroundingSource(properties.getCensus()).resolve(
                    ctx.scenario.region, ctx.scenario.mobileCity.surroundingBufferKm,
                    censusCacheDir(), job::log);
            sources.put("surroundingSource", "census-buffer");
            sources.put("surroundingBufferKm", ctx.scenario.mobileCity.surroundingBufferKm);
            sources.put("surroundingCensusPopulation", surrounding.population());
            sources.put("surroundingPopulationAvailable", surrounding.populationAvailable());
            sources.put("surroundingAgesAvailable", surrounding.agesAvailable());
        }
        if (ctx.scenario.mobileCity.regionalExport && properties.getTransit().isEnabled()) {
            Path uploaded = ctx.scenario.population.gtfsUploadPath != null
                    && !ctx.scenario.population.gtfsUploadPath.isBlank()
                    ? Path.of(ctx.scenario.population.gtfsUploadPath) : null;
            try {
                transitWork = Files.createTempDirectory(ctx.artifacts, ".mobilecity-gtfs-");
                GtfsSource.Resolved resolved = new GtfsSource(properties.getTransit()).resolveFullDay(
                        ctx.scenario.region, uploaded,
                        Path.of(properties.getDataDir()).resolve("gtfs-cache"),
                        transitWork.resolve("feed-cropped.zip"), job::log);
                if (resolved != null) {
                    transitFeed = new MobileCityGtfsAdapter.Feed(resolved.croppedFeed(),
                            resolved.feedName(), resolved.source(), resolved.crop());
                    sources.put("transitSource", "gtfs-" + resolved.source());
                    sources.put("transitFeedName", resolved.feedName());
                } else {
                    if (!ctx.scenario.mobileCity.allowSyntheticTransitFallback) {
                        throw new IOException("Kein GTFS-Feed für MobileCity verfügbar; GTFS hochladen oder modellierten ÖV-Fallback aktivieren");
                    }
                    sources.put("transitSource", "modelled-fallback");
                    job.log("MobileCity-ÖV verwendet den sichtbaren modellierten Fallback");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("GTFS-Auflösung für MobileCity unterbrochen", e);
            } catch (IOException e) {
                if (uploaded != null) {
                    throw new IOException("Hochgeladener GTFS-Feed kann nicht für MobileCity verwendet werden", e);
                }
                if (!ctx.scenario.mobileCity.allowSyntheticTransitFallback) throw e;
                sources.put("transitSource", "modelled-fallback");
                sources.put("transitFallbackReason", e.getMessage());
                job.log("MobileCity-GTFS nicht verfügbar – modellierter Fallback: " + e.getMessage());
            }
        } else if (ctx.scenario.mobileCity.regionalExport) {
            if (!ctx.scenario.mobileCity.allowSyntheticTransitFallback) {
                throw new IOException("GTFS-Auflösung ist deaktiviert und der modellierte MobileCity-ÖV-Fallback wurde nicht freigegeben");
            }
            sources.put("transitSource", "modelled-fallback");
        }
        int files;
        try {
            files = new MobileCityExporter(ctx.zones, ctx.scenario,
                    Path.of(properties.getDataDir()).resolve("mobilecity"), sources, transitFeed,
                    surrounding)
                    .export(ctx.artifacts);
        } finally {
            if (transitWork != null && Files.exists(transitWork)) {
                try (var walk = Files.walk(transitWork)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
        job.log(files + " MobileCity-Dateien unter mobilecity/ geschrieben ("
                + (ctx.scenario.mobileCity.regionalExport ? "Regionalpaket mit Modellannahmen" : ctx.scenario.mobileCity.useTemplate ? "Vorlagenpaket" : "Teilbeitrag, kein Vollimport") + ")");
    }

    // --- Stufe 10: MobileCity-Validierung ---------------------------------------------------

    /** Optionaler echter ConfigTool-Nachlauf; Verfügbarkeit und Ergebnis bleiben getrennt. */
    private void stageMobileCityValidation(PipelineContext ctx) throws Exception {
        Scenario.MobileCity mobileCity = ctx.scenario.mobileCity;
        String skipReason = null;
        if (!ctx.scenario.targets().contains("mobilecity")) {
            skipReason = "Zielmodell nicht ausgewählt";
        } else if (!mobileCity.regionalExport && !mobileCity.useTemplate) {
            skipReason = "Teilbeiträge sind keine vollständigen Importpakete";
        } else if (!mobileCity.validateNative) {
            skipReason = "nicht angefordert";
        }
        if (skipReason != null) {
            MobileCityValidationRunner.writeSkipped(ctx.artifacts, skipReason);
            ctx.job.stage("MobileCity-Validierung").status = "SKIPPED";
            ctx.job.stage("MobileCity-Validierung").message = skipReason;
            ctx.job.summary.put("mobileCityValidationStatus", "skipped");
            ctx.job.log("MobileCity-Validierung übersprungen (" + skipReason + ")");
            return;
        }

        MobileCityValidationRunner runner =
                new MobileCityValidationRunner(properties.getMobileCityValidation());
        String unavailable = runner.unavailableReason();
        if (unavailable != null) {
            MobileCityValidationRunner.writeSkipped(ctx.artifacts, unavailable);
            ctx.job.stage("MobileCity-Validierung").status = "SKIPPED";
            ctx.job.stage("MobileCity-Validierung").message = unavailable;
            ctx.job.summary.put("mobileCityValidationStatus", "skipped");
            ctx.job.log("MobileCity-Validierung übersprungen (" + unavailable + ")");
            return;
        }
        try {
            MobileCityValidationRunner.Result result = runner.run(ctx.artifacts, ctx.job::log);
            ctx.job.summary.put("mobileCityValidationStatus", "passed");
            ctx.job.summary.put("mobileCityValidationYears", result.years());
            ctx.job.summary.put("mobileCityValidationErrors", result.errors().size());
            ctx.job.summary.put("mobileCityValidationNonFiniteCells", result.nonFiniteCells());
            ctx.job.summary.put("mobileCityValidationReport",
                    "mobilecity-validation/validation-report.json");
            String horizon = result.years().isEmpty() ? "Jahresliste fehlt"
                    : result.years().getFirst() + "–" + result.years().getLast();
            ctx.job.log("Native MobileCity-Prüfung bestanden (" + horizon
                    + ", 0 Fehler, 0 nicht endliche Zellen)");
        } catch (Exception e) {
            ctx.job.summary.put("mobileCityValidationStatus", "failed");
            ctx.job.summary.put("mobileCityValidationReport",
                    "mobilecity-validation/validation-report.json");
            throw e;
        }
    }

    // --- Stufe 11: Manifest ----------------------------------------------------------------

    private void stageManifest(PipelineContext ctx) throws IOException {
        writeManifest(ctx.artifacts, ctx.scenario, ctx.job, ctx.zones.size(), ctx.population,
                ctx.logistics, !ctx.pois.isEmpty(), ctx.populationSource,
                ctx.populationSourceLabel, ctx.totalPersons,
                ctx.logisticsGenerator.sources(), ctx.distributionSource, ctx.matrixSource,
                ctx.roadNetworkSource, ctx.commuterSource, ctx.workplaceMarginSource,
                ctx.osmDataSource, ctx.osmDataSha256, ctx.roadNetworkSha256,
                ctx.populationProvenance);
        Path manifestPath = ctx.artifacts.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(manifestPath.toFile());
        if(ctx.scenario.dataPlan!=null&&ctx.scenario.dataPlan.enabled){
            var sourceReport=manifest.putObject("sources");sourceReport.put("plan","sources/plan.json");sourceReport.put("lock","sources/lock.json");sourceReport.put("resolution","sources/resolution.json");
            SourceRunReport.write(ctx.artifacts, mapper, ctx.scenario, ctx.job, ctx.sourceInputs, null);
            sourceReport.set("decisions",mapper.valueToTree(ctx.sourceInputs.decisions()));
        }
        if (ctx.job.summary.containsKey("energyInfrastructure")) {
            ObjectNode energy = manifest.putObject("energyInfrastructure");
            energy.put("contract", EnergyInfrastructure.CONTRACT);
            energy.put("inventory", "energy/stations.geojson");
            energy.put("quality", "energy/quality.json");
            energy.put("networkAccessRequested", ctx.scenario.energyInfrastructure.verifyNetworkAccess);
            if (ctx.scenario.energyInfrastructure.verifyNetworkAccess) energy.put("networkAccessReport", "energy/network-access.json");
            energy.set("summary", mapper.valueToTree(ctx.job.summary.get("energyInfrastructure")));
        }
        ObjectNode vehicles = manifest.putObject("vehicles");
        vehicles.put("schemaVersion", VehicleConfiguration.CONTRACT);
        vehicles.put("sha256", ctx.vehicles.sha256());
        vehicles.put("application", ctx.scenario.vehicleConfiguration.application());
        vehicles.put("snapshot", "vehicles/resolved.json");
        vehicles.put("compatibility", "vehicles/compatibility.json");
        if (ctx.scenario.vehicleOperations.enabled()) {
            var operations = vehicles.putObject("operations");
            operations.put("contract", de.synthesis.vehicle.VehicleOperationsConfiguration.CONTRACT);
            operations.put("configurationSha256", de.synthesis.vehicle.VehicleJson.hash(ctx.scenario.vehicleOperations));
            operations.put("identities", "vehicles/trip-identities.json"); operations.put("ledger", "vehicles/energy-ledger.json");
            if (ctx.scenario.vehicleOperations.chainTrips()) operations.put("nativeChains", "vehicles/native-chains.json");
            operations.put("executionStatus", "not-run-at-manifest-stage");
        }
        if (ctx.companyTours.active()) {
            var company = manifest.putObject("companyTours");
            company.put("contract", de.synthesis.companytour.CompanyTourExecution.CONTRACT);
            company.put("input", "company-tours/input.json");
            company.put("routing", "company-tours/routing.json");
            company.put("regionalProjection", "company-tours/regional-projection.json");
            company.put("networkPaths", "company-tours/network-paths.json");
            company.put("execution", "company-tours/execution.json");
            company.put("additionalLegs", ctx.companyTours.legs().size());
            company.set("selections", mapper.valueToTree(ctx.companyTours.imports().stream()
                    .map(i -> new de.synthesis.companytour.CompanyTourExecution.Selection(i.importId(), i.executionHash())).toList()));
        }
        VehicleArtifacts.write(manifestPath, manifest);
        updateTrafficManifest(ctx);
    }

    // --- Stufe 12: Simulation logiTopp (Achse B) ----------------------------------------------

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
                    ? "logiTopp-Adapter fehlt oder ist inkompatibel: " + runner.projectDir()
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

    // --- Stufe 13: SUMO (nachgelagerte Verkehrssimulation) ----------------------------------

    /** Mikroskopische Verkehrssimulation der PKW-Trips + logiTopp-Zustelltouren. */
    private void stageSumo(PipelineContext ctx) {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;
        Path artifacts = ctx.artifacts;
        if (!scenario.targets().contains("sumo")) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (Verkehrssimulation nicht ausgewählt)");
            return;
        }
        SumoRunner runner = new SumoRunner(properties.getSumo(), ctx.vehicles.resolved(), scenario.population.seed);
        if (!properties.getSumo().isEnabled() || !runner.isAvailable()) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)");
            return;
        }
        if (ctx.trafficDemand == null || (ctx.trafficDemand.personDemandStatus().equals("not-generated")
                && ctx.trafficDemand.freight().isEmpty() && ctx.trafficDemand.parcels().isEmpty() && !ctx.trafficDemand.companies().active())) {
            job.stage("SUMO (Verkehr)").status = "SKIPPED";
            job.log("SUMO übersprungen (keine vorbereitete Nachfrage verfügbar)");
            return;
        }
        try {
            runner.useSharedRoadRoutes(scenario.population.sharedRoadRoutes).useFreight(scenario).useEnergyAndOperations(scenario);
            String osmXml = roadNetworkOsm(ctx);
            job.log("Baue SUMO-Netz und simuliere Verkehr…");
            SumoRunner.PtProvider ptProvider = buildPtProvider(ctx);
            SumoRunner.Result sumo = runner.run(artifacts, osmXml,
                    TrafficDemandArtifacts.read(artifacts, mapper), ptProvider, job::log);
            writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
            job.log(String.format("SUMO: %d Fahrzeuge (%d PKW, %d Rad, %d Lieferverkehr, %d schwere Güterfahrten) + %d Fuß "
                            + "+ %d ÖV-Fahrgäste, Ø %.1f min / %.1f km / %.0f km/h, CO₂ %.1f kg (%d s)",
                    sumo.vehicles(), sumo.carTrips(), sumo.bikeTrips(), sumo.deliveryTrips(), sumo.heavyFreightTrips(),
                    sumo.walkTrips(), sumo.ptRiders(), sumo.meanDurationSec() / 60,
                    sumo.meanRouteLengthM() / 1000, sumo.meanSpeedMs() * 3.6, sumo.co2Kg(),
                    sumo.durationSeconds()));
            job.summary.put("sumoVehicles", sumo.vehicles());
            job.summary.put("heavyFreightTrips", sumo.heavyFreightTrips());
            job.summary.put("companyTourLegs", sumo.companyTourLegs());
            if (scenario.population.sharedRoadRoutes) {
                var routes = mapper.readTree(artifacts.resolve("sumo/shared-road-routes-summary.json").toFile());
                job.summary.put("sharedRoadRoutesMapped", routes.path("mapped").asInt());
                job.summary.put("sharedRoadRoutesFallback", routes.path("fallback").asInt());
            }
        } catch (Exception e) {
            // FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster Abwahl unterscheiden können
            job.stage("SUMO (Verkehr)").status = "FAILED";
            job.stage("SUMO (Verkehr)").message = String.valueOf(e);
            job.log("SUMO fehlgeschlagen: " + e);
            if (scenario.vehicleConfiguration.appliesToSumo()) {
                try { VehicleArtifacts.write(artifacts.resolve("vehicles/sumo-execution.json"),
                        Map.of("status", "failed", "message", String.valueOf(e))); }
                catch (IOException io) { job.log("Fahrzeug-Ausführungsbericht konnte nicht geschrieben werden: " + io); }
            }
        }
    }

    /**
     * Baut den ÖV-Provider für den SUMO-Lauf (Phase C): sobald das Netz steht, wird der GTFS-Feed
     * beschafft (Auto-Fetch nach Region, Upload-Fallback), zugeschnitten und via gtfs2pt.py in die
     * Fahrplan-Infrastruktur überführt. {@code null} = kein Feed oder venv/gtfs2pt fehlt.
     */
    private SumoRunner.PtProvider buildPtProvider(PipelineContext ctx) {
        GenerationJob job = ctx.job;
        if (!properties.getTransit().isEnabled()) {
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
        Path uploaded = ctx.scenario.population.gtfsUploadPath != null
                && !ctx.scenario.population.gtfsUploadPath.isBlank()
                ? Path.of(ctx.scenario.population.gtfsUploadPath) : null;
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        SumoRunner.PtProvider provider = net -> {
            Files.createDirectories(transitDir);
            GtfsSource.Resolved resolved = gtfs.resolve(ctx.scenario.region, uploaded, rawCache,
                    croppedFeed, job::log);
            if (resolved == null) {
                return null;
            }
            return transit.build(net, resolved.croppedFeed(), date, transitDir, job::log);
        };
        String modes=properties.getTransit().getModes();
        if(modes!=null && java.util.Arrays.stream(modes.split(",",-1)).allMatch(m->"bus".equals(m.trim())))
            return SumoRunner.PtProvider.roadOnly(provider);
        return provider;
    }

    // --- Stufe 14: Netz-Feedback (Nachfrage ↔ Netz) --------------------------------------------

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
            SumoRunner runner = new SumoRunner(properties.getSumo(), ctx.vehicles.resolved(), scenario.population.seed)
                    .useSharedRoadRoutes(scenario.population.sharedRoadRoutes).useFreight(scenario).useEnergyAndOperations(scenario);
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
                publishTrafficDemand(ctx, true);
                updateTrafficManifest(ctx);
                // SUMO auf der neuen Nachfrage – liefert die Faktoren der nächsten Iteration
                // und hält CO₂/Netzlast-Artefakte konsistent zur finalen Nachfrage
                String osmXml = roadNetworkOsm(ctx);
                SumoRunner.Result sumo = runner.run(artifacts, osmXml,
                        TrafficDemandArtifacts.read(artifacts, mapper), null, job::log);
                writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
                job.summary.put("sumoVehicles", sumo.vehicles());
                job.summary.put("heavyFreightTrips", sumo.heavyFreightTrips());
                if (scenario.population.sharedRoadRoutes) {
                    var routes = mapper.readTree(artifacts.resolve("sumo/shared-road-routes-summary.json").toFile());
                    job.summary.put("sharedRoadRoutesMapped", routes.path("mapped").asInt());
                    job.summary.put("sharedRoadRoutesFallback", routes.path("fallback").asInt());
                }
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

    // --- Stufe 15: LLM-Moduswahl (GTA 3c) ------------------------------------------------------

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
        long external = ctx.zones.stream().filter(z -> z.external).count();
        job.summary.put("zonesInternal", ctx.zones.size() - external);
        job.summary.put("zonesExternal", external);
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
    private MatrixGenerator.Impedance buildRouting(PipelineContext ctx) throws IOException {
        if (!ctx.scenario.population.useRealRouting) {
            return null;
        }
        try {
            String osmXml = roadNetworkOsm(ctx);
            RoutingEngine engine = RoutingEngine.fromOsmXml(osmXml,ctx.scenario.population.roadLocatedActivities);
            ctx.job.log("GraphHopper-Graph aufgebaut – Matrizen werden geroutet");
            return engine;
        } catch (Exception e) {
            if(ctx.scenario.population.roadLocatedActivities)throw new IOException("Straßennahe Orte: Straßengraph nicht verfügbar; kein Orts-Fallback",e);
            ctx.job.fallback("Matrizen", "Straßennetz nicht verfügbar, Luftlinienmatrizen: " + e.getMessage());
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
        node.put("heavyFreightTrips", sumo.heavyFreightTrips());
        node.put("companyTourLegs", sumo.companyTourLegs());
        Path companyReport = file.getParent().getParent().resolve("company-tours/execution.json");
        if (Files.exists(companyReport)) node.set("companyTours", mapper.readTree(companyReport.toFile()));
        node.put("ptRiders", sumo.ptRiders());
        node.put("ptVehicles", sumo.ptVehicles());
        node.put("co2Kg", sumo.co2Kg());
        node.set("co2KgByType", mapper.valueToTree(sumo.co2KgByType()));
        node.put("vehicleCountScope", "inserted-including-bicycles");
        node.put("meanStatisticsScope", "motor-vehicle-trip-statistics");
        Path execution=file.resolveSibling("traffic-execution.json");
        if(Files.exists(execution)) {
            var accounting = mapper.readTree(execution.toFile()); node.set("accounting", accounting);
            if (accounting.path("vehicleCountBasis").asText().equals("selected-road-trip-segments"))
                node.put("meanStatisticsScope", "motor-vehicle-journeys-including-duty-stops");
        }
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
        } catch (Error error) {
            info.status = "FAILED";
            info.message = error.toString();
            throw error;
        } finally {
            info.durationMs = System.currentTimeMillis() - start;
            job.persist(scenarios.artifactsDir(job.scenarioId).resolve(GenerationJob.FILE));
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

    private void putSummaryNumber(ObjectNode target, String field, Map<String, Object> summary,
                                  String summaryKey) {
        Object value = summary.get(summaryKey);
        if (value instanceof Number number) {
            target.set(field, mapper.valueToTree(number));
        }
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
        if (scenario.population.useCensusStructure) {
            quality.put("populationStructure", "census-regional-weighting-" + job.summary.get("censusStructureStatus"));
            quality.put("populationStructureReport", "population/census-structure.json");
        }
        quality.put("matrices", matrixSource);
        quality.put("roadNetwork", roadNetworkSource);
        quality.put("syntheticLocations",scenario.population.roadLocatedActivities?"experimental-common-public-roads":"uniform-zone-points-and-anchors");
        if(scenario.population.roadLocatedActivities)quality.put("syntheticLocationsReport",RoadLocations.FILE);
        quality.put("heavyFreight", scenario.freight.enabled ? FreightDemandGenerator.sourceQuality(scenario) : "disabled");
        quality.put("heavyFreightSources", "freight/sources.json");
        quality.put("freightProfileChecks", scenario.freight.profileChecksEnabled ? "standard-full-load-assumption" : "disabled");
        quality.put("freightProfileReport", "freight/route-checks.json");
        quality.put("sevasRestrictions", scenario.freight.sevasEnabled ? "user-uploaded-unverified" : "disabled");
        quality.put("trafficCategories", "sumo/traffic-categories.json");
        quality.put("commuters", commuterSource);
        quality.put("workplaceMargins", workplaceMarginSource);
        quality.put("depots", !scenario.logistics.depots.isEmpty() ? "user-defined"
                : logisticsSources.depotsFromOsm() ? "osm-pois" : "auto-placed");
        quality.put("packstations", logisticsSources.packstationsFromOsm() ? "osm-pois" : "synthetic");

        ObjectNode externalRelations = manifest.putObject("externalRelations");
        externalRelations.put("enabled", scenario.population.externalTrips);
        externalRelations.put("externalZones", scenario.population.externalTrips
                ? ExternalZones.count() : 0);
        externalRelations.put("destinationSource", commuterSource);
        externalRelations.put("workplaceMarginSource", workplaceMarginSource);
        Object candidateSource = job.summary.get("externalDestinationCandidateSource");
        if (candidateSource != null) {
            ObjectNode candidate = externalRelations.putObject("osmDestinationCandidate");
            candidate.put("status", "diagnostic-only");
            candidate.put("source", candidateSource.toString());
            candidate.put("artifact", "population/"
                    + OsmExternalDestinationCandidates.NAME);
            putSummaryNumber(candidate, "count", job.summary,
                    "externalDestinationCandidateCount");
            Object candidateSha = job.summary.get("externalDestinationCandidateSha256");
            if (candidateSha != null) {
                candidate.put("sha256", candidateSha.toString());
            }
            putSummaryNumber(candidate, "directionShareMae", job.summary,
                    "externalDestinationDirectionShareMae");
            putSummaryNumber(candidate, "meanDistanceRelativeError", job.summary,
                    "externalDestinationMeanDistanceRelativeError");
            putSummaryNumber(candidate, "shareBeyond20KmDifference", job.summary,
                    "externalDestinationShareBeyond20KmDifference");
        }
        externalRelations.put("externalWorkDestinations", population.externalWorkDestinations());
        externalRelations.put("externalWorkShareActual", population.persons().isEmpty() ? 0
                : (double) population.externalWorkDestinations() / population.persons().size());
        Object targetShare = job.summary.get("externalWorkShareTarget");
        if (targetShare instanceof Number number) {
            externalRelations.put("externalWorkShareTarget", number.doubleValue());
        }
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
