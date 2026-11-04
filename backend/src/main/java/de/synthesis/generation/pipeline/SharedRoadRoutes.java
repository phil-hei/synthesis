package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.xml.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static de.synthesis.generation.pipeline.RoutingEngine.*;

/** Per-run routing, provenance and explicit fallback accounting for the optional road adapter. */
final class SharedRoadRoutes implements AutoCloseable {
    private final RoutingEngine engine;
    private final SumoRoadRouteMapper mapping;
    private final ObjectMapper json = new ObjectMapper();
    private final Writer report;
    private final Path dir;
    private final String osmHash, sumoHash;
    private int mapped, fallback;

    SharedRoadRoutes(Path dir, String osmXml) throws IOException {
        this.dir = dir;
        osmHash = hash(osmXml); sumoHash = FreightProfileChecks.sha256(dir.resolve("net.net.xml"));
        mapping = new SumoRoadRouteMapper(dir.resolve("net.net.xml"), dir.resolve("route-geo.nod.xml"),
                dir.resolve("route-geo.edg.xml"));
        report = Files.newBufferedWriter(dir.resolve("shared-road-routes.jsonl"));
        try { engine = RoutingEngine.fromOsmXml(osmXml); }
        catch (IOException | RuntimeException e) { report.close(); throw e; }
    }

    String vehicle(String id, String type, String vehicleClass, SumoRunner.Trip trip) throws IOException {
        var mode = "bicycle".equals(vehicleClass) ? MatrixGenerator.TravelMode.BIKE : MatrixGenerator.TravelMode.CAR;
        RoadPath path = engine.roadPath(Double.parseDouble(trip.fromLat()), Double.parseDouble(trip.fromLon()),
                Double.parseDouble(trip.toLat()), Double.parseDouble(trip.toLon()), mode);
        var match = mapping.map(path, vehicleClass);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vehicleId", id); row.put("vehicleType", type); row.put("vehicleClass", vehicleClass);
        row.put("osmSha256", osmHash); row.put("sumoNetSha256", sumoHash);
        row.put("path", path);
        row.put("requestedFrom", List.of(Double.parseDouble(trip.fromLon()), Double.parseDouble(trip.fromLat())));
        row.put("requestedTo", List.of(Double.parseDouble(trip.toLon()), Double.parseDouble(trip.toLat())));
        if (match == null) {
            fallback++;
            row.put("status", "fallback-duarouter");
            row.put("reason", path == null ? "graphhopper-unreachable" : "no-directed-permitted-osm-mapping");
            report.write(json.writeValueAsString(row)); report.write('\n');
            return null;
        }
        mapped++;
        String edgeList = String.join(" ", match.edges());
        row.put("status", "shared"); row.put("sumo", match);
        report.write(json.writeValueAsString(row)); report.write('\n');
        return String.format(Locale.ROOT,
                "  <vehicle id=\"%s\" type=\"%s\" depart=\"%d\" departLane=\"best\" departPos=\"%.3f\" arrivalPos=\"%.3f\"><route edges=\"%s\"/></vehicle>%n",
                escape(id), escape(type), trip.departSec(), match.departPos(), match.arrivalPos(), escape(edgeList));
    }

    /** duarouter must retain every mapped vehicle and its exact prescribed street sequence. */
    static void verifyWritten(Path dir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        Map<String, String> expected = new HashMap<>();
        try (var reader = Files.newBufferedReader(dir.resolve("shared-road-routes.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var row = json.readTree(line);
                if ("shared".equals(row.path("status").asText())) {
                    List<String> edges = new ArrayList<>();
                    row.path("sumo").path("edges").forEach(edge -> edges.add(edge.asText()));
                    expected.put(row.path("vehicleId").asText(), hash(String.join(" ", edges)));
                }
            }
        }
        verify(dir.resolve("routes.rou.xml"), expected);
        var summary = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(dir.resolve("shared-road-routes-summary.json").toFile());
        summary.put("verifiedRoutes", expected.size());
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("shared-road-routes-summary.json").toFile(), summary);
    }

