package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.*;
import de.synthesis.vehicle.VehicleConfiguration.VehicleInstance;
import de.synthesis.vehicle.VehicleType.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Physical identity and a declared-consumption ledger across native trip segments. No charging/dispatch. */
public final class VehicleContinuity {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    public static final String CONTRACT = "synthesis-vehicle-continuity-v1";
    private static final List<String> FILES = List.of("operations-input.json", "trip-identities.json", "energy-ledger.json", "native-chains.json");
    private final VehicleOperationsConfiguration config;
    private final VehicleConfiguration vehicles;
    private final Map<String, VehicleInstance> instances = new TreeMap<>();
    private final Map<String, VehicleType> types = new TreeMap<>();
    private final Map<String, String> bindings = new TreeMap<>();
    private final Map<String, Segment> segments = new LinkedHashMap<>();
    private Map<String,List<String>> prescribedRoutes=Map.of();
    void prescribedRoutes(Map<String,List<String>> paths){prescribedRoutes=Map.copyOf(paths);}
    List<String> prescribedRoute(String key){return prescribedRoutes.get(key);}
    private Set<String> regionalEntries = Set.of();
    void regionalEntries(Set<String> keys){regionalEntries=Set.copyOf(keys);}
    boolean regionalEntry(String key){return regionalEntries.contains(key);}
    private final Set<String> sourceKeys = new HashSet<>();
    record Segment(String nativeId, String sourceTripKey, String vehicleId, String typeId,
                   int plannedDepartureSeconds, double fromLon, double fromLat, double toLon, double toLat, int serviceSeconds) { }
    record Observed(double depart, double arrival, Double distanceKm, boolean vaporized) { }

