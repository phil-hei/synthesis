package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.vehicle.VehicleArtifacts;
import org.w3c.dom.Element;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Conservative offline SEVAS subset. No exceptions, temporal rules or partial-edge bans. */
public final class SevasFreightPermissions {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final List<String> CONDITIONS = List.of("tage_einzl", "tage_grppe", "zeit1_von", "zeit1_bis", "zeit2_von", "zeit2_bis");
    private static final List<String> SIGNS = List.of("1010_51", "1010_57", "1010_60", "1020_12", "1020_30", "1024_12", "1024_13", "1024_14", "1026_31", "1026_32", "1026_33", "1026_34", "1026_35", "1026_36", "1026_37", "1026_38", "1026_39", "1026_62", "1048_14", "1048_15", "1049_13", "1053_33", "1053_36", "1053_37");
    record Segment(String id, String way, int direction, String unsupported, List<double[]> points) { }
    public static JsonNode parse(byte[] bytes) {
        if (bytes.length > 5_000_000) throw new IllegalArgumentException("SEVAS-GeoJSON darf höchstens 5 MB groß sein");
        try { JsonNode data = JSON.readTree(bytes); segments(data); return data; }
        catch (IOException e) { throw new IllegalArgumentException("Ungültiges SEVAS-GeoJSON", e); }
    }
    public static void validate(JsonNode data) {
        if (data == null || data.isNull()) return;
        try { if (JSON.writeValueAsBytes(data).length > 5_000_000) throw new IllegalArgumentException("SEVAS-GeoJSON darf höchstens 5 MB groß sein"); }
        catch (IOException e) { throw new IllegalArgumentException("Ungültiges SEVAS-GeoJSON", e); }
        segments(data);
    }
    private static String text(JsonNode p, String key) {
        JsonNode v=p.get(key);
        if (v==null || !v.isValueNode() || v.isNull()) throw new IllegalArgumentException("SEVAS-Feld fehlt: " + key);
        return v.asText();
    }
    private static List<Segment> segments(JsonNode data) {
        if (data==null || !"FeatureCollection".equals(data.path("type").asText()) || !data.path("features").isArray()
                || data.path("features").isEmpty() || data.path("features").size()>10_000)
            throw new IllegalArgumentException("SEVAS benötigt eine FeatureCollection mit 1 bis 10.000 Restriktionssegmenten");
        if (data.has("crs")) throw new IllegalArgumentException("SEVAS: GeoJSON ohne CRS-Feld in Longitude/Latitude erwartet");
        List<Segment> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for (JsonNode f:data.path("features")) {
            JsonNode p=f.path("properties"),g=f.path("geometry");
            String id=text(p,"segment_id"),way=text(p,"osm_id"),version=text(p,"osm_vers"),dir=text(p,"fahrtri");
            if (!id.matches("[0-9]{1,20}") || !way.matches("[0-9]{1,20}") || !ids.add(id)
                    || !version.matches("osm_[0-9]{8}") || !Set.of("0","1","2").contains(dir))
                throw new IllegalArgumentException("SEVAS: ungültige/mehrfache ID, Netzversion oder Richtung");
            String reason="253".equals(text(p,"typ")) && text(p,"wert").isEmpty() ? "" : "unsupported-sign-or-value";
            for (String key:CONDITIONS) {
                String v=text(p,key);
                if (!v.equals(key.equals("tage_einzl") ? "0000000" : key.equals("tage_grppe") ? "0" : "")) reason="temporal-rule";
            }
            for (String sign:SIGNS) text(p,"vz_"+sign); // Missing exception fields are never interpreted as false.
            Set<String> known = new HashSet<>(List.of("segment_id", "restrkn_id", "name", "osm_vers", "osm_id", "fahrtri", "typ", "wert", "gemeinde", "kreis", "regbezirk"));
            known.addAll(CONDITIONS); for (String sign:SIGNS) known.add("vz_"+sign);
            var fields=p.fields();
            while(fields.hasNext()) { var a=fields.next(); if (!known.contains(a.getKey())) reason="unknown-schema-field"; if (a.getKey().startsWith("vz_")
                    && (!SIGNS.contains(a.getKey().substring(3)) || !a.getValue().isValueNode()
                    || !(a.getKey().equals("vz_1053_33") ? a.getValue().asText().isEmpty() : a.getValue().asText().equals("false")))) reason="exception-or-additional-sign"; }
            if (!"Feature".equals(f.path("type").asText()) || !"LineString".equals(g.path("type").asText())
                    || !g.path("coordinates").isArray() || g.path("coordinates").size()>2_000)
                throw new IllegalArgumentException("SEVAS: LineString erwartet");
            List<double[]> points=new ArrayList<>();
            for (JsonNode c:g.path("coordinates")) {
                if (!c.isArray() || c.size()!=2 || !c.get(0).isNumber() || !c.get(1).isNumber()) throw new IllegalArgumentException("SEVAS: Koordinate erwartet");
                double x=c.get(0).asDouble(),y=c.get(1).asDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || x<5 || x>10 || y<50 || y>53) throw new IllegalArgumentException("SEVAS: NRW-Koordinate in Longitude/Latitude erwartet");
                if (points.isEmpty() || x!=points.getLast()[0] || y!=points.getLast()[1]) points.add(new double[]{x,y});
            }
            if(points.size()<2 || length(points)>20_000) throw new IllegalArgumentException("SEVAS: leeres oder überlanges Segment");
            result.add(new Segment(id,way,Integer.parseInt(dir),reason,points));
        }
        return result;
    }
    static int apply(JsonNode data, Path net, Path nodes, Path plainEdges, Path report) throws IOException {
        List<Segment> segments=segments(data);
        Map<String,List<TrafficRoutes.Edge>> byWay=new HashMap<>();
        for(var e:new TrafficRoutes(net,nodes,plainEdges).allEdges()) byWay.computeIfAbsent(e.id().replaceFirst("^-","").split("#",2)[0],k->new ArrayList<>()).add(e);
        List<Map<String,Object>> rows=new ArrayList<>(); Set<String> changed=new TreeSet<>();
        for(var s:segments) {
            String status=s.unsupported(); List<String> selected=new ArrayList<>();
            if(status.isEmpty()) {
                var candidates=byWay.getOrDefault(s.way(),List.of());
                if (segments.stream().anyMatch(other -> !other.id().equals(s.id()) && other.way().equals(s.way()) && !other.unsupported().isEmpty())) {
                    rows.add(Map.of("segmentId",s.id(),"osmId",s.way(),"status","mixed-rules-on-way","edges",List.of()));
                    continue;
                }
                if(candidates.isEmpty()) status="way-missing";
                else {
                    List<List<double[]>> shapes=candidates.stream().map(TrafficRoutes.Edge::shape).toList();
                    if(!covered(s.points(),shapes)) status="geometry-mismatch";
                    else for(var edge:candidates) {
                        var shape=edge.shape();
                        if(!near(shape,s.points())) continue;
                        double delta=position(s.points().getLast(),shape)-position(s.points().getFirst(),shape);
                        if(Math.abs(delta)<1) { status="ambiguous-direction"; break; }
                        if(s.direction()==1 && delta<0 || s.direction()==2 && delta>0) continue;
                        if(!covered(shape,List.of(s.points()))) { status="partial-edge"; break; }
                        selected.add(edge.id());
                    }
                    if(status.isEmpty() && selected.isEmpty()) status="no-directed-edge";
                }
            }
            // Validate a whole segment before changing any of its edges.
            if(status.isEmpty()) { changed.addAll(selected); status="applied"; }
            rows.add(Map.of("segmentId",s.id(),"osmId",s.way(),"status",status,"edges",status.equals("applied")?selected:List.of()));
        }
        if (!changed.isEmpty()) XmlRecords.rewrite(net, Set.of("edge"), edge -> {
            if (!edge.hasAttribute("from") || !changed.contains(edge.getAttribute("id"))) return;
            for (Element lane : TrafficRoutes.elements(edge,"lane")) {
                if(lane.hasAttribute("allow") && !lane.getAttribute("allow").isBlank() && !lane.getAttribute("allow").equals("all")) {
                    var allow=new TreeSet<>(List.of(lane.getAttribute("allow").split("\\s+"))); allow.remove("truck"); allow.remove("trailer");
                    if(allow.isEmpty()) { lane.removeAttribute("allow"); lane.setAttribute("disallow","all"); }
                    else lane.setAttribute("allow",String.join(" ",allow));
                } else {
                    lane.removeAttribute("allow"); var denied=new TreeSet<String>();
                    if(!lane.getAttribute("disallow").isBlank()) denied.addAll(List.of(lane.getAttribute("disallow").split("\\s+")));
                    denied.addAll(List.of("truck","trailer")); lane.setAttribute("disallow",String.join(" ",denied));
                }
            }
        });
        Map<String,Long> counts=new TreeMap<>(); for(var row:rows) counts.merge(row.get("status").toString(),1L,Long::sum);
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(data)));
            VehicleArtifacts.write(report,Map.of("contract","synthesis-sevas-permissions-v1","quality","user-uploaded-unverified",
                    "snapshotSha256",hash,"sourceSnapshot",data,"counts",counts,"segments",rows,"restrictedEdges",changed,
                    "toleranceMetres",5,"routingPreferencesSupported",false,"networkVersionVerified",false));
        } catch(Exception e) { throw new IOException("SEVAS-Netzberechtigungen konnten nicht geschrieben werden",e); }
        return changed.size();
    }
    private static double metric(double[] a,double[] b) {return Math.hypot((a[0]-b[0])*111320*Math.cos(Math.toRadians(a[1])),(a[1]-b[1])*111320);}
    private static double length(List<double[]> a) {double n=0;for(int i=1;i<a.size();i++)n+=metric(a.get(i-1),a.get(i));return n;}
    private static double distance(double[] p,List<double[]> a) { double best=Double.POSITIVE_INFINITY;for(int i=1;i<a.size();i++) best=Math.min(best,TrafficRoutes.distance(p[0],p[1],a.get(i-1),a.get(i)));return best; }
    private static boolean near(List<double[]> a,List<double[]> b) {return a.stream().anyMatch(p->distance(p,b)<=5);}
    /** Every sample (at most 2m apart) must fit: deliberately stricter than the exploratory 95% rule. */
    private static boolean covered(List<double[]> a,List<List<double[]>> targets) {
        for(int i=1;i<a.size();i++) {double[] x=a.get(i-1),y=a.get(i);int steps=Math.max(1,(int)Math.ceil(metric(x,y)/2));
            for(int j=0;j<=steps;j++) { double t=(double)j/steps;double[] p={x[0]+(y[0]-x[0])*t,x[1]+(y[1]-x[1])*t};
                if(targets.stream().noneMatch(line->distance(p,line)<=5))return false; }} return true;
    }
    private static double position(double[] p,List<double[]> line) {
        double best=Double.POSITIVE_INFINITY,result=0,offset=0;
        for(int i=1;i<line.size();i++){double[] a=line.get(i-1),b=line.get(i);double sx=111320*Math.cos(Math.toRadians(p[1]));
            double ax=(a[0]-p[0])*sx,ay=(a[1]-p[1])*111320,dx=(b[0]-a[0])*sx,dy=(b[1]-a[1])*111320;
            double den=dx*dx+dy*dy,t=den==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));double d=Math.hypot(ax+t*dx,ay+t*dy),len=metric(a,b);
            if(d<best){best=d;result=offset+t*len;}offset+=len;
        } return result;
    }
}
