package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.vehicle.VehicleArtifacts;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/** ID-based accounting of every requested road vehicle/person, including native drops and day-end cases. */
final class TrafficExecutionAudit {
    record Request(String id,String source,String vehicleClass,boolean person,Map<String,String> endpoints) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern ID=Pattern.compile("(?:vehicle|trip|person) '([^']+)'");
    private static final Pattern TELEPORT=Pattern.compile("Teleporting vehicle '([^']+)'");
    private static final Pattern PAIR=Pattern.compile("No connection between edge '([^']+)' and edge '([^']+)'");
    private record Observation(double depart,boolean completed,boolean vaporized) { }
    private record Logs(Map<String,List<String>> warnings,Map<String,List<String>> pairs,List<String> teleports) { }

    private static Map<String,Request> requests(Path dir,Map<String,TrafficRoutes.Category> categories) throws IOException {
        Map<String,Request> result=new TreeMap<>();Map<String,String> classes=new HashMap<>();
        for(String name:List.of("trips.xml","freight-trips.xml","persons.xml")) {
            Path file=dir.resolve(name);if(!Files.exists(file))continue;
            XmlRecords.read(file,Set.of("vType","trip","vehicle","person"), e->{
                if(e.getTagName().equals("vType")){classes.put(e.getAttribute("id"),e.getAttribute("vClass"));return;}
                String id=e.getAttribute("id");boolean person=e.getTagName().equals("person");
                var category=categories.get(id);
                String source=person?"person-demand":category==null?"unclassified":category.demandSource();
                String clazz=person?"pedestrian":classes.getOrDefault(e.getAttribute("type"),"passenger");
                Map<String,String> ends=new TreeMap<>();
                Element trip=person?TrafficRoutes.elements(e,"personTrip").stream().findFirst().orElse(e):e;
                for(String key:List.of("from","to","fromLonLat","toLonLat"))if(trip.hasAttribute(key))ends.put(key,trip.getAttribute(key));
                if(result.put(id,new Request(id,source,clazz,person,ends))!=null)throw new IOException("Doppelte angeforderte SUMO-ID: "+id);
            });
        }
        return result;
    }
    private static Set<String> routed(Path dir) throws IOException {
        Set<String> ids=new HashSet<>();
        XmlRecords.read(dir.resolve("routes.rou.xml"),Set.of("vehicle","person"),e->{
            if(!ids.add(e.getAttribute("id")))throw new IOException("Doppelte geroutete SUMO-ID: "+e.getAttribute("id"));
        });return ids;
    }
    private static Logs logs(Path file) throws IOException {
        Map<String,List<String>> warnings=new HashMap<>();Map<String,List<String>> pairs=new HashMap<>();List<String> teleports=new ArrayList<>();
        if(!Files.exists(file))return new Logs(warnings,pairs,teleports);
        try(var reader=Files.newBufferedReader(file)) {
            String line;List<String> pair=null;
            while((line=reader.readLine())!=null) {
                var p=PAIR.matcher(line);if(p.find())pair=List.of(p.group(1),p.group(2));
                var id=ID.matcher(line);
                if(id.find() && (line.startsWith("Warning:") || line.startsWith("Error:"))) {
                    var rows=warnings.computeIfAbsent(id.group(1),k->new ArrayList<>());if(rows.size()<20)rows.add(line);
                    if(line.contains("Mandatory edge") && pair!=null){pairs.put(id.group(1),pair);pair=null;}
                }
                var t=TELEPORT.matcher(line);if(t.find())teleports.add(t.group(1));
            }
        }return new Logs(warnings,pairs,teleports);
    }
    private static String reason(List<String> warnings,boolean pair) {
        if(warnings.stream().anyMatch(s->s.contains("No edge found")))return "coordinate-unmatched";
        if(pair)return "no-directed-connection";
        return warnings.isEmpty()?"unexplained-native-drop":"native-no-route";
    }
    static void routing(Path dir,Map<String,TrafficRoutes.Category> categories,boolean strict,String nativeFailure) throws IOException {
        var requests=requests(dir,categories);var log=logs(dir.resolve("sumo.log"));Set<String> routes=Set.of();String parseError=null;
        try{routes=routed(dir);}catch(IOException e){parseError=e.getMessage();}
        boolean complete=nativeFailure==null && parseError==null;
        List<Map<String,Object>> rows=new ArrayList<>();Map<String,Integer> groups=new TreeMap<>();int missing=0,coordinateWarnings=0;
        for(var request:requests.values()) {
            boolean present=routes.contains(request.id());var warnings=log.warnings().getOrDefault(request.id(),List.of());
            String why=present?"routed":reason(warnings,log.pairs().containsKey(request.id()));
            if(!present){missing++;groups.merge(request.source()+"/"+request.vehicleClass()+"/"+why,1,Integer::sum);}
            if(present && warnings.stream().anyMatch(w->w.contains("No edge found")))coordinateWarnings++;
            Map<String,Object> row=new LinkedHashMap<>();row.put("request",request);row.put("status",!complete?"routing-aborted":why);
            row.put("routePresentInOutput",present);row.put("nativeWarnings",warnings);row.put("nativeMatchedEdges",log.pairs().get(request.id()));rows.add(row);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("contract","synthesis-traffic-routing-v1");
        report.put("status",!complete?"failed":missing>0 || coordinateWarnings>0?"incomplete":"complete");report.put("nativeCompleted",complete);
        report.put("nativeFailure",nativeFailure);report.put("routeParseError",parseError);report.put("requested",requests.size());
        report.put("routed",complete?requests.size()-missing:null);report.put("unrouted",complete?missing:null);
        report.put("retainedRoutesWithCoordinateWarnings",coordinateWarnings);report.put("reasons",groups);report.put("requests",rows);
        VehicleArtifacts.write(dir.resolve("traffic-routing.json"),JSON.valueToTree(report));
        if(nativeFailure==null && parseError!=null)throw new IOException("SUMO-Routen nicht lesbar; siehe sumo/traffic-routing.json: "+parseError);
        if(strict && complete && (missing>0 || coordinateWarnings>0))throw new IOException("SUMO-Routing unvollständig: "+missing+" fehlende Anfragen, "+coordinateWarnings+" Koordinatenwarnungen trotz Route; siehe sumo/traffic-routing.json");
    }
    static void requireRoutingComplete(Path dir,boolean strict)throws IOException {
        if(!strict)return;var report=JSON.readTree(dir.resolve("traffic-routing.json").toFile());
        if(!report.path("status").asText().equals("complete"))throw new IOException("SUMO-Routing unvollständig: "+report.path("unrouted").asInt()+" fehlende Anfragen; siehe sumo/traffic-routing.json");
    }
    static void requireExecutionComplete(Path dir,boolean strict)throws IOException {
        if(!strict)return;var report=JSON.readTree(dir.resolve("traffic-execution.json").toFile());int missing=0;
        for(String kind:List.of("vehicles","persons"))for(String key:List.of("unrouted","notDeparted","vaporized"))missing+=report.path(kind).path(key).asInt();
        if(missing>0)throw new IOException("SUMO-Ausführungsbilanz: "+missing+" verlorene Anfragen; siehe sumo/traffic-execution.json");
    }
    static void execution(Path dir,Map<String,TrafficRoutes.Category> categories,boolean strict) throws IOException {
        var requests=requests(dir,categories);var routes=routed(dir);var log=logs(dir.resolve("sumo.log"));Map<String,Observation> observed=new HashMap<>();
        XmlRecords.read(dir.resolve("tripinfo.xml"),Set.of("tripinfo","personinfo"),e->{
            boolean person=e.getTagName().equals("personinfo"),completed;
            if(person) {
                completed=e.getChildNodes().getLength()>0;boolean step=false;
                for(var n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child){step=true;completed &= number(child,"arrival",-1)>=0;}
                completed &= step;
            }else completed=number(e,"arrival",-1)>=0;
            var row=new Observation(number(e,"depart",-1),completed,vaporized(e));
            if(observed.put(e.getAttribute("id"),row)!=null)throw new IOException("Doppelte SUMO-Tripinfo-ID: "+e.getAttribute("id"));
        });
        var chains=NativeVehicleChains.observations(dir.getParent());
        Map<String,Integer> nativeVehicles=counts();Set<String> countedNative=new HashSet<>();Map<String,String> nativeSources=new HashMap<>();
        for(var r:requests.values())if(!r.person()) {
            var chain=chains.get(r.id());String id=chain==null?r.id():chain.nativeVehicleId();nativeSources.put(id,r.source());
            if(!countedNative.add(id))continue;
            var obs=observed.get(id);boolean routed=routes.contains(r.id()),departed=obs!=null && obs.depart()>=0;
            String status=!routed?"unrouted":!departed?"not-departed":obs.vaporized()?"vaporized":!obs.completed()?"unfinished-at-day-end":"completed";
            increment(nativeVehicles,routed,departed,status);
        }
        int[] totalTeleports={-1};
        XmlRecords.read(dir.resolve("statistics.xml"),Set.of("teleports"),e->totalTeleports[0]=(int)number(e,"total",-1));
        boolean teleportAccounting=totalTeleports[0]>=0 && totalTeleports[0]==log.teleports().size();
        Map<String,Integer> teleports=new HashMap<>();for(String id:log.teleports())teleports.merge(id,1,Integer::sum);
        Map<String,Map<String,Object>> groups=new TreeMap<>();List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,Integer> vehicles=counts(),persons=counts();int unrecoverable=0;
        for(var r:requests.values()) {
            var chain=chains.get(r.id());var obs=chain==null?observed.get(r.id()):new Observation(chain.depart(),chain.arrival()>=0,chain.vaporized());
            boolean routed=routes.contains(r.id()),departed=obs!=null && obs.depart()>=0;
            String status=!routed?"unrouted":!departed?"not-departed":obs.vaporized()?"vaporized":!obs.completed()?"unfinished-at-day-end":"completed";
            if(Set.of("unrouted","not-departed","vaporized").contains(status))unrecoverable++;
            Map<String,Object> row=new LinkedHashMap<>();row.put("request",r);row.put("status",status);
            row.put("nativeVehicleId",chain==null?r.id():chain.nativeVehicleId());
            row.put("teleportEvents",teleportAccounting?teleports.getOrDefault(chain==null?r.id():chain.nativeVehicleId(),0):null);rows.add(row);
            Map<String,Integer> category=r.person()?persons:vehicles;increment(category,routed,departed,status);
            String key=r.source()+"/"+r.vehicleClass();
            var group=groups.computeIfAbsent(key,k->{Map<String,Object> v=new LinkedHashMap<>();v.put("source",r.source());v.put("vehicleClass",r.vehicleClass());v.put("kind",r.person()?"person":"vehicle");v.put("counts",counts());return v;});
            @SuppressWarnings("unchecked") var c=(Map<String,Integer>)group.get("counts");increment(c,routed,departed,status);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("contract","synthesis-traffic-execution-v1");
        report.put("status",unrecoverable>0?"incomplete":vehicles.get("unfinished")+persons.get("unfinished")>0 || totalTeleports[0]!=0?"review-required":"complete");
        boolean routingVerified=Files.exists(dir.resolve("traffic-routing.json")) && JSON.readTree(dir.resolve("traffic-routing.json").toFile()).path("status").asText().equals("complete");
        report.put("routingVerified",routingVerified);report.put("demandRetained",unrecoverable==0 && routingVerified);report.put("vehicles",vehicles);report.put("persons",persons);report.put("groups",groups.values());
        report.put("nativeVehicles",nativeVehicles);report.put("vehicleCountBasis",chains.isEmpty()?"requested-road-vehicles":"selected-road-trip-segments");
        report.put("teleportAccountingComplete",teleportAccounting);report.put("nativeVehicleTeleports",totalTeleports[0]<0?null:totalTeleports[0]);
        report.put("teleportEvents",log.teleports().stream().map(id->Map.of("id",id,"source",nativeSources.getOrDefault(id,"additional-vehicle"))).toList());
        report.put("requests",rows);report.put("simulationEndSeconds",86400);
        VehicleArtifacts.write(dir.resolve("traffic-execution.json"),JSON.valueToTree(report));
        if(strict && unrecoverable>0)throw new IOException("SUMO-Ausführungsbilanz: "+unrecoverable+" verlorene Anfragen; siehe sumo/traffic-execution.json");
    }
    private static double number(Element e,String key,double fallback){String value=e.getAttribute(key);return value.isBlank()?fallback:Double.parseDouble(value);}
    static boolean vaporized(Element e){String reason=e.getAttribute("vaporized");return !reason.isBlank() && !reason.equals("end");}
    private static Map<String,Integer> counts(){Map<String,Integer> c=new LinkedHashMap<>();for(String key:List.of("requested","routed","departed","completed","unrouted","notDeparted","unfinished","vaporized"))c.put(key,0);return c;}
    private static void increment(Map<String,Integer> c,boolean routed,boolean departed,String status){
        c.merge("requested",1,Integer::sum);if(routed)c.merge("routed",1,Integer::sum);if(departed)c.merge("departed",1,Integer::sum);
        c.merge(switch(status){case "unrouted"->"unrouted";case "not-departed"->"notDeparted";case "unfinished-at-day-end"->"unfinished";default->status;},1,Integer::sum);
    }
}