    VehicleContinuity(VehicleOperationsConfiguration config, VehicleConfiguration vehicles) {
        this.config = config; this.vehicles = vehicles;
        for (var instance : vehicles.initialVehicles()) instances.put(instance.vehicleId(), instance);
        for (var type : vehicles.vehicleTypes()) types.put(type.key(), type);
        for (var binding : config.bindings()) bindings.put(binding.sourceTripKey(), binding.vehicleId());
    }
    public static void clear(Path artifacts) throws IOException {
        for (String file : FILES) Files.deleteIfExists(artifacts.resolve("vehicles").resolve(file));
        for (String file : List.of("native-chains.trips.xml", "native-chains.rou.xml", "native-chains.rou.alt.xml", "simulation.rou.xml", "chain-stops.xml"))
            Files.deleteIfExists(artifacts.resolve("sumo").resolve(file));
    }
    TypeRef typeFor(String sourceKey) {
        String id = sourceKey == null ? null : bindings.get(sourceKey);
        var instance = id == null ? null : instances.get(id); return instance == null ? null : instance.typeRef();
    }
    boolean chainsEnabled() { return config.chainTrips(); }
    List<Segment> selectedSegments() { return List.copyOf(segments.values()); }
    VehicleConfiguration vehicleConfiguration() { return vehicles; }
    VehicleOperationsConfiguration operationsConfiguration() { return config; }
    void register(String nativeId, String sourceKey, String typeId, int depart,
                  String fromLon, String fromLat, String toLon, String toLat) throws IOException {
        register(nativeId, sourceKey, typeId, depart, fromLon, fromLat, toLon, toLat, 0);
    }
    void register(String nativeId, String sourceKey, String typeId, int depart,
                  String fromLon, String fromLat, String toLon, String toLat, int serviceSeconds) throws IOException {
        if (sourceKey != null && !sourceKeys.add(sourceKey)) throw new IOException("Quell-Fahrt mehrfach exportiert: " + sourceKey);
        if (segments.putIfAbsent(nativeId, new Segment(nativeId, sourceKey, sourceKey == null ? null : bindings.get(sourceKey), typeId, depart,
                Double.parseDouble(fromLon), Double.parseDouble(fromLat), Double.parseDouble(toLon), Double.parseDouble(toLat), serviceSeconds)) != null)
            throw new IOException("Doppelte native Fahrzeug-ID: " + nativeId);
    }
    void annotate(Path file) throws IOException {
        if (!Files.exists(file)) return;
        XmlRecords.rewrite(file, Set.of("trip", "vehicle"), element -> {
            var segment = segments.get(element.getAttribute("id")); if (segment == null) return;
            for (var entry : Map.of("synthesis.sourceTripKey", Objects.toString(segment.sourceTripKey(), "unavailable"),
                    "synthesis.physicalVehicleId", Objects.toString(segment.vehicleId(), "unassigned")).entrySet()) {
                var param = element.getOwnerDocument().createElementNS(null, "param");
                param.setAttribute("key", entry.getKey()); param.setAttribute("value", entry.getValue()); element.appendChild(param);
            }
        });
    }
    void input(Path artifacts) throws IOException {
        VehicleArtifacts.write(artifacts.resolve("vehicles/operations-input.json"), Map.of(
                "contract", VehicleOperationsConfiguration.CONTRACT, "configuration", config,
                "configurationSha256", VehicleJson.hash(config), "vehicleConfigurationSha256", VehicleJson.hash(vehicles)));
        VehicleArtifacts.write(artifacts.resolve("vehicles/trip-identities.json"), Map.of("contract", CONTRACT,
                "dayScope", "selected SUMO day and mode caps", "segments", segments.values(),
                "keyEncoding", "person:<base64url(personOid)>:<base64url(tripId)>; parcel:<base64url(tourId)>:<source-row-ordinal|return>; freight:<base64url(tripId)>"));
        VehicleArtifacts.write(artifacts.resolve("vehicles/energy-ledger.json"), Map.of("contract", CONTRACT,
                "status", "pending", "configurationSha256", VehicleJson.hash(config), "vehicleConfigurationSha256", VehicleJson.hash(vehicles), "energyConstrainsTraffic", false, "chargingSimulated", false));
    }
    void finish(Path artifacts, Path sumoDir) throws IOException {
        Map<String, Observed> observed = new HashMap<>();
        XmlRecords.read(sumoDir.resolve("tripinfo.xml"), Set.of("tripinfo"), element -> {
            String id = element.getAttribute("id"); if (!segments.containsKey(id)) return;
            var info = new Observed(number(element.getAttribute("depart")), number(element.getAttribute("arrival")),
                    number(element.getAttribute("routeLength")) / 1000, TrafficExecutionAudit.vaporized(element));
            if (observed.putIfAbsent(id, info) != null) throw new IOException("Doppelte Fahrtbeobachtung: " + id);
        });
        var nativeChains = NativeVehicleChains.observations(artifacts);
        for (var entry : nativeChains.entrySet()) {
            var o = entry.getValue(); observed.put(entry.getKey(), new Observed(o.depart(), o.arrival(), null, o.vaporized() || o.teleports() > 0));
        }
        Set<String> teleports = new HashSet<>(); var pattern = Pattern.compile("Teleporting vehicle '([^']+)'");
        if (Files.exists(sumoDir.resolve("sumo.log"))) try (var lines = Files.lines(sumoDir.resolve("sumo.log"))) {
            lines.forEach(line -> { var match = pattern.matcher(line); if (match.find()) teleports.add(match.group(1)); });
        }
        Map<String, String> routedIdentities = new HashMap<>();
        XmlRecords.read(sumoDir.resolve("routes.rou.xml"), Set.of("vehicle"), element -> {
            for (var p : TrafficRoutes.elements(element, "param")) if (p.getAttribute("key").equals("synthesis.physicalVehicleId"))
                routedIdentities.put(element.getAttribute("id"), p.getAttribute("value"));
        });
        List<Map<String, Object>> duties = new ArrayList<>(); int assigned = 0, issueCount = 0, knownEnergySegments = 0;
        Map<String, List<Segment>> grouped = new HashMap<>();
        for (var segment : segments.values()) if (segment.vehicleId() != null) grouped.computeIfAbsent(segment.vehicleId(), ignored -> new ArrayList<>()).add(segment);
        for (var instance : instances.values()) {
            List<Segment> duty = grouped.getOrDefault(instance.vehicleId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(Segment::plannedDepartureSeconds).thenComparing(s -> Objects.toString(s.sourceTripKey(), ""))).toList();
            if (duty.isEmpty()) continue;
            var type = types.get(instance.typeRef().key()); var power = type.powertrain();
            Double capacity = power instanceof ElectricPowertrain p ? p.usableBatteryCapacityKwh() : power instanceof CombustionPowertrain p ? p.tankCapacityL() : null;
            Double rate = power instanceof ElectricPowertrain p ? p.kwhPerKm() : power instanceof CombustionPowertrain p ? p.litresPerKm() : null;
            Double balance = power instanceof ElectricPowertrain ? instance.state().batteryEnergyKwh() : instance.state().fuelLitres();
            String unit = power instanceof ElectricPowertrain ? "kWh" : power instanceof CombustionPowertrain ? "L" : "not-applicable";
            boolean valid = true; Segment previous = null; Observed previousInfo = null; List<Map<String, Object>> rows = new ArrayList<>();
            for (var segment : duty) {
                assigned++; var info = observed.get(segment.nativeId()); List<String> issues = new ArrayList<>();
                if (!instance.vehicleId().equals(routedIdentities.get(segment.nativeId()))) issues.add("native-identity-not-preserved-or-unrouted");
                boolean completed = info != null && info.depart() >= 0 && info.arrival() >= info.depart() && !info.vaporized();
                if (!completed) issues.add("not-completed");
                if (teleports.contains(segment.nativeId())) issues.add("native-teleport");
                if (previous != null && !regionalEntry(segment.sourceTripKey()) && distance(previous.toLon(), previous.toLat(), segment.fromLon(), segment.fromLat()) > 25)
                    issues.add("location-discontinuity-over-25m");
                if (previousInfo != null && (previousInfo.arrival() < 0 || info == null || info.depart() < previousInfo.arrival() - 1e-6))
                    issues.add("overlap-or-unfinished-predecessor");
                if (previous == null && !Set.of("first-trip-origin", "first-assigned-trip-origin").contains(instance.locationRef()))
                    issues.add("initial-location-reference-not-resolved");
                boolean energyApplicable = !(power instanceof HumanPowered);
                if (energyApplicable && balance == null) issues.add("initial-or-carried-energy-unknown");
                if (energyApplicable && rate == null) issues.add("reference-consumption-unknown");
                if (energyApplicable && info != null && info.distanceKm() == null) issues.add("segment-distance-not-measured-for-native-chain");
                Double before = balance, consumption = null;
                if (energyApplicable) {
                    if (!completed || teleports.contains(segment.nativeId()) || info == null || info.distanceKm() == null || !Double.isFinite(info.distanceKm()) || info.distanceKm() < 0 || rate == null || balance == null) balance = null;
                    else {
                        consumption = info.distanceKm() * rate;
                        if (!Double.isFinite(consumption) || !Double.isFinite(balance - consumption)) {
                            issues.add("reference-consumption-overflow"); consumption = null; balance = null;
                        } else balance -= consumption;
                    }
                    if (balance != null && balance < 0) issues.add("energy-deficit");
                    if (balance != null && capacity != null && balance < capacity * config.reserveFraction()) issues.add("below-reserve");
                }
                if (consumption != null) knownEnergySegments++;
                if (!issues.isEmpty()) valid = false;
                Map<String, Object> row = new LinkedHashMap<>(); row.put("nativeId", segment.nativeId()); row.put("sourceTripKey", segment.sourceTripKey());
                row.put("physicalVehicleId", instance.vehicleId()); row.put("observation", info); row.put("unit", unit);
                row.put("balanceBefore", before); row.put("consumed", consumption); row.put("balanceAfter", balance);
                row.put("energyDeficit", balance == null ? null : Math.max(0, -balance));
                row.put("reserveAmount", capacity == null ? null : capacity * config.reserveFraction());
                row.put("stateValid", valid); row.put("issues", issues); rows.add(row); issueCount += issues.size();
                previous = segment; previousInfo = info;
            }
            Map<String, Object> output = new LinkedHashMap<>(); output.put("vehicleId", instance.vehicleId()); output.put("typeRef", instance.typeRef());
            output.put("initialState", instance.state()); output.put("unit", unit); output.put("referenceConsumptionPerKm", rate);
            output.put("balanceAfter", balance); output.put("stateValid", valid); output.put("segments", rows);
            output.put("startLocationBasis", "first-assigned-trip-origin; no building entrance model");
            output.put("lastRequestedDestination", previous == null ? null : List.of(previous.toLon(), previous.toLat())); duties.add(output);
        }
        var absent = bindings.keySet().stream().filter(key -> !sourceKeys.contains(key)).toList();
        Map<String, Object> report = new LinkedHashMap<>(); report.put("contract", CONTRACT);
        report.put("status", issueCount == 0 && absent.isEmpty() && assigned > 0 ? assigned == segments.size() ? "tracked" : "partially-tracked" : "review-required");
        report.put("configurationSha256", VehicleJson.hash(config)); report.put("vehicleConfigurationSha256", VehicleJson.hash(vehicles));
        report.put("nativeNetworkSha256", FreightProfileChecks.sha256(sumoDir.resolve("net.net.xml")));
        report.put("tripinfoSha256", FreightProfileChecks.sha256(sumoDir.resolve("tripinfo.xml")));
        report.put("selectedSegments", segments.size()); report.put("assignedSegments", assigned); report.put("unassignedSegments", segments.size() - assigned);
        report.put("bindingsNotSelected", absent); report.put("issues", issueCount); report.put("vehicles", duties);
        report.put("consumptionModel", "declared constant consumption per km multiplied by observed native routeLength; not a calibrated/native battery model");
        report.put("nativeFleetIdentitySimulated", !nativeChains.isEmpty()); report.put("energyStateTracked", knownEnergySegments > 0); report.put("knownEnergySegments", knownEnergySegments); report.put("energyConstrainsTraffic", false);
        report.put("chargingSimulated", false); report.put("reserveFraction", config.reserveFraction()); report.put("locationToleranceMetres", 25);
        report.put("scope", config.chainTrips() ? "Assigned segments execute as native vehicle chains; times come from native stops. Per-segment distance and energy remain unknown; see native-chains.json for traffic execution. No charging or energy constraints."
                : "Existing selected trip segments retain independent native IDs; explicit physical identities share one ledger. Gaps, overlaps and energy deficits invalidate the chain; no energy resets, synthetic trips, refuelling or time shifts.");
        VehicleArtifacts.write(artifacts.resolve("vehicles/energy-ledger.json"), JSON.valueToTree(report));
    }
    private static double number(String value) throws IOException {
        try { double n = Double.parseDouble(value); if (!Double.isFinite(n)) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IOException("Ungültige native Fahrtbeobachtung: " + value, e); }
    }
    static double distance(double lon1, double lat1, double lon2, double lat2) {
        return Math.hypot((lon1 - lon2) * 111320 * Math.cos(Math.toRadians((lat1 + lat2) / 2)), (lat1 - lat2) * 111320);
    }
}
