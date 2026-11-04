package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.vehicle.*;
import de.synthesis.vehicle.VehicleConfiguration.VehicleInstance;
import de.synthesis.generation.pipeline.VehicleContinuity.Segment;
import org.w3c.dom.Element;
import javax.xml.stream.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** One native vehicle per explicitly assigned duty; no inferred fleet, dispatch or energy model. */
final class NativeVehicleChains {
    static final String CONTRACT = "synthesis-native-vehicle-chains-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    @FunctionalInterface interface Execute { void run(String[] arguments) throws IOException, InterruptedException; }
    record Observation(String nativeVehicleId, double depart, double arrival, boolean vaporized, int teleports) { }
    private record Stop(String requestId, String lane, String edge, double position, int duration, Integer until) { }
    private record Lane(String id, double length) { }
    private record LegRoute(List<String> edges, double fromPosition, double toPosition) { }
    private static final class Duty {
        final VehicleInstance vehicle; final String nativeId; final List<Segment> segments;
        final List<Stop> stops = new ArrayList<>(); final Map<String, LegRoute> legRoutes = new HashMap<>();
        List<String> prescribed;
        String fromEdge; Lane firstLane; double startPosition; Element routed;
        Duty(VehicleInstance vehicle, List<Segment> segments,int visit) {
            this.vehicle = vehicle; this.segments = segments;
            nativeId = "physical." + Base64.getUrlEncoder().withoutPadding().encodeToString(vehicle.vehicleId().getBytes(StandardCharsets.UTF_8))+(visit==0?"":".visit."+visit);
        }
    }
    private final Path artifacts, dir;
    private final VehicleContinuity continuity;
    private final List<Duty> duties = new ArrayList<>();
    private final Map<String, Duty> assigned = new HashMap<>();
    private final Map<String, Object> report = new LinkedHashMap<>();

    private NativeVehicleChains(Path artifacts, Path dir, VehicleContinuity continuity) {
        this.artifacts = artifacts; this.dir = dir; this.continuity = continuity;
        report.put("contract", CONTRACT); report.put("configurationSha256", VehicleJson.hash(continuity.operationsConfiguration()));
        report.put("vehicleConfigurationSha256", VehicleJson.hash(continuity.vehicleConfiguration()));
        report.put("simulationEndSeconds", 86400); report.put("locationToleranceMetres", 25);
        report.put("parkingModel", "unlimited roadside parking at the mapped road position; no building entrance or parking capacity model");
        report.put("dwellModel", "max(1 second, declared delivery service); wait until planned next departure; late arrivals propagate");
        report.put("energyConstrainsTraffic", false); report.put("cargoChangesSimulated", false);
    }

    static NativeVehicleChains prepare(Path artifacts, Path dir, VehicleContinuity continuity, TrafficRoutes network,
                                       Path duarouter, long seed, Execute execute) throws IOException, InterruptedException {
        var result = new NativeVehicleChains(artifacts, dir, continuity);
        result.report.put("seed", seed);
        try { result.prepare(network, duarouter, seed, execute); return result; }
        catch (IOException | InterruptedException | RuntimeException failure) {
            result.report.put("status", "failed"); result.report.put("message", failure.getMessage()); result.save(); throw failure;
        }
    }

