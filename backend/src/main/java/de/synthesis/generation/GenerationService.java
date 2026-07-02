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

@Service
public class GenerationService {

    public static final List<String> STAGES = List.of(
            "Zonierung", "OSM-Daten (POIs)", "Attraktivitäten", "Bevölkerungssynthese",
            "Aktivitätenpläne", "Matrizen", "Logistik", "Legacy-Export", "MobileCity-Export",
            "Manifest", "Simulation (logiTopp)", "SUMO (Verkehr)", "LLM-Moduswahl");

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

    private void run(Scenario scenario, GenerationJob job) {
        job.state = GenerationJob.State.RUNNING;
        job.startedAt = Instant.now();
        Path artifacts = scenarios.artifactsDir(scenario.id);
        // OSM-Straßennetz wird von Routing (Matrizen) UND SUMO gebraucht – einmal pro Lauf laden
        java.util.concurrent.atomic.AtomicReference<String> roadNetwork = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Random random = new Random(scenario.population.seed);

            // 1. Zonierung (Zellgröße wird bei Bedarf automatisch aufs Zonenlimit angehoben)
            List<Zone> zones = stage(job, "Zonierung", () -> {
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
            });

            // 2. OSM-POIs + Siedlungsdaten (optional, mit Fallback)
            record OsmData(List<OverpassClient.Poi> pois, List<OverpassClient.Place> places) {
            }
            OsmData osm = stage(job, "OSM-Daten (POIs)", () -> {
                if (!scenario.population.useOsmData) {
                    job.stage("OSM-Daten (POIs)").status = "SKIPPED";
                    job.log("OSM-Abruf deaktiviert, nutze synthetische Daten");
                    return new OsmData(List.of(), List.of());
                }
                List<OverpassClient.Poi> pois = List.of();
                List<OverpassClient.Place> places = List.of();
                try {
                    pois = overpass.fetchPois(scenario.region);
                    job.log(pois.size() + " POIs von Overpass geladen");
                } catch (Exception e) {
                    job.stage("OSM-Daten (POIs)").message = "Overpass nicht erreichbar: " + e.getMessage();
                    job.log("Overpass-POIs fehlgeschlagen (" + e.getMessage() + ") – Fallback auf synthetische Daten");
                }
                try {
                    places = overpass.fetchPlaces(scenario.region);
                    job.log(places.size() + " Siedlungen (place-Nodes mit Einwohnerzahl) geladen");
                } catch (Exception e) {
                    job.log("Overpass-Places fehlgeschlagen (" + e.getMessage() + ") – Bevölkerung über Dichte/POIs");
                }
                return new OsmData(pois, places);
            });
            List<OverpassClient.Poi> pois = osm.pois();

            // Hochgeladenes Bevölkerungsraster (Zensus o. ä.) hat Vorrang vor allen anderen Quellen
            double[] uploadWeights = null;
            long uploadPopulation = 0;
            Path gridFile = scenarios.uploadsDir(scenario.id).resolve("population-grid.csv");
            if (Files.exists(gridFile)) {
                PopulationGridParser.ParseResult grid = PopulationGridParser.parse(gridFile);
                uploadWeights = new double[zones.size()];
                for (PopulationGridParser.GridPoint point : grid.points()) {
                    int index = GridZoning.zoneIndexOf(zones, point.lat(), point.lon());
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

            // Bevölkerung verteilen (vor Attraktivitäten, da Private_* davon abhängen)
            String populationSource;
            int totalPersons;
            if (scenario.population.totalPersons != null) {
                totalPersons = scenario.population.totalPersons;
                populationSource = "user-defined";
            } else if (uploadWeights != null) {
                totalPersons = (int) uploadPopulation;
                populationSource = "upload-grid";
            } else if (!osm.places().isEmpty()) {
                totalPersons = (int) PopulationEstimator.estimateForRegion(scenario.region, osm.places());
                populationSource = "osm-places-estimate";
                job.log("Einwohner aus OSM-Siedlungsdaten geschätzt: " + totalPersons);
            } else {
                totalPersons = (int) Math.round(zones.stream().mapToDouble(z -> z.areaKm2).sum()
                        * scenario.population.personsPerKm2);
                populationSource = "density-estimate";
                job.log("Einwohner über Dichteannahme geschätzt: " + totalPersons);
            }
            PopulationGenerator population = new PopulationGenerator(zones, random);
            int[] poiCounts = countPoisPerZone(zones, pois);
            double[] weights = uploadWeights != null
                    ? uploadWeights
                    : osm.places().isEmpty() ? null : PopulationEstimator.zoneWeights(zones, osm.places());
            String distributionSource = uploadWeights != null ? "upload-grid"
                    : !osm.places().isEmpty() ? "osm-places"
                    : !pois.isEmpty() ? "poi-density" : "uniform-noise";
            population.distributePopulation(totalPersons, pois.isEmpty() ? null : poiCounts, weights);

            // 3. Attraktivitäten
            stage(job, "Attraktivitäten", () -> {
                if (pois.isEmpty()) {
                    AttractivityCalculator.synthetic(zones, random);
                } else {
                    AttractivityCalculator.fromPois(zones, pois);
                }
                // Außenbeziehungen: externe Zielzonen anhängen (ohne Einwohner, nur Attraktivität)
                if (scenario.population.externalTrips) {
                    List<Zone> external = ExternalZones.create(zones);
                    zones.addAll(external);
                    job.log(external.size() + " externe Zielzonen (~25 km) für Außenbeziehungen ergänzt");
                }
                Path zoneDir = artifacts.resolve("zone-repository");
                CsvArtifacts.writeZonesCsv(zoneDir.resolve("zones.csv"), zones);
                CsvArtifacts.writeAttractivitiesCsv(zoneDir.resolve("attractivities.csv"), zones);
                Files.writeString(zoneDir.resolve("zones.geojson"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                                GeoJson.zonesToFeatureCollection(mapper, zones)));
                job.log("zones.csv + attractivities.csv geschrieben ("
                        + (pois.isEmpty() ? "synthetisch" : "OSM-basiert") + ")");
                return null;
            });

            // 4. Bevölkerungssynthese
            PopulationGenerator.Result popResult = stage(job, "Bevölkerungssynthese", () -> {
                PopulationGenerator.Result result =
                        population.generate(artifacts.resolve("population"), scenario);
                job.log(result.persons().size() + " Personen in " + result.householdCount()
                        + " Haushalten, " + result.carCount() + " Pkw");
                return result;
            });

            // 5. Aktivitätenpläne – Achse A: Verhaltensquelle (actitopp | gta | template)
            stage(job, "Aktivitätenpläne", () -> {
                Path populationDir = artifacts.resolve("population");
                Path activityCsv = populationDir.resolve("activity.csv");
                ActivityGenerator generator = new ActivityGenerator(random);
                long count = -1;
                String source = "Template";
                String behavior = scenario.population.behaviorSourceEffective();
                if ("gta".equals(behavior)) {
                    GtaBehaviorRunner gta = new GtaBehaviorRunner(
                            properties.getLlm(), scenario.population.seed,
                            Path.of(properties.getDataDir(), "llm-cache"));
                    if (properties.getLlm().isEnabled() && gta.isAvailable()) {
                        try {
                            job.log("Starte GTA-Verhaltensquelle (lokales LLM "
                                    + properties.getLlm().getModel() + ")…");
                            GtaBehaviorRunner.Result gtaResult = gta.run(
                                    populationDir.resolve("person.csv"), job::log);
                            count = generator.generateFromGta(activityCsv, popResult.persons(),
                                    p -> gtaResult.plans().get(p.id()));
                            source = "GTA (" + properties.getLlm().getModel() + ", "
                                    + gtaResult.personas() + " Personas, " + gtaResult.personsCovered()
                                    + "/" + gtaResult.personsTotal() + " abgedeckt)";
                            job.summary.put("gtaPersonas", gtaResult.personas());
                            job.summary.put("gtaCovered", gtaResult.personsCovered());
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
                                    result.scheduleCsv(), popResult.persons(), activityCsv);
                            source = "actiTopp";
                        } catch (Exception e) {
                            job.log("actiTopp fehlgeschlagen (" + e.getMessage() + ") – Fallback Template-Generator");
                            count = generator.generate(activityCsv, popResult.persons());
                        }
                    } else {
                        job.log("actiTopp nicht verfügbar (" + runner.projectDir()
                                + ") – Template-Generator");
                        count = generator.generate(activityCsv, popResult.persons());
                    }
                }
                if (count < 0) {
                    count = generator.generate(activityCsv, popResult.persons());
                }
                job.log(count + " Aktivitäten (Wochenpläne, " + source + ") geschrieben");
                job.summary.put("activitySource", source);
                return null;
            });

            // 6. Matrizen (echtes Routing via GraphHopper, Fallback Luftlinie)
            stage(job, "Matrizen", () -> {
                MatrixGenerator.Impedance routing = buildRouting(scenario, roadNetwork, job);
                try {
                    List<String> files = new MatrixGenerator()
                            .generate(artifacts.resolve("matrix"), zones, routing);
                    job.log(files.size() + " Visum-Matrizen (" + zones.size() + "x" + zones.size()
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
                return null;
            });

            // 7. Logistik
            LogisticsGenerator logisticsGenerator = new LogisticsGenerator(zones, random);
            LogisticsGenerator.Result logistics = stage(job, "Logistik", () -> {
                LogisticsGenerator.Result result = logisticsGenerator
                        .generate(artifacts.resolve("logistics"), scenario, popResult.persons().size(), pois);
                job.log(result.depotCount() + " Verteilzentren"
                        + (logisticsGenerator.sources().depotsFromOsm() ? " (aus OSM)" : "") + ", "
                        + result.packstationCount() + " Packstationen"
                        + (logisticsGenerator.sources().packstationsFromOsm() ? " (aus OSM)" : "")
                        + ", Paketnachfrage-Parameter geschrieben");
                return result;
            });

            // 8. Legacy-Export (mobiTopp 0.3.x / logiTopp-Layout)
            stage(job, "Legacy-Export", () -> {
                int files = new LegacyExportGenerator(zones, random).generate(artifacts);
                job.log(files + " Dateien im Legacy-Layout (mobiTopp 0.3.x/logiTopp) unter legacy/ geschrieben");
                return null;
            });

            // 9. MobileCity-Export – Achse B: Zielmodell (nur wenn gewählt)
            stage(job, "MobileCity-Export", () -> {
                if (!scenario.targets().contains("mobilecity")) {
                    job.stage("MobileCity-Export").status = "SKIPPED";
                    job.log("MobileCity-Export übersprungen (Zielmodell nicht ausgewählt)");
                    return null;
                }
                int files = new MobileCityExporter(zones).export(artifacts);
                job.log(files + " MobileCity-Config-Tabellen (REGdistricts/REGattractivity/"
                        + "POPinhabitants) unter mobilecity/ geschrieben");
                return null;
            });

            // 10. Manifest
            stage(job, "Manifest", () -> {
                writeManifest(artifacts, scenario, job, zones.size(), popResult, logistics,
                        !pois.isEmpty(), populationSource, totalPersons, logisticsGenerator.sources(),
                        distributionSource);
                return null;
            });

            // 10. logiTopp-Wochensimulation – Achse B: Zielmodell (nur wenn gewählt)
            stage(job, "Simulation (logiTopp)", () -> {
                if (!scenario.targets().contains("logitopp")) {
                    job.stage("Simulation (logiTopp)").status = "SKIPPED";
                    job.log("logiTopp-Simulation übersprungen (Zielmodell nicht ausgewählt)");
                    return null;
                }
                SimulationRunner runner = new SimulationRunner(properties.getSimulation());
                if (!properties.getSimulation().isEnabled() || !runner.isAvailable()) {
                    job.stage("Simulation (logiTopp)").status = "SKIPPED";
                    job.log("Simulation übersprungen ("
                            + (properties.getSimulation().isEnabled()
                            ? "Legacy-Projekt nicht gefunden: " + runner.projectDir()
                            : "deaktiviert") + ")");
                    return null;
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
                return null;
            });

            // 11. SUMO – Achse B: mikroskopische Verkehrssimulation der Auto-Trips
            stage(job, "SUMO (Verkehr)", () -> {
                if (!scenario.targets().contains("sumo")) {
                    job.stage("SUMO (Verkehr)").status = "SKIPPED";
                    job.log("SUMO übersprungen (Zielmodell nicht ausgewählt)");
                    return null;
                }
                SumoRunner runner = new SumoRunner(properties.getSumo());
                Path demandResult = artifacts.resolve("simulation").resolve("results")
                        .resolve("demandsimulationResult.csv");
                if (!properties.getSumo().isEnabled() || !runner.isAvailable()) {
                    job.stage("SUMO (Verkehr)").status = "SKIPPED";
                    job.log("SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)");
                    return null;
                }
                if (!Files.exists(demandResult)) {
                    job.stage("SUMO (Verkehr)").status = "SKIPPED";
                    job.log("SUMO übersprungen (benötigt logiTopp-Trips – bitte Zielmodell logiTopp mitwählen)");
                    return null;
                }
                try {
                    String osmXml = roadNetworkOsm(scenario, roadNetwork, job);
                    job.log("Baue SUMO-Netz und simuliere Verkehr…");
                    SumoRunner.Result sumo = runner.run(artifacts, osmXml, demandResult, job::log);
                    writeSumoSummary(artifacts.resolve("sumo").resolve("summary.json"), sumo);
                    job.log(String.format("SUMO: %d Fahrzeuge, Ø %.1f min / %.1f km / %.0f km/h (%d s)",
                            sumo.vehicles(), sumo.meanDurationSec() / 60, sumo.meanRouteLengthM() / 1000,
                            sumo.meanSpeedMs() * 3.6, sumo.durationSeconds()));
                    job.summary.put("sumoVehicles", sumo.vehicles());
                } catch (Exception e) {
                    // FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster Abwahl unterscheiden können
                    job.stage("SUMO (Verkehr)").status = "FAILED";
                    job.stage("SUMO (Verkehr)").message = String.valueOf(e);
                    job.log("SUMO fehlgeschlagen: " + e);
                }
                return null;
            });

            // 12. LLM-Moduswahl – GTA 3c: alternativer Modal-Split aus dem LLM, geerdet in Reisezeiten
            stage(job, "LLM-Moduswahl", () -> {
                if (!scenario.population.llmModeChoice) {
                    job.stage("LLM-Moduswahl").status = "SKIPPED";
                    job.log("LLM-Moduswahl übersprungen (nicht aktiviert)");
                    return null;
                }
                Path demandResult = artifacts.resolve("simulation").resolve("results")
                        .resolve("demandsimulationResult.csv");
                LlmModeChoiceRunner runner = new LlmModeChoiceRunner(
                        properties.getLlm(), scenario.population.seed);
                if (!properties.getLlm().isEnabled() || !runner.isAvailable()) {
                    job.stage("LLM-Moduswahl").status = "SKIPPED";
                    job.log("LLM-Moduswahl übersprungen (LLM nicht erreichbar)");
                    return null;
                }
                if (!Files.exists(demandResult)) {
                    job.stage("LLM-Moduswahl").status = "SKIPPED";
                    job.log("LLM-Moduswahl übersprungen (benötigt logiTopp-Trips)");
                    return null;
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
                return null;
            });

            job.summary.put("zones", zones.size());
            job.summary.put("populationSource", populationSource);
            job.summary.put("populationDistribution", distributionSource);
            job.summary.put("persons", popResult.persons().size());
            job.summary.put("households", popResult.householdCount());
            job.summary.put("cars", popResult.carCount());
            job.summary.put("depots", logistics.depotCount());
            job.summary.put("osmDataUsed", !pois.isEmpty());
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

    /**
     * Builds a GraphHopper routing impedance from the region's OSM road network, or
     * {@code null} when routing is disabled or the network cannot be fetched/built
     * (the matrix generator then falls back to beeline estimates).
     */
    private MatrixGenerator.Impedance buildRouting(Scenario scenario,
                                                   java.util.concurrent.atomic.AtomicReference<String> roadNetwork,
                                                   GenerationJob job) {
        if (!scenario.population.useRealRouting) {
            return null;
        }
        try {
            String osmXml = roadNetworkOsm(scenario, roadNetwork, job);
            RoutingEngine engine = RoutingEngine.fromOsmXml(osmXml);
            job.log("GraphHopper-Graph aufgebaut – Matrizen werden geroutet");
            return engine;
        } catch (Exception e) {
            job.log("Routing nicht verfügbar (" + e.getMessage() + ") – Fallback auf Luftlinie");
            return null;
        }
    }

    /** OSM-Straßennetz einmal pro Lauf laden (Routing + SUMO teilen sich den Abruf). */
    private String roadNetworkOsm(Scenario scenario,
                                  java.util.concurrent.atomic.AtomicReference<String> cache,
                                  GenerationJob job) throws Exception {
        String cached = cache.get();
        if (cached != null) {
            job.log("OSM-Straßennetz aus diesem Lauf wiederverwendet");
            return cached;
        }
        job.log("Lade OSM-Straßennetz (Overpass)…");
        String osmXml = overpass.fetchRoadNetworkOsm(scenario.region);
        cache.set(osmXml);
        return osmXml;
    }

    private void writeSumoSummary(Path file, SumoRunner.Result sumo) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("vehicles", sumo.vehicles());
        node.put("meanDurationMin", Math.round(sumo.meanDurationSec() / 60 * 10) / 10.0);
        node.put("meanRouteLengthKm", Math.round(sumo.meanRouteLengthM() / 1000 * 100) / 100.0);
        node.put("meanSpeedKmh", Math.round(sumo.meanSpeedMs() * 3.6 * 10) / 10.0);
        node.put("runtimeSeconds", sumo.durationSeconds());
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
