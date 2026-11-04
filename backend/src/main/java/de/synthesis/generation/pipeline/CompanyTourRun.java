package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.companytour.CompanyTourExecution;
import de.synthesis.vehicle.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Company inputs use the existing native physical chains; accounting requires complete stops and return. */
final class CompanyTourRun {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompanyTourExecution.Batch input;
    private final CompanyTourExecution.Batch original;
    private final de.synthesis.companytour.CompanyTourRegion.Projection projection;
    private Map<String,List<String>> prescribed=Map.of();
    CompanyTourExecution.Batch original(){return original;}
    void prescribed(Map<String,List<String>> value){prescribed=value;}
    Map<String,List<String>> prescribedRoutes(){return prescribed;}
    List<String> prescribed(String key){return prescribed.get(key);}
    Set<String> chainEntries(){return projection.chainEntries();}
    private final Map<String, CompanyTourExecution.Leg> byKey = new HashMap<>();
    private final Map<String, String> ids = new TreeMap<>();
    CompanyTourRun(CompanyTourExecution.Batch input) {this(input,de.synthesis.companytour.CompanyTourRegion.project(input));}
    CompanyTourRun(CompanyTourExecution.Batch original,de.synthesis.companytour.CompanyTourRegion.Projection projection){this.original=original;this.projection=projection;this.input=projection.batch();this.input.legs().forEach(l->byKey.put(l.sourceKey(),l));}
    int legCount(){return input.legs().size();}
    CompanyTourExecution.Leg leg(String sourceKey) { return byKey.get(sourceKey); }
    List<SumoRunner.Trip> trips() {
        return input.legs().stream().map(l -> new SumoRunner.Trip(l.departSec(), switch (l.vehicleClass()) {
            case "truck" -> "heavyTruck"; case "trailer" -> "articulatedTruck"; default -> "lkw";
        }, l.from().lon().toString(), l.from().lat().toString(), l.to().lon().toString(), l.to().lat().toString(), l.sourceKey(), l.serviceSeconds())).toList();
    }
    void register(String key, String id) { if (byKey.containsKey(key)) ids.put(key, id); }
    void begin(Path artifacts) throws IOException {
        ids.clear();
        Files.deleteIfExists(artifacts.resolve("company-tours/routing.json"));
        VehicleArtifacts.write(artifacts.resolve("company-tours/input.json"), original);
        de.synthesis.companytour.CompanyTourRegion.writeReport(artifacts,original,projection);
        VehicleArtifacts.write(artifacts.resolve("company-tours/execution.json"), Map.of("contract", "synthesis-company-tour-report-v1",
                "status", "not-executed", "requestedLegs", input.legs().size(), "simulationEndSeconds", 86400));
    }
    void auditRoutes(Path artifacts, Path dir, VehicleConfiguration vehicles) throws IOException {
        Map<String, Double> length = new HashMap<>(), speed = new HashMap<>(), vehicleSpeed = new HashMap<>();
        XmlRecords.read(dir.resolve("net.net.xml"), Set.of("edge"), e -> {
            var lanes = TrafficRoutes.elements(e, "lane");
            if (!lanes.isEmpty()) { length.put(e.getAttribute("id"), Double.parseDouble(lanes.getFirst().getAttribute("length")));
                speed.put(e.getAttribute("id"), lanes.stream().mapToDouble(l -> Double.parseDouble(l.getAttribute("speed"))).max().orElse(0)); }
        });
        vehicles.vehicleTypes().forEach(t -> vehicleSpeed.put(t.key(), t.physical().maxSpeedKmh() / 3.6));
        Map<String, String> types = new HashMap<>(); vehicles.initialVehicles().forEach(v -> types.put(v.vehicleId(), v.typeRef().key()));
        Map<String, ElementRoute> routes = new HashMap<>();
        XmlRecords.read(dir.resolve("routes.rou.xml"), Set.of("vehicle"), e -> {
            var r = TrafficRoutes.elements(e, "route"); if (!r.isEmpty()) routes.put(e.getAttribute("id"), new ElementRoute(List.of(r.getFirst().getAttribute("edges").split("\\s+"))));
        });
        List<Map<String, Object>> rows = new ArrayList<>(); boolean valid = true;
        for (var leg : input.legs()) {
            var route = routes.get(ids.get(leg.sourceKey())); double lower = 0;
            if (route != null) for (int i = 1; i + 1 < route.edges.size(); i++) {
                String edge = route.edges.get(i); double max = Math.min(speed.getOrDefault(edge, 0.), vehicleSpeed.get(types.get(leg.vehicleId())));
                if (max > 0) lower += length.getOrDefault(edge, 0.) / max;
            }
            boolean possible = route != null && lower <= leg.plannedArrivalSec() - leg.departSec(); valid &= possible;
            rows.add(Map.of("sourceTripKey", leg.sourceKey(), "routed", route != null, "minimumTravelSecondsLowerBound", lower,
                    "plannedTravelSeconds", leg.plannedArrivalSec() - leg.departSec(), "planningFeasibleLowerBound", possible));
        }
        VehicleArtifacts.write(artifacts.resolve("company-tours/routing.json"), Map.of("contract", "synthesis-company-tour-routing-v1",
                "status", valid ? "routed" : "invalid", "checks", rows, "networkSha256", FreightProfileChecks.sha256(dir.resolve("net.net.xml")),
                "boundMeaning", "optimistic free-flow lower bound from interior edges only; not a guarantee of planned arrivals", "accessCertified", false));
        if (!valid) throw new IOException("Unternehmenstouren sind nicht vollständig routbar oder Planfahrzeiten sind nachweislich zu kurz; siehe company-tours/routing.json");
    }
    private record ElementRoute(List<String> edges) {}
    void finish(Path artifacts) throws IOException {
        var chains = JSON.readTree(artifacts.resolve("vehicles/native-chains.json").toFile());
        Map<String, JsonNode> observed = new HashMap<>(), nativeVehicles = new HashMap<>();
        for (var vehicle : chains.path("vehicles")) for (var segment : vehicle.path("segments")) {
            String key = segment.path("sourceTripKey").asText(); observed.put(key, segment); nativeVehicles.put(key, vehicle);
        }
        List<Map<String, Object>> tours = new ArrayList<>(); int completed = 0, requested = 0;
        for (var imported : input.imports()) {
            Map<String, List<CompanyTourExecution.Leg>> groups = new LinkedHashMap<>();
            imported.legs().forEach(l -> groups.computeIfAbsent(l.tourId(), ignored -> new ArrayList<>()).add(l));
            for (var entry : groups.entrySet()) {
                requested++; boolean all = !projection.approximate() || chains.path("regionalVisitTimingValid").asBoolean(), windowsSatisfied = true; List<Map<String, Object>> stops = new ArrayList<>();
                for (var leg : entry.getValue()) {
                    var info = observed.get(leg.sourceKey()); var vehicle = nativeVehicles.get(leg.sourceKey());
                    double arrival = info == null ? -1 : info.path("arrival").asDouble(-1);
                    double ended = info == null ? -1 : info.path("stopEnded").asDouble(-1);
                    boolean complete = info != null && vehicle != null && vehicle.path("vehicleId").asText().equals(leg.vehicleId())
                            && !vehicle.path("teleports").isNull() && vehicle.path("teleports").asInt(-1) == 0
                            && arrival >= 0 && ended >= arrival + leg.serviceSeconds() && ended < 86400;
                    all &= complete;
                    boolean window = arrival >= 0 && leg.windows().stream().allMatch(w -> arrival >= w.startSec()
                            && ("service".equals(leg.windowPolicy()) ? ended : arrival) <= w.endSec());
                    if (!"report-only".equals(leg.windowPolicy())) windowsSatisfied &= window;
                    Map<String, Object> row = new LinkedHashMap<>(); row.put("sourceTripKey", leg.sourceKey()); row.put("stopId", leg.stopId());
                    row.put("returning", leg.returning()); row.put("plannedArrival", leg.plannedArrivalSec()); row.put("plannedDeparture", leg.plannedEndSec());
                    row.put("arrival", arrival); row.put("stopEnded", ended); row.put("serviceSeconds", leg.serviceSeconds());
                    row.put("arrivalDelay", arrival < 0 ? null : arrival - leg.plannedArrivalSec()); row.put("windowSatisfied", window);
                    row.put("complete", complete); stops.add(row);
                }
                // The last tour of a physical duty additionally requires the vehicle to leave SUMO normally.
                var last = entry.getValue().getLast(); var nativeVehicle = nativeVehicles.get(last.sourceKey());
                boolean finalDuty = input.legs().stream().filter(l -> l.vehicleId().equals(last.vehicleId())).mapToInt(CompanyTourExecution.Leg::plannedEndSec).max().orElse(-1) == last.plannedEndSec();
                if (finalDuty || projection.approximate()) all &= nativeVehicle != null && nativeVehicle.path("nativeCompleted").asBoolean();
                if (all) completed++;
                tours.add(Map.of("importId", imported.importId(), "tourId", entry.getKey(), "vehicleId", last.vehicleId(),
                        "status", all ? windowsSatisfied ? projection.approximate()?"regional-completed":"completed" : "completed-with-window-violations" : "incomplete",
                        "completed", all, "windowPolicy", last.windowPolicy(), "stops", stops));
            }
        }
        boolean review = tours.stream().anyMatch(t -> !Set.of("completed","regional-completed").contains(t.get("status")));
        Map<String,Object> report=new LinkedHashMap<>();report.put("contract","synthesis-company-tour-report-v1");report.put("status",review?"review-required":projection.approximate()?"regional-complete":"complete");
        report.put("requestedTours",requested);report.put("completedTours",projection.approximate()?0:completed);report.put("completedRegionalTours",completed);report.put("outsideExecutionSimulated",false);report.put("originalRequestedLegs",original.legs().size());
        report.put("originalRequestedTours",original.imports().stream().mapToLong(i->i.legs().stream().map(CompanyTourExecution.Leg::tourId).distinct().count()).sum());
        report.put("outsideOnlyLegs",projection.checks().stream().filter(c->c.scope().equals("outside")).count());report.put("requestedLegs",input.legs().size());report.put("tours",tours);report.put("simulationEndSeconds",86400);
        report.put("dwellModel","minimum declared dwell plus waiting until next planned departure; planned arrivals are not forced");
        VehicleArtifacts.write(artifacts.resolve("company-tours/execution.json"),report);
    }
}
