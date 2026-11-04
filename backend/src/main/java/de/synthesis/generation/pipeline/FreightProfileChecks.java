package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleArtifacts;
import de.synthesis.vehicle.VehicleCatalog;
import de.synthesis.vehicle.VehicleType;
import org.w3c.dom.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

/** Optional static freight profile enforcement and source-rule audit. Never certifies access. */
final class FreightProfileChecks {
    record Profile(String catalogId, String version, String vehicleClass, double lengthM, double widthM,
                   double heightM, double actualMassKg, double ratedGrossMassKg, double maxSpeedKmh) { }
    record Event(String status, String reason, String sourceType, String sourceId, String key, String value) { }
    record Way(Map<String,String> tags, List<String> nodes) { }
    record Member(String role, String type, String ref) {
        String getAttribute(String key) { return switch (key) { case "role" -> role; case "type" -> type; case "ref" -> ref; default -> ""; }; }
    }
    record Turn(String id, Map<String,String> tags, List<Member> members) { }
    private final Map<String, Profile> profiles = new TreeMap<>();
    private final Map<String, Way> ways = new HashMap<>();
    private final Map<String, Map<String,String>> nodes = new HashMap<>();
    private final List<Turn> turns = new ArrayList<>();
    private final Map<String, List<Turn>> turnsByFrom = new HashMap<>();
    private final Map<String, String> edges = new HashMap<>();
    private final Path osm;
    private static final List<String> LIMITS = List.of("maxheight","maxwidth","maxlength","maxweight","maxweightrating","maxaxleload");
    private static final Pattern NUMBER = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*(m|cm|t|kg)?\\s*");

    FreightProfileChecks(Path osm) throws IOException {
        this.osm = osm;
        for (String clazz : List.of("truck","trailer")) {
            String id = clazz.equals("truck") ? "standard.heavyTruck.diesel" : "standard.articulatedTruck.diesel";
            VehicleType t = VehicleCatalog.standards().stream().filter(v -> v.id().equals(id) && v.version().equals("1")).findFirst().orElseThrow();
            var p = t.physical();
            profiles.put(clazz, new Profile(id,t.version(),clazz,p.lengthM(),p.widthM(),p.heightM(),p.maxGrossMassKg(),p.maxGrossMassKg(),p.maxSpeedKmh()));
        }
        XmlRecords.read(osm, Set.of("node"), n -> {
            var tags = tags(n);
            if (!tags.isEmpty()) nodes.put(n.getAttribute("id"),tags);
        });
        XmlRecords.read(osm, Set.of("way", "relation"), record -> {
            if (record.getTagName().equals("way")) {
                ways.put(record.getAttribute("id"), new Way(tags(record), TrafficRoutes.elements(record,"nd").stream()
                        .map(n -> n.getAttribute("ref")).filter(nodes::containsKey).toList()));
                return;
            }
            var tags = tags(record);
            if (!tags.getOrDefault("type", "").startsWith("restriction")) return;
            var members = TrafficRoutes.elements(record,"member").stream()
                    .map(m -> new Member(m.getAttribute("role"), m.getAttribute("type"), m.getAttribute("ref"))).toList();
            var turn = new Turn(record.getAttribute("id"), tags, members); turns.add(turn);
            for (var m : members) if (m.role().equals("from") && m.type().equals("way"))
                turnsByFrom.computeIfAbsent(m.ref(), ignored -> new ArrayList<>()).add(turn);
        });
    }

