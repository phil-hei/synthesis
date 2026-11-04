package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Class-filtered freight snapping and actual SUMO route geometry, independent of demand source. */
final class TrafficRoutes {
    record Category(String trafficCategory, String demandSource, String vehicleType, String movement) { }
    record Edge(String id, List<double[]> shape, Set<String> allowed, Set<String> denied) { }
    private final Map<String, Edge> edges = new TreeMap<>();
    static final List<String> CLASSES = List.of("passenger", "bicycle", "pedestrian", "delivery", "truck", "trailer");
    private final Map<Long, List<Edge>> tiles = new HashMap<>();
    private final List<Edge> wideEdges = new ArrayList<>();
    private final Map<String, String> endpointNodes = new HashMap<>();
    private final Set<String> deadEnds = new HashSet<>();
    private final List<Map<String,Object>> boundaryWarnings = new ArrayList<>();
    private double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
    private double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
    private static final double TILE = .005;
    private static final class Links {
        int[] values=new int[2];int size;
        void add(int value){if(size==values.length)values=Arrays.copyOf(values,size*2);values[size++]=value;}
    }
    private static final class Vertex {
        final String id;final int index,mask;final int[] laneMasks;final Links incoming=new Links();
        Vertex(String id,int index,int mask,int[] laneMasks){this.id=id;this.index=index;this.mask=mask;this.laneMasks=laneMasks;}
        int laneMask(String index){try{int i=Integer.parseInt(index);return i>=0 && i<laneMasks.length?laneMasks[i]:mask;}catch(NumberFormatException e){return mask;}}
    }
    private final Map<String,Vertex> vertices=new HashMap<>();
    private final List<Vertex> vertexList=new ArrayList<>();
    private final Map<Long,BitSet> reverseReachability=new LinkedHashMap<>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,BitSet> entry){return size()>64;}
    };
    private boolean graphAvailable;
    record Pair(Match from,Match to,Boolean possibleDirectedConnection,boolean directionAdjusted) { }

    record Match(String edgeId, double distanceMetres, String fromNode, String toNode,
                 boolean fromDeadEnd, boolean toDeadEnd, boolean nearNetworkEnvelope) { }
    private static long tile(int x, int y) { return ((long)x << 32) ^ (y & 0xffffffffL); }
    private static int cell(double value) { return (int)Math.floor(value / TILE); }


    TrafficRoutes(Path network, Path plainNodes, Path plainEdges) throws IOException {
        Map<String, double[]> nodes = new HashMap<>();
        XmlRecords.read(plainNodes, Set.of("node"), n -> {
            double lon = Double.parseDouble(n.getAttribute("x")), lat = Double.parseDouble(n.getAttribute("y"));
            nodes.put(n.getAttribute("id"), new double[]{lon,lat});
            minLon = Math.min(minLon,lon); maxLon = Math.max(maxLon,lon);
            minLat = Math.min(minLat,lat); maxLat = Math.max(maxLat,lat);
            if (n.getAttribute("type").equals("dead_end")) deadEnds.add(n.getAttribute("id"));
        });
        Map<String, Set<String>> netEdges = new HashMap<>();
        XmlRecords.read(network, Set.of("edge","connection"), e -> {
            if(e.getTagName().equals("connection")) {
                graphAvailable=true;
                var from=vertices.get(e.getAttribute("from"));var to=vertices.get(e.getAttribute("to"));
                if(from!=null && to!=null) {
                    int mask=from.laneMask(e.getAttribute("fromLane")) & to.laneMask(e.getAttribute("toLane")) & permissionMask(e);
                    if(mask!=0){to.incoming.add(from.index);to.incoming.add(mask);}
                }
                return;
            }
            if (e.getAttribute("id").startsWith(":")) return;
            Set<String> allowed = new HashSet<>();
            for (Element lane : elements(e, "lane")) for (String clazz : CLASSES) {
                Set<String> allow = words(lane.getAttribute("allow"));
                Set<String> disallow = words(lane.getAttribute("disallow"));
                if ((allow.isEmpty() || allow.contains("all") || allow.contains(clazz))
                        && !disallow.contains("all") && !disallow.contains(clazz)) allowed.add(clazz);
            }
            netEdges.put(e.getAttribute("id"), Set.copyOf(allowed));
            int mask=0;for(int i=0;i<CLASSES.size();i++)if(allowed.contains(CLASSES.get(i)))mask|=1<<i;
            int[] laneMasks=elements(e,"lane").stream().mapToInt(TrafficRoutes::permissionMask).toArray();
            var vertex=new Vertex(e.getAttribute("id"),vertexList.size(),mask,laneMasks);vertices.put(vertex.id,vertex);vertexList.add(vertex);
        });
        XmlRecords.read(plainEdges, Set.of("edge"), e -> {
            List<double[]> shape = new ArrayList<>();
            if (nodes.containsKey(e.getAttribute("from"))) shape.add(nodes.get(e.getAttribute("from")));
            if (!e.getAttribute("shape").isBlank()) for (String point : e.getAttribute("shape").trim().split("\\s+")) {
                String[] xy = point.split(",");
                shape.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
            }
            if (nodes.containsKey(e.getAttribute("to"))) shape.add(nodes.get(e.getAttribute("to")));
            Set<String> allowed = netEdges.get(e.getAttribute("id"));
            if (allowed == null || shape.size() < 2) return;
            Edge edge = new Edge(e.getAttribute("id"), List.copyOf(shape), allowed, Set.of());
            edges.put(edge.id(),edge);
            endpointNodes.put(edge.id(),e.getAttribute("from") + "\n" + e.getAttribute("to"));
            String type=e.getAttribute("type");
            if(type.contains("motorway") || type.contains("trunk") || type.contains("primary"))
                for(String node:List.of(e.getAttribute("from"),e.getAttribute("to"))) {
                    double[] point=nodes.get(node);
                    if(point!=null && deadEnds.contains(node) && nearEnvelope(point[0],point[1],1000))
                        boundaryWarnings.add(Map.of("edgeId",edge.id(),"nodeId",node,"coordinate",List.of(point[0],point[1]),
                                "reason","arterial-dead-end-near-network-envelope","status","review-required"));
                }
            double x0=Double.POSITIVE_INFINITY,y0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1;
            for (double[] point : shape) { x0=Math.min(x0,point[0]);x1=Math.max(x1,point[0]);y0=Math.min(y0,point[1]);y1=Math.max(y1,point[1]); }
            if ((long)(cell(x1)-cell(x0)+1)*(cell(y1)-cell(y0)+1)>256) wideEdges.add(edge);
            else for(int x=cell(x0);x<=cell(x1);x++) for(int y=cell(y0);y<=cell(y1);y++)
                tiles.computeIfAbsent(tile(x,y),ignored->new ArrayList<>()).add(edge);
        });
    }

    String nearest(double lon, double lat, String vehicleClass) throws IOException {
        return match(lon,lat,vehicleClass).edgeId();
    }

    Match match(double lon, double lat, String vehicleClass) throws IOException {
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lat)>90 || Math.abs(lon)>180)
            throw new IOException("Ungültige Straßenkoordinate: " + lon + "," + lat);
        double dx=300/(111320*Math.max(.001,Math.cos(Math.toRadians(lat)))),dy=300/111320.;
        Map<String,Edge> candidates=new TreeMap<>();
        for (var e : wideEdges) candidates.put(e.id(),e);
        for(int x=cell(lon-dx);x<=cell(lon+dx);x++) for(int y=cell(lat-dy);y<=cell(lat+dy);y++)
            for(var e:tiles.getOrDefault(tile(x,y),List.of())) candidates.put(e.id(),e);
        String best=null; double closest=300;
        for (var edge : candidates.values()) {
            if (!edge.allowed().contains(vehicleClass)) continue;
            for(int i=1;i<edge.shape().size();i++) {
                double d=distance(lon,lat,edge.shape().get(i-1),edge.shape().get(i));
                if(d<closest) { closest=d;best=edge.id(); }
            }
        }
        if(best==null) throw new IOException("Keine für " + vehicleClass + " zulässige Kante innerhalb 300 m bei " + lon + "," + lat);
        String[] nodes=endpointNodes.get(best).split("\n",-1);
        boolean fringe=lon-minLon<dx || maxLon-lon<dx || lat-minLat<dy || maxLat-lat<dy;
        return new Match(best,closest,nodes[0],nodes[1],deadEnds.contains(nodes[0]),deadEnds.contains(nodes[1]),fringe);
    }

    /** Bounded candidates for infrastructure audits; never changes ordinary demand snapping. */
    List<Match> candidates(double lon, double lat, String clazz, double radius, int limit) throws IOException {
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90)
            throw new IOException("Ungültige Stationskoordinate");
        double dx = radius / (111320 * Math.max(.001, Math.cos(Math.toRadians(lat)))), dy = radius / 111320.;
        Map<String, Edge> nearby = new TreeMap<>();
        for (var edge : wideEdges) nearby.put(edge.id(), edge);
        for (int x = cell(lon - dx); x <= cell(lon + dx); x++) for (int y = cell(lat - dy); y <= cell(lat + dy); y++)
            for (var edge : tiles.getOrDefault(tile(x, y), List.of())) nearby.put(edge.id(), edge);
        List<Match> found = new ArrayList<>();
        for (var edge : nearby.values()) {
            if (!edge.allowed().contains(clazz)) continue;
            double d = Double.POSITIVE_INFINITY;
            for (int i = 1; i < edge.shape().size(); i++) d = Math.min(d, distance(lon, lat, edge.shape().get(i - 1), edge.shape().get(i)));
            if (d <= radius) {
                String[] ends = endpointNodes.get(edge.id()).split("\n", -1);
                found.add(new Match(edge.id(), d, ends[0], ends[1], deadEnds.contains(ends[0]), deadEnds.contains(ends[1]), nearEnvelope(lon, lat, radius)));
            }
        }
        return found.stream().sorted(Comparator.comparingDouble(Match::distanceMetres).thenComparing(Match::edgeId)).limit(limit).toList();
    }
    double distanceToEdge(String edgeId, double lon, double lat) {
        var edge = edges.get(edgeId); if (edge == null) return Double.POSITIVE_INFINITY;
        double result = Double.POSITIVE_INFINITY;
        for (int i = 1; i < edge.shape().size(); i++) result = Math.min(result, distance(lon, lat, edge.shape().get(i - 1), edge.shape().get(i)));
        return result;
    }
    List<double[]> geometry(String id)throws IOException{var edge=edges.get(id);if(edge==null)throw new IOException("Routenkante ohne Geometrie: "+id);return edge.shape();}
    record Projection(double lon, double lat, double fraction) { }
    Projection project(String id, double lon, double lat) {
        var shape = edges.get(id).shape(); double total = 0;
        for (int i = 1; i < shape.size(); i++) total += VehicleContinuity.distance(shape.get(i - 1)[0], shape.get(i - 1)[1], shape.get(i)[0], shape.get(i)[1]);
        double walked = 0, best = Double.POSITIVE_INFINITY, bestFraction = 0; double[] bestPoint = shape.getFirst();
        for (int i = 1; i < shape.size(); i++) {
            double[] a = shape.get(i - 1), b = shape.get(i); double sx = 111320 * Math.cos(Math.toRadians(lat));
            double vx = (b[0] - a[0]) * sx, vy = (b[1] - a[1]) * 111320;
            double fraction = vx * vx + vy * vy == 0 ? 0 : Math.max(0, Math.min(1, ((lon - a[0]) * sx * vx + (lat - a[1]) * 111320 * vy) / (vx * vx + vy * vy)));
            double length = VehicleContinuity.distance(a[0], a[1], b[0], b[1]);
            double d = distance(lon, lat, a, b);
            if (d < best) { best = d; bestPoint = new double[]{a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1])}; bestFraction = total == 0 ? 0 : (walked + fraction * length) / total; }
            walked += length;
        }
        return new Projection(bestPoint[0], bestPoint[1], bestFraction);
    }

    private boolean nearEnvelope(double lon,double lat,double metres) {
        double dx=metres/(111320*Math.max(.001,Math.cos(Math.toRadians(lat)))),dy=metres/111320.;
        return lon-minLon<dx || maxLon-lon<dx || lat-minLat<dy || maxLat-lat<dy;
    }
    List<Map<String,Object>> boundaryWarnings() { return List.copyOf(boundaryWarnings); }
    private static int permissionMask(Element element) {
        var allow=words(element.getAttribute("allow"));var deny=words(element.getAttribute("disallow"));int mask=0;
        for(int i=0;i<CLASSES.size();i++)if((allow.isEmpty() || allow.contains("all") || allow.contains(CLASSES.get(i)))
                && !deny.contains("all") && !deny.contains(CLASSES.get(i)))mask|=1<<i;
        return mask;
    }
    /** Necessary directed reachability only: lane changes and internal/via restrictions remain native checks. */
    Boolean possibleConnection(String from,String to,String clazz) {
        if(clazz.equals("pedestrian") || !graphAvailable)return null;
        var start=vertices.get(from);var end=vertices.get(to);int bit=CLASSES.indexOf(clazz);
        if(start==null || end==null || bit<0)return null;
        long key=((long)end.index<<8)|bit;int mask=1<<bit;
        var reached=reverseReachability.get(key);
        if(reached==null) {
            reached=new BitSet(vertexList.size());int[] queue=new int[vertexList.size()];int read=0,write=0;
            if((end.mask&mask)!=0){reached.set(end.index);queue[write++]=end.index;}
            while(read<write) {
                var vertex=vertexList.get(queue[read++]);
                for(int i=0;i<vertex.incoming.size;i+=2) {
                    int candidate=vertex.incoming.values[i];
                    if((vertex.incoming.values[i+1]&mask)!=0 && !reached.get(candidate) && (vertexList.get(candidate).mask&mask)!=0){reached.set(candidate);queue[write++]=candidate;}
                }
            }
            reverseReachability.put(key,reached);
        }
        return reached.get(start.index);
    }
    Pair matchPair(double fromLon,double fromLat,double toLon,double toLat,String clazz,String explicitFrom,String explicitTo)throws IOException {
        var from=matchResolved(explicitFrom,fromLon,fromLat,clazz);var to=matchResolved(explicitTo,toLon,toLat,clazz);
        Boolean connection=possibleConnection(from.edgeId(),to.edgeId(),clazz);
        if(!Boolean.FALSE.equals(connection))return new Pair(from,to,connection,false);
        var origins=new ArrayList<Match>();origins.add(from);var destinations=new ArrayList<Match>();destinations.add(to);
        if(explicitFrom==null)addOpposite(origins,from,fromLon,fromLat,clazz);
        if(explicitTo==null)addOpposite(destinations,to,toLon,toLat,clazz);
        List<Pair> options=new ArrayList<>();
        for(var origin:origins)for(var destination:destinations)
            if(Boolean.TRUE.equals(possibleConnection(origin.edgeId(),destination.edgeId(),clazz)))options.add(new Pair(origin,destination,true,true));
        return options.stream().min(Comparator.comparingDouble((Pair pair)->pair.from().distanceMetres()+pair.to().distanceMetres())
                .thenComparing(pair->pair.from().edgeId()).thenComparing(pair->pair.to().edgeId())).orElse(new Pair(from,to,false,false));
    }
    /** Explicit coarse company-location policy. Ordinary/private snapping stays unchanged.
     * Minimize the sum of endpoint displacements among nearby reachable candidates.
     * The caller must label its source point approximate-nearest and record the result.
     */
    Pair matchApproximatePair(double fromLon,double fromLat,double toLon,double toLat,String clazz)throws IOException {
        List<Match> origins=candidates(fromLon,fromLat,clazz,5000,Integer.MAX_VALUE);
        List<Match> destinations=candidates(toLon,toLat,clazz,5000,Integer.MAX_VALUE);
        if(origins.isEmpty()||destinations.isEmpty())throw new IOException("Keine zulässige Straße für Näherungszuordnung innerhalb 5 km");
        record Choice(int origin,int destination,double distance) {}
        var choices=new PriorityQueue<Choice>(Comparator.comparingDouble(Choice::distance)
                .thenComparingInt(Choice::origin).thenComparingInt(Choice::destination));
        var seen=new HashSet<Long>();
        choices.add(new Choice(0,0,origins.getFirst().distanceMetres()+destinations.getFirst().distanceMetres()));seen.add(0L);
        while(!choices.isEmpty()) {
            var choice=choices.remove();var origin=origins.get(choice.origin());var destination=destinations.get(choice.destination());
            boolean forward=!origin.edgeId().equals(destination.edgeId())
                    ||project(origin.edgeId(),fromLon,fromLat).fraction()<=project(destination.edgeId(),toLon,toLat).fraction();
            if(forward&&Boolean.TRUE.equals(possibleConnection(origin.edgeId(),destination.edgeId(),clazz)))
                return new Pair(origin,destination,true,true);
            for(int[] next:List.of(new int[]{choice.origin()+1,choice.destination()},new int[]{choice.origin(),choice.destination()+1})) {
                long key=((long)next[0]<<32)|next[1];
                if(next[0]<origins.size()&&next[1]<destinations.size()&&seen.add(key))
                    choices.add(new Choice(next[0],next[1],origins.get(next[0]).distanceMetres()+destinations.get(next[1]).distanceMetres()));
            }
        }
        throw new IOException("Keine erreichbare Näherungszuordnung für "+clazz+" innerhalb 5 km");
    }

    private void addOpposite(List<Match> candidates,Match original,double lon,double lat,String clazz) {
        String opposite=original.edgeId().startsWith("-")?original.edgeId().substring(1):"-"+original.edgeId();
        // Only the opposite direction of this exact OSM street segment, never a farther street.
        String[] endpoints=endpointNodes.get(original.edgeId()).split("\n",-1);
        if(!endpointNodes.getOrDefault(opposite,"").equals(endpoints[1]+"\n"+endpoints[0]))return;
        try { candidates.add(matchResolved(opposite,lon,lat,clazz)); }catch(IOException ignored){ }
    }
    List<Double> envelope() { return List.of(minLon,minLat,maxLon,maxLat); }
    Match matchResolved(String requested,double lon,double lat,String clazz) throws IOException {
        if(requested==null)return match(lon,lat,clazz);
        resolve(requested,lon,lat,clazz);Edge edge=edges.get(requested);double distance=Double.POSITIVE_INFINITY;
        for(int i=1;i<edge.shape().size();i++)distance=Math.min(distance,distance(lon,lat,edge.shape().get(i-1),edge.shape().get(i)));
        String[] nodes=endpointNodes.get(requested).split("\n",-1);
        return new Match(requested,distance,nodes[0],nodes[1],deadEnds.contains(nodes[0]),deadEnds.contains(nodes[1]),false);
    }
    String resolve(String requested, double lon, double lat, String vehicleClass) throws IOException {
        if (requested == null) return nearest(lon, lat, vehicleClass);
        Edge edge = edges.get(requested);
        if (edge == null) throw new IOException("SUMO-Kante fehlt im aktuellen Netz: " + requested);
        if (!edge.allowed().contains(vehicleClass))
            throw new IOException("SUMO-Kante " + requested + " ist für " + vehicleClass + " gesperrt");
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 1; i < edge.shape().size(); i++)
            distance = Math.min(distance, distance(lon, lat, edge.shape().get(i - 1), edge.shape().get(i)));
        if (distance > 300) throw new IOException("SUMO-Kante " + requested + " liegt mehr als 300 m vom angegebenen Zugang entfernt");
        return requested;
    }

    void writeNetwork(Path file) throws IOException {
        var mapper = new ObjectMapper();
        try (var out = mapper.getFactory().createGenerator(file.toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            out.writeStartObject(); out.writeStringField("type", "FeatureCollection");
            out.writeStringField("contract", "synthesis-freight-network-v1"); out.writeArrayFieldStart("features");
            for (var edge : edges.values()) {
                if (!edge.allowed().contains("truck") && !edge.allowed().contains("trailer")) continue;
                var f = mapper.createObjectNode().put("type", "Feature");
                f.putObject("properties").put("edgeId", edge.id()).put("truck", edge.allowed().contains("truck"))
                        .put("trailer", edge.allowed().contains("trailer"));
                var coordinates = f.putObject("geometry").put("type", "LineString").putArray("coordinates");
                for (double[] point : edge.shape()) coordinates.addArray().add(point[0]).add(point[1]);
                mapper.writeTree(out, f);
            }
            out.writeEndArray(); out.writeEndObject();
        }
    }

    List<Edge> allEdges() { return List.copyOf(edges.values()); }

    static double distance(double lon, double lat, double[] a, double[] b) {
        double scaleX = 111_320 * Math.cos(Math.toRadians(lat)), scaleY = 111_320;
        double ax = (a[0] - lon) * scaleX, ay = (a[1] - lat) * scaleY;
        double bx = (b[0] - lon) * scaleX, by = (b[1] - lat) * scaleY;
        double dx = bx - ax, dy = by - ay;
        double t = dx * dx + dy * dy == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    static Set<String> vehicleIds(Path routes) throws IOException {
        Set<String> result = new HashSet<>();
        XmlRecords.read(routes, Set.of("vehicle"), v -> result.add(v.getAttribute("id")));
        return result;
    }

    void write(Path dir, Map<String, Category> categories, List<FreightDemandGenerator.Trip> freight,
               int ptVehicles) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        var chains = NativeVehicleChains.observations(dir.getParent());

        Set<String> emitted = new HashSet<>();
        Map<String, Integer> routed = new TreeMap<>();
        try (var out = mapper.getFactory().createGenerator(dir.resolve("traffic-routes.geojson").toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            out.writeStartObject(); out.writeStringField("type", "FeatureCollection"); out.writeArrayFieldStart("features");
            XmlRecords.read(dir.resolve("routes.rou.xml"), Set.of("vehicle"), v -> {
                Category c = categories.get(v.getAttribute("id"));
                if (c == null) return;
                emitted.add(v.getAttribute("id"));
                routed.merge(c.trafficCategory(), 1, Integer::sum);
                var route = elements(v, "route");
                if (route.isEmpty()) throw new IOException("SUMO-Fahrzeug ohne Route: " + v.getAttribute("id"));
                var f = mapper.createObjectNode().put("type", "Feature");
                var p = f.putObject("properties");
                p.put("id", v.getAttribute("id")).put("trafficCategory", c.trafficCategory())
                        .put("demandSource", c.demandSource()).put("vehicleType", c.vehicleType())
                        .put("movement", c.movement()).put("departSec", Double.parseDouble(v.getAttribute("depart")));
                var observation = chains.get(v.getAttribute("id"));
                if (observation != null) {
                    p.put("nativeVehicleId", observation.nativeVehicleId()).put("actualDeparture", observation.depart()).put("actualArrival", observation.arrival());
                }
                var g = f.putObject("geometry").put("type", "LineString");
                var coordinates = g.putArray("coordinates");
                for (String id : route.getFirst().getAttribute("edges").split("\\s+")) {
                    Edge edge = edges.get(id);
                    if (edge == null) throw new IOException("Routengeometrie fehlt für SUMO-Kante " + id);
                    for (double[] point : edge.shape()) coordinates.addArray().add(point[0]).add(point[1]);
                }
                mapper.writeTree(out, f);
            });
            out.writeEndArray(); out.writeEndObject();
        }
        List<String> missingFreight = freight.stream().map(FreightDemandGenerator.Trip::id)
                .filter(id -> !emitted.contains(id)).toList();
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("traffic-categories.json").toFile(), Map.of(
                "contract", "synthesis-traffic-categories-v1", "vehicles", categories,
                "routedByCategory", routed, "unroutedVehicleIds", categories.keySet().stream()
                        .filter(id -> !emitted.contains(id)).sorted().toList(),
                "publicTransportVehicles", ptVehicles, "geometry", "sumo-routed-edge-centerlines"));
        if (!missingFreight.isEmpty()) throw new IOException("SUMO hat " + missingFreight.size()
                + " schwere Güterfahrten verworfen: " + missingFreight.stream().limit(5).toList());
    }

    static Document read(Path path) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return f.newDocumentBuilder().parse(path.toFile());
        } catch (Exception e) { throw new IOException("Ungültiges SUMO-XML: " + path.getFileName(), e); }
    }
    static List<Element> elements(Node parent, String tag) {
        NodeList nodes = parent instanceof Document d ? d.getElementsByTagName(tag) : ((Element) parent).getElementsByTagName(tag);
        List<Element> result = new ArrayList<>();
        // Xerces rescans the unmatched tail on each getLength(): cache it once for large SUMO networks.
        int length = nodes.getLength();
        for (int i = 0; i < length; i++) result.add((Element) nodes.item(i));
        return result;
    }
    private static Set<String> words(String text) {
        return text.isBlank() ? Set.of() : new HashSet<>(List.of(text.trim().split("\\s+")));
    }
}
