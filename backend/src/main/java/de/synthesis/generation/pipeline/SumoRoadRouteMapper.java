package de.synthesis.generation.pipeline;

import javax.xml.stream.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static de.synthesis.generation.pipeline.RoutingEngine.*;

/** Conservative mapping of directed OSM paths onto SUMO edges and legal lane connections. */
final class SumoRoadRouteMapper {
    static final double TOLERANCE_M = 15;
    record Lane(String allow, String disallow, double length, double speed) {
        boolean permits(String vehicleClass) {
            Set<String> yes = tokens(allow), no = tokens(disallow);
            return (yes.isEmpty() || yes.contains("all") || yes.contains(vehicleClass))
                    && !no.contains("all") && !no.contains(vehicleClass);
        }
    }
    record Edge(String id, Set<String> ways, List<Point> shape, List<Lane> lanes) {}
    record Connection(String to, int fromLane, int toLane, String via) {}
    record Mapping(List<String> edges, double departPos, double arrivalPos, double edgeFreeFlowTimeSec) {}
    record Projection(double distance, double position, double length) {}
    private final Map<String, Edge> edges = new TreeMap<>();
    private final Map<String, List<Connection>> connections = new HashMap<>();
    private final Map<String, Lane> internalLanes = new HashMap<>();
    private final Map<String, List<Edge>> byWay = new HashMap<>();

