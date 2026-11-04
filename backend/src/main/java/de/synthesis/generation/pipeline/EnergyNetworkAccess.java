package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import de.synthesis.vehicle.*;
import de.synthesis.vehicle.VehicleType.*;
import org.w3c.dom.Element;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Directed native routing probes, separate from transport demand. Never certifies a physical entrance. */
public final class EnergyNetworkAccess {
    public static final String CONTRACT = "synthesis-energy-network-access-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    @FunctionalInterface interface Command { void run(String... arguments) throws IOException, InterruptedException; }
    private record Lane(String id, int index, double length, Set<String> allowed, Set<String> denied) {
        boolean permits(String clazz) { return (allowed.isEmpty() || allowed.contains("all") || allowed.contains(clazz)) && !denied.contains("all") && !denied.contains(clazz); }
    }
    private record Probe(String id, String type, String from, String to, int lane, double position, boolean entry) { }
    private record Candidate(Map<String, Object> output, String edge, VehicleType profile, JsonNode station,
                             List<String> entryIds, List<String> exitIds) { }
    public static void clear(Path artifacts) throws IOException {
        for (String name : List.of("network-access.json", "network-access.geojson", "access-probes.trips.xml", "access-probes.rou.xml", "access-probes.rou.alt.xml"))
            Files.deleteIfExists(artifacts.resolve("energy").resolve(name));
    }
    static void generate(Path artifacts, Path dir, TrafficRoutes network, Scenario.EnergyInfrastructure config,
                         VehicleConfiguration vehicles, long seed, Path router, Command command) throws IOException, InterruptedException {
        Path output = artifacts.resolve("energy/network-access.json");
        VehicleArtifacts.write(output, Map.of("contract", CONTRACT, "status", "started", "configuration", config, "vehicleConfigurationSha256", VehicleJson.hash(vehicles)));
        try { execute(artifacts, dir, network, config, vehicles, seed, router, command); }
        catch (IOException | InterruptedException | RuntimeException error) {
            var failure = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(output.toFile());
            failure.put("status", "failed"); failure.put("message", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); failure.put("physicalEntranceVerified", false);
            VehicleArtifacts.write(output, failure);
            throw error;
        }
    }
    private static void execute(Path artifacts, Path dir, TrafficRoutes network, Scenario.EnergyInfrastructure config,
                         VehicleConfiguration vehicles, long seed, Path router, Command command) throws IOException, InterruptedException {
        Path inventory = artifacts.resolve("energy/stations.geojson");
        JsonNode stations = JSON.readTree(inventory.toFile());
        if (!EnergyInfrastructure.CONTRACT.equals(stations.path("contract").asText())) throw new IOException("Stationsbestand fehlt oder hat falschen Vertrag");
        var context = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(artifacts.resolve("energy/network-access.json").toFile());
        context.set("region", stations.path("region")); context.set("referenceYear", stations.path("referenceYear"));
        VehicleArtifacts.write(artifacts.resolve("energy/network-access.json"), context);
        Map<String, VehicleType> profiles = new TreeMap<>();
        for (String id : List.of("standard.pkw.elektro", "standard.lieferwagen.elektro", "standard.heavyTruck.diesel", "standard.articulatedTruck.diesel"))
            VehicleCatalog.standards().stream().filter(t -> t.id().equals(id)).forEach(t -> profiles.put(t.key(), t));
        for (var type : vehicles.vehicleTypes()) if (Set.of("passenger", "delivery", "truck", "trailer", "bicycle").contains(type.routing().sumoVehicleClass())) profiles.put(type.key(), type);
        List<Map<String, Object>> rows = new ArrayList<>(); List<Candidate> candidates = new ArrayList<>(); Set<String> selectedEdges = new HashSet<>();
        Map<String, String> profileIds = new TreeMap<>(); int p = 0;
        for (String key : profiles.keySet()) profileIds.put(key, "energy_profile_" + p++);
        for (JsonNode feature : stations.path("features")) {
            JsonNode station = feature.path("properties"); double lon = station.path("longitude").asDouble(), lat = station.path("latitude").asDouble();
            for (var profile : profiles.values()) {
                var row = new LinkedHashMap<String, Object>(); row.put("stationId", station.path("id").asText()); row.put("originalCoordinate", List.of(lon, lat));
                row.put("typeRef", new TypeRef(profile.id(), profile.version())); row.put("vehicleClass", profile.routing().sumoVehicleClass());
                row.put("profileProvenance", profile.provenance()); row.put("vehicleEnvelope", profile.physical()); row.put("massBasis", "maximum-rated-gross-mass; unknown when absent");
                row.put("energyCompatibility", compatibility(station, profile)); row.put("physicalEntranceVerified", false);
                List<Map<String, Object>> bindings = new ArrayList<>(); row.put("candidates", bindings); rows.add(row);
                for (var match : network.candidates(lon, lat, profile.routing().sumoVehicleClass(), config.maxSnapDistanceM, 4)) {
                    selectedEdges.add(match.edgeId()); var projection = network.project(match.edgeId(), lon, lat);
                    var binding = new LinkedHashMap<String, Object>(); binding.put("edgeId", match.edgeId()); binding.put("distanceMetres", match.distanceMetres());
                    binding.put("projectedCoordinate", List.of(projection.lon(), projection.lat())); binding.put("edgeFraction", projection.fraction()); binding.put("nearNetworkEnvelope", match.nearNetworkEnvelope());
                    binding.put("status", "pending"); bindings.add(binding);
                    candidates.add(new Candidate(binding, match.edgeId(), profile, station, new ArrayList<>(), new ArrayList<>()));
                }
            }
        }
        Map<String, List<Lane>> lanes = new HashMap<>(); Map<String, Set<String>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        XmlRecords.read(dir.resolve("net.net.xml"), Set.of("edge", "connection"), element -> {
            if (element.getTagName().equals("edge") && selectedEdges.contains(element.getAttribute("id"))) {
                List<Lane> list = new ArrayList<>();
                for (Element lane : TrafficRoutes.elements(element, "lane")) list.add(new Lane(lane.getAttribute("id"), Integer.parseInt(lane.getAttribute("index")),
                        Double.parseDouble(lane.getAttribute("length")), words(lane.getAttribute("allow")), words(lane.getAttribute("disallow"))));
                lanes.put(element.getAttribute("id"), list);
            } else if (element.getTagName().equals("connection")) {
                String from = element.getAttribute("from"), to = element.getAttribute("to");
                if (selectedEdges.contains(to) && !from.equals(to) && !from.startsWith(":")) incoming.computeIfAbsent(to, ignored -> new TreeSet<>()).add(from);
                if (selectedEdges.contains(from) && !from.equals(to) && !to.startsWith(":")) outgoing.computeIfAbsent(from, ignored -> new TreeSet<>()).add(to);
            }
        });
        Map<String, Map<String, String>> roadTags = new HashMap<>(), stationTags = new HashMap<>();
        Set<String> wayIds = new HashSet<>(); selectedEdges.forEach(edge -> wayIds.add(FreightProfileChecks.wayId(edge)));
        XmlRecords.read(dir.resolve("network.osm"), Set.of("way"), element -> { if (wayIds.contains(element.getAttribute("id"))) roadTags.put(element.getAttribute("id"), FreightProfileChecks.tags(element)); });
        Path osmSource = artifacts.resolve("energy/osm-source.json");
        if (Files.exists(osmSource)) for (JsonNode element : JSON.readTree(osmSource.toFile()).path("data").path("elements")) {
            Map<String, String> tags = new TreeMap<>(); element.path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
            stationTags.put("osm:" + element.path("type").asText() + ":" + element.path("id").asText(), tags);
        }
        List<Probe> probes = new ArrayList<>();
        for (var candidate : candidates) {
            var row = candidate.output(); String clazz = candidate.profile().routing().sumoVehicleClass();
            var lane = lanes.getOrDefault(candidate.edge(), List.of()).stream().filter(l -> l.permits(clazz) && l.length() > 0).findFirst().orElse(null);
            List<EnergyAccessRules.Issue> issues = new ArrayList<>();
            var tags = roadTags.get(FreightProfileChecks.wayId(candidate.edge()));
            if (tags == null) issues.add(new EnergyAccessRules.Issue("unknown", "road", candidate.edge(), "source-way-unmapped"));
            else issues.addAll(EnergyAccessRules.check(tags, candidate.profile(), candidate.edge().startsWith("-")));
            issues.addAll(EnergyAccessRules.check(stationTags.getOrDefault(candidate.station().path("id").asText(), Map.of()), candidate.profile(), false));
            if (!candidate.station().path("access").asText().equals("public")) issues.add(new EnergyAccessRules.Issue("unknown", "access", candidate.station().path("access").asText(), "public-access-unconfirmed"));
            if (Set.of("truck", "trailer").contains(clazz) && !candidate.station().path("hgvAccess").asText().equals("yes"))
                issues.add(new EnergyAccessRules.Issue("unknown", "hgv", candidate.station().path("hgvAccess").asText(), "station-hgv-access-unconfirmed"));
            row.put("sourceIssues", issues);
            if (lane == null || issues.stream().anyMatch(i -> i.status().equals("violation"))) { row.put("status", "blocked"); continue; }
            double fraction = ((Number) row.get("edgeFraction")).doubleValue(); double pos = Math.max(0, Math.min(lane.length(), fraction * lane.length()));
            row.put("laneId", lane.id()); row.put("laneIndex", lane.index()); row.put("lanePositionM", pos);
            row.put("positionMethod", "source coordinate projected onto geographic edge centerline, fraction transferred to native lane length; not an entrance survey");
            var entries = incoming.getOrDefault(candidate.edge(), Set.of()); var exits = outgoing.getOrDefault(candidate.edge(), Set.of());
            row.put("neighborSearchTruncated", entries.size() > 8 || exits.size() > 8);
            for (String edge : entries.stream().limit(8).toList()) { String id = "energy_probe_" + probes.size(); candidate.entryIds().add(id); probes.add(new Probe(id, profileIds.get(candidate.profile().key()), edge, candidate.edge(), lane.index(), pos, true)); }
            for (String edge : exits.stream().limit(8).toList()) { String id = "energy_probe_" + probes.size(); candidate.exitIds().add(id); probes.add(new Probe(id, profileIds.get(candidate.profile().key()), candidate.edge(), edge, lane.index(), pos, false)); }
        }
        Path trips = artifacts.resolve("energy/access-probes.trips.xml"), routed = artifacts.resolve("energy/access-probes.rou.xml");
        writeProbes(trips, probes, profiles, profileIds);
        Set<String> successful = Set.of();
        if (!probes.isEmpty()) {
            command.run(router.toString(), "-n", dir.resolve("net.net.xml").toAbsolutePath().toString(), "-r", trips.toAbsolutePath().toString(),
                    "-o", routed.toAbsolutePath().toString(), "--ignore-errors", "--no-step-log", "--seed", Long.toString(seed), "--routing-threads", "1");
            successful = TrafficRoutes.vehicleIds(routed);
        }
        // Audit every edge of each native probe path, including neighboring source nodes.
        // Native class routing alone does not enforce height/weight/purpose restrictions.
        var routeIssues = auditProbeRoutes(dir.resolve("network.osm"), routed, probes, profiles, profileIds, network);
        Set<String> profileAllowed = new HashSet<>(successful);
        for (var result : routeIssues.entrySet()) if (result.getValue().stream().anyMatch(i -> i.status().equals("violation"))) profileAllowed.remove(result.getKey());
        for (var candidate : candidates) if (!candidate.output().get("status").equals("blocked")) {
            boolean entry = candidate.entryIds().stream().anyMatch(profileAllowed::contains), exit = candidate.exitIds().stream().anyMatch(profileAllowed::contains);
            candidate.output().put("nativeEntryRouteExists", candidate.entryIds().stream().anyMatch(successful::contains));
            candidate.output().put("nativeExitRouteExists", candidate.exitIds().stream().anyMatch(successful::contains));
            candidate.output().put("entryProfilePathFound", entry); candidate.output().put("exitProfilePathFound", exit);
            candidate.output().put("status", entry && exit ? "directed-route-checked" : "no-confirmed-directed-access");
        }
        int mapped = 0, blocked = 0, unmapped = 0;
        for (var row : rows) {
            @SuppressWarnings("unchecked") var bindings = (List<Map<String, Object>>) row.get("candidates");
            String status = bindings.stream().anyMatch(b -> b.get("status").equals("directed-route-checked")) ? "mapped-review-required"
                    : !bindings.isEmpty() && bindings.stream().allMatch(b -> b.get("status").equals("blocked")) ? "blocked" : "unmapped";
            row.put("status", status); if (status.startsWith("mapped")) mapped++; else if (status.equals("blocked")) blocked++; else unmapped++;
        }
        Map<String, Object> report = new LinkedHashMap<>(); report.put("contract", CONTRACT); report.put("status", "completed");
        report.put("inventorySha256", FreightProfileChecks.sha256(inventory)); report.put("networkSha256", FreightProfileChecks.sha256(dir.resolve("net.net.xml")));
        report.put("sourceOsmSha256", FreightProfileChecks.sha256(dir.resolve("network.osm"))); report.put("vehicleConfigurationSha256", VehicleJson.hash(vehicles));
        report.put("configuration", config); report.put("region", stations.path("region")); report.put("referenceYear", stations.path("referenceYear")); report.put("profiles", profiles.values()); report.put("stationRecords", stations.path("features").size());
        report.put("profileChecks", rows.size()); report.put("mappedProfileChecks", mapped); report.put("blockedProfileChecks", blocked); report.put("unmappedProfileChecks", unmapped);
        report.put("routingProbes", probes.size()); report.put("successfulRoutingProbes", successful.size()); report.put("profileAllowedRoutingProbes", profileAllowed.size());
        report.put("probeRouteFindings", routeIssues.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(e -> Map.of("probeId", e.getKey(), "issueCount", e.getValue().size(), "issues", e.getValue().stream().limit(20).toList())).toList());
        report.put("routingSeed", seed); report.put("sourceNodeMatchToleranceMetres", 10); report.put("checks", rows);
        report.put("physicalEntranceVerified", false); report.put("usableCapacityCertified", false); report.put("energyStateSimulated", false);
        report.put("scope", "Local class-permitted directed entry/exit routing only; no reachability from every origin, no physical entrance, live availability or HGV service certification. Up to four nearest edges per profile and eight neighbors per direction. Unknown/conditional restrictions remain review items. Probe trips are never simulated as transport demand.");
        VehicleArtifacts.write(artifacts.resolve("energy/network-access.json"), report);
    }
    private static Map<String, List<EnergyAccessRules.Issue>> auditProbeRoutes(Path osm, Path routed,
            List<Probe> probes, Map<String, VehicleType> profiles, Map<String, String> profileIds, TrafficRoutes network) throws IOException {
        Map<String, Probe> byId = new HashMap<>(); probes.forEach(probe -> byId.put(probe.id(), probe));
        Map<String, VehicleType> byType = new HashMap<>(); profiles.forEach((key, type) -> byType.put(profileIds.get(key), type));
        Map<String, List<String>> paths = new TreeMap<>(); Set<String> ways = new HashSet<>();
        if (Files.exists(routed)) XmlRecords.read(routed, Set.of("vehicle"), element -> {
            var probe = byId.get(element.getAttribute("id")); if (probe == null) throw new IOException("Unbekannte Stationsprobe in Routerausgabe");
            var routes = TrafficRoutes.elements(element, "route");
            if (routes.size() != 1) throw new IOException("Stationsprobe ohne eindeutige Route");
            var edges = Arrays.asList(routes.getFirst().getAttribute("edges").split("\\s+"));
            if (edges.isEmpty() || !edges.getFirst().equals(probe.from()) || !edges.getLast().equals(probe.to())) throw new IOException("Stationsprobe mit abweichenden Endkanten");
            paths.put(probe.id(), edges); edges.forEach(edge -> ways.add(FreightProfileChecks.wayId(edge)));
        });
        Map<String, Map<String, String>> sourceWays = new HashMap<>(), sourceNodes = new HashMap<>();
        Map<String, double[]> nodeCoordinates = new HashMap<>();
        Map<String, List<String>> references = new HashMap<>(); Set<String> neededNodes = new HashSet<>();
        XmlRecords.read(osm, Set.of("way"), element -> { if (ways.contains(element.getAttribute("id"))) {
            sourceWays.put(element.getAttribute("id"), FreightProfileChecks.tags(element));
            var refs = TrafficRoutes.elements(element, "nd").stream().map(n -> n.getAttribute("ref")).toList();
            references.put(element.getAttribute("id"), refs); neededNodes.addAll(refs);
        }});
        XmlRecords.read(osm, Set.of("node"), element -> { if (neededNodes.contains(element.getAttribute("id"))) {
            var tags = FreightProfileChecks.tags(element); if (!tags.isEmpty()) {
                sourceNodes.put(element.getAttribute("id"), tags);
                nodeCoordinates.put(element.getAttribute("id"), new double[]{Double.parseDouble(element.getAttribute("lon")), Double.parseDouble(element.getAttribute("lat"))});
            }
        }});
        Map<String, List<EnergyAccessRules.Issue>> result = new TreeMap<>();
        for (var path : paths.entrySet()) {
            var type = byType.get(byId.get(path.getKey()).type()); List<EnergyAccessRules.Issue> findings = new ArrayList<>();
            for (String edge : path.getValue()) {
                String way = FreightProfileChecks.wayId(edge); var tags = sourceWays.get(way);
                if (tags == null) findings.add(new EnergyAccessRules.Issue("unknown", "edge", edge, "source-way-unmapped"));
                else for (var issue : EnergyAccessRules.check(tags, type, edge.startsWith("-"))) findings.add(new EnergyAccessRules.Issue(issue.status(), "way:" + way + ":" + issue.key(), issue.value(), issue.reason()));
                for (String node : references.getOrDefault(way, List.of())) {
                    var coordinate = nodeCoordinates.get(node);
                    if (coordinate == null || network.distanceToEdge(edge, coordinate[0], coordinate[1]) > 10) continue;
                    for (var issue : EnergyAccessRules.check(sourceNodes.getOrDefault(node, Map.of()), type, edge.startsWith("-")))
                        findings.add(new EnergyAccessRules.Issue(issue.status(), "node:" + node + ":" + issue.key(), issue.value(), issue.reason()));
                }
            }
            result.put(path.getKey(), List.copyOf(new LinkedHashSet<>(findings)));
        }
        return result;
    }
    private static Set<String> words(String text) { return text.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(text.trim().split("\\s+"))); }
    private static String compatibility(JsonNode station, VehicleType type) {
        if (!(type.powertrain() instanceof ElectricPowertrain electric)) return "no-matching-modeled-energy-offer";
        Set<String> connectors = new HashSet<>();
        for (JsonNode offer : station.path("offers")) if (offer.path("carrier").asText().equals("electricity")) {
            for (JsonNode c : offer.path("connectors")) connectors.add(c.path("type").asText());
            for (JsonNode point : offer.path("points")) for (JsonNode c : point.path("connectors")) connectors.add(c.path("type").asText());
        }
        if (connectors.isEmpty() || connectors.contains("unknown")) return "unknown";
        return electric.connectors().stream().anyMatch(connectors::contains) ? "connector-match-only" : "no-matching-connector";
    }
    private static void writeProbes(Path file, List<Probe> probes, Map<String, VehicleType> profiles, Map<String, String> ids) throws IOException {
        try (var out = Files.newBufferedWriter(file)) {
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out); xml.writeStartElement("routes");
            for (var type : profiles.values()) { xml.writeEmptyElement("vType"); xml.writeAttribute("id", ids.get(type.key())); xml.writeAttribute("vClass", type.routing().sumoVehicleClass()); }
            for (var probe : probes) {
                xml.writeEmptyElement("trip"); xml.writeAttribute("id", probe.id()); xml.writeAttribute("type", probe.type()); xml.writeAttribute("depart", "0");
                xml.writeAttribute("from", probe.from()); xml.writeAttribute("to", probe.to());
                xml.writeAttribute(probe.entry() ? "arrivalLane" : "departLane", Integer.toString(probe.lane()));
                xml.writeAttribute(probe.entry() ? "arrivalPos" : "departPos", Double.toString(probe.position()));
            }
            xml.writeEndElement(); xml.flush();
        } catch (XMLStreamException e) { throw new IOException(e); }
    }
}
