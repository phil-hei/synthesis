package de.synthesis.generation.pipeline;

import de.synthesis.companytour.*;
import de.synthesis.vehicle.*;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.companytour.CompanyTourExecution.*;
import static de.synthesis.companytour.CompanyTourRegion.*;

/** Routes original OD pairs on the actual class-permitted SUMO network, then clips their paths. */
final class CompanyTourNetwork {
    @FunctionalInterface interface Execute {void run(String[] args)throws IOException,InterruptedException;}
    record Result(Projection projection,Map<String,List<String>> regionalEdges) {}
    private record Slice(String edge,double start,double end) {}
    // One primitive record per edge avoids three composite String keys and boxed values.
    private record EdgeMetrics(double length,double delivery,double truck,double trailer) {
        double speed(String clazz) { return switch(clazz) {
            case "delivery" -> delivery; case "truck" -> truck; case "trailer" -> trailer;
            default -> throw new IllegalArgumentException("Nicht unterstützte Unternehmens-Fahrzeugklasse: "+clazz);
        }; }
    }
    static Result prepare(Batch original,TrafficRoutes network,Path dir,Path artifacts,VehicleConfiguration fleet,Path router,long seed,Execute execute)throws IOException,InterruptedException {
        Map<String,Leg> requests=new LinkedHashMap<>();Map<String,TrafficRoutes.Pair> matches=new HashMap<>();Map<String,EdgeMetrics> metrics=new HashMap<>();Map<String,Double> vehicleSpeeds=new HashMap<>();
        var types=new HashMap<String,VehicleType>();fleet.vehicleTypes().forEach(t->types.put(t.key(),t));fleet.initialVehicles().forEach(v->vehicleSpeeds.put(v.vehicleId(),types.get(v.typeRef().key()).physical().maxSpeedKmh()/3.6));
        XmlRecords.read(dir.resolve("net.net.xml"),Set.of("edge"),e->{
            var lanes=TrafficRoutes.elements(e,"lane");if(lanes.isEmpty())return;
            double[] maxima=new double[3];int i=0;
            for(String clazz:List.of("delivery","truck","trailer")) maxima[i++]=lanes.stream().filter(l->{
                var allow=Set.of(l.getAttribute("allow").strip().split("\\s+"));var deny=Set.of(l.getAttribute("disallow").strip().split("\\s+"));
                return (l.getAttribute("allow").isBlank()||allow.contains("all")||allow.contains(clazz))&&!deny.contains("all")&&!deny.contains(clazz);
            }).mapToDouble(l->Double.parseDouble(l.getAttribute("speed"))).max().orElse(0);
            metrics.put(e.getAttribute("id"),new EdgeMetrics(Double.parseDouble(lanes.getFirst().getAttribute("length")),maxima[0],maxima[1],maxima[2]));
        });
        Map<String,Object> report=new LinkedHashMap<>();report.put("contract","synthesis-company-network-paths-v1");report.put("status","preparing");report.put("networkSha256",FreightProfileChecks.sha256(dir.resolve("net.net.xml")));report.put("timeModel","planned travel time scaled by directed edge free-flow time; not measured external travel");report.put("individualDimensionsCertified",false);report.put("approximateLocationPolicy","Keep resolved nearest-street pairs; unresolved points or explicitly labelled approximate-nearest points use the nearest reachable class-permitted endpoint pair within 5 km; snap distances are reported");
        Path reportFile=artifacts.resolve("company-tours/network-paths.json");VehicleArtifacts.write(reportFile,report);
        try {
            try(var writer=Files.newBufferedWriter(dir.resolve("company-network.trips.xml"))){var xml=XMLOutputFactory.newFactory().createXMLStreamWriter(writer);xml.writeStartElement("routes");int n=0;
                for(var input:original.imports())if(input.networkProjectionRequested())for(var leg:input.legs()){
                    String id="company-network-"+n++;requests.put(id,leg);
                    TrafficRoutes.Pair pair;
                    boolean approximate=leg.from().provenance().contains("approximate-nearest")||leg.to().provenance().contains("approximate-nearest");
                    try {
                        if(approximate)pair=network.matchApproximatePair(leg.from().lon(),leg.from().lat(),leg.to().lon(),leg.to().lat(),leg.vehicleClass());
                        else {
                            try {pair=network.matchPair(leg.from().lon(),leg.from().lat(),leg.to().lon(),leg.to().lat(),leg.vehicleClass(),null,null);}
                            catch(IOException unavailable){pair=network.matchApproximatePair(leg.from().lon(),leg.from().lat(),leg.to().lon(),leg.to().lat(),leg.vehicleClass());}
                            if(Boolean.FALSE.equals(pair.possibleDirectedConnection()))
                                pair=network.matchApproximatePair(leg.from().lon(),leg.from().lat(),leg.to().lon(),leg.to().lat(),leg.vehicleClass());
                        }
                    }catch(IOException e){throw new IOException("Vollständiges lokales Routingnetz deckt externe Tourorte auch mit Näherungszuordnung bis 5 km nicht ab: "+leg.sourceKey()+". Größeres lokales OSM-Netz über synthesis.overpass.road-network-file bereitstellen",e);}
                    if(Boolean.FALSE.equals(pair.possibleDirectedConnection()))throw new IOException("Keine gerichtete Verbindung für externe Originaletappe: "+leg.sourceKey());matches.put(id,pair);
                    xml.writeEmptyElement("vType");xml.writeAttribute("id",id+"-type");xml.writeAttribute("vClass",leg.vehicleClass());xml.writeAttribute("maxSpeed",vehicleSpeeds.get(leg.vehicleId()).toString());
                    xml.writeEmptyElement("trip");xml.writeAttribute("id",id);xml.writeAttribute("type",id+"-type");xml.writeAttribute("depart",Integer.toString(leg.departSec()));xml.writeAttribute("from",pair.from().edgeId());xml.writeAttribute("to",pair.to().edgeId());
                    xml.writeAttribute("departPos",Double.toString(network.project(pair.from().edgeId(),leg.from().lon(),leg.from().lat()).fraction()*metrics.get(pair.from().edgeId()).length()));
                    xml.writeAttribute("arrivalPos",Double.toString(network.project(pair.to().edgeId(),leg.to().lon(),leg.to().lat()).fraction()*metrics.get(pair.to().edgeId()).length()));
                }xml.writeEndElement();xml.close();
            }catch(XMLStreamException e){throw new IOException(e);}
            execute.run(new String[]{router.toString(),"-n","net.net.xml","-r","company-network.trips.xml","-o","company-network.rou.xml","--unsorted-input","true","--seed",Long.toString(seed),"--routing-threads","1","--no-step-log"});
            Map<String,NetworkPath> paths=new HashMap<>();Map<String,List<Slice>> slices=new HashMap<>();List<Map<String,Object>> rows=new ArrayList<>();
            XmlRecords.read(dir.resolve("company-network.rou.xml"),Set.of("vehicle"),v->{
                var leg=requests.get(v.getAttribute("id"));if(leg==null)return;var route=TrafficRoutes.elements(v,"route");if(route.size()!=1)throw new IOException("Externe Route fehlt");var edges=List.of(route.getFirst().getAttribute("edges").split("\\s+"));
                var points=new ArrayList<PathPoint>();var segments=new ArrayList<Slice>();double seconds=0;
                for(int j=0;j<edges.size();j++){
                    String edge=edges.get(j);var shape=network.geometry(edge);double total=0;for(int i=1;i<shape.size();i++)total+=VehicleContinuity.distance(shape.get(i-1)[0],shape.get(i-1)[1],shape.get(i)[0],shape.get(i)[1]);
                    double lo=j==0?network.project(edge,leg.from().lon(),leg.from().lat()).fraction():0,hi=j+1==edges.size()?network.project(edge,leg.to().lon(),leg.to().lat()).fraction():1;
                    if(hi+1e-9<lo)throw new IOException("Rückwärts gerichteter Straßenabschnitt in Originalroute");double walked=0,startTime=seconds;
                    double duration=metrics.get(edge).length()/Math.min(metrics.get(edge).speed(leg.vehicleClass()),vehicleSpeeds.get(leg.vehicleId()));
                    for(int i=1;i<shape.size();i++){double[] a=shape.get(i-1),b=shape.get(i);double distance=VehicleContinuity.distance(a[0],a[1],b[0],b[1]),from=total==0?0:walked/total,to=total==0?1:(walked+distance)/total;walked+=distance;
                        double start=Math.max(from,lo),end=Math.min(to,hi);if(end<=start||to<=from)continue;
                        double f=(start-from)/(to-from),g=(end-from)/(to-from);var p=new Point(a[0]+f*(b[0]-a[0]),a[1]+f*(b[1]-a[1]),"directed-network");var q=new Point(a[0]+g*(b[0]-a[0]),a[1]+g*(b[1]-a[1]),"directed-network");
                        points.add(new PathPoint(p,seconds));seconds+=(end-start)*duration;points.add(new PathPoint(q,seconds));
                    }segments.add(new Slice(edge,startTime,seconds));
                }
                if(points.size()<2){points.add(new PathPoint(leg.from(),0));points.add(new PathPoint(leg.to(),0));}
                var path=new NetworkPath(List.copyOf(points),edges,(String)report.get("networkSha256"));paths.put(leg.sourceKey(),path);slices.put(leg.sourceKey(),segments);
                rows.add(Map.of("sourceTripKey",leg.sourceKey(),"vehicleClass",leg.vehicleClass(),"snap",matches.get(v.getAttribute("id")),"path",path));
            });
            if(paths.size()!=requests.size())throw new IOException("Native Originalrouten unvollständig; kein Luftlinienersatz");
            var projection=CompanyTourRegion.projectNetwork(original,paths);Map<String,List<String>> prescribed=new HashMap<>();
            for(var check:projection.checks())if(check.regionalLeg()!=null&&paths.containsKey(check.sourceTripKey())){
                double total=paths.get(check.sourceTripKey()).points().getLast().seconds();double lo=check.entryFraction()*total,hi=check.exitFraction()*total;
                var edges=slices.get(check.sourceTripKey()).stream().filter(s->s.end()>lo+1e-8&&s.start()<hi-1e-8).map(Slice::edge).toList();
                if(edges.isEmpty())edges=paths.get(check.sourceTripKey()).edgeIds();prescribed.put(check.regionalLeg().sourceKey(),edges);
            }
            report.put("status","routed");report.put("paths",rows);report.put("regionalRoutes",prescribed);VehicleArtifacts.write(reportFile,report);return new Result(projection,Map.copyOf(prescribed));
        }catch(IOException|InterruptedException|RuntimeException e){report.put("status","failed");report.put("message",Objects.toString(e.getMessage(),e.getClass().getSimpleName()));VehicleArtifacts.write(reportFile,report);throw e;}
    }
}
