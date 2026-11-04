package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.LogitoppDemandAdapter;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.vehicle.SumoVehicleTypes;
import de.synthesis.vehicle.VehicleConfiguration;
import de.synthesis.vehicle.VehicleArtifacts;
import de.synthesis.vehicle.VehicleOperationsConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nachgelagerte SUMO-Verkehrssimulation: baut aus dem OSM-Straßennetz ein SUMO-Netz
 * (netconvert) und speist die Auto-Trips der mobiTopp/logiTopp-Nachfrage
 * (demandsimulationResult.csv) in eine mikroskopische Verkehrssimulation.
 *
 * <p>Der Weg ist bewusst rein nativ (kein Python/sumolib/pyproj): die Trips
 * werden als Geo-Koordinaten (fromLonLat/toLonLat) geschrieben, das Snapping
 * auf Netzkanten übernimmt {@code duarouter --mapmatch.distance}. Erzeugt unter
 * artifacts/sumo/ ein lauffähiges SUMO-Szenario (net/routes/sumocfg) und eine
 * Ergebnis-Zusammenfassung.
 */
public class SumoRunner {

    public record Result(int vehicles, double meanDurationSec, double meanRouteLengthM,
                         double meanSpeedMs, long durationSeconds, int carTrips, int bikeTrips,
                         int walkTrips, int deliveryTrips, int heavyFreightTrips, int companyTourLegs, int ptRiders, int ptVehicles,
                         double co2Kg, Map<String, Double> co2KgByType) {
    }

    /**
     * Liefert – gegeben das frisch gebaute SUMO-Netz – die ÖV-Fahrplan-Infrastruktur
     * (gtfs2pt.py). Wird direkt nach dem Netzbau aufgerufen, weil gtfs2pt das Netz braucht
     * und dessen Ausgabe wiederum in duarouter/sumo einfließt. {@code null} = ohne ÖV.
     */
    @FunctionalInterface
    public interface PtProvider {
        TransitRunner.PtInfrastructure provide(Path netFile) throws Exception;
        /** Opaque providers retain railway signaling unless explicitly declared road-only. */
        default boolean roadVehiclesOnly(){return false;}
        static PtProvider roadOnly(PtProvider provider) {
            java.util.Objects.requireNonNull(provider);
            return new PtProvider() {
                public TransitRunner.PtInfrastructure provide(Path net)throws Exception{return provider.provide(net);}
                public boolean roadVehiclesOnly(){return true;}
            };
        }
    }

    /** Max. Snapping-Distanz (m) von Geo-Koordinate zu Netzkante in duarouter. */
    private static final int MAPMATCH_DISTANCE_M = 300;
    static final String TURNAROUND_OPTION = "--no-turnarounds.except-deadend";

    private final Path binDir;
    private final String sumoHome;
    private final int day;
    private final int maxTrips;
    private final int timeoutMinutes;
    private final SumoVehicleTypes vehicleTypes;
    private boolean sharedRoadRoutes;
    private boolean fullDemand;
    private de.synthesis.scenario.Scenario legacyFreightScenario;
    private boolean profileChecksEnabled;
    private long freightSeed;
    private FreightProfileChecks freightChecks;
    private TrafficEndpointMapper endpointMapper;
    private List<Double> modelRegion = List.of();
    private com.fasterxml.jackson.databind.JsonNode sevasSnapshot;
    private List<FreightDemandGenerator.Trip> freightTrips = List.of();
    private final Map<String, TrafficRoutes.Category> trafficCategories = new java.util.TreeMap<>();

    public SumoRunner useFreight(de.synthesis.scenario.Scenario scenario) {
        FreightDemandGenerator.validate(scenario);
        if (scenario.freight.enabled && scenario.freight.day != day)
            throw new IllegalArgumentException("Güterverkehrstag stimmt nicht mit dem SUMO-Betriebstag überein (" + day + ")");
        sevasSnapshot = scenario.freight.sevasEnabled ? scenario.freight.sevasSnapshot : null;
        if (scenario.freight.sevasEnabled && (sevasSnapshot == null || sevasSnapshot.isNull())) throw new IllegalArgumentException("Bitte SEVAS-Restriktionen hochladen");
        profileChecksEnabled = scenario.freight.profileChecksEnabled;
        freightSeed = scenario.population.seed;
        legacyFreightScenario = scenario;
        freightTrips = List.of();
        fullDemand = scenario.freight.enabled;
        if(scenario.region!=null)modelRegion=List.of(scenario.region.minLon,scenario.region.minLat,scenario.region.maxLon,scenario.region.maxLat);
        return this;
    }