    private void prepare(TrafficRoutes network, Path duarouter, long seed, Execute execute) throws IOException, InterruptedException {
        var vehicles = continuity.vehicleConfiguration();
        Map<String, VehicleType> types = new HashMap<>(); for (var type : vehicles.vehicleTypes()) types.put(type.key(), type);
        Map<String, List<Segment>> grouped = new HashMap<>();
        for (var s : continuity.selectedSegments()) if (s.vehicleId() != null) grouped.computeIfAbsent(s.vehicleId(), ignored -> new ArrayList<>()).add(s);
        for (var vehicle : vehicles.initialVehicles()) {
            var segments = grouped.getOrDefault(vehicle.vehicleId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(Segment::plannedDepartureSeconds).thenComparing(Segment::sourceTripKey)).toList();
            if (segments.isEmpty()) continue;
            if (!Set.of("first-trip-origin", "first-assigned-trip-origin").contains(vehicle.locationRef()))
                throw new IOException("Fahrzeugkette " + vehicle.vehicleId() + ": Startort muss first-assigned-trip-origin sein");
            List<List<Segment>> visits=new ArrayList<>();List<Segment> visit=new ArrayList<>();
            for(var segment:segments){if(!visit.isEmpty()&&continuity.regionalEntry(segment.sourceTripKey())){visits.add(List.copyOf(visit));visit.clear();}visit.add(segment);}visits.add(List.copyOf(visit));
            int visitId=0;
            for(var partition:visits){
            for (int i = 0; i < partition.size(); i++) {
                var s = partition.get(i);
                if (!s.typeId().equals(partition.getFirst().typeId())) throw new IOException("Fahrzeugtyp wechselt innerhalb der Kette " + vehicle.vehicleId());
                if (s.serviceSeconds() < 0) throw new IOException("Negative Haltezeit für " + s.sourceTripKey());
                if (i > 0) {
                    var previous = partition.get(i - 1);
                    if (VehicleContinuity.distance(previous.toLon(), previous.toLat(), s.fromLon(), s.fromLat()) > 25)
                        throw new IOException("Ortsbruch in Fahrzeugkette " + vehicle.vehicleId() + " vor " + s.sourceTripKey() + "; keine automatische Leerfahrt");
                }
            }
            var duty = new Duty(vehicle, partition,visitId++);
            if(partition.stream().anyMatch(s->continuity.prescribedRoute(s.sourceTripKey())!=null)){
                var sequence=new ArrayList<String>();for(var segment:partition){var route=continuity.prescribedRoute(segment.sourceTripKey());if(route==null)throw new IOException("Unvollständige festgelegte regionale Fahrzeugroute");int begin=!sequence.isEmpty()&&sequence.getLast().equals(route.getFirst())?1:0;sequence.addAll(route.subList(begin,route.size()));}duty.prescribed=List.copyOf(sequence);
            }
            duties.add(duty); for (var s : partition) assigned.put(s.nativeId(), duty);
            }
        }
        Map<String, Element> original = new HashMap<>(), definitions = new TreeMap<>(); Set<String> originalIds = new HashSet<>();
        XmlRecords.read(dir.resolve("routes.rou.xml"), Set.of("vType", "vehicle"), e -> {
            if (e.getTagName().equals("vType")) definitions.put(e.getAttribute("id"), e);
            else { originalIds.add(e.getAttribute("id")); if (assigned.containsKey(e.getAttribute("id"))) original.put(e.getAttribute("id"), e); }
        });
        Set<String> neededEdges = new HashSet<>();
        for (var duty : duties) for (var s : duty.segments) {
            var vehicle = original.get(s.nativeId());
            if (vehicle == null) throw new IOException("Nicht geroutete Fahrt in Fahrzeugkette: " + s.sourceTripKey());
            var route = routeEdges(vehicle); neededEdges.add(route.getFirst()); neededEdges.add(route.getLast());
        }
        Map<String, List<Element>> lanes = new HashMap<>();
        XmlRecords.read(dir.resolve("net.net.xml"), Set.of("edge"), e -> {
            if (neededEdges.contains(e.getAttribute("id"))) lanes.put(e.getAttribute("id"), TrafficRoutes.elements(e, "lane"));
        });
        for (var duty : duties) {
            if (originalIds.contains(duty.nativeId)) throw new IOException("Native Fahrzeug-ID kollidiert: " + duty.nativeId);
            var type = types.get(duty.vehicle.typeRef().key()); var clazz = type.routing().sumoVehicleClass();
            var first = duty.segments.getFirst(); duty.fromEdge = routeEdges(original.get(first.nativeId())).getFirst();
            duty.firstLane = permittedLane(lanes.get(duty.fromEdge), clazz);
            duty.startPosition = position(network, duty.fromEdge, first.fromLon(), first.fromLat(), duty.firstLane, type.physical().lengthM());
            for (int i = 0; i < duty.segments.size(); i++) {
                var s = duty.segments.get(i); String edge = routeEdges(original.get(s.nativeId())).getLast();
                var lane = permittedLane(lanes.get(edge), clazz);
                double pos = position(network, edge, s.toLon(), s.toLat(), lane, type.physical().lengthM());
                duty.stops.add(new Stop(s.nativeId(), lane.id(), edge, pos, Math.max(1, s.serviceSeconds()),
                        i + 1 < duty.segments.size() ? duty.segments.get(i + 1).plannedDepartureSeconds() : null));
            }
        }
        report.put("physicalVehicles", duties.stream().map(d->d.vehicle.vehicleId()).distinct().count()); report.put("nativeVisits",duties.size());
        report.put("executionScope",duties.stream().anyMatch(d->d.nativeId.contains(".visit."))?"regional-visits":"full-duty");report.put("outsideExecutionSimulated",false); report.put("assignedSegments", assigned.size());
        report.put("unassignedSegments", continuity.selectedSegments().size() - assigned.size());
        report.put("status", "planned"); save();
        if (duties.isEmpty()) return;
        try (var out = Files.newBufferedWriter(dir.resolve("native-chains.trips.xml"))) {
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out); xml.writeStartElement("routes");
            for (var e : definitions.values()) XmlRecords.write(e, xml);
            for (var duty : duties.stream().sorted(Comparator.comparingInt(d -> d.segments.getFirst().plannedDepartureSeconds())).toList()) {
                xml.writeStartElement("trip"); attribute(xml, "id", duty.nativeId); attribute(xml, "type", duty.segments.getFirst().typeId());
                attribute(xml, "depart", duty.segments.getFirst().plannedDepartureSeconds()); attribute(xml, "from", duty.fromEdge);
                attribute(xml, "to", duty.stops.getLast().edge()); attribute(xml, "departLane", laneIndex(duty.firstLane.id()));
                if(duty.prescribed!=null&&duty.prescribed.size()>2)attribute(xml,"via",String.join(" ",duty.prescribed.subList(1,duty.prescribed.size()-1)));
                attribute(xml, "departPos", duty.startPosition); attribute(xml, "arrivalPos", duty.stops.getLast().position());
                for (var stop : duty.stops) {
                    xml.writeEmptyElement("stop"); attribute(xml, "lane", stop.lane()); attribute(xml, "endPos", stop.position());
                    attribute(xml, "duration", stop.duration()); attribute(xml, "parking", "true"); attribute(xml, "tripId", stop.requestId());
                    if (stop.until() != null) attribute(xml, "until", stop.until());
                }
                xml.writeEmptyElement("param"); attribute(xml, "key", "synthesis.physicalVehicleId"); attribute(xml, "value", duty.vehicle.vehicleId());
                xml.writeEndElement();
            }
            xml.writeEndElement(); xml.flush(); xml.close();
        } catch (XMLStreamException e) { throw new IOException("Fahrzeugketten-XML konnte nicht geschrieben werden", e); }
        execute.run(new String[]{duarouter.toString(), "-n", "net.net.xml", "-r", "native-chains.trips.xml", "-o", "native-chains.rou.xml",
                "--seed", Long.toString(seed), "--routing-threads", "1", "--no-step-log"});
        Map<String, Duty> byId = new HashMap<>(); for (var duty : duties) byId.put(duty.nativeId, duty);
        XmlRecords.read(dir.resolve("native-chains.rou.xml"), Set.of("vehicle"), e -> {
            var duty = byId.get(e.getAttribute("id")); if (duty == null || duty.routed != null) throw new IOException("Unerwartete native Fahrzeugkette");
            duty.routed = e; splitRoute(duty);
        });
        for (var duty : duties) {if (duty.routed == null) throw new IOException("Native Fahrzeugkette fehlt: " + duty.vehicle.vehicleId());if(duty.prescribed!=null&&!routeEdges(duty.routed).equals(duty.prescribed))throw new IOException("Native Fahrzeugkette verändert festgelegte regionale Straßenroute: "+duty.vehicle.vehicleId()+" expected="+duty.prescribed+" actual="+routeEdges(duty.routed));}
        // Keep logical segment routes for geometry, restrictions and demand accounting; the simulation loads physical vehicles separately.
        XmlRecords.rewrite(dir.resolve("routes.rou.xml"), Set.of("vehicle"), e -> {
            var duty = assigned.get(e.getAttribute("id")); if (duty == null) return;
            var leg = duty.legRoutes.get(e.getAttribute("id"));
            var route = TrafficRoutes.elements(e, "route").getFirst(); route.setAttribute("edges", String.join(" ", leg.edges()));
            e.setAttribute("departPos", Double.toString(leg.fromPosition())); e.setAttribute("arrivalPos", Double.toString(leg.toPosition()));
        });
        Files.copy(dir.resolve("routes.rou.xml"), dir.resolve("simulation.rou.xml"), StandardCopyOption.REPLACE_EXISTING);
        XmlRecords.filter(dir.resolve("simulation.rou.xml"), Set.of("vehicle"), e -> {
            var duty = assigned.get(e.getAttribute("id")); if (duty == null) return true;
            if (!duty.segments.getFirst().nativeId().equals(e.getAttribute("id"))) return false;
            while (e.hasAttributes()) e.removeAttributeNode((org.w3c.dom.Attr)e.getAttributes().item(0));
            for (int i = 0; i < duty.routed.getAttributes().getLength(); i++) {
                var a = duty.routed.getAttributes().item(i); e.setAttribute(a.getNodeName(), a.getNodeValue());
            }
            while (e.hasChildNodes()) e.removeChild(e.getFirstChild());
            for (var child = duty.routed.getFirstChild(); child != null; child = child.getNextSibling()) e.appendChild(e.getOwnerDocument().importNode(child, true));
            return true;
        });
        report.put("status", "routed"); report.put("nativeFleetIdentitySimulated", false); save();
    }

    String simulationRoutes() { return duties.isEmpty() ? "routes.rou.xml" : "simulation.rou.xml"; }
    boolean hasVehicles() { return !duties.isEmpty(); }

    void finish() throws IOException {
        try { finishNative(); }
        catch (IOException | RuntimeException failure) {
            report.put("status", "failed"); report.put("message", failure.getMessage()); save(); throw failure;
        }
    }
    private void finishNative() throws IOException {
        Map<String, Element> tripinfos = new HashMap<>(), stops = new HashMap<>(); Map<String, Integer> teleports = new HashMap<>();
        Set<String> dutyIds = new HashSet<>(); for (var duty : duties) dutyIds.add(duty.nativeId);
        XmlRecords.read(dir.resolve("tripinfo.xml"), Set.of("tripinfo"), e -> { if (dutyIds.contains(e.getAttribute("id"))) tripinfos.put(e.getAttribute("id"), e); });
        XmlRecords.read(dir.resolve("chain-stops.xml"), Set.of("stopinfo"), e -> {
            if (!dutyIds.contains(e.getAttribute("id"))) return;
            String key = e.getAttribute("id") + "/" + e.getAttribute("tripId");
            if (stops.putIfAbsent(key, e) != null) throw new IOException("Doppelte Haltebeobachtung: " + key);
        });
        var pattern = Pattern.compile("Teleporting vehicle '([^']+)'");
        try (var lines = Files.lines(dir.resolve("sumo.log"))) {
            lines.forEach(line -> { var m = pattern.matcher(line); if (m.find()) teleports.merge(m.group(1), 1, Integer::sum); });
        }
        int[] totalTeleports = {-1};
        XmlRecords.read(dir.resolve("statistics.xml"), Set.of("teleports"), e -> totalTeleports[0] = (int)number(e, "total"));
        boolean teleportAccounting = totalTeleports[0] >= 0 && totalTeleports[0] == teleports.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Observation> observations = new TreeMap<>(); List<Map<String, Object>> vehicles = new ArrayList<>();
        int completed = 0, unfinished = 0, notDeparted = 0, physicalCompleted = 0;
        for (var duty : duties) {
            var info = tripinfos.get(duty.nativeId); double depart = number(info, "depart"), nativeArrival = number(info, "arrival");
            boolean vaporized = info != null && TrafficExecutionAudit.vaporized(info);
            int teleportCount = teleports.getOrDefault(duty.nativeId, 0); if (nativeArrival >= 0 && !vaporized) physicalCompleted++;
            List<Map<String, Object>> legs = new ArrayList<>();
            for (int i = 0; i < duty.segments.size(); i++) {
                var s = duty.segments.get(i); var stop = stops.get(duty.nativeId + "/" + s.nativeId());
                double arrived = number(stop, "started"), ended = number(stop, "ended");
                if (stop != null && (!stop.getAttribute("lane").equals(duty.stops.get(i).lane())
                        || Math.abs(number(stop, "pos") - duty.stops.get(i).position()) > .1))
                    throw new IOException("Haltebeobachtung passt nicht zur Fahrzeugkette " + duty.vehicle.vehicleId());
                if (depart < 0) notDeparted++; else if (arrived < 0) unfinished++; else completed++;
                observations.put(s.nativeId(), new Observation(duty.nativeId, depart, arrived, vaporized && arrived < 0, teleportCount));
                Map<String, Object> row = new LinkedHashMap<>(); row.put("requestId", s.nativeId()); row.put("sourceTripKey", s.sourceTripKey());
                row.put("plannedDeparture", s.plannedDepartureSeconds()); row.put("depart", depart); row.put("arrival", arrived);
                row.put("departureDelay", depart < 0 ? null : Math.max(0, depart - s.plannedDepartureSeconds()));
                row.put("stopEnded", ended); row.put("stopLane", duty.stops.get(i).lane()); row.put("stopPosition", duty.stops.get(i).position());
                row.put("nativeRoute", duty.legRoutes.get(s.nativeId()));
                row.put("requestedOrigin", List.of(s.fromLon(), s.fromLat())); row.put("requestedDestination", List.of(s.toLon(), s.toLat()));
                row.put("serviceSeconds", s.serviceSeconds()); row.put("status", depart < 0 ? "not-departed" : arrived >= 0 ? "completed" : vaporized ? "vaporized" : "unfinished-at-day-end");
                legs.add(row); depart = ended;
            }
            Map<String, Object> row = new LinkedHashMap<>(); row.put("vehicleId", duty.vehicle.vehicleId()); row.put("nativeVehicleId", duty.nativeId);
            row.put("nativeDeparture",number(info,"depart"));row.put("nativeArrival", nativeArrival); row.put("nativeCompleted", nativeArrival >= 0 && !vaporized);
            row.put("teleports", teleportAccounting ? teleportCount : null); row.put("segments", legs); vehicles.add(row);
        }
        boolean visitsValid=true;
        for(String id:vehicles.stream().map(v->(String)v.get("vehicleId")).distinct().toList()){
            double previousEnd=-1;
            for(var visit:vehicles.stream().filter(v->v.get("vehicleId").equals(id)).toList()){
                double start=((Number)visit.get("nativeDeparture")).doubleValue(),end=((Number)visit.get("nativeArrival")).doubleValue();
                if(start<0||end<0||start<previousEnd)visitsValid=false;previousEnd=end;
            }
        }
        report.put("regionalVisitTimingValid",visitsValid);
        report.put("teleportAccountingComplete", teleportAccounting);
        report.put("phase", "executed"); report.put("status", notDeparted > 0 ? "incomplete" : !visitsValid || !teleportAccounting || unfinished > 0 || physicalCompleted < duties.size()
                || teleports.keySet().stream().anyMatch(dutyIds::contains) ? "review-required" : "complete");
        report.put("nativeFleetIdentitySimulated", !duties.isEmpty()); report.put("completedSegments", completed);
        report.put("unfinishedSegments", unfinished); report.put("notDepartedSegments", notDeparted); report.put("completedNativeVisits",physicalCompleted);report.put("completedPhysicalVehicles", vehicles.stream().map(v->v.get("vehicleId")).distinct().filter(id->vehicles.stream().filter(v->v.get("vehicleId").equals(id)).allMatch(v->Boolean.TRUE.equals(v.get("nativeCompleted")))).count());
        report.put("observations", observations); report.put("vehicles", vehicles);
        report.put("nativeNetworkSha256", FreightProfileChecks.sha256(dir.resolve("net.net.xml")));
        report.put("nativeRoutesSha256", FreightProfileChecks.sha256(dir.resolve(simulationRoutes())));
        report.put("stopOutputSha256", FreightProfileChecks.sha256(dir.resolve("chain-stops.xml"))); save();
    }

    static Map<String, Observation> observations(Path artifacts) throws IOException {
        Path file = artifacts.resolve("vehicles/native-chains.json"); if (!Files.exists(file)) return Map.of();
        var root = JSON.readTree(file.toFile()); if (!CONTRACT.equals(root.path("contract").asText()) || !root.path("phase").asText().equals("executed")) return Map.of();
        Map<String, Observation> result = new HashMap<>();
        var fields = root.path("observations").fields(); while (fields.hasNext()) {
            var field = fields.next(); result.put(field.getKey(), JSON.treeToValue(field.getValue(), Observation.class));
        }
        return result;
    }

    private void save() throws IOException { VehicleArtifacts.write(artifacts.resolve("vehicles/native-chains.json"), JSON.valueToTree(report)); }
    private static List<String> routeEdges(Element vehicle) throws IOException {
        var route = TrafficRoutes.elements(vehicle, "route"); if (route.size() != 1 || route.getFirst().getAttribute("edges").isBlank()) throw new IOException("Eindeutige native Route fehlt");
        return List.of(route.getFirst().getAttribute("edges").trim().split("\\s+"));
    }
    private static Lane permittedLane(List<Element> lanes, String clazz) throws IOException {
        if (lanes != null) for (var lane : lanes) {
            var allow = Set.of(lane.getAttribute("allow").split("\\s+")); var disallow = Set.of(lane.getAttribute("disallow").split("\\s+"));
            if ((lane.getAttribute("allow").isBlank() || allow.contains("all") || allow.contains(clazz)) && !disallow.contains("all") && !disallow.contains(clazz))
                return new Lane(lane.getAttribute("id"), Double.parseDouble(lane.getAttribute("length")));
        }
        throw new IOException("Keine zulässige Haltespur für " + clazz);
    }
    private static double position(TrafficRoutes network, String edge, double lon, double lat, Lane lane, double vehicleLength) throws IOException {
        if (lane.length() < vehicleLength + .2) throw new IOException("Haltekante kürzer als das Fahrzeug: " + edge);
        return Math.max(vehicleLength + .1, Math.min(lane.length() - .1, network.project(edge, lon, lat).fraction() * lane.length()));
    }
    private static String laneIndex(String lane) { return lane.substring(lane.lastIndexOf('_') + 1); }
    private static void attribute(XMLStreamWriter xml, String key, Object value) throws XMLStreamException { xml.writeAttribute(key, String.valueOf(value)); }
    private static double number(Element e, String key) { return e == null || e.getAttribute(key).isBlank() ? -1 : Double.parseDouble(e.getAttribute(key)); }
    private static void splitRoute(Duty duty) throws IOException {
        var edges = routeEdges(duty.routed); int begin = 0; double start = duty.startPosition;
        for (var stop : duty.stops) {
            int end = begin;
            while (end < edges.size() && (!edges.get(end).equals(stop.edge()) || end == begin && stop.position() < start - .01)) end++;
            if (end == edges.size()) throw new IOException("Haltereihenfolge fehlt in nativer Route " + duty.nativeId);
            duty.legRoutes.put(stop.requestId(), new LegRoute(List.copyOf(edges.subList(begin, end + 1)), start, stop.position()));
            begin = end; start = stop.position();
        }
    }
}