    Profile profile(String clazz) { return profiles.get(clazz); }
    static String wayId(String edge) { return edge.replaceFirst("^-","").split("#",2)[0]; }
    static Map<String,String> tags(Element e) {
        Map<String,String> result = new TreeMap<>();
        for (var t : TrafficRoutes.elements(e,"tag")) result.put(t.getAttribute("k"),t.getAttribute("v"));
        return result;
    }
    static String effective(Map<String,String> tags, String base, String clazz, boolean backward) {
        String dir = backward ? ":backward" : ":forward";
        List<String> keys = new ArrayList<>();
        if (clazz.equals("trailer")) keys.addAll(List.of(base+":hgv_articulated"+dir,base+":hgv_articulated"));
        keys.addAll(List.of(base+":hgv"+dir,base+":hgv",base+dir,base));
        return keys.stream().filter(tags::containsKey).findFirst().orElse(null);
    }
    static boolean conditional(Map<String,String> tags,String base,boolean backward) {
        return tags.keySet().stream().anyMatch(k -> k.startsWith(base+":") && k.endsWith(":conditional")
                && !(backward && k.contains(":forward")) && !(!backward && k.contains(":backward")));
    }
    static Double quantity(String value, boolean mass) {
        var m = NUMBER.matcher(value);
        if (!m.matches()) return null;
        double x = Double.parseDouble(m.group(1)); String unit = m.group(2);
        double factor = mass ? (unit == null || unit.equals("t") ? 1000 : unit.equals("kg") ? 1 : Double.NaN)
                : (unit == null || unit.equals("m") ? 1 : unit.equals("cm") ? .01 : Double.NaN);
        return Double.isFinite(x*factor) && x>0 ? x*factor : null;
    }
    static List<Event> evaluate(Map<String,String> tags, Profile p, boolean backward, String type, String id) {
        List<Event> events = new ArrayList<>();
        for (String base : LIMITS) {
            String key = effective(tags,base,p.vehicleClass(),backward);
            if (conditional(tags,base,backward)) {
                events.add(new Event("unknown","conditional-numeric-rule-unresolved",type,id,base,tags.toString())); continue;
            }
            if (key == null) continue;
            String value = tags.get(key);
            Double limit = quantity(value,base.equals("maxweight") || base.equals("maxweightrating") || base.equals("maxaxleload"));
            Double actual = switch(base) {
                case "maxheight" -> p.heightM(); case "maxwidth" -> p.widthM(); case "maxlength" -> p.lengthM();
                case "maxweight" -> p.actualMassKg(); case "maxweightrating" -> p.ratedGrossMassKg(); default -> null;
            };
            if (limit == null || actual == null) events.add(new Event("unknown",limit == null ? "non-numeric-limit-or-unit" : "axle-load-missing",type,id,key,value));
            else if (actual>limit+1e-9) events.add(new Event("violation","explicit-numeric-comparison",type,id,key,value));
        }
        // Preserve unsupported numeric forms; ignored direction/mode and signage metadata are not restrictions on this profile.
        for (var entry : tags.entrySet()) for (String base : LIMITS) {
            String k=entry.getKey();
            if (!k.startsWith(base+":")) continue;
            var suffix = new HashSet<>(List.of(k.substring(base.length()+1).split(":")));
            if (suffix.contains("source") || suffix.contains("signed") || suffix.contains("advisory")) continue;
            if (!Set.of("forward","backward","hgv","hgv_articulated","conditional").containsAll(suffix)
                    || !k.matches(base+"(?::(?:hgv|hgv_articulated))?(?::(?:forward|backward))?(?::conditional)?"))
                events.add(new Event("unknown","unsupported-numeric-key",type,id,k,entry.getValue()));
        }
        String dir = backward ? ":backward" : ":forward";
        boolean hasAccessConditional = tags.keySet().stream().anyMatch(k -> k.endsWith(":conditional")
                && List.of("hgv_articulated","hgv","motor_vehicle","vehicle","access").stream().anyMatch(b -> k.startsWith(b+":"))
                && !(backward && k.contains(":forward")) && !(!backward && k.contains(":backward")));
        if (hasAccessConditional) events.add(new Event("unknown","conditional-access-rule-unresolved",type,id,"access:conditional",tags.toString()));
        for (String base : p.vehicleClass().equals("trailer") ? List.of("hgv_articulated","hgv","motor_vehicle","vehicle","access") : List.of("hgv","motor_vehicle","vehicle","access")) {
            String key = tags.containsKey(base+dir) ? base+dir : base;
            if (!tags.containsKey(key)) continue;
            String value = tags.get(key);
            if (!Set.of("yes","designated","permissive").contains(value)) events.add(new Event(
                    value.equals("no") && !hasAccessConditional ? "violation" : "unknown",
                    value.equals("no") && !hasAccessConditional ? "explicit-access-prohibition" : "purpose-or-exemption-not-confirmed",type,id,key,value));
            break;
        }
        for (var entry : tags.entrySet()) {
            String key=entry.getKey();
            if (key.startsWith("hgv") && !Set.of("hgv","hgv:forward","hgv:backward","hgv_articulated","hgv_articulated:forward","hgv_articulated:backward").contains(key)
                    && !key.endsWith(":conditional"))
                events.add(new Event("unknown","unsupported-hgv-key",type,id,key,entry.getValue()));
        }
        for (var entry : tags.entrySet()) if (entry.getKey().startsWith("maxspeed") && entry.getKey().contains(":conditional"))
            events.add(new Event("review","speed-rule-not-access-ban",type,id,entry.getKey(),entry.getValue()));
        if (tags.containsKey("barrier") && !tags.get("barrier").equals("no"))
            events.add(new Event("unknown","node-control-unresolved",type,id,"barrier",tags.get("barrier")));
        return events;
    }
    List<Event> events(String edge, Profile p) {
        Way way = ways.get(wayId(edge));
        if (way == null) return List.of(new Event("unknown","source-way-unmapped","way",wayId(edge),"edge",edge));
        List<Event> result = new ArrayList<>(evaluate(way.tags(),p,edge.startsWith("-"),"way",wayId(edge)));
        // A source node can lie outside this SUMO split edge: audit conservatively, never close a whole way on that basis.
        for (String node : way.nodes()) {
            if (nodes.get(node).keySet().stream().anyMatch(k -> List.of("maxheight","maxwidth","maxlength","maxweight","maxaxleload","hgv","access","vehicle","motor_vehicle","barrier").stream().anyMatch(k::startsWith)))
                result.add(new Event("unknown","node-position-or-direction-unresolved","node",node,"control",nodes.get(node).toString()));
        }
        return result;
    }
    void prepare(Path network, Path report) throws IOException {
        List<Map<String,Object>> changed = new ArrayList<>();
        XmlRecords.rewrite(network, Set.of("edge"), edge -> {
            if (!edge.hasAttribute("from")) return;
            String id = edge.getAttribute("id"); edges.put(id,edge.getAttribute("to"));
            for (var p : profiles.values()) {
                var violations = events(id,p).stream().filter(e -> e.status().equals("violation")).toList();
                if (violations.isEmpty()) continue;
                for (var lane : TrafficRoutes.elements(edge,"lane")) deny(lane,p.vehicleClass());
                changed.add(Map.of("edgeId",id,"vehicleClass",p.vehicleClass(),"events",violations));
            }
        });
        VehicleArtifacts.write(report,Map.of("contract","synthesis-freight-profile-permissions-v1","profiles",profiles,
                "loadCase","assumed-at-rated-gross-mass","axleLoadsKnown",false,"sourceOsmSha256",sha256(osm),
                "profileSha256",profileSha256(),"restrictedEdges",changed,"unknownRulesClosed",false));
    }
    static void deny(Element lane,String clazz) {
        if (lane.hasAttribute("allow")) {
            var allowed = new TreeSet<>(List.of(lane.getAttribute("allow").split("\\s+")));
            if (allowed.contains("all")) throw new IllegalArgumentException("allow=all unsupported");
            allowed.remove(clazz);
            if (allowed.isEmpty()) { lane.removeAttribute("allow"); lane.setAttribute("disallow","all"); }
            else lane.setAttribute("allow",String.join(" ",allowed));
        } else {
            var denied = new TreeSet<String>();
            if (!lane.getAttribute("disallow").isBlank()) denied.addAll(List.of(lane.getAttribute("disallow").split("\\s+")));
            denied.add(clazz); lane.setAttribute("disallow",String.join(" ",denied));
        }
    }
    List<Event> turnEvents(List<String> path,String clazz) {
        List<Event> result = new ArrayList<>(); Set<String> unresolved = new HashSet<>();
        for (int i=0;i<path.size();i++) {
            String from=path.get(i);
            for (Turn turn : turnsByFrom.getOrDefault(wayId(from),List.of())) {
                var members=turn.members();
                var froms=members.stream().filter(m -> m.getAttribute("role").equals("from") && m.getAttribute("type").equals("way")).toList();
                var tos=members.stream().filter(m -> m.getAttribute("role").equals("to") && m.getAttribute("type").equals("way")).toList();
                var vias=members.stream().filter(m -> m.getAttribute("role").equals("via") && m.getAttribute("type").equals("node")).toList();
                String rule=turn.tags().get("restriction");
                String specific=clazz.equals("trailer") ? turn.tags().getOrDefault("restriction:hgv_articulated",turn.tags().get("restriction:hgv")) : turn.tags().get("restriction:hgv");
                if (specific!=null) rule=specific;
                var except = new HashSet<>(Arrays.asList(turn.tags().getOrDefault("except","").split(";")));
                if (except.contains("hgv") || except.contains("motor_vehicle") || except.contains("vehicle") || (clazz.equals("trailer") && except.contains("hgv_articulated"))) continue;
                if (rule==null && turn.tags().keySet().stream().allMatch(k -> !k.startsWith("restriction:") || k.equals("restriction:bicycle"))) continue;
                boolean unsupported = froms.size()!=1 || tos.size()!=1 || vias.size()!=1 || members.size()!=3
                        || turn.tags().keySet().stream().anyMatch(k -> k.contains("conditional"))
                        || (froms.size()==1 && tos.size()==1 && froms.getFirst().getAttribute("ref").equals(tos.getFirst().getAttribute("ref")) && !"no_u_turn".equals(rule))
                        || rule==null || !Set.of("no_left_turn","no_right_turn","no_straight_on","no_u_turn","only_left_turn","only_right_turn","only_straight_on").contains(rule)
                        || except.stream().anyMatch(e -> !Set.of("","bicycle","bus","psv","taxi","motorcar","emergency","foot","motorcycle").contains(e));
                if (unsupported || !edges.containsKey(from)) {
                    if (unresolved.add(turn.id())) result.add(new Event("unknown","turn-relation-unresolved","relation",turn.id(),"restriction",turn.tags().toString()));
                    continue;
                }
                String junction=edges.get(from);
                if (!junction.equals(vias.getFirst().getAttribute("ref"))) {
                    // Joined junctions cannot be mapped exactly by the source via-node ID.
                    if (junction.startsWith("cluster") && unresolved.add(turn.id())) result.add(new Event("unknown","joined-via-node-unmapped","relation",turn.id(),"restriction",rule));
                    continue;
                }
                if (i+1>=path.size()) continue; // route ends before making the turn
                String to=path.get(i+1); boolean target=wayId(to).equals(tos.getFirst().getAttribute("ref"));
                if (rule.startsWith("only_")) {
                    // Multiple only_* relations from the same via node permit the UNION of targets.
                    Set<String> targets = new HashSet<>(); boolean groupUnknown=false;
                    for (Turn sibling : turnsByFrom.getOrDefault(wayId(from),List.of())) {
                        var sv=sibling.members().stream().filter(m -> m.getAttribute("role").equals("via") && m.getAttribute("type").equals("node")).toList();
                        if (sv.size()!=1 || !sv.getFirst().getAttribute("ref").equals(vias.getFirst().getAttribute("ref"))) continue;
                        var sx=new HashSet<>(Arrays.asList(sibling.tags().getOrDefault("except","").split(";")));
                        if (sx.contains("hgv") || sx.contains("vehicle") || sx.contains("motor_vehicle") || (clazz.equals("trailer") && sx.contains("hgv_articulated"))) continue;
                        String sr=clazz.equals("trailer") ? sibling.tags().getOrDefault("restriction:hgv_articulated",sibling.tags().getOrDefault("restriction:hgv",sibling.tags().get("restriction"))) : sibling.tags().getOrDefault("restriction:hgv",sibling.tags().get("restriction"));
                        if (sibling.tags().keySet().stream().anyMatch(k -> k.contains("conditional"))) { groupUnknown=true;continue; }
                        if (sr!=null && sr.startsWith("only_")) {
                            if (sibling.members().size()!=3) groupUnknown=true;
                            else for (var m : sibling.members()) if (m.getAttribute("role").equals("to") && m.getAttribute("type").equals("way")) targets.add(m.getAttribute("ref"));
                        }
                    }
                    if (groupUnknown) { result.add(new Event("unknown","conditional-only-group-unresolved","relation",turn.id(),"restriction",rule));continue; }
                    target=targets.contains(wayId(to));
                }
                if (rule.equals("no_u_turn") && froms.getFirst().getAttribute("ref").equals(tos.getFirst().getAttribute("ref")))
                    target=target && from.startsWith("-")!=to.startsWith("-");
                boolean violation=rule.startsWith("only_") ? !target : target;
                if (violation) result.add(new Event("violation","source-turn-rule-violated","relation",turn.id(),"restriction",rule));
            }
        }
        return result;
    }
    void audit(Path routeFile, Path tripFile, Path report) throws IOException {
        Map<String,Element> routed = new HashMap<>();
        XmlRecords.read(routeFile, Set.of("vehicle"), v -> {
            if (routed.put(v.getAttribute("id"),v)!=null) throw new IOException("Doppelte Fahrzeug-ID im Routing");
        });
        List<Map<String,Object>> rows = new ArrayList<>(); Map<String,Integer> statuses = new TreeMap<>();
        XmlRecords.read(tripFile, Set.of("trip"), trip -> {
            String id=trip.getAttribute("id"); var v=routed.get(id); String status; List<Event> events = new ArrayList<>();
            String type=trip.getAttribute("type");
            if (!Set.of("heavyTruck","articulatedTruck").contains(type)) throw new IOException("Unbekanntes Standard-Frachtprofil: "+type);
            String clazz=type.equals("heavyTruck") ? "truck" : "trailer";
            if (v==null) status="no-native-route";
            else {
                var routes=TrafficRoutes.elements(v,"route");
                if (routes.size()!=1 || !v.getAttribute("type").equals(type)) throw new IOException("Ungültige Güterroute: "+id);
                var path=List.of(routes.getFirst().getAttribute("edges").split("\\s+"));
                if (!path.getFirst().equals(trip.getAttribute("from")) || !path.getLast().equals(trip.getAttribute("to"))) throw new IOException("Geänderte Güter-Endpunkte: "+id);
                for (String edge : path) events.addAll(events(edge,profiles.get(clazz)));
                events.addAll(turnEvents(path,clazz));
                status=events.stream().anyMatch(e -> e.status().equals("violation")) ? "explicit-violation"
                        : events.isEmpty() ? "no-explicit-violation-found" : "review-required";
            }
            statuses.merge(status,1,Integer::sum);
            rows.add(Map.of("id",id,"status",status,"vehicleClass",clazz,"events",events));
        });
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("contract","synthesis-freight-route-checks-v1"); summary.put("routeStatuses",statuses);
        summary.put("requested",rows.size()); summary.put("routes",rows); summary.put("profiles",profiles);
        summary.put("accessCertified",false); summary.put("sourceRuleCoverageVerified",false);
        summary.put("sourceRestrictionRelations",turns.size()); summary.put("conditionalTimeEvaluation","unresolved-in-static-pipeline-check");
        summary.put("loadCase","assumed-at-rated-gross-mass"); summary.put("sourceOsmSha256",sha256(osm));
        summary.put("routeSha256",sha256(routeFile)); summary.put("profileSha256",profileSha256());
        VehicleArtifacts.write(report,summary);
        if (statuses.containsKey("explicit-violation") || statuses.containsKey("no-native-route"))
            throw new IOException("Güterverkehrsprüfung fehlgeschlagen: "+statuses+"; siehe freight/route-checks.json");
    }
    String profileSha256() throws IOException {
        return hash(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(profiles));
    }
    static String sha256(Path file) throws IOException {
        try (var stream=Files.newInputStream(file)) {
            var digest=MessageDigest.getInstance("SHA-256"); byte[] chunk=new byte[65536]; int count;
            while ((count=stream.read(chunk))!=-1) digest.update(chunk,0,count);
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
