package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.ActitoppRunner;
import de.synthesis.generation.pipeline.GtaBehaviorRunner;
import de.synthesis.generation.pipeline.ActivityGenerator;
import de.synthesis.generation.pipeline.AttractivityCalculator;
import de.synthesis.generation.pipeline.CsvArtifacts;
import de.synthesis.generation.pipeline.ExternalZones;
import de.synthesis.generation.pipeline.GeoJson;
import de.synthesis.generation.pipeline.GridZoning;
import de.synthesis.generation.pipeline.LegacyExportGenerator;
import de.synthesis.generation.pipeline.LogisticsGenerator;
import de.synthesis.generation.pipeline.MatrixGenerator;
import de.synthesis.generation.pipeline.MobileCityExporter;
import de.synthesis.generation.pipeline.ModeChoiceCalibrator;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.generation.pipeline.PopulationEstimator;
import de.synthesis.generation.pipeline.PopulationGenerator;
import de.synthesis.generation.pipeline.PopulationGridParser;
import de.synthesis.generation.pipeline.RoutingEngine;
import de.synthesis.generation.pipeline.SimulationResultIngestor;
import de.synthesis.generation.pipeline.SimulationRunner;
import de.synthesis.generation.pipeline.VisumMatrixWriter;
import de.synthesis.generation.pipeline.LlmModeChoiceRunner;
import de.synthesis.generation.pipeline.SumoRunner;
import de.synthesis.generation.pipeline.Zone;
import de.synthesis.scenario.Scenario;
import de.synthesis.scenario.ScenarioRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    /** Auto-raises the cell size to the finest resolution within the zone limit. */
    public ZonePreview previewZones(Scenario.Region region, double cellSizeMeters) {
        double effective = GridZoning.autoCellSize(region, cellSizeMeters, properties.getMaxZones());
        return new ZonePreview(
                GridZoning.generate(region, effective, properties.getMaxZones()),
                effective, effective != cellSizeMeters);
    }

    public record RegionEstimate(long estimatedPopulation, String source,
                                 List<PlaceInfo> places) {
        public record PlaceInfo(String name, String type, long population) {
        }
    }

    /** Live population estimate for a region from OSM settlement nodes (no persistence). */
    public RegionEstimate estimateRegion(Scenario.Region region) {
        try {
            List<OverpassClient.Place> places = overpass.fetchPlaces(region);
            if (places.isEmpty()) {
                return new RegionEstimate(0, "none", List.of());
            }
            long estimate = PopulationEstimator.estimateForRegion(region, places);
            List<RegionEstimate.PlaceInfo> infos = places.stream()
                    .sorted((a, b) -> Long.compare(b.population(), a.population()))
                    .limit(8)
                    .map(p -> new RegionEstimate.PlaceInfo(p.name(), p.type(), p.population()))
                    .toList();
            return new RegionEstimate(estimate, "osm-places", infos);
        } catch (Exception e) {
            return new RegionEstimate(0, "unavailable", List.of());
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
        String distributionSource;
        int totalPersons;
        PopulationGenerator populationGenerator;
        PopulationGenerator.Result population;
        LogisticsGenerator logisticsGenerator;
        LogisticsGenerator.Result logistics;
        /** Pkw-Reisezeitmatrix (Minuten) aus der Matrizen-Stufe – Basis fürs Netz-Feedback. */
        float[][] carTravelTimeMin;

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
        double effective = GridZoning.autoCellSize(
                scenario.region, scenario.zoning.cellSizeMeters, properties.getMaxZones());
        if (effective != scenario.zoning.cellSizeMeters) {
            job.log("Zellgröße automatisch von " + Math.round(scenario.zoning.cellSizeMeters)
                    + " m auf " + Math.round(effective) + " m erhöht ("
                    + properties.getMaxZones() + "-Zonen-Limit)");
        }
        List<Zone> result = GridZoning.generate(scenario.region, effective, properties.getMaxZones());
        job.summary.put("cellSizeMeters", Math.round(effective));
        job.log(result.size() + " Zonen erzeugt (" + Math.round(effective) + " m Raster)");
        return result;
    }

    // --- Stufe 2: OSM-Daten -------------------------------------------------------

    /** POIs + Siedlungsdaten (optional, mit Fallback). Befüllt ctx.pois/ctx.places. */
    private void stageOsmData(PipelineContext ctx) {
        GenerationJob job = ctx.job;
        if (!ctx.scenario.population.useOsmData) {
            job.stage("OSM-Daten (POIs)").status = "SKIPPED";
            job.log("OSM-Abruf deaktiviert, nutze synthetische Daten");
            return;
        }
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
    }

    // --- Zwischenschritt: Einwohnerzahl + räumliche Verteilung --------------------

    /**
     * Bestimmt Gesamteinwohner (Nutzerwert > Upload-Raster > OSM-Schätzung > Dichte) und
     * verteilt sie auf die Zonen. Befüllt ctx.populationGenerator/totalPersons/
     * populationSource/distributionSource. Kein eigener Pipeline-Schritt (Logs erscheinen
     * zwischen den Stufen), muss aber vor den Attraktivitäten laufen (Private_* hängen davon ab).
     */
    private void preparePopulationDistribution(PipelineContext ctx) throws IOException {
        Scenario scenario = ctx.scenario;
        GenerationJob job = ctx.job;

        // Hochgeladenes Bevölkerungsraster (Zensus o. ä.) hat Vorrang vor allen anderen Quellen
        double[] uploadWeights = null;
        long uploadPopulation = 0;
        Path gridFile = scenarios.uploadsDir(scenario.id).resolve("population-grid.csv");
        if (Files.exists(gridFile)) {
            PopulationGridParser.ParseResult grid = PopulationGridParser.parse(gridFile);
            uploadWeights = new double[ctx.zones.size()];
            for (PopulationGridParser.GridPoint point : grid.points()) {
                int index = GridZoning.zoneIndexOf(ctx.zones, point.lat(), point.lon());
                if (index >= 0) {
                    uploadWeights[index] += point.population();
                    uploadPopulation += Math.round(point.population());
                }
            }
            if (uploadPopulation > 0) {
                job.log("Bevölkerungsraster-Upload (" + grid.format() + "): " + uploadPopulation
                        + " Einwohner in der Region");
            } else {
                uploadWeights = null;
                job.log("Bevölkerungsraster-Upload enthält keine Punkte in der Region – wird ignoriert");
            }
        }

        if (scenario.population.totalPersons != null) {
            ctx.totalPersons = scenario.population.totalPersons;
            ctx.populationSource = "user-defined";
        } else if (uploadWeights != null) {
            ctx.totalPersons = (int) uploadPopulation;
            ctx.populationSource = "upload-grid";
        } else if (!ctx.places.isEmpty()) {
            ctx.totalPersons = (int) PopulationEstimator.estimateForRegion(scenario.region, ctx.places);
            ctx.populationSource = "osm-places-estimate";
            job.log("Einwohner aus OSM-Siedlungsdaten geschätzt: " + ctx.totalPersons);
        } else {
            ctx.totalPersons = (int) Math.round(ctx.zones.stream().mapToDouble(z -> z.areaKm2).sum()
                    * scenario.population.personsPerKm2);
            ctx.populationSource = "density-estimate";
            job.log("Einwohner über Dichteannahme geschätzt: " + ctx.totalPersons);
        }
        ctx.populationGenerator = new PopulationGenerator(ctx.zones, ctx.random);
        int[] poiCounts = countPoisPerZone(ctx.zones, ctx.pois);
        double[] weights = uploadWeights != null
                ? uploadWeights
                : ctx.places.isEmpty() ? null : PopulationEstimator.zoneWeights(ctx.zones, ctx.places);
        ctx.distributionSource = uploadWeights != null ? "upload-grid"
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
            job.log(external.size() + " externe Zielzonen (~25 km) für Außenbeziehungen ergänzt");
        }
        Path zoneDir = ctx.artifacts.resolve("zone-repository");
        CsvArtifacts.writeZonesCsv(zoneDir.resolve("zones.csv"), ctx.zones);
        CsvArtifacts.writeAttractivitiesCsv(zoneDir.resolve("attractivities.csv"), ctx.zones);
        Files.writeString(zoneDir.resolve("zones.geojson"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        GeoJson.zonesToFeatureCollection(mapper, ctx.zones)));
        job.log("zones.csv + attractivities.csv geschrieben ("
                + (ctx.pois.isEmpty() ? "synthetisch" : "OSM-basiert") + ")");
    }

    // --- Stufe 4: Bevölkerungssynthese ---------------------------------------------

    /** Personen/Haushalte/Pkw/feste Ziele. Befüllt ctx.population. */
    private PopulationGenerator.Result stagePopulationSynthesis(PipelineContext ctx) throws IOException {
        PopulationGenerator.Result result =
                ctx.populationGenerator.generate(ctx.artifacts.resolve("population"), ctx.scenario);
        ctx.job.log(result.persons().size() + " Personen in " + result.householdCount()
                + " Haushalten, " + result.carCount() + " Pkw");
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
                + "POPinhabitants) unter mobilecity/ geschrieben");
    }

    // --- Stufe 10: Manifest ----------------------------------------------------------------

    private void stageManifest(PipelineContext ctx) throws IOException {
        writeManifest(ctx.artifacts, ctx.scenario, ctx.job, ctx.zones.size(), ctx.population,
                ctx.logistics, !ctx.pois.isEmpty(), ctx.populationSource, ctx.totalPersons,
                ctx.logisticsGenerator.sources(), ctx.distributionSource);
    }

    // --- Stufe 11: Simulation logiTopp (Achse B) ----------------------------------------------

    /** logiTopp-Wochensimulation, optional mit iterativer Modal-Split-Kalibrierung (Phase 1). */
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
        SimulationResultIngestor.Summary summary;
        if (scenario.population.calibrateModeChoice) {
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
            ModeChoiceCalibrator.Result cal = calibrator.calibrate(mnl -> {
                SimulationRunner.Result run = runner.run(scenario, artifacts, job::log, mnl);
                last[0] = new SimulationResultIngestor(mapper)
                        .ingest(run.resultsDir(), artifacts, run.durationSeconds());
                return readModalSplit(artifacts.resolve("simulation").resolve("kpis.json"));
            });
            cal.log().forEach(job::log);
            job.log(String.format("Kalibrierung beendet: %d Iterationen, RMSE %.3f → %.3f",
                    cal.iterations(), cal.startRmse(), cal.finalRmse()));
            job.summary.put("calibrationIterations", cal.iterations());
            job.summary.put("calibrationRmse", Math.round(cal.finalRmse() * 1000) / 1000.0);
            summary = last[0];
        } else {
            SimulationRunner.Result run = runner.run(scenario, artifacts, job::log);
            job.log("Simulation beendet nach " + run.durationSeconds() + " s, werte Ergebnisse aus…");
            summary = new SimulationResultIngestor(mapper)
                    .ingest(run.resultsDir(), artifacts, run.durationSeconds());
        }
        job.log(summary.trips() + " Wege, " + summary.parcels() + " Pakete ("
                + summary.delivered() + " zugestellt), " + summary.tours()
                + " Touren – KPIs unter simulation/");
        job.summary.put("simulatedTrips", summary.trips());
        job.summary.put("simulatedParcels", summary.parcels());
        job.summary.put("deliveredParcels", summary.delivered());
        job.summary.put("simulatedTours", summary.tours());
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
            SumoRunner.Result sumo = runner.run(artifacts, osmXml, demandResult,
                    plannedTours, job::log);
            writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
            job.log(String.format("SUMO: %d Fahrzeuge (davon %d Lieferverkehr), "
                            + "Ø %.1f min / %.1f km / %.0f km/h, CO₂ %.1f kg (%d s)",
                    sumo.vehicles(), sumo.deliveryTrips(), sumo.meanDurationSec() / 60,
                    sumo.meanRouteLengthM() / 1000, sumo.meanSpeedMs() * 3.6,
                    sumo.co2Kg(), sumo.durationSeconds()));
            job.summary.put("sumoVehicles", sumo.vehicles());
        } catch (Exception e) {
            // FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster Abwahl unterscheiden können
            job.stage("SUMO (Verkehr)").status = "FAILED";
            job.stage("SUMO (Verkehr)").message = String.valueOf(e);
            job.log("SUMO fehlgeschlagen: " + e);
        }
    }

    // --- Stufe 13: Netz-Feedback (Nachfrage ↔ Netz) --------------------------------------------

    /**
     * Speist die staubelasteten SUMO-Reisezeiten in die Pkw-Matrix zurück und lässt logiTopp
     * einmal neu entscheiden (Ziel-/Verkehrsmittelwahl reagiert auf Stau). Mechanik: je Zonenpaar
     * das Verhältnis staugewichteter zu freier Routenzeit auf dem SUMO-Netz (Faktor 1–3), angewandt
     * auf die bestehende CAR_TTC-Matrix – die GraphHopper-Basis bleibt erhalten, nur der
     * Stau-Effekt fließt ein. Eine Iteration (kein Gleichgewichts-Looping).
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
            job.log("Berechne Stau-Faktoren je Zonenrelation (frei vs. staugewichtet auf dem SUMO-Netz)…");
            SumoRunner.OdFactors od = runner.computeOdFactors(
                    artifacts.resolve("sumo"), ctx.zones, job::log);
            if (od.routedPairs() == 0) {
                job.stage("Netz-Feedback").status = "SKIPPED";
                job.log("Netz-Feedback übersprungen (keine routbaren Zonenrelationen auf dem SUMO-Netz)");
                return;
            }
            int n = ctx.zones.size();
            float[][] scaled = new float[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    scaled[i][j] = (float) (ctx.carTravelTimeMin[i][j] * od.factors()[i][j]);
                }
            }
            VisumMatrixWriter.write(
                    artifacts.resolve("matrix").resolve("travel_times").resolve("CAR_TTC.mtx.bz2"),
                    ctx.zones, scaled);
            VisumMatrixWriter.write(
                    artifacts.resolve("legacy").resolve("matrices").resolve("travel_times")
                            .resolve("CAR_TTC.mtx"),
                    ctx.zones, scaled);
            job.log(String.format(
                    "Pkw-Matrix mit Stau-Faktoren aktualisiert: %d Relationen, Ø Faktor %.2f, max %.2f",
                    od.routedPairs(), od.meanFactor(), od.maxFactor()));

            Map<String, Double> before = readModalSplit(
                    artifacts.resolve("simulation").resolve("kpis.json"));
            job.log("logiTopp entscheidet neu auf staubelasteten Reisezeiten…");
            SimulationRunner simRunner = new SimulationRunner(properties.getSimulation());
            Path calibrated = artifacts.resolve("simulation").resolve("mode_choice_calibrated.txt");
            SimulationRunner.Result run = Files.exists(calibrated)
                    ? simRunner.run(scenario, artifacts, job::log, calibrated)
                    : simRunner.run(scenario, artifacts, job::log);
            new SimulationResultIngestor(mapper).ingest(run.resultsDir(), artifacts, run.durationSeconds());
            Map<String, Double> after = readModalSplit(
                    artifacts.resolve("simulation").resolve("kpis.json"));
            job.log(String.format(
                    "Netz-Feedback: Pkw %.0f%%→%.0f%%, Fuß %.0f%%→%.0f%%, Rad %.0f%%→%.0f%%, ÖV %.0f%%→%.0f%%",
                    before.getOrDefault("car", 0.0) * 100, after.getOrDefault("car", 0.0) * 100,
                    before.getOrDefault("pedestrian", 0.0) * 100, after.getOrDefault("pedestrian", 0.0) * 100,
                    before.getOrDefault("bike", 0.0) * 100, after.getOrDefault("bike", 0.0) * 100,
                    before.getOrDefault("publicTransport", 0.0) * 100,
                    after.getOrDefault("publicTransport", 0.0) * 100));
            job.summary.put("feedbackMeanFactor", Math.round(od.meanFactor() * 100) / 100.0);
            job.summary.put("feedbackRelations", od.routedPairs());
        } catch (Exception e) {
            job.stage("Netz-Feedback").status = "FAILED";
            job.stage("Netz-Feedback").message = String.valueOf(e);
            job.log("Netz-Feedback fehlgeschlagen: " + e);
        }
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
        job.summary.put("populationDistribution", ctx.distributionSource);
        job.summary.put("persons", ctx.population.persons().size());
        job.summary.put("households", ctx.population.householdCount());
        job.summary.put("cars", ctx.population.carCount());
        job.summary.put("depots", ctx.logistics.depotCount());
        job.summary.put("osmDataUsed", !ctx.pois.isEmpty());
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
        ctx.job.log("Lade OSM-Straßennetz (Overpass)…");
        String osmXml = overpass.fetchRoadNetworkOsm(ctx.scenario.region);
        ctx.roadNetwork.set(osmXml);
        return osmXml;
    }

    private void writeSumoSummary(Path file, SumoRunner.Result sumo) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("vehicles", sumo.vehicles());
        node.put("meanDurationMin", Math.round(sumo.meanDurationSec() / 60 * 10) / 10.0);
        node.put("meanRouteLengthKm", Math.round(sumo.meanRouteLengthM() / 1000 * 100) / 100.0);
        node.put("meanSpeedKmh", Math.round(sumo.meanSpeedMs() * 3.6 * 10) / 10.0);
        node.put("runtimeSeconds", sumo.durationSeconds());
        node.put("carTrips", sumo.carTrips());
        node.put("deliveryTrips", sumo.deliveryTrips());
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
                               boolean osmUsed, String populationSource, int totalPersons,
                               LogisticsGenerator.Sources logisticsSources,
                               String distributionSource) throws IOException {
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
        quality.put("populationStructure", "synthetic");
        quality.put("matrices", "beeline-estimate");
        quality.put("depots", !scenario.logistics.depots.isEmpty() ? "user-defined"
                : logisticsSources.depotsFromOsm() ? "osm-pois" : "auto-placed");
        quality.put("packstations", logisticsSources.packstationsFromOsm() ? "osm-pois" : "synthetic");
        manifest.putObject("populationEstimate").put("totalPersons", totalPersons);

        manifest.putArray("notes")
                .add("Formate validiert (2026-06-10) gegen einen erfolgreichen Short-Term-Lauf des "
                        + "mobiTopp-Tutorials (main-Branch); Details in docs/05-validierung.md.")
                .add("Matrizen liegen im Visum-$V;D3-Textformat (.mtx.bz2) vor; Distanzen in Metern; "
                        + "Distanzmatrizen unter other/ (Tutorial-Layout).")
                .add("Matrizen sind Luftlinien-Schätzungen (Umwegfaktor); für realistische Werte "
                        + "GraphHopper/R5-Routing einsetzen (Roadmap Phase 1).")
                .add("activity.csv nutzt numerische Aktivitätscodes (LegacyActivityType); das "
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