    private final VehicleConfiguration resolvedVehicles;
    private de.synthesis.scenario.Scenario.EnergyInfrastructure energyConfiguration;
    private VehicleContinuity continuity;
    private CompanyTourRun companyTours;
    private int companyTourLegs;
    private VehicleOperationsConfiguration baseOperationsConfiguration = VehicleOperationsConfiguration.empty();
    public SumoRunner useCompanyTours(de.synthesis.companytour.CompanyTourExecution.Batch input) {
        return configureCompanyTours(input,de.synthesis.companytour.CompanyTourRegion.project(input),Map.of());
    }
    private SumoRunner configureCompanyTours(de.synthesis.companytour.CompanyTourExecution.Batch input,de.synthesis.companytour.CompanyTourRegion.Projection projection,Map<String,List<String>> prescribed) {
        companyTours = null; companyTourLegs = 0;
        operationsConfiguration = baseOperationsConfiguration;
        if (!input.active()) return this;
        vehicleTypes.includeCompanyPhysicalVehicles();
        var bindings = new ArrayList<>(operationsConfiguration.enabled() ? operationsConfiguration.bindings() : List.<VehicleOperationsConfiguration.Binding>of());
        var regionalInput=projection.batch();
        regionalInput.legs().forEach(l -> bindings.add(new VehicleOperationsConfiguration.Binding(l.sourceKey(), l.vehicleId())));
        var effective = new VehicleOperationsConfiguration(operationsConfiguration.schemaVersion(), true,
                operationsConfiguration.reserveFraction(), List.copyOf(bindings), true);
        operationsConfiguration = effective;
        continuity = new VehicleContinuity(effective, resolvedVehicles);
        companyTours = new CompanyTourRun(input,projection);companyTours.prescribed(prescribed);continuity.regionalEntries(companyTours.chainEntries());continuity.prescribedRoutes(prescribed); companyTourLegs = companyTours.legCount(); fullDemand = true;
        return this;
    }
    private VehicleOperationsConfiguration operationsConfiguration = VehicleOperationsConfiguration.empty();
    public SumoRunner useEnergyAndOperations(de.synthesis.scenario.Scenario scenario) {
        var errors = VehicleOperationsConfiguration.validate(scenario);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst().path() + ": " + errors.getFirst().message());
        energyConfiguration = scenario.energyInfrastructure;
        operationsConfiguration = scenario.vehicleOperations;
        baseOperationsConfiguration = scenario.vehicleOperations;
        if (scenario.vehicleOperations.enabled()) {
            vehicleTypes.includeCompanyPhysicalVehicles();
            continuity = new VehicleContinuity(scenario.vehicleOperations, resolvedVehicles);
        }
        return this;
    }

    public SumoRunner useSharedRoadRoutes(boolean enabled) {
        sharedRoadRoutes = enabled;
        return this;
    }

    public SumoRunner(SynthesisProperties.Sumo config) {
        this(config, VehicleConfiguration.empty(), 42);
    }

    public SumoRunner(SynthesisProperties.Sumo config, VehicleConfiguration resolvedVehicles, long seed) {
        this.binDir = Path.of(config.getBinDir());
        this.sumoHome = config.getSumoHome();
        this.day = config.getDay();
        this.maxTrips = config.getMaxTrips();
        this.timeoutMinutes = config.getTimeoutMinutes();
        this.resolvedVehicles = resolvedVehicles;
        this.freightSeed = seed;
        this.vehicleTypes = new SumoVehicleTypes(resolvedVehicles, seed);
    }

    public boolean isAvailable() {
        return Files.exists(binDir.resolve("sumo")) && Files.exists(binDir.resolve("netconvert"))
                && Files.exists(binDir.resolve("duarouter")) && Files.isDirectory(Path.of(sumoHome));
    }

    /** Rückwärtskompatibler Einstieg ohne Lieferverkehr. */
    public Result run(Path artifacts, String osmXml, Path demandResult, Consumer<String> log)
            throws IOException, InterruptedException {
        return run(artifacts, osmXml, demandResult, null, null, log);
    }

    /** Einstieg ohne ÖV (nur Fahrzeuge + Fuß). */
    public Result run(Path artifacts, String osmXml, Path demandResult, Path plannedTours,
                      Consumer<String> log) throws IOException, InterruptedException {
        return run(artifacts, osmXml, demandResult, plannedTours, null, log);
    }

    /**
     * @param osmXml       OSM-Straßennetz (Overpass) als XML
     * @param demandResult mobiTopp-Trip-Ergebnis (demandsimulationResult.csv)
     * @param plannedTours logiTopp-Zustelltouren (planned-tours.csv); null = ohne Lieferverkehr
     * @param ptProvider   liefert die ÖV-Infrastruktur zum gebauten Netz; null = ohne ÖV
     */
    public Result run(Path artifacts, String osmXml, Path demandResult, Path plannedTours,
                      PtProvider ptProvider, Consumer<String> log)
            throws IOException, InterruptedException {
        return run(
                artifacts,
                osmXml,
                new LogitoppDemandAdapter().read(demandResult),
                plannedTours,
                ptProvider,
                log);
    }

    /** Führt SUMO mit der bereits normalisierten SyntheSis-Nachfrage aus. */
    public Result run(Path artifacts, String osmXml, TravelDemand demand, Path plannedTours,
                      PtProvider ptProvider, Consumer<String> log)
            throws IOException, InterruptedException {
        if (legacyFreightScenario != null) freightTrips = FreightDemandGenerator.generate(legacyFreightScenario);
        return runPrepared(artifacts, osmXml, demand,
                plannedTours == null || !Files.exists(plannedTours) ? List.of() : de.synthesis.generation.demand.ParcelTourAdapter.read(plannedTours, day),
                ptProvider, log);
    }

    /** Consumes the shared immutable demand. Does not regenerate freight or re-read source plans. */
    public Result run(Path artifacts, String osmXml, de.synthesis.generation.demand.TrafficDemand demand,
                      PtProvider ptProvider, Consumer<String> log) throws IOException, InterruptedException {
        if (!demand.freight().isEmpty() && demand.freightDay() != day)
            throw new IllegalArgumentException("Güterverkehrstag und SUMO-Tag stimmen nicht überein");
        for (var input : demand.companies().imports())
            if (java.time.LocalDate.parse(input.simulationDate()).getDayOfWeek().getValue()-1 != day)
                throw new IllegalArgumentException("Unternehmenstourendatum passt nicht zum SUMO-Wochentag");
        useCompanyTours(demand.companies());
        freightTrips = demand.freight(); fullDemand = !freightTrips.isEmpty() || demand.companies().active();
        return runPrepared(artifacts, osmXml, demand.persons(), demand.parcels().stream().filter(l -> l.day() == day).toList(), ptProvider, log);
    }
    private Result runPrepared(Path artifacts, String osmXml, TravelDemand demand,
                      List<de.synthesis.generation.demand.ParcelTourAdapter.Leg> parcelLegs,
                      PtProvider ptProvider, Consumer<String> log) throws IOException, InterruptedException {
        Path dir = artifacts.resolve("sumo");
        Files.createDirectories(dir);
        Files.deleteIfExists(artifacts.resolve("company-tours/execution.json"));
        Files.deleteIfExists(artifacts.resolve("company-tours/routing.json"));
        Files.deleteIfExists(artifacts.resolve("company-tours/regional-projection.json"));
        Files.deleteIfExists(artifacts.resolve("company-tours/network-paths.json"));
        if (companyTours != null) companyTours.begin(artifacts);
        else Files.deleteIfExists(artifacts.resolve("company-tours/input.json"));
        VehicleContinuity.clear(artifacts);
        continuity = operationsConfiguration.enabled() ? new VehicleContinuity(operationsConfiguration, resolvedVehicles) : null;
        if(continuity!=null && companyTours!=null){continuity.regionalEntries(companyTours.chainEntries());continuity.prescribedRoutes(companyTours.prescribedRoutes());}
        EnergyNetworkAccess.clear(artifacts);
        for (String report : List.of("shared-road-routes.jsonl", "shared-road-routes-summary.json", "shared-road-times.jsonl",
                "road-signals.json", "traffic-routes.geojson", "traffic-categories.json", "network-coverage.json", "traffic-routing.json", "traffic-execution.json", "summary.json", "sumo.log", "trips.xml", "freight-trips.xml", "persons.xml", "routes.rou.xml", "routes.rou.alt.xml", "native-chains.trips.xml", "native-chains.rou.xml", "native-chains.rou.alt.xml", "simulation.rou.xml", "chain-stops.xml", "scenario.sumocfg", "tripinfo.xml", "statistics.xml", "edgedata.xml", "congestion.geojson", "freight-network.geojson", "freight-permissions.json", "sevas-permissions.json"))
            Files.deleteIfExists(dir.resolve(report));
        Files.deleteIfExists(artifacts.resolve("freight/execution.json"));
        Files.deleteIfExists(artifacts.resolve("freight/edge-mapping.json"));
        Files.deleteIfExists(artifacts.resolve("freight/route-checks.json"));
        Files.deleteIfExists(artifacts.resolve("freight/profile-permissions.json"));
        freightChecks = null;
        if (vehicleTypes.active()) VehicleArtifacts.write(artifacts.resolve("vehicles/sumo-execution.json"),
                Map.of("status", "started", "fleetStateSimulated", false));

        Files.writeString(dir.resolve("network.osm"), osmXml, StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        boolean roadVehiclesOnly=ptProvider==null || ptProvider.roadVehiclesOnly();
        var signalPolicy=RoadSignalPolicy.prepare(dir,roadVehiclesOnly);
        // 1. OSM → SUMO-Netz: Auto-/Radkanten + Gehwege/Querungen für die multimodale Simulation
        List<String> netArgs = new ArrayList<>(List.of(binDir.resolve("netconvert").toString(),
                "--osm-files", "network.osm", "-o", "net.net.xml",
                "--geometry.remove", "false",
                "--junctions.join", String.valueOf(!sharedRoadRoutes),
                "--ramps.guess", "--tls.guess-signals", "--tls.discard-simple",
                "--keep-edges.by-vclass", "passenger,bicycle,pedestrian,delivery,truck,trailer", "--remove-edges.isolated",
                TURNAROUND_OPTION, "--sidewalks.guess", "--crossings.guess"));
        netArgs.addAll(signalPolicy.arguments());
        if (sharedRoadRoutes) netArgs.addAll(List.of("--osm.bike-access", "--output.original-names", "--plain-output-prefix",
                "route-geo", "--proj.plain-geo"));
        exec(dir, log, netArgs.toArray(new String[0]));
        boolean roadLightsVerified=signalPolicy.verify(dir.resolve("net.net.xml"));
        log.accept("SUMO-Signalimport: " + (roadVehiclesOnly
                ? "Straßenverkehr ohne Bahnsignalsteuerung; " + (roadLightsVerified ? "duale Straßenampeln nachgewiesen" : "entfernte oder zusammengefasste Ampelknoten prüfpflichtig; siehe sumo/road-signals.json")
                : "Bahnsignalsteuerung für nicht als Straßenverkehr deklarierten ÖV erhalten"));
        OsmFreightPermissions.apply(dir.resolve("network.osm"), dir.resolve("net.net.xml"),
                dir.resolve("freight-permissions.json"));
        if (sevasSnapshot != null) {
            exec(dir, log, binDir.resolve("netconvert").toString(), "-s", "net.net.xml",
                    "--plain-output-prefix", "sevas-geo", "--proj.plain-geo", "true");
            int restrictedSevasEdges = SevasFreightPermissions.apply(sevasSnapshot, dir.resolve("net.net.xml"),
                    dir.resolve("sevas-geo.nod.xml"), dir.resolve("sevas-geo.edg.xml"), dir.resolve("sevas-permissions.json"));
            log.accept("SEVAS: " + restrictedSevasEdges + " gerichtete Kanten für schwere LKW gesperrt; Zuordnung und übersprungene Regeln in sumo/sevas-permissions.json");
            if (restrictedSevasEdges == 0) log.accept("SEVAS: keine anwendbaren Sperren im aktuellen Netz gefunden");
        }

        if (profileChecksEnabled) {
            freightChecks = new FreightProfileChecks(dir.resolve("network.osm"));
            Files.createDirectories(artifacts.resolve("freight"));
            freightChecks.prepare(dir.resolve("net.net.xml"), artifacts.resolve("freight/profile-permissions.json"));
            log.accept("LKW-Profilprüfung: angenommene Standardprofile mit 26/40 t Volllast; unbekannte Regeln bleiben prüfpflichtig");
        }

        // ÖV-Infrastruktur (gtfs2pt) zum frisch gebauten Netz beschaffen – Ergebnis fließt
        // sowohl in die intermodale Personen-Routung als auch in die Simulation ein
        TransitRunner.PtInfrastructure pt = null;
        if (ptProvider != null) {
            try {
                pt = ptProvider.provide(dir.resolve("net.net.xml").toAbsolutePath());
            } catch (IOException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("ÖV-Infrastruktur fehlgeschlagen: " + e.getMessage(), e);
            }
        }

        if(roadVehiclesOnly)RoadSignalPolicy.requireRoadVehicles(pt);

        // 2. Den gemeinsamen Demand-Vertrag genau einmal nach Tag, Modus und Cap selektieren.
        DemandSelection selection = selectDemand(demand, pt != null);
        List<Trip> trips = new ArrayList<>(selection.cars());
        int carTrips = trips.size();
        List<Trip> bikes = selection.bikes();
        int bikeTrips = bikes.size();
        trips.addAll(bikes);
        int deliveryTrips = 0;

        List<Trip> delivery = parcelLegs.stream().filter(l -> !l.vehicleClass().equals("tram")).map(SumoRunner::parcelTrip).toList();
        deliveryTrips = delivery.size(); trips.addAll(delivery);
        trafficCategories.clear();
        // Explicit class-filtered edge IDs avoid snapping freight onto passenger-only edges.
        exec(dir, log, binDir.resolve("netconvert").toString(), "-s", "net.net.xml",
                "--plain-output-prefix", "traffic-geo", "--proj.plain-geo", "true");
        TrafficRoutes trafficRoutes = new TrafficRoutes(dir.resolve("net.net.xml"),
                dir.resolve("traffic-geo.nod.xml"), dir.resolve("traffic-geo.edg.xml"));
        if(companyTours!=null&&companyTours.original().imports().stream().anyMatch(de.synthesis.companytour.CompanyTourExecution.Prepared::networkProjectionRequested)){
            var source=companyTours.original();
            var regional=CompanyTourNetwork.prepare(source,trafficRoutes,dir,artifacts,resolvedVehicles,binDir.resolve("duarouter"),freightSeed,args->exec(dir,log,args));
            configureCompanyTours(source,regional.projection(),regional.regionalEdges());companyTours.begin(artifacts);
        }
        if(companyTours!=null)trips.addAll(companyTours.trips());
        if (energyConfiguration != null && energyConfiguration.enabled && energyConfiguration.verifyNetworkAccess)
            EnergyNetworkAccess.generate(artifacts, dir, trafficRoutes, energyConfiguration, resolvedVehicles, freightSeed,
                    binDir.resolve("duarouter"), args -> exec(dir, log, args));
        endpointMapper = new TrafficEndpointMapper(trafficRoutes);
        writeTripsXml(trips, dir.resolve("trips.xml"), osmXml);
        trafficRoutes.writeNetwork(dir.resolve("freight-network.geojson"));
        if (!freightTrips.isEmpty()) writeFreightXml(freightTrips, trafficRoutes, dir.resolve("freight-trips.xml"));
        if (continuity != null) {
            continuity.annotate(dir.resolve("trips.xml")); continuity.annotate(dir.resolve("freight-trips.xml")); continuity.input(artifacts);
        }
        if (fullDemand) log.accept("Güterverkehr: " + freightTrips.size() + " schwere Fahrten; Personenverkehr ohne SUMO-Trip-Cap");

        // Personen: Fußgänger (Fußweg) und – falls ÖV verfügbar – ÖV-Nachfrage (intermodal:
        // Fußweg → Ride → Fußweg). Beide sind SUMO-<person>, keine <trip>.
        List<Trip> persons = new ArrayList<>(selection.walkers());
        int walkTrips = persons.size();
        int ptRiders = 0;
        if (pt != null) {
            List<Trip> riders = selection.transitRiders();
            ptRiders = riders.size();
            persons.addAll(riders);
        }
        String routeFiles = freightTrips.isEmpty() ? "trips.xml" : "trips.xml,freight-trips.xml";
        if (!persons.isEmpty()) {
            writePersonsXml(persons, dir.resolve("persons.xml"));
            routeFiles += ",persons.xml";
        }
        endpointMapper.write(dir, dir.resolve("network.osm"), fullDemand, modelRegion);
        int ptVehicles = pt != null ? pt.ptVehicles() : 0;
        log.accept(carTrips + " PKW- + " + bikeTrips + " Rad- + " + walkTrips + " Fuß-Trips (Tag "
                + day + (maxTrips < 0 ? ", ohne Mengenlimit) + " : ", max " + maxTrips + " je Modus) + ") + deliveryTrips
                + " Lieferverkehr-Legs + " + ptRiders + " ÖV-Fahrgäste ("
                + ptVehicles + " ÖV-Fahrten) übernommen");
        log.accept("Demand-Bilanz: " + selection.emitted() + " übernommen, "
                + selection.otherDay() + " anderer Tag, "
                + selection.unsupported() + " nicht abbildbar, "
                + selection.capped() + " oberhalb Modus-Cap");

        // 3. Geo-Trips/-Personen auf Netzkanten abbilden + routen (duarouter snappt LonLat nativ);
        // die ÖV-Halte/-Fahrzeuge sind für die intermodale Personen-Routung nötig
        List<String> dua = new ArrayList<>(List.of(binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", routeFiles, "-o", "routes.rou.xml",
                "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log"));
        if (sharedRoadRoutes) dua.addAll(List.of("--keep-route-probability", "1"));
        if (profileChecksEnabled) dua.addAll(List.of("--seed", Long.toString(freightSeed), "--routing-threads", "1"));
        if (pt != null) {
            dua.add("--additional-files");
            dua.add(pt.vtypes().toAbsolutePath() + "," + pt.stops().toAbsolutePath()
                    + "," + pt.vehicles().toAbsolutePath());
        }
        try { exec(dir, log, dua.toArray(new String[0])); }
        catch (IOException failure) {
            try { TrafficExecutionAudit.routing(dir, trafficCategories, false, failure.getMessage()); }
            catch (IOException auditFailure) { failure.addSuppressed(auditFailure); }
            throw failure;
        }
        TrafficExecutionAudit.routing(dir, trafficCategories, false, null);
        if (sharedRoadRoutes) {
            SharedRoadRoutes.verifyWritten(dir);
            var routeSummary = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(dir.resolve("shared-road-routes-summary.json").toFile());
            log.accept("Gemeinsame Straßenrouten: " + routeSummary.path("mapped").asInt()
                    + " geprüft, " + routeSummary.path("fallback").asInt()
                    + " Ersatzrouten; Details: sumo/shared-road-routes-summary.json");
        }

        TrafficExecutionAudit.requireRoutingComplete(dir, fullDemand);
        var routedIds = TrafficRoutes.vehicleIds(dir.resolve("routes.rou.xml"));
        if (freightTrips.stream().anyMatch(t -> !routedIds.contains(t.id())))
            throw new IOException("SUMO-Routing hat schwere Güterfahrten verworfen; siehe sumo/sumo.log");
        NativeVehicleChains nativeChains = continuity != null && continuity.chainsEnabled()
                ? NativeVehicleChains.prepare(artifacts, dir, continuity, trafficRoutes, binDir.resolve("duarouter"), freightSeed, args -> exec(dir, log, args)) : null;
        if (companyTours != null) companyTours.auditRoutes(artifacts, dir, resolvedVehicles);
        if (nativeChains != null) log.accept("SUMO-Fahrzeugketten: explizit zugeordnete Fahrten behalten ein natives Fahrzeug; Haltezeiten und Verspätungen wirken auf Folgefahrten");
        if (freightChecks != null && !freightTrips.isEmpty()) {
            freightChecks.audit(dir.resolve("routes.rou.xml"), dir.resolve("freight-trips.xml"), artifacts.resolve("freight/route-checks.json"));
            log.accept("LKW-Routen geprüft; Status und offene Regeln in freight/route-checks.json");
        }
        // 4. SUMO-Lauf
        // Kantenscharfe Messung (Netzlast): über den ganzen Tag aggregierte edgeData
        Files.writeString(dir.resolve("additional.xml"), """
                <additional>
                  <edgeData id="netzlast" file="edgedata.xml"/>
                </additional>
                """);
        String additionalFiles = "additional.xml";
        if (pt != null) {
            additionalFiles += "," + pt.vtypes().toAbsolutePath() + "," + pt.stops().toAbsolutePath()
                    + "," + pt.vehicles().toAbsolutePath();
        }
        int lastDeparture = Math.max(trips.stream().mapToInt(Trip::departSec).max().orElse(0),
                freightTrips.stream().mapToInt(de.synthesis.generation.pipeline.FreightDemandGenerator.Trip::departSec).max().orElse(0));
        int simulationEnd = simulationEndSeconds(demand, day, lastDeparture);
        log.accept("SUMO-Zeithorizont: " + simulationEnd + " s; Tagesauswahl unverändert, Nachlauf für späte Fahrten");
        Files.writeString(dir.resolve("scenario.sumocfg"), String.format("""
                <configuration>
                  <input><net-file value="net.net.xml"/><route-files value="%s"/>
                    <additional-files value="%s"/></input>
                  <output><tripinfo-output value="tripinfo.xml"/><tripinfo-output.write-unfinished value="true"/><statistic-output value="statistics.xml"/>%s</output>
                  <time><begin value="0"/><end value="%d"/></time>
                  <processing><time-to-teleport value="300"/><ignore-route-errors value="true"/>
                    <device.emissions.probability value="1"/></processing>
                  <report><no-step-log value="true"/><duration-log.statistics value="true"/></report>
                </configuration>
                """, nativeChains == null ? "routes.rou.xml" : nativeChains.simulationRoutes(), additionalFiles,
                nativeChains != null && nativeChains.hasVehicles() ? "<stop-output value=\"chain-stops.xml\"/><stop-output.write-unfinished value=\"true\"/>" : "", simulationEnd));
        exec(dir, log, binDir.resolve("sumo").toString(), "-c", "scenario.sumocfg", "--seed", Long.toString(freightSeed));
        if (nativeChains != null && nativeChains.hasVehicles()) nativeChains.finish();
        if (companyTours != null) companyTours.finish(artifacts);
        TrafficExecutionAudit.execution(dir, trafficCategories, false);
        if (continuity != null) continuity.finish(artifacts, dir);
        writeFreightExecution(artifacts, dir, simulationEnd);
        TrafficExecutionAudit.requireExecutionComplete(dir, fullDemand);
        if (sharedRoadRoutes) SharedRoadRoutes.writeObservedTimes(dir);

        // Kantengeometrien in WGS84 exportieren und mit den Messwerten zur Staukarte verbinden
        exec(dir, log, binDir.resolve("netconvert").toString(),
                "-s", "net.net.xml", "--plain-output-prefix", "plain", "--proj.plain-geo", "true");
        int congestionEdges = writeCongestionGeoJson(dir);
        log.accept(congestionEdges + " befahrene Kanten in sumo/congestion.geojson (Netzlast-Layer)");

        long duration = (System.currentTimeMillis() - start) / 1000;
        Map<String, Double> co2 = parseEmissions(dir.resolve("tripinfo.xml"));
        Result result = parseStatistics(dir.resolve("statistics.xml"), duration, carTrips, bikeTrips,
                walkTrips, deliveryTrips, ptRiders, ptVehicles, co2);
        if (vehicleTypes.active()) VehicleArtifacts.write(artifacts.resolve("vehicles/sumo-execution.json"),
                Map.of("status", "completed", "simulatedTrips", result.vehicles(), "co2KgByType", co2,
                        "nativeVehicleChainsSimulated", nativeChains != null && nativeChains.hasVehicles(),
                        "fleetStateSimulated", false));
        trafficRoutes.write(dir, trafficCategories, freightTrips, ptVehicles);
        return result;
    }

    /** Keep departures on the selected day, but allow planned arrivals plus an hour of clearance. */
    static int simulationEndSeconds(TravelDemand demand, int day, int lastSelectedDeparture) {
        double last = Math.max(0, lastSelectedDeparture);
        for (var trip : demand.trips()) {
            if (trip.plannedDepartureMinute() < day * 1440. || trip.plannedDepartureMinute() >= (day + 1) * 1440.
                    || trip.mode() == TravelDemand.Mode.PASSENGER || trip.mode() == TravelDemand.Mode.OTHER) continue;
            last = Math.max(last, (Math.max(trip.plannedDepartureMinute(), trip.plannedArrivalMinute()) - day * 1440.) * 60);
        }
        if (!Double.isFinite(last) || last > Integer.MAX_VALUE - 3600.)
            throw new IllegalArgumentException("Ungültiger SUMO-Zeithorizont");
        return Math.max(86400, (int) Math.ceil(last) + 3600);
    }

    private void writeFreightExecution(Path artifacts, Path dir, int simulationEnd) throws IOException {
        if (freightTrips.isEmpty()) return;
        Map<String, org.w3c.dom.Element> observed = new HashMap<>();
        XmlRecords.read(dir.resolve("tripinfo.xml"), Set.of("tripinfo"),
                info -> observed.put(info.getAttribute("id"), info));
        List<String> completed = new ArrayList<>(), unfinished = new ArrayList<>(), notDeparted = new ArrayList<>();
        var chains = NativeVehicleChains.observations(artifacts);
        for (var trip : freightTrips) {
            var info = observed.get(trip.id());
            var chain = chains.get(trip.id());
            double depart = chain != null ? chain.depart() : info == null ? -1 : Double.parseDouble(info.getAttribute("depart"));
            double arrival = chain != null ? chain.arrival() : info == null ? -1 : Double.parseDouble(info.getAttribute("arrival"));
            if (depart < 0) notDeparted.add(trip.id());
            else if (arrival < 0 || chain != null && chain.vaporized()) unfinished.add(trip.id());
            else completed.add(trip.id());
        }
        java.nio.file.Files.createDirectories(artifacts.resolve("freight"));
        VehicleArtifacts.write(artifacts.resolve("freight/execution.json"), Map.of(
                "contract", "synthesis-freight-execution-v1", "requested", freightTrips.size(),
                "completed", completed.size(), "unfinishedAtDayEnd", unfinished,
                "notDeparted", notDeparted, "simulationEndSeconds", simulationEnd,
                "teleportsEnabled", true, "nativeVehicleChainsSimulated", !chains.isEmpty(), "fleetStateSimulated", false));
        if (!notDeparted.isEmpty()) throw new IOException("SUMO hat " + notDeparted.size()
                + " schwere Güterfahrten nicht eingesetzt; siehe freight/execution.json");
    }

    // --- Netz-Feedback: OD-Stau-Faktoren -------------------------------------------

    /** Stau-Faktoren je Zonenrelation (staugewichtete / freie Routenzeit, geklemmt auf 1–3). */
    public record OdFactors(double[][] factors, int routedPairs, double meanFactor, double maxFactor) {
    }

    /**
     * Routet alle Zonen-Zentroid-Paare zweimal auf dem SUMO-Netz – einmal frei, einmal
     * gewichtet mit den gemessenen Kantenzeiten (edgedata.xml) – und liefert je Relation
     * das Zeitverhältnis. Externe Zonen liegen außerhalb des Netzes und fallen beim
     * Mapmatch heraus (Faktor bleibt 1). Voraussetzung: {@link #run} lief zuvor im selben
     * Verzeichnis (net.net.xml + edgedata.xml vorhanden).
     */
    public OdFactors computeOdFactors(Path dir, List<Zone> zones, Consumer<String> log)
            throws IOException, InterruptedException {
        int n = zones.size();
        Path odTrips = dir.resolve("od-trips.xml");
        try (Writer writer = Files.newBufferedWriter(odTrips)) {
            writer.write("<routes>\n");
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    writer.write(String.format(
                            "  <trip id=\"od_%d_%d\" depart=\"0\" fromLonLat=\"%s,%s\" toLonLat=\"%s,%s\"/>%n",
                            i, j, zones.get(i).centroidLon, zones.get(i).centroidLat,
                            zones.get(j).centroidLon, zones.get(j).centroidLat));
                }
            }
            writer.write("</routes>\n");
        }
        exec(dir, log, binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", "od-trips.xml", "-o", "od-free.rou.xml",
                "--write-costs", "true", "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log");
        exec(dir, log, binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", "od-trips.xml", "-o", "od-congested.rou.xml",
                "--weight-files", "edgedata.xml", "--weight-attribute", "traveltime",
                "--write-costs", "true", "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log");

        double[][] free = parseOdCosts(dir.resolve("od-free.rou.xml"), n);
        double[][] congested = parseOdCosts(dir.resolve("od-congested.rou.xml"), n);
        double[][] factors = new double[n][n];
        int routed = 0;
        double sum = 0;
        double max = 1;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                factors[i][j] = 1;
                if (free[i][j] > 0.001 && congested[i][j] > 0) {
                    double f = Math.max(1, Math.min(3, congested[i][j] / free[i][j]));
                    factors[i][j] = f;
                    routed++;
                    sum += f;
                    max = Math.max(max, f);
                }
            }
        }
        return new OdFactors(factors, routed, routed > 0 ? sum / routed : 1, max);
    }

    /** rou.xml (--write-costs) → Routenzeit (s) je od_i_j; 0 = nicht routbar. */
    private double[][] parseOdCosts(Path rouFile, int n) throws IOException {
        double[][] costs = new double[n][n];
        int[] current = {-1, -1};
        readLines(rouFile, line -> {
            if (line.contains("<vehicle ")) {
                current[0] = -1;
                String id = attrStr(line, "id");
                if (id != null && id.startsWith("od_")) {
                    String[] parts = id.split("_");
                    if (parts.length == 3) {
                        current[0] = Integer.parseInt(parts[1]);
                        current[1] = Integer.parseInt(parts[2]);
                    }
                }
            } else if (current[0] >= 0 && line.contains("<route ")) {
                double cost = attrD(line, "cost");
                if (cost > 0) {
                    costs[current[0]][current[1]] = cost;
                }
                current[0] = -1;
            }
        });
        return costs;
    }

    // --- Netzlast (edgeData → GeoJSON) -------------------------------------------

    private record EdgeMetric(double speedMs, double timeLossS, double sampledS) {
    }

    /**
     * Verbindet die tages-aggregierten Kantenmesswerte (edgedata.xml) mit den
     * WGS84-Kantengeometrien des plain-geo-Exports (plain.nod/.edg.xml) zu
     * {@code congestion.geojson}: eine LineString-Feature je befahrener Kante mit
     * gemessener Geschwindigkeit, Tempolimit und relSpeed (Stau-Indikator).
     */
    private int writeCongestionGeoJson(Path dir) throws IOException {
        Map<String, EdgeMetric> metrics = new java.util.HashMap<>();
        readLines(dir.resolve("edgedata.xml"), line -> {
            if (line.contains("<edge ") && attrStr(line, "id") != null) {
                double sampled = attrD(line, "sampledSeconds");
                if (sampled >= 1) {
                    metrics.put(attrStr(line, "id"),
                            new EdgeMetric(attrD(line, "speed"), attrD(line, "timeLoss"), sampled));
                }
            }
        });
        Map<String, double[]> nodes = new java.util.HashMap<>();
        readLines(dir.resolve("plain.nod.xml"), line -> {
            if (line.contains("<node ") && attrStr(line, "id") != null) {
                nodes.put(attrStr(line, "id"), new double[]{attrD(line, "x"), attrD(line, "y")});
            }
        });

        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode collection = json.createObjectNode();
        collection.put("type", "FeatureCollection");
        com.fasterxml.jackson.databind.node.ArrayNode features = collection.putArray("features");
        readLines(dir.resolve("plain.edg.xml"), line -> {
            if (!line.contains("<edge ")) {
                return;
            }
            String id = attrStr(line, "id");
            EdgeMetric metric = id == null ? null : metrics.get(id);
            if (metric == null) {
                return; // nur befahrene Kanten in die Staukarte
            }
            com.fasterxml.jackson.databind.node.ArrayNode coords = json.createArrayNode();
            double[] from = nodes.get(attrStr(line, "from"));
            double[] to = nodes.get(attrStr(line, "to"));
            if (from != null) {
                coords.add(json.createArrayNode().add(from[0]).add(from[1]));
            }
            String shape = attrStr(line, "shape");
            if (shape != null) {
                for (String point : shape.split(" ")) {
                    String[] xy = point.split(",");
                    if (xy.length >= 2) {
                        coords.add(json.createArrayNode()
                                .add(Double.parseDouble(xy[0])).add(Double.parseDouble(xy[1])));
                    }
                }
            }
            if (to != null) {
                coords.add(json.createArrayNode().add(to[0]).add(to[1]));
            }
            if (coords.size() < 2) {
                return;
            }
            double limit = line.contains("speed=\"") ? attrD(line, "speed") : 13.89;
            com.fasterxml.jackson.databind.node.ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            com.fasterxml.jackson.databind.node.ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "LineString");
            geometry.set("coordinates", coords);
            com.fasterxml.jackson.databind.node.ObjectNode props = feature.putObject("properties");
            props.put("edge", id);
            props.put("speedKmh", Math.round(metric.speedMs() * 3.6 * 10) / 10.0);
            props.put("limitKmh", Math.round(limit * 3.6 * 10) / 10.0);
            props.put("relSpeed", limit > 0
                    ? Math.round(Math.min(1, metric.speedMs() / limit) * 100) / 100.0 : 1);
            props.put("timeLossS", Math.round(metric.timeLossS()));
            props.put("sampledS", Math.round(metric.sampledS()));
        });
        json.writeValue(dir.resolve("congestion.geojson").toFile(), collection);
        return features.size();
    }

    private static void readLines(Path file, java.util.function.Consumer<String> consumer)
            throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        }
    }

    private static String attrStr(String line, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static double attrD(String line, String name) {
        String value = attrStr(line, name);
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * tripinfo.xml → CO₂ (kg) je Fahrzeugtyp. Das Emissions-Device schreibt je Fahrzeug
     * einen &lt;emissions CO2_abs="…"&gt;-Kindknoten (mg); zeilenweiser Scan merkt sich
     * den vType der umschließenden tripinfo-Zeile.
     */
    private Map<String, Double> parseEmissions(Path tripinfo) throws IOException {
        Map<String, Double> co2 = new java.util.LinkedHashMap<>();
        if (!Files.exists(tripinfo)) {
            return co2;
        }
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var input = Files.newInputStream(tripinfo)) {
            var xml = factory.createXMLStreamReader(input);
            try {
                String currentType = null;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                        if ("tripinfo".equals(xml.getLocalName())) {
                            currentType = xml.getAttributeValue(null, "vType");
                        } else if ("emissions".equals(xml.getLocalName()) && currentType != null) {
                            String value = xml.getAttributeValue(null, "CO2_abs");
                            if (value != null) co2.merge(vehicleTypes.resultName(currentType),
                                    Double.parseDouble(value) / 1_000_000, Double::sum);
                        }
                    } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT
                            && "tripinfo".equals(xml.getLocalName())) currentType = null;
                }
            } finally { xml.close(); }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("SUMO-Emissionsergebnis ist ungültig", e);
        }
        co2.replaceAll((k, v) -> Math.round(v * 100) / 100.0);
        return co2;
    }

    record Trip(int departSec, String type, String fromLon, String fromLat,
                String toLon, String toLat, String sourceTripKey, int serviceSeconds) {
        Trip(int departSec, String type, String fromLon, String fromLat, String toLon, String toLat, String sourceTripKey) {
            this(departSec, type, fromLon, fromLat, toLon, toLat, sourceTripKey, 0);
        }
        Trip(int departSec, String type, String fromLon, String fromLat, String toLon, String toLat) {
            this(departSec, type, fromLon, fromLat, toLon, toLat, null);
        }
    }

    record DemandSelection(
            List<Trip> cars,
            List<Trip> bikes,
            List<Trip> walkers,
            List<Trip> transitRiders,
            int otherDay,
            int unsupported,
            int capped) {

        int emitted() {
            return cars.size() + bikes.size() + walkers.size() + transitRiders.size();
        }
    }

    /**
     * Kanonische Personenwege → SUMO-Nachfrage des konfigurierten Tages.
     *
     * <p>Mitfahrer bleiben explizit ausgeschlossen: ohne Fahrer-Mitfahrer-Verknüpfung
     * würde ihre Übernahme Fahrzeuge doppelt zählen. Ein fehlendes ÖV-Netz wird ebenfalls
     * in {@code unsupported} bilanziert. Überschreitet ein Modus das Limit, wird eine
     * deterministische Quantilstichprobe über seinen Tagesverlauf gezogen.
     */
    DemandSelection selectDemand(TravelDemand demand, boolean publicTransportAvailable) {
        int dayStart = day * 1440;
        int dayEnd = (day + 1) * 1440;
        List<TravelDemand.PersonTrip> carCandidates = new ArrayList<>();
        List<TravelDemand.PersonTrip> bikeCandidates = new ArrayList<>();
        List<TravelDemand.PersonTrip> walkCandidates = new ArrayList<>();
        List<TravelDemand.PersonTrip> transitCandidates = new ArrayList<>();
        int otherDay = 0;
        int unsupported = 0;
        for (TravelDemand.PersonTrip personTrip : demand.trips()) {
            double begin = personTrip.plannedDepartureMinute();
            if (begin < dayStart || begin >= dayEnd) {
                otherDay++;
                continue;
            }
            switch (personTrip.mode()) {
                case CAR -> carCandidates.add(personTrip);
                case BIKE -> bikeCandidates.add(personTrip);
                case PEDESTRIAN -> walkCandidates.add(personTrip);
                case PUBLIC_TRANSPORT -> {
                    if (!publicTransportAvailable) {
                        unsupported++;
                        continue;
                    }
                    transitCandidates.add(personTrip);
                }
                case PASSENGER, OTHER -> {
                    unsupported++;
                    continue;
                }
                default -> throw new IllegalStateException(
                        "Nicht behandelter Demand-Modus " + personTrip.mode());
            }
        }
        List<Trip> cars = temporalSample(carCandidates, "pkw", dayStart);
        List<Trip> bikes = temporalSample(bikeCandidates, "fahrrad", dayStart);
        List<Trip> walkers = temporalSample(walkCandidates, "fuss", dayStart);
        List<Trip> transitRiders = temporalSample(transitCandidates, "oev", dayStart);
        int eligible = carCandidates.size() + bikeCandidates.size()
                + walkCandidates.size() + transitCandidates.size();
        int emitted = cars.size() + bikes.size() + walkers.size() + transitRiders.size();
        int capped = eligible - emitted;
        DemandSelection selection = new DemandSelection(
                cars,
                bikes,
                walkers,
                transitRiders,
                otherDay,
                unsupported,
                capped);
        if (selection.emitted() + selection.otherDay()
                + selection.unsupported() + selection.capped() != demand.tripCount()) {
            throw new IllegalStateException("SUMO-Demand-Selektion verletzt die Mengenbilanz");
        }
        return selection;
    }

    /**
     * Wählt bei einem Cap die Mittelpunkte gleich breiter empirischer Quantile. Dadurch
     * bleibt der Tagesverlauf proportional vertreten, ohne zusätzliche Zufallsquelle.
     * Innerhalb gleicher Abfahrtszeit entkoppelt ein stabiler Weg-Hash die Auswahl von
     * Personen-/Zonenreihenfolgen.
     */
    private List<Trip> temporalSample(
            List<TravelDemand.PersonTrip> candidates, String type, int dayStart) {
        List<TravelDemand.PersonTrip> ordered = candidates.stream()
                .sorted(Comparator.comparingDouble(TravelDemand.PersonTrip::plannedDepartureMinute)
                        .thenComparingLong(SumoRunner::stableTripHash)
                        .thenComparing(TravelDemand.PersonTrip::personOid)
                        .thenComparing(TravelDemand.PersonTrip::tripId))
                .toList();
        int limit = fullDemand || maxTrips < 0 ? ordered.size() : maxTrips;
        List<Trip> selected = new ArrayList<>(Math.min(limit, ordered.size()));
        if (ordered.size() <= limit) {
            for (TravelDemand.PersonTrip trip : ordered) {
                selected.add(toSumoTrip(trip, type, dayStart));
            }
        } else if (limit > 0) {
            for (int i = 0; i < limit; i++) {
                int index = (int) Math.floor((i + 0.5) * ordered.size() / limit);
                selected.add(toSumoTrip(ordered.get(index), type, dayStart));
            }
        }
        return List.copyOf(selected);
    }

    private static Trip toSumoTrip(
            TravelDemand.PersonTrip personTrip, String type, int dayStart) {
        int departSec =
                (int) Math.floor((personTrip.plannedDepartureMinute() - dayStart) * 60);
        return new Trip(
                departSec,
                type,
                Double.toString(personTrip.fromLon()),
                Double.toString(personTrip.fromLat()),
                Double.toString(personTrip.toLon()),
                Double.toString(personTrip.toLat()), VehicleOperationsConfiguration.personKey(personTrip.personOid(), personTrip.tripId()));
    }

    private static long stableTripHash(TravelDemand.PersonTrip trip) {
        long hash = 0xcbf29ce484222325L;
        String value = trip.personOid() + '\0' + trip.tripId();
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Nachfrage-Personen als SUMO-&lt;person&gt; schreiben: Fußgänger laufen einen
     * &lt;personTrip&gt; (Standardmodus Fußweg), ÖV-Fahrgäste (Typ "oev") reisen intermodal
     * ({@code modes="public"}: Fußweg → Ride → Fußweg). duarouter snappt die LonLat-Koordinaten
     * über {@code --mapmatch.distance} und nutzt für den Ride-Teil die ÖV-Halte/-Fahrzeuge.
     */
    private void writePersonsXml(List<Trip> persons, Path out) throws IOException {
        persons.sort(Comparator.comparingInt(Trip::departSec));
        try (Writer writer = Files.newBufferedWriter(out)) {
            writer.write("<routes>\n");
            int id = 0;
            for (Trip t : persons) {
                String personId="p"+id++;
                var mapping=endpointMapper.map(personId,"person-demand","pedestrian",t.fromLon(),t.fromLat(),t.toLon(),t.toLat());
                try {
                    var xml=javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
                    xml.writeStartElement("person");xml.writeAttribute("id",personId);xml.writeAttribute("depart",Integer.toString(t.departSec()));
                    xml.writeEmptyElement("personTrip");
                    if(mapping!=null){xml.writeAttribute("from",mapping.get("from"));xml.writeAttribute("to",mapping.get("to"));}
                    else{xml.writeAttribute("fromLonLat",t.fromLon()+","+t.fromLat());xml.writeAttribute("toLonLat",t.toLon()+","+t.toLat());}
                    if("oev".equals(t.type()))xml.writeAttribute("modes","public");
                    xml.writeEndElement();xml.writeCharacters("\n");xml.flush();
                }catch(javax.xml.stream.XMLStreamException e){throw new IOException(e);}
            }
            writer.write("</routes>\n");
        }
    }

    /**
     * planned-tours.csv → Zustelltouren-Legs des Tages: jede Zeile ist ein Stopp
     * (fromX/Y → toX/Y) mit Fahrzeugtyp (logiTopp-Codes: 1=BIKE→Lastenrad,
     * sonst Lkw; TRAM wird verworfen). Abfahrt ≈ Ankunft (plannedTimeSimMin)
     * minus Fahrzeit (tripDur), beides in Sim-Minuten der Modellwoche.
     *
     * <p>logiTopp berücksichtigt die Rückfahrt intern, schreibt sie aber nicht als
     * letzte Zeile. Pro normaler Tour wird deshalb aus erstem Depot und letztem
     * Stopp ein explizites Abschluss-Leg ergänzt. Bereits als {@code isReturning}
     * markierte Touren und Touren, die am Depot enden, werden nicht doppelt geschlossen.</p>
     */
    List<Trip> readDeliveryLegs(Path plannedTours) throws IOException {
        return de.synthesis.generation.demand.ParcelTourAdapter.read(plannedTours, day).stream()
                .filter(l -> !l.vehicleClass().equals("tram")).map(SumoRunner::parcelTrip).toList();
    }
    private static Trip parcelTrip(de.synthesis.generation.demand.ParcelTourAdapter.Leg l) {
        return new Trip(l.departSec(), l.vehicleClass().equals("bicycle") ? "lastenrad" : "lkw",
                l.fromLon(), l.fromLat(), l.toLon(), l.toLat(), l.sourceKey(), l.serviceSeconds());
    }

    /** Alle Trips (nach Abfahrt sortiert) mit Fahrzeugtypen als trips.xml schreiben. */
    private void writeTripsXml(List<Trip> trips, Path out, String osmXml) throws IOException {
        trips.sort(Comparator.comparingInt(Trip::departSec));
        vehicleTypes.beginExport();
        try (SharedRoadRoutes shared = sharedRoadRoutes ? new SharedRoadRoutes(out.getParent(), osmXml) : null;
             Writer writer = Files.newBufferedWriter(out)) {
            writer.write("<routes>\n");
            // HBEFA3-Emissionsklassen: Benzin-Pkw EU4, Diesel-Lieferwagen EU4, Rad/Lastenrad emissionsfrei
            writer.write("  <vType id=\"pkw\" vClass=\"passenger\" emissionClass=\"HBEFA3/PC_G_EU4\"/>\n");
            writer.write("  <vType id=\"fahrrad\" vClass=\"bicycle\" emissionClass=\"Zero\"/>\n");
            writer.write("  <vType id=\"lkw\" vClass=\"delivery\" emissionClass=\"HBEFA3/LDV_D_EU4\"/>\n");
            writer.write("  <vType id=\"lastenrad\" vClass=\"bicycle\" emissionClass=\"Zero\"/>\n");
            vehicleTypes.writeDefinitions(writer);
            int id = 0;
            for (Trip t : trips) {
                var company = companyTours == null ? null : companyTours.leg(t.sourceTripKey());
                String type = vehicleTypes.select(t.type(), t.departSec() + "|" + t.fromLon() + "|"
                        + t.fromLat() + "|" + t.toLon() + "|" + t.toLat(), continuity == null ? null : continuity.typeFor(t.sourceTripKey()));
                if (continuity != null) continuity.register("v" + id, t.sourceTripKey(), type, t.departSec(), t.fromLon(), t.fromLat(), t.toLon(), t.toLat(), t.serviceSeconds());
                boolean logistics = company != null || "lkw".equals(t.type()) || "lastenrad".equals(t.type());
                String source = company != null ? "company-tours" : logistics ? "logitopp-parcels" : "person-demand";
                trafficCategories.put("v" + id, new TrafficRoutes.Category(logistics ? "logistics" : "private",
                        source, type, ""));
                if (company != null) companyTours.register(t.sourceTripKey(), "v" + id);
                var fixed=companyTours==null?null:companyTours.prescribed(t.sourceTripKey());
                var mapping=endpointMapper.map("v"+id,source,vehicleTypes.vehicleClass(type),
                        t.fromLon(),t.fromLat(),t.toLon(),t.toLat(),fixed==null?null:fixed.getFirst(),fixed==null?null:fixed.getLast());
                if (shared != null && !(continuity != null && continuity.chainsEnabled() && continuity.typeFor(t.sourceTripKey()) != null)) {
                    String explicit = shared.vehicle("v" + id, type, vehicleTypes.vehicleClass(type), t);
                    if (explicit != null) { writer.write(explicit); id++; continue; }
                }
                try {
                    var xml=javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
                    xml.writeEmptyElement("trip");xml.writeAttribute("id","v"+id++);xml.writeAttribute("type",type);xml.writeAttribute("depart",Integer.toString(t.departSec()));
                    if(mapping!=null){xml.writeAttribute("from",mapping.get("from"));xml.writeAttribute("to",mapping.get("to"));}
                    else{xml.writeAttribute("fromLonLat",t.fromLon()+","+t.fromLat());xml.writeAttribute("toLonLat",t.toLon()+","+t.toLat());}
                    if(fixed!=null&&fixed.size()>2)xml.writeAttribute("via",String.join(" ",fixed.subList(1,fixed.size()-1)));
                    xml.writeCharacters("\n");xml.flush();
                }catch(javax.xml.stream.XMLStreamException e){throw new IOException(e);}
            }
            writer.write("</routes>\n");
        }
        vehicleTypes.writeUsage(out.getParent().getParent().resolve("vehicles/sumo-types.json"));
    }

    private void writeFreightXml(List<FreightDemandGenerator.Trip> trips, TrafficRoutes routes, Path file) throws IOException {
        Map<String, Map<String, String>> mappings = new java.util.TreeMap<>();
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("<routes>\n");
            if (freightChecks == null) {
            writer.write("<vType id=\"heavyTruck\" vClass=\"truck\" length=\"10\" maxSpeed=\"22.22\" accel=\"1.0\" decel=\"4\" minGap=\"2.5\" tau=\"1.5\" emissionClass=\"HBEFA3/HDV_D_EU6\"/>\n");
            writer.write("<vType id=\"articulatedTruck\" vClass=\"trailer\" length=\"16.5\" maxSpeed=\"22.22\" accel=\"0.8\" decel=\"4\" minGap=\"2.5\" tau=\"1.5\" emissionClass=\"HBEFA3/HDV_D_EU6\"/>\n");
            } else {
                for (String clazz : List.of("truck", "trailer")) {
                    var p = freightChecks.profile(clazz);
                    String type = clazz.equals("truck") ? "heavyTruck" : "articulatedTruck";
                    writer.write("<vType id=\"" + type + "\" vClass=\"" + clazz + "\" length=\"" + p.lengthM()
                            + "\" width=\"" + p.widthM() + "\" height=\"" + p.heightM() + "\" mass=\"" + p.actualMassKg()
                            + "\" maxSpeed=\"" + p.maxSpeedKmh()/3.6 + "\" accel=\"1.0\" decel=\"4\" minGap=\"2.5\" tau=\"1.5\" emissionClass=\"HBEFA3/HDV_D_EU6\"/>\n");
                }
            }
            for (var trip : trips) {
                String legacyType = trip.vehicleClass().equals("truck") ? "heavyTruck" : "articulatedTruck";
                String sourceKey = VehicleOperationsConfiguration.freightKey(trip.id());
                String type = vehicleTypes.select(legacyType, trip.id(), continuity == null ? null : continuity.typeFor(sourceKey));
                if (continuity != null) continuity.register(trip.id(), sourceKey, type, trip.departSec(),
                        Double.toString(trip.fromLon()), Double.toString(trip.fromLat()), Double.toString(trip.toLon()), Double.toString(trip.toLat()));
                String clazz = vehicleTypes.vehicleClass(type);
                String key = trip.relationId() + ":" + clazz;
                var mapping = mappings.get(key);
                if (mapping == null) {
                    mapping = Map.of("relationId", trip.relationId(), "vehicleClass", clazz,
                            "fromEdge", routes.resolve(trip.fromEdge(), trip.fromLon(), trip.fromLat(), clazz),
                            "toEdge", routes.resolve(trip.toEdge(), trip.toLon(), trip.toLat(), clazz),
                            "method", trip.fromEdge() == null ? "nearest-permitted-coordinate" : "explicit-directed-edges");
                    mappings.put(key, mapping);
                }
                String from = mapping.get("fromEdge"), to = mapping.get("toEdge");
                endpointMapper.map(trip.id(),"heavy-freight",clazz,Double.toString(trip.fromLon()),Double.toString(trip.fromLat()),
                        Double.toString(trip.toLon()),Double.toString(trip.toLat()),from,to);
                // Edge IDs may contain XML-special characters; use the XML writer.
                try {
                    var xml = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
                    xml.writeEmptyElement("trip"); xml.writeAttribute("id", trip.id());
                    xml.writeAttribute("type", type); xml.writeAttribute("depart", Integer.toString(trip.departSec()));
                    xml.writeAttribute("from", from); xml.writeAttribute("to", to); xml.writeCharacters("\n"); xml.flush();
                } catch (javax.xml.stream.XMLStreamException e) { throw new IOException(e); }
                trafficCategories.put(trip.id(), new TrafficRoutes.Category("logistics", "heavy-freight", type, trip.movement()));
            }
            writer.write("</routes>\n");
        }
        VehicleArtifacts.write(file.getParent().getParent().resolve("freight/edge-mapping.json"), Map.of(
                "contract", "synthesis-freight-edge-mapping-v1", "relations", mappings.values()));
        vehicleTypes.writeUsage(file.getParent().getParent().resolve("vehicles/sumo-types.json"));
    }

    Result parseStatistics(Path statistics, long durationSeconds, int carTrips, int bikeTrips,
                           int walkTrips, int deliveryTrips, int ptRiders, int ptVehicles,
                           Map<String, Double> co2ByType) throws IOException {
        double co2Total = Math.round(co2ByType.values().stream().mapToDouble(Double::doubleValue).sum() * 100) / 100.0;
        Map<String,org.w3c.dom.Element> tags=new HashMap<>();
        if(Files.exists(statistics)) XmlRecords.read(statistics,Set.of("vehicles","vehicleTripStatistics","bikeTripStatistics"),e->tags.put(e.getTagName(),e));
        var motor=tags.get("vehicleTripStatistics");var bike=tags.get("bikeTripStatistics");var vehicles=tags.get("vehicles");
        int total=vehicles!=null && vehicles.hasAttribute("inserted")?(int)statistic(vehicles,"inserted")
                :(int)(statistic(motor,"count")+statistic(bike,"count"));
        return new Result(total,statistic(motor,"duration"),statistic(motor,"routeLength"),statistic(motor,"speed"),
                durationSeconds,carTrips,bikeTrips,walkTrips,deliveryTrips,freightTrips.size(),companyTourLegs,ptRiders,ptVehicles,co2Total,co2ByType);
    }
    private static double statistic(org.w3c.dom.Element tag,String key) {
        return tag==null || tag.getAttribute(key).isBlank()?0:Double.parseDouble(tag.getAttribute(key));
    }

    /** Liest ein numerisches Attribut aus einem XML-Element (Reihenfolge-unabhängig). */
    private static double attr(String element, String name) {
        Matcher m = Pattern.compile(name + "=\"([\\d.eE+-]+)\"").matcher(element);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // --- Prozess-Helfer --------------------------------------------------------

    private void exec(Path workDir, Consumer<String> log, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.environment().put("SUMO_HOME", sumoHome);
        builder.redirectErrorStream(true);
        Path logFile = workDir.resolve("sumo.log");
        builder.redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(command[0] + " nach " + timeoutMinutes + " min abgebrochen (Timeout)");
        }
        if (process.exitValue() != 0) {
            throw new IOException(Path.of(command[0]).getFileName() + " fehlgeschlagen (Exit "
                    + process.exitValue() + "): " + lastLines(logFile));
        }
    }

    private static String lastLines(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 4), lines.size()));
        } catch (IOException e) {
            return "(Log nicht lesbar)";
        }
    }
}
