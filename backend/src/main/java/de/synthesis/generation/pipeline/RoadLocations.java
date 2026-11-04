package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleArtifacts;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Opt-in synthetic road locations; external fixed anchors stay unchanged, opportunities stay in-zone. */
public final class RoadLocations {
    public static final String FILE = "population/road-locations.json";
    public record Point(double lon,double lat) { }
    private record Segment(long way,int edge,int part,Point a,Point b) { }
    private record Key(int zone,double lon,double lat,String kind,boolean externalPlacement) { }
    private static final class Assignment {
        final Map<String,Object> row=new LinkedHashMap<>();
        final Point point;
        int uses;
        final List<String> examples=new ArrayList<>();
        Assignment(Point point){this.point=point;}
    }
    private final Map<Integer,List<Segment>> segments=new TreeMap<>();
    private final List<Zone> zones;
    private final STRtree zoneIndex=new STRtree();
    private final Envelope studyEnvelope=new Envelope();
    private final Map<Key,Assignment> assignments=new LinkedHashMap<>();
    private final String sourceSha256;
    private int failures;
    private Map<String,Object> connectivity=Map.of();
    private Map<String,Object> pedestrianConnectivity=Map.of();

    RoadLocations(List<Zone> zones,String sourceSha256) {
        this.zones=List.copyOf(zones);this.sourceSha256=sourceSha256;
        for(var z:zones) {
            if(!z.external)studyEnvelope.expandToInclude(new Envelope(z.minLon,z.maxLon,z.minLat,z.maxLat));
            segments.put(z.index,new ArrayList<>());
            zoneIndex.insert(new Envelope(z.minLon,z.maxLon,z.minLat,z.maxLat),z);
        }
        zoneIndex.build();
    }
    static boolean eligibleTags(Map<String,Object> tags) {
        if(tags.get("highway")==null || !Set.of("residential","living_street","unclassified","tertiary","tertiary_link","secondary","primary").contains(tags.get("highway")))return false;
        if(!guessableSidewalk(tags))return false;
        for(String key:List.of("access","vehicle","motor_vehicle","motorcar","bicycle","foot")) {
            Object value=tags.get(key);
            if(value!=null && !Set.of("yes","designated","permissive").contains(value.toString()))return false;
        }
        for(var entry:tags.entrySet()) {
            String key=entry.getKey();
            if(key.contains(":conditional") || key.startsWith("access:") || key.startsWith("motor_vehicle:")
                    || key.startsWith("vehicle:") || key.startsWith("motorcar:") || key.startsWith("bicycle:") || key.startsWith("foot:"))return false;
        }
        for(String key:List.of("bridge","tunnel","area"))if(tags.containsKey(key) && !"no".equals(tags.get(key)))return false;
        return !tags.containsKey("layer") || "0".equals(tags.get("layer"));
    }
    /** Match the conservative <=50 km/h envelope of the runner's native sidewalk guessing.
     * Secondary/tertiary/primary defaults exceed it in SUMO even though GH permits walking.
     * Unrecognized explicit speed values are rejected rather than guessed.
     */
    static boolean guessableSidewalk(Map<String,Object> tags) {
        return lowSpeed(tags,Set.of("residential","living_street","unclassified").contains(tags.getOrDefault("highway","")));
    }
    private static boolean lowSpeed(Map<String,Object> tags,boolean lowDefault) {
        if(tags.get("maxspeed")==null && !lowDefault)return false;
        for(String key:List.of("maxspeed","maxspeed:forward","maxspeed:backward")) {
            Object value=tags.get(key);if(value==null)continue;
            try {
                String text=value.toString();
                if(!text.matches("[0-9]+(?:\\.[0-9]+)?"))return false;
                double speed=Double.parseDouble(text);
                if(!Double.isFinite(speed) || speed<=0 || speed>50)return false;
            } catch(NumberFormatException unknown){return false;}
        }
        return true;
    }
    /** Conservative passenger connector types shared with SUMO's standard OSM import.
     * Bridges/tunnels/motorways can connect locations but cannot host them.
     * Service and track roads are deliberately excluded even if GH allows cars.
     */
    static boolean passengerConnectorTags(Map<String,Object> tags) {
        if(!Set.of("motorway","motorway_link","trunk","trunk_link","primary","primary_link",
                "secondary","secondary_link","tertiary","tertiary_link","unclassified",
                "residential","living_street","unsurfaced").contains(tags.getOrDefault("highway","")))return false;
        for(String key:List.of("access","vehicle","motor_vehicle","motorcar")) {
            Object value=tags.get(key);
            if(value!=null && !Set.of("yes","designated","permissive").contains(value.toString()))return false;
        }
        return tags.keySet().stream().noneMatch(k->k.contains(":conditional") || k.startsWith("access:")
                || k.startsWith("vehicle:") || k.startsWith("motor_vehicle:") || k.startsWith("motorcar:"));
    }
    /** Native low-speed walking roads and pedestrian paths; fast roads never join foot islands. */
    static boolean pedestrianConnectorTags(Map<String,Object> tags) {
        Object highway=tags.getOrDefault("highway","");
        boolean path=Set.of("service","track","path","footway","pedestrian","steps","step","stairs","bridleway").contains(highway);
        boolean road=Set.of("primary","primary_link","secondary","secondary_link","tertiary","tertiary_link",
                "residential","living_street","unclassified").contains(highway);
        if(!(path?lowSpeed(tags,true):road && guessableSidewalk(tags)))return false;
        for(String key:List.of("access","foot")) {
            Object value=tags.get(key);
            if(value!=null && !Set.of("yes","designated","permissive").contains(value.toString()))return false;
        }
        return tags.keySet().stream().noneMatch(k->k.contains(":conditional") || k.startsWith("access:") || k.startsWith("foot:"));
    }
    void connectivity(Map<String,Object> evidence){connectivity=Map.copyOf(evidence);}
    void pedestrianConnectivity(Map<String,Object> evidence){pedestrianConnectivity=Map.copyOf(evidence);}
    void add(long way,int edge,int part,Point a,Point b) {
        if(a.equals(b))return;
        for(Object item:zoneIndex.query(new Envelope(Math.min(a.lon,b.lon),Math.max(a.lon,b.lon),Math.min(a.lat,b.lat),Math.max(a.lat,b.lat)))) {
            Zone z=(Zone)item;var clipped=clip(z,a,b);
            if(clipped!=null)segments.get(z.index).add(new Segment(way,edge,part,clipped[0],clipped[1]));
        }
    }
    void finish() {
        var order=Comparator.comparingLong(Segment::way).thenComparingInt(Segment::edge).thenComparingInt(Segment::part);
        segments.values().forEach(list->list.sort(order));
    }
    public Point locate(Zone zone,double lon,double lat,String kind,String recordId)throws IOException {
        return locate(zone,lon,lat,kind,recordId,false);
    }
    /** Synthetic opportunities may move within their existing external zone, at most 1500m. */
    public Point locateOpportunity(Zone zone,double lon,double lat,String kind,String recordId)throws IOException {
        return locate(zone,lon,lat,kind,recordId,true);
    }
    private Point locate(Zone zone,double lon,double lat,String kind,String recordId,boolean opportunity)throws IOException {
        if(zone.external && opportunity && !kind.startsWith("opportunity:"))throw new IllegalArgumentException("Opportunity kind required");
        Key key=new Key(zone.index,lon,lat,kind,zone.external && opportunity);var known=assignments.get(key);
        if(known!=null){use(known,recordId);if(known.point==null)throw missing(zone);return known.point;}
        Point requested=new Point(lon,lat),best=null;Segment selected=null;double closest=Double.POSITIVE_INFINITY;
        if(zone.external && !opportunity)best=requested;
        else for(var segment:segments.getOrDefault(zone.index,List.of())) {
            Point candidate=nearest(requested,segment.a,segment.b);
            candidate=serialized(candidate);
            // The true LAEA containment and the actual exported precision are authoritative.
            if(!zone.contains(candidate.lat,candidate.lon)) {
                candidate=serialized(interpolate(segment.a,segment.b,.5));
                if(!zone.contains(candidate.lat,candidate.lon))continue;
            }
            double distance=TrafficRoutes.distance(lon,lat,new double[]{candidate.lon,candidate.lat},new double[]{candidate.lon,candidate.lat});
            if(zone.external && distance>1500)continue;
            if(zone.external && insideStudyArea(candidate))continue;
            if(distance<closest){closest=distance;best=candidate;selected=segment;}
        }
        var assignment=new Assignment(best);assignments.put(key,assignment);use(assignment,recordId);
        var row=assignment.row;row.put("zoneId",zone.id);row.put("matrixColumn",zone.index);row.put("kind",kind);
        row.put("requested",requested);row.put("selected",best);
        row.put("status",best==null?"no-eligible-road":zone.external?opportunity?"external-opportunity-placed":"external-unchanged":"placed");
        if(zone.external && opportunity)row.put("maximumDisplacementMetres",1500);
        if(selected!=null){row.put("osmWayId",selected.way);row.put("graphEdge",selected.edge);row.put("displacementMetres",closest);}
        if(best==null){failures++;throw missing(zone);}return best;
    }
    private static void use(Assignment a,String id){a.uses++;if(a.examples.size()<8)a.examples.add(id);}
    private boolean insideStudyArea(Point p) {
        if(!studyEnvelope.contains(p.lon,p.lat))return false;
        for(var z:zones)if(!z.external && z.contains(p.lat,p.lon))return true;
        return false;
    }
    private static IOException missing(Zone zone){return new IOException("Straßennahe Orte: keine geeignete öffentliche Straße in Zone "+zone.id+"; siehe "+FILE);}
    public void write(Path file)throws IOException {
        List<Map<String,Object>> rows=new ArrayList<>();Map<String,Integer> counts=new TreeMap<>();
        for(var a:assignments.values()) {
            var row=new LinkedHashMap<>(a.row);row.put("uses",a.uses);row.put("recordExamples",a.examples);row.put("examplesTruncated",a.uses>a.examples.size());rows.add(row);
            counts.merge((String)row.get("kind"),a.uses,Integer::sum);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("contract","synthesis-road-locations-v4");report.put("status",failures>0?"failed":"prepared");
        report.put("model","synthetic road points, not observed addresses or entrances");report.put("sourceOsmSha256",sourceSha256);
        report.put("policy","public ordinary roads excluding service/track; conservative numeric speed <=50 km/h or low-speed native defaults for sidewalk guessing; car/bike/foot access and profile subnetworks checked in GraphHopper; largest weak passenger and pedestrian components using conservative SUMO-compatible connector types; no conditional/restricted/bridge/tunnel candidates; native directed SUMO reachability remains authoritative");
        report.put("selection","nearest clipped segment within original zone; serialized WGS84 containment checked; no additional random draws");
        report.put("externalOpportunityMaxDisplacementMetres",1500);
        report.put("externalFixedAnchorsPreserved",true);
        report.put("externalOpportunitiesExcludeStudyArea",true);
        report.put("zoneInteriorMarginMetres",1);report.put("passengerConnectivity",connectivity);report.put("pedestrianConnectivity",pedestrianConnectivity);
        report.put("countsByKind",counts);report.put("failedLocations",failures);report.put("assignments",rows);
        report.put("zoneCandidates",zones.stream().map(z->Map.of("zoneId",z.id,"matrixColumn",z.index,"external",z.external,"segments",segments.getOrDefault(z.index,List.of()).size())).toList());
        VehicleArtifacts.write(file,report);
    }
    private static Point serialized(Point p){return new Point(Double.parseDouble(String.format(Locale.ROOT,"%.6f",p.lon)),Double.parseDouble(String.format(Locale.ROOT,"%.6f",p.lat)));}
    private static Point interpolate(Point a,Point b,double t){return new Point(a.lon+(b.lon-a.lon)*t,a.lat+(b.lat-a.lat)*t);}
    private static Point nearest(Point p,Point a,Point b) {
        double sx=Math.cos(Math.toRadians(p.lat)),x=(b.lon-a.lon)*sx,y=b.lat-a.lat;
        double denom=x*x+y*y,t=denom==0?0:((p.lon-a.lon)*sx*x+(p.lat-a.lat)*y)/denom;
        return interpolate(a,b,Math.max(0,Math.min(1,t)));
    }
    private static Point[] clip(Zone z,Point a,Point b) {
        double[] p=z.laeaRes>0?Epsg3035.fromLatLon(a.lat,a.lon):new double[]{a.lon,a.lat};
        double[] q=z.laeaRes>0?Epsg3035.fromLatLon(b.lat,b.lon):new double[]{b.lon,b.lat};
        double dx=1/(111320*Math.cos(Math.toRadians(z.centroidLat))),dy=1/111320.;
        double x0=z.laeaRes>0?z.laeaE+1:z.minLon+dx,x1=z.laeaRes>0?z.laeaE+z.laeaRes-1:z.maxLon-dx;
        double y0=z.laeaRes>0?z.laeaN+1:z.minLat+dy,y1=z.laeaRes>0?z.laeaN+z.laeaRes-1:z.maxLat-dy;
        if(x0>=x1 || y0>=y1)return null;
        double[] interval={0,1};
        if(!bound(p[0],q[0]-p[0],x0,x1,interval)||!bound(p[1],q[1]-p[1],y0,y1,interval)||interval[0]>=interval[1])return null;
        return new Point[]{interpolate(a,b,interval[0]),interpolate(a,b,interval[1])};
    }
    private static boolean bound(double start,double delta,double low,double high,double[] interval) {
        if(delta==0)return start>=low && start<=high;
        double a=(low-start)/delta,b=(high-start)/delta;
        interval[0]=Math.max(interval[0],Math.min(a,b));interval[1]=Math.min(interval[1],Math.max(a,b));return interval[0]<=interval[1];
    }
}