    private static void verify(Path routes, Map<String, String> expected) throws IOException {
        Set<String> checked = new HashSet<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream input = Files.newInputStream(routes)) {
            XMLStreamReader xml = factory.createXMLStreamReader(input);
            try {
                String vehicle = null;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT && "vehicle".equals(xml.getLocalName()))
                        vehicle = xml.getAttributeValue(null, "id");
                    else if (event == XMLStreamConstants.START_ELEMENT && "route".equals(xml.getLocalName())
                            && expected.containsKey(vehicle)) {
                        String edges = xml.getAttributeValue(null, "edges");
                        if (edges == null || !expected.get(vehicle).equals(hash(edges.trim().replaceAll("\\s+", " "))))
                            throw new IOException("SUMO-Routenbasis verändert: " + vehicle);
                        checked.add(vehicle);
                    } else if (event == XMLStreamConstants.END_ELEMENT && "vehicle".equals(xml.getLocalName())) vehicle = null;
                }
            } finally { xml.close(); }
        } catch (XMLStreamException e) { throw new IOException("SUMO-Routenprüfung fehlgeschlagen", e); }
        if (checked.size() != expected.size()) throw new IOException("SUMO hat gemeinsame Routen verworfen: "
                + (expected.size() - checked.size()));

    }
    /** Keep actual simulation times separate from the static edge-speed estimate. */
    static void writeObservedTimes(Path dir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        Map<String, Map<String, String>> arrivals = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream input = Files.newInputStream(dir.resolve("tripinfo.xml"))) {
            XMLStreamReader xml = factory.createXMLStreamReader(input);
            try {
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT || !"tripinfo".equals(xml.getLocalName())) continue;
                    Map<String, String> attributes = new LinkedHashMap<>();
                    for (String key : List.of("depart", "arrival", "duration", "departDelay", "waitingTime", "timeLoss", "rerouteNo", "vaporized")) {
                        String value = xml.getAttributeValue(null, key);
                        if (value != null) attributes.put(key, value);
                    }
                    arrivals.put(xml.getAttributeValue(null, "id"), attributes);
                }
            } finally { xml.close(); }
        } catch (XMLStreamException e) { throw new IOException("SUMO-Ankünfte nicht lesbar", e); }
        try (var reader = Files.newBufferedReader(dir.resolve("shared-road-routes.jsonl"));
             var writer = Files.newBufferedWriter(dir.resolve("shared-road-times.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var route = json.readTree(line);
                String id = route.path("vehicleId").asText();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vehicleId", id); row.put("routeStatus", route.path("status").asText());
                row.put("graphHopperTimeSec", route.path("path").path("timeSec").isNumber()
                        ? route.path("path").path("timeSec").doubleValue() : null);
                row.put("sumoEdgeFreeFlowEstimateSec", route.path("sumo").path("edgeFreeFlowTimeSec").isNumber()
                        ? route.path("sumo").path("edgeFreeFlowTimeSec").doubleValue() : null);
                Map<String, String> arrival = arrivals.get(id);
                row.put("completion", arrival == null ? "missing-tripinfo" :
                        arrival.getOrDefault("vaporized", "").isBlank() ? "completed" : "vaporized");
                row.put("sumoTripInfo", arrival);
                writer.write(json.writeValueAsString(row)); writer.write('\n');
            }
        }
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"); }
    @Override public void close() throws IOException {
        try {
            report.close();
            json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("shared-road-routes-summary.json").toFile(),
                    Map.of("schemaVersion", 1, "mapped", mapped, "fallback", fallback, "verifiedRoutes", 0,
                            "osmSha256", osmHash, "sumoNetSha256", sumoHash, "graphHopperVersion", "8.0",
                            "geometryToleranceM", SumoRoadRouteMapper.TOLERANCE_M,
                            "netImport", "preserve-osm-sections-no-junction-join"));
        } finally { engine.close(); }
    }
}