    SumoRoadRouteMapper(Path net, Path geoNodes, Path geoEdges) throws IOException {
        Map<String, Point> nodes = new HashMap<>();
        parse(geoNodes, xml -> {
            if (xml.isStartElement() && "node".equals(xml.getLocalName()))
                nodes.put(attr(xml, "id"), new Point(number(xml, "x"), number(xml, "y")));
        });
        final String[] current = new String[3];
        Set<String> ways = new HashSet<>(); List<Point> shape = new ArrayList<>();
        parse(geoEdges, xml -> {
            if (xml.isStartElement() && "edge".equals(xml.getLocalName())) {
                current[0] = attr(xml, "id"); current[1] = attr(xml, "from"); current[2] = attr(xml, "to");
                ways.clear(); shape.clear();
                for (String pair : tokensOrdered(attr(xml, "shape"))) {
                    String[] xy = pair.split(","); shape.add(new Point(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
                }
                if (shape.isEmpty()) {
                    Point from = nodes.get(current[1]), to = nodes.get(current[2]);
                    if (from == null || to == null) throw new IOException("Geografische SUMO-Knoten fehlen");
                    shape.add(from); shape.add(to);
                }
            } else if (xml.isStartElement() && "param".equals(xml.getLocalName()) && "origId".equals(attr(xml, "key")))
                ways.addAll(tokens(attr(xml, "value")));
            else if (xml.isEndElement() && "edge".equals(xml.getLocalName()))
                edges.put(current[0], new Edge(current[0], Set.copyOf(ways), List.copyOf(shape), new ArrayList<>()));
        });
        parse(net, xml -> {
            if (!xml.isStartElement()) return;
            switch (xml.getLocalName()) {
                case "edge" -> current[0] = attr(xml, "id");
                case "lane" -> {
                    Lane properties = new Lane(attr(xml, "allow"), attr(xml, "disallow"),
                            number(xml, "length"), number(xml, "speed"));
                    Edge target = edges.get(current[0]);
                    if (target != null) target.lanes.add(properties);
                    else internalLanes.put(attr(xml, "id"), properties);
                }
                case "connection" -> {
                    String from = attr(xml, "from"), to = attr(xml, "to");
                    if (edges.containsKey(from) && edges.containsKey(to))
                        connections.computeIfAbsent(from, ignored -> new ArrayList<>()).add(
                                new Connection(to, (int) number(xml, "fromLane"), (int) number(xml, "toLane"), attr(xml, "via")));
                }
                default -> { }
            }
        });
        for (Edge edge : edges.values()) for (String way : edge.ways)
            byWay.computeIfAbsent(way, ignored -> new ArrayList<>()).add(edge);
    }

    Mapping map(RoadPath path, String vehicleClass) {
        if (path == null || path.segments().isEmpty()) return null;
        List<Set<String>> candidates = new ArrayList<>();
        for (WaySegment block : path.segments()) {
            List<Point> points = block.points(); Set<String> eligible = new TreeSet<>();
            for (Edge edge : byWay.getOrDefault(String.valueOf(block.osmWayId()), List.of())) {
                if (edge.lanes.stream().noneMatch(l -> l.permits(vehicleClass))) continue;
                Projection a = project(points.getFirst(), edge.shape), b = project(points.getLast(), edge.shape);
                Projection c = project(edge.shape.getFirst(), points), d = project(edge.shape.getLast(), points);
                if ((a.distance < TOLERANCE_M && b.distance < TOLERANCE_M && b.position > a.position + .01)
                        || (c.distance < TOLERANCE_M && d.distance < TOLERANCE_M && d.position > c.position + .01))
                    eligible.add(edge.id);
            }
            if (eligible.isEmpty()) return null;
            candidates.add(eligible);
        }
        Point start = path.segments().getFirst().points().getFirst();
        Point end = path.segments().getLast().points().getLast();
        record State(int block, String edge) {}
        ArrayDeque<State> queue = new ArrayDeque<>(); Map<State, State> previous = new HashMap<>();
        candidates.getFirst().stream().sorted(Comparator.comparingDouble(e -> project(start, edges.get(e).shape).distance))
                .filter(e -> project(start, edges.get(e).shape).distance < TOLERANCE_M).forEach(e -> {
                    State state = new State(0, e); previous.put(state, null); queue.add(state);
                });
        State finish = null;
        while (!queue.isEmpty()) {
            State state = queue.remove(); int block = state.block;
            if (block == candidates.size() - 1 && project(end, edges.get(state.edge).shape).distance < TOLERANCE_M) {
                finish = state; break;
            }
            // A SUMO edge can retain several original OSM way IDs after geometry merging.
            if (block + 1 < candidates.size() && candidates.get(block + 1).contains(state.edge)) {
                State next = new State(block + 1, state.edge);
                if (!previous.containsKey(next)) { previous.put(next, state); queue.add(next); }
            }
            for (Connection connection : connections.getOrDefault(state.edge, List.of())) {
                if (!permitted(state.edge, connection, vehicleClass)) continue;
                for (int nextBlock = block; nextBlock <= Math.min(block + 1, candidates.size() - 1); nextBlock++) {
                    if (!candidates.get(nextBlock).contains(connection.to)) continue;
                    State next = new State(nextBlock, connection.to);
                    if (!previous.containsKey(next)) { previous.put(next, state); queue.add(next); }
                }
            }
        }
        if (finish == null) return null;
        List<String> route = new ArrayList<>();
        for (State state = finish; state != null; state = previous.get(state)) {
            if (route.isEmpty() || !route.getLast().equals(state.edge)) route.add(state.edge);
        }
        Collections.reverse(route);
        // Matching IDs and connected endpoints alone can shortcut a curved or looping OSM way.
        // Require the entire original geometry (sampled at <=15 m) to remain covered.
        for (WaySegment block : path.segments()) {
            var shapes = route.stream().map(edges::get)
                    .filter(e -> e.ways.contains(String.valueOf(block.osmWayId()))).map(Edge::shape).toList();
            for (int i = 1; i < block.points().size(); i++) {
                Point a = block.points().get(i - 1), b = block.points().get(i);
                double length = Math.hypot((b.lon() - a.lon()) * Math.cos(Math.toRadians(a.lat())) * 111_195,
                        (b.lat() - a.lat()) * 111_195);
                int samples = Math.max(1, (int) Math.ceil(length / TOLERANCE_M));
                for (int j = 0; j <= samples; j++) {
                    double fraction = (double) j / samples;
                    Point point = new Point(a.lon() + fraction * (b.lon() - a.lon()), a.lat() + fraction * (b.lat() - a.lat()));
                    if (shapes.stream().noneMatch(shape -> project(point, shape).distance < TOLERANCE_M)) return null;
                }
            }
        }
        double depart = position(start, edges.get(route.getFirst()), vehicleClass);
        double arrival = position(end, edges.get(route.getLast()), vehicleClass);
        if (route.size() == 1 && arrival <= depart) return null;
        double time = 0;
        for (int i = 0; i < route.size(); i++) {
            Lane lane = edges.get(route.get(i)).lanes.stream().filter(l -> l.permits(vehicleClass)).findFirst().orElseThrow();
            double length = lane.length;
            if (i == 0) length -= depart;
            if (i == route.size() - 1) length -= lane.length - arrival;
            time += Math.max(0, length) / lane.speed;
        }
        return new Mapping(List.copyOf(route), depart, arrival, time);
    }

    private boolean permitted(String from, Connection c, String vehicleClass) {
        Edge first = edges.get(from), second = edges.get(c.to);
        Lane internal = internalLanes.get(c.via);
        return first.lanes.get(c.fromLane).permits(vehicleClass) && second.lanes.get(c.toLane).permits(vehicleClass)
                && (c.via.isBlank() || internal != null && internal.permits(vehicleClass));
    }
    private static double position(Point point, Edge edge, String vehicleClass) {
        Projection p = project(point, edge.shape);
        double length = edge.lanes.stream().filter(l -> l.permits(vehicleClass)).findFirst().orElseThrow().length;
        return p.length > 0 ? Math.min(length - .01, Math.max(0, p.position / p.length * length)) : 0;
    }
    static Projection project(Point point, List<Point> shape) {
        double scale = Math.cos(Math.toRadians(point.lat())) * 111_195;
        double best = Double.POSITIVE_INFINITY, position = 0, offset = 0;
        for (int i = 1; i < shape.size(); i++) {
            Point a = shape.get(i - 1), b = shape.get(i);
            double ax = (a.lon() - point.lon()) * scale, ay = (a.lat() - point.lat()) * 111_195;
            double dx = (b.lon() - a.lon()) * scale, dy = (b.lat() - a.lat()) * 111_195;
            double length = Math.hypot(dx, dy);
            if (length == 0) continue;
            double fraction = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (length * length)));
            double distance = Math.hypot(ax + fraction * dx, ay + fraction * dy);
            if (distance < best) { best = distance; position = offset + fraction * length; }
            offset += length;
        }
        return new Projection(best, position, offset);
    }
    @FunctionalInterface private interface XmlConsumer { void accept(XMLStreamReader xml) throws IOException; }
    private static void parse(Path path, XmlConsumer consumer) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream input = Files.newInputStream(path)) {
            XMLStreamReader xml = factory.createXMLStreamReader(input);
            try { while (xml.hasNext()) { xml.next(); consumer.accept(xml); } }
            finally { xml.close(); }
        } catch (XMLStreamException e) { throw new IOException("SUMO-Netz nicht lesbar: " + path.getFileName(), e); }
    }
    private static String attr(XMLStreamReader xml, String key) {
        String value = xml.getAttributeValue(null, key); return value == null ? "" : value;
    }
    private static double number(XMLStreamReader xml, String key) { return Double.parseDouble(attr(xml, key)); }
    private static Set<String> tokens(String value) { return new HashSet<>(tokensOrdered(value)); }
    private static List<String> tokensOrdered(String value) { return value.isBlank() ? List.of() : List.of(value.trim().split("\\s+")); }
}
