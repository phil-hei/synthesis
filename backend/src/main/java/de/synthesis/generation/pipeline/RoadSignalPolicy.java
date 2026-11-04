package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleArtifacts;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** OSM railway controls must not hold road-only traffic on shared tram lanes. */
final class RoadSignalPolicy {
    static final String REPORT="road-signals.json";
    private static final Set<String> ROAD_CLASSES=Set.of("passenger","bicycle","bus","delivery","truck","trailer");
    private final Path report;
    private final boolean roadOnly;
    private final Map<String,Object> evidence=new LinkedHashMap<>();
    private final Set<String> roadLights=new TreeSet<>();
    private record Approach(String edge,String target,double length){}

    private RoadSignalPolicy(Path dir,boolean roadOnly){this.report=dir.resolve(REPORT);this.roadOnly=roadOnly;}
    static RoadSignalPolicy prepare(Path dir,boolean roadOnly)throws IOException {
        var policy=new RoadSignalPolicy(dir,roadOnly);
        policy.evidence.put("contract","synthesis-road-signal-import-v1");
        policy.evidence.put("roadVehiclesOnly",roadOnly);
        policy.evidence.put("sourceOsmSha256",OsmDataSnapshot.sha256(dir.resolve("network.osm")));
        if(roadOnly)policy.readRoadLights(dir.resolve("network.osm"));
        policy.evidence.put("forcedRoadTrafficLightSourceIds",policy.roadLights);
        policy.evidence.put("nativeImportArguments",policy.arguments());
        policy.evidence.put("status",roadOnly?"prepared":"rail-signals-preserved");
        policy.evidence.put("scope",roadOnly?"No rail demand: discard imported railway signals, retain split nodes and explicitly restore dual-tagged road traffic lights. Rail crossings and ordinary traffic lights are unchanged.":"Opaque public transport provider may include rail traffic; preserve railway signaling.");
        VehicleArtifacts.write(policy.report,policy.evidence);return policy;
    }
    List<String> arguments() {
        if(!roadOnly)return List.of();
        var args=new ArrayList<>(List.of("--railway.signals.discard"));
        if(!roadLights.isEmpty())args.addAll(List.of("--tls.set",String.join(",",roadLights)));
        return List.copyOf(args);
    }
    boolean verify(Path net)throws IOException {
        if(!roadOnly)return true;
        Map<String,String> restored=new TreeMap<>(),junctionTypes=new HashMap<>();int[] railSignals={0};
        Map<String,List<Approach>> approaches=new TreeMap<>();
        roadLights.forEach(id->approaches.put(id,new ArrayList<>()));
        XmlRecords.read(net,Set.of("edge","junction"),e->{
            String id=e.getAttribute("id"),type=e.getAttribute("type");
            if(e.getTagName().equals("junction")) {
                junctionTypes.put(id,type);
                if(type.equals("rail_signal"))railSignals[0]++;
                if(roadLights.contains(id))restored.put(id,type);
            }else if(roadLights.contains(e.getAttribute("from")) && !id.startsWith(":")) {
                double length=0;
                for(var lane:TrafficRoutes.elements(e,"lane"))length=Math.max(length,Double.parseDouble(lane.getAttribute("length")));
                approaches.computeIfAbsent(e.getAttribute("from"),ignored->new ArrayList<>()).add(new Approach(id,e.getAttribute("to"),length));
            }
        });
        // Guessing can cross several split nodes. Load only the required short
        // continuations instead of retaining every edge of a regional network.
        for(int round=0;round<32;round++) {
            Set<String> missing=new TreeSet<>();
            for(var entry:restored.entrySet())if(entry.getValue().equals("priority"))
                controlledPaths(entry.getKey(),25,Set.of(),approaches,junctionTypes,missing);
            if(missing.isEmpty())break;
            missing.forEach(id->approaches.put(id,new ArrayList<>()));
            XmlRecords.read(net,Set.of("edge"),e->{
                if(!e.getAttribute("id").startsWith(":") && missing.contains(e.getAttribute("from"))) {
                    double length=0;
                    for(var lane:TrafficRoutes.elements(e,"lane"))length=Math.max(length,Double.parseDouble(lane.getAttribute("length")));
                    approaches.get(e.getAttribute("from")).add(new Approach(e.getAttribute("id"),e.getAttribute("to"),length));
                }
            });
        }
        Map<String,Object> transferred=new TreeMap<>();
        for(var entry:restored.entrySet())if(entry.getValue().equals("priority")) {
            var paths=controlledPaths(entry.getKey(),25,Set.of(),approaches,junctionTypes,new TreeSet<>());
            if(paths!=null)transferred.put(entry.getKey(),paths.stream().map(path->Map.of(
                    "edges",path.stream().map(Approach::edge).toList(),
                    "controlledJunction",path.getLast().target(),
                    "lengthMetres",path.stream().mapToDouble(Approach::length).sum())).toList());
        }
        evidence.put("roadLightApproachesToNativeTrafficLights",transferred);
        var unresolved=new TreeSet<>(roadLights);unresolved.removeAll(restored.keySet());
        evidence.put("retainedSourceRoadLightTypes",restored);
        evidence.put("sourceRoadLightIdsNotRetainedOrMerged",unresolved);
        evidence.put("remainingRailSignals",railSignals[0]);
        boolean valid=railSignals[0]==0 && restored.entrySet().stream().allMatch(e->e.getValue().startsWith("traffic_light") || transferred.containsKey(e.getKey()));
        evidence.put("status",valid?unresolved.isEmpty()?"applied":"applied-source-ids-require-review":"failed");
        VehicleArtifacts.write(report,evidence);
        if(!valid)throw new IOException("SUMO-Straßensignale nicht konsistent; siehe sumo/"+REPORT);
        return unresolved.isEmpty();
    }
    /** Every outgoing branch must reach a native light within the cumulative 25m bound. */
    private static List<List<Approach>> controlledPaths(String node,double remaining,Set<String> visited,
            Map<String,List<Approach>> graph,Map<String,String> types,Set<String> missing) {
        if(types.getOrDefault(node,"").startsWith("traffic_light"))return List.of(List.of());
        if(!"priority".equals(types.get(node)) || visited.contains(node) || visited.size()>=32)return null;
        if(!graph.containsKey(node)){missing.add(node);return null;}
        var links=graph.get(node);if(links.isEmpty())return null;
        var seen=new HashSet<>(visited);seen.add(node);var result=new ArrayList<List<Approach>>();boolean valid=true;
        for(var link:links) {
            if(!Double.isFinite(link.length()) || link.length()<=0 || link.length()>remaining){valid=false;continue;}
            var tails=controlledPaths(link.target(),remaining-link.length(),seen,graph,types,missing);
            if(tails==null){valid=false;continue;}
            for(var tail:tails){var path=new ArrayList<Approach>();path.add(link);path.addAll(tail);result.add(List.copyOf(path));}
        }
        return valid?List.copyOf(result):null;
    }
    private void readRoadLights(Path source)throws IOException {
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External XML forbidden");});
        int signals=0;
        try(var in=new BufferedInputStream(Files.newInputStream(source))) {
            var reader=factory.createXMLStreamReader(in);
            try {
                String id=null,railway=null;boolean railwaySignal=false,railRule=false,roadLight=false;
                while(reader.hasNext()) {
                    int event=reader.next();
                    if(event==XMLStreamConstants.START_ELEMENT) {
                        if(reader.getLocalName().equals("node")){id=reader.getAttributeValue(null,"id");railway=null;railwaySignal=false;railRule=false;roadLight=false;}
                        else if(id!=null && reader.getLocalName().equals("tag")) {
                            String key=reader.getAttributeValue(null,"k"),value=reader.getAttributeValue(null,"v");
                            if("railway".equals(key)){railway=value;railwaySignal="signal".equals(value);}
                            railRule|="railway:signal:main".equals(key) || "railway:signal:combined".equals(key);
                            roadLight|="highway".equals(key) && "traffic_signals".equals(value);
                        }
                    }else if(event==XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("node")) {
                        if(railwaySignal || railRule)signals++;
                        if(roadLight && (railwaySignal || railRule) && !"level_crossing".equals(railway) && !"crossing".equals(railway)) {
                            if(id==null || !id.matches("-?[0-9]+"))throw new IOException("Ungültige OSM-Signal-ID");
                            roadLights.add(id);
                        }
                        id=null;
                    }else if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)throw new XMLStreamException("DTD/entities forbidden");
                }
            }finally{reader.close();}
        }catch(XMLStreamException e){throw new IOException("OSM-Signale nicht lesbar",e);}
        evidence.put("sourceRailSignalOrDefaultRuleNodes",signals);
    }
    /** A provider declaration is checked before its vehicles enter routing or simulation. */
    static void requireRoadVehicles(TransitRunner.PtInfrastructure pt)throws IOException {
        if(pt==null)return;
        Map<String,String> types=new HashMap<>();types.put("DEFAULT_VEHTYPE","passenger");types.put("DEFAULT_BIKETYPE","bicycle");
        inspectPtXml(pt.vtypes(),types,true);
        inspectPtXml(pt.stops(),types,false);
        inspectPtXml(pt.vehicles(),types,false);
    }
    private static void inspectPtXml(Path file,Map<String,String> types,boolean definitions)throws IOException {
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External XML forbidden");});
        try(var in=new BufferedInputStream(Files.newInputStream(file))) {
            var reader=factory.createXMLStreamReader(in);
            try {
                while(reader.hasNext()) {
                    int event=reader.next();
                    if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)throw new XMLStreamException("DTD/entities forbidden");
                    if(event!=XMLStreamConstants.START_ELEMENT)continue;
                    String tag=reader.getLocalName();
                    if(tag.equals("include") || tag.equals("vTypeDistribution"))throw new IOException("Nicht vollständig prüfbare ÖV-Includes oder Fahrzeugtyp-Verteilungen");
                    if(tag.equals("vType")) {
                        String id=reader.getAttributeValue(null,"id"),vClass=reader.getAttributeValue(null,"vClass");
                        if(!definitions || vClass==null || !ROAD_CLASSES.contains(vClass) || id==null || id.isBlank() || types.putIfAbsent(id,vClass)!=null)
                            throw new IOException("ÖV-Fahrzeugtypen müssen eindeutig und straßengebunden im VType-Artefakt stehen");
                    }else if(Set.of("vehicle","trip","flow").contains(tag)) {
                        String type=reader.getAttributeValue(null,"type");if(type==null)type="DEFAULT_VEHTYPE";
                        if(definitions || !types.containsKey(type))throw new IOException("Als Straßenverkehr deklarierter ÖV referenziert ungeprüften Fahrzeugtyp "+type);
                    }
                }
            }finally{reader.close();}
        }catch(XMLStreamException e){throw new IOException("ÖV-Straßenfahrzeuge nicht prüfbar: "+file.getFileName(),e);}
    }
}
