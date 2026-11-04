package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleArtifacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Geometric coverage and actual class permissions; dead-end flags are review hints, not access bans. */
final class TrafficEndpointMapper {
    private final TrafficRoutes network;
    private final List<Map<String,Object>> rows = new ArrayList<>();
    private int missing;
    private int disconnected;
    TrafficEndpointMapper(TrafficRoutes network) { this.network=network; }

    Map<String,String> map(String id,String source,String clazz,String fromLon,String fromLat,String toLon,String toLat) {
        return map(id,source,clazz,fromLon,fromLat,toLon,toLat,null,null);
    }
    Map<String,String> map(String id,String source,String clazz,String fromLon,String fromLat,String toLon,String toLat,String fromEdge,String toEdge) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("id",id);row.put("source",source);row.put("vehicleClass",clazz);
        Map<String,String> result=new HashMap<>();
        if(source.equals("company-tours")&&fromEdge==null&&toEdge==null) {
            try {
                double x=Double.parseDouble(fromLon),y=Double.parseDouble(fromLat),u=Double.parseDouble(toLon),v=Double.parseDouble(toLat);
                TrafficRoutes.Pair pair; boolean approximate=false;
                try{pair=network.matchPair(x,y,u,v,clazz,null,null);}
                catch(IOException unavailable){pair=network.matchApproximatePair(x,y,u,v,clazz);approximate=true;}
                if(Boolean.FALSE.equals(pair.possibleDirectedConnection())){pair=network.matchApproximatePair(x,y,u,v,clazz);approximate=true;}
                row.put("from",Map.of("coordinate",List.of(x,y),"status","mapped","match",pair.from()));
                row.put("to",Map.of("coordinate",List.of(u,v),"status","mapped","match",pair.to()));
                row.put("possibleDirectedConnection",pair.possibleDirectedConnection());row.put("approximateNearest",approximate);
                row.put("directionAdjustedOnSameStreet",!approximate&&pair.directionAdjusted());row.put("status","mapped");rows.add(row);
                return Map.of("from",pair.from().edgeId(),"to",pair.to().edgeId());
            }catch(IOException unavailable){row.put("approximationFailure",unavailable.getMessage());}
        }
        for (String end : List.of("from","to")) {
            double lon=Double.parseDouble(end.equals("from")?fromLon:toLon),lat=Double.parseDouble(end.equals("from")?fromLat:toLat);
            Map<String,Object> endpoint=new LinkedHashMap<>();endpoint.put("coordinate",List.of(lon,lat));row.put(end,endpoint);
            try {
                var match=network.matchResolved(end.equals("from")?fromEdge:toEdge,lon,lat,clazz);result.put(end,match.edgeId());
                endpoint.put("status","mapped"); endpoint.put("match",match);
            } catch(IOException e) { endpoint.put("status","unmapped");endpoint.put("reason",e.getMessage());missing++; }
        }
        if(result.size()==2) {
            try {
                var pair=network.matchPair(Double.parseDouble(fromLon),Double.parseDouble(fromLat),Double.parseDouble(toLon),Double.parseDouble(toLat),clazz,fromEdge,toEdge);
                row.put("possibleDirectedConnection",pair.possibleDirectedConnection());row.put("directionAdjustedOnSameStreet",pair.directionAdjusted());
                @SuppressWarnings("unchecked") var origin=(Map<String,Object>)row.get("from");
                @SuppressWarnings("unchecked") var destination=(Map<String,Object>)row.get("to");
                origin.put("match",pair.from());destination.put("match",pair.to());
                result.put("from",pair.from().edgeId());result.put("to",pair.to().edgeId());
                if(Boolean.FALSE.equals(pair.possibleDirectedConnection()))disconnected++;
            }catch(IOException e){throw new IllegalStateException("Validated endpoint changed",e);}
        }
        row.put("status",result.size()!=2?"unmapped":Boolean.FALSE.equals(row.get("possibleDirectedConnection"))?"no-directed-path":"mapped");rows.add(row);
        return result.size()==2?result:null;
    }
    void write(Path dir,Path osm,boolean strict) throws IOException { write(dir,osm,strict,List.of()); }
    void write(Path dir,Path osm,boolean strict,List<Double> modelRegion) throws IOException {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("contract","synthesis-network-coverage-v1");report.put("sourceOsmSha256",FreightProfileChecks.sha256(osm));
        if(java.nio.file.Files.exists(dir.resolve("net.net.xml")))report.put("sumoNetworkSha256",FreightProfileChecks.sha256(dir.resolve("net.net.xml")));
        report.put("distanceModel","external edge centerlines; local lon/lat metric approximation; permissions from actual SUMO lanes");
        report.put("networkEnvelope",network.envelope());report.put("modelRegion",modelRegion);
        report.put("searchRadiusMetres",300);report.put("companyApproximationRadiusMetres",5000);report.put("requests",rows);report.put("unmappedEndpoints",missing);
        report.put("disconnectedRequests",disconnected);report.put("directedConnectionCheck","edge-level class-permitted graph overapproximates lane/via movements; absence proves a missing path, presence is not certification; pedestrians checked natively");
        report.put("status",missing==0 && disconnected==0?"covered":"incomplete");report.put("boundaryReview",network.boundaryWarnings());
        report.put("boundaryReviewBandMetres",1000);report.put("completeTopologyVerified",false);
        report.put("boundaryCheck","arterial dead ends near network envelope are review hints; a source boundary cannot be certified from its envelope");
        report.put("sourceExtractionPadding","unknown to SUMO runner; network envelope is observed, not a declared extraction buffer");
        report.put("endpointOrigins",TrafficEndpointOrigins.annotate(dir.toAbsolutePath().getParent(),rows));
        VehicleArtifacts.write(dir.resolve("network-coverage.json"),report);
        if (strict && (missing>0 || disconnected>0)) throw new IOException("Straßennetz-Vorprüfung: " + missing + " Endpunkte ohne zulässige Straße innerhalb 300 m, "+disconnected+" Anfragen ohne gerichtete Verbindung; siehe sumo/network-coverage.json");
    }
}
