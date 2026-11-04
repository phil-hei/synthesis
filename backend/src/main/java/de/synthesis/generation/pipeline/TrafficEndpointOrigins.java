package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exact coordinate references in this run's native inputs; a match is not causal attribution. */
final class TrafficEndpointOrigins {
    private static final int MAX_EXAMPLES = 8;
    private record Coordinate(double lon, double lat) {
        Coordinate { if (lon == 0) lon = 0; if (lat == 0) lat = 0; }
    }
    private record Source(String file, String kind, String location, String id, String zone, String zoneSpace) { }
    private static final List<Source> SOURCES = List.of(
            new Source("legacy/demand-data/household.csv", "household-home", "homeLocation", "householdId", "homeZone", "matrixColumn"),
            new Source("legacy/demand-data/opportunity.csv", "opportunity", "location", "activityType", "zoneId", "matrixColumn"),
            new Source("legacy/demand-data/fixedDestination.csv", "fixed-destination", "location", "personOid", "zoneId", "matrixColumn"),
            new Source("legacy/logistics/distribution-centers.csv", "depot", "", "id", "zone", "external"),
            new Source("legacy/zone-repository/zones.csv", "zone-anchor", "centroidLocation", "id", "matrixColumn", "matrixColumn"));
    private static final class References {
        int count;
        final Set<String> kinds = new TreeSet<>();
        final List<Map<String,Object>> examples = new ArrayList<>();
        void add(String kind, Map<String,Object> example) {
            count++; kinds.add(kind);
            if (examples.size() < MAX_EXAMPLES) examples.add(example);
        }
        Map<String,Object> report() { return Map.of("recordCount",count,"kinds",kinds,"examples",examples,"examplesTruncated",count>examples.size()); }
    }

    static Map<String,Object> annotate(Path artifacts, List<Map<String,Object>> requests) {
        Map<Coordinate, References> wanted = new HashMap<>();
        List<Map<String,Object>> endpoints = new ArrayList<>();
        for (var request : requests) {
            if ("mapped".equals(request.get("status"))) continue;
            for (String end : List.of("from","to")) {
                @SuppressWarnings("unchecked") var endpoint=(Map<String,Object>)request.get(end);
                if ("unmapped".equals(request.get("status")) && !"unmapped".equals(endpoint.get("status"))) continue;
                endpoints.add(endpoint); wanted.computeIfAbsent(coordinate(endpoint), ignored -> new References());
            }
        }
        List<Map<String,Object>> files = new ArrayList<>();
        if (!wanted.isEmpty()) for (var source : SOURCES) files.add(scan(artifacts,source,wanted));
        int unmatched=0; Map<String,Integer> byKind=new TreeMap<>();
        for (var endpoint : endpoints) {
            var references=wanted.get(coordinate(endpoint));
            endpoint.put("coordinateSources",references.report());
            if (references.count==0) unmatched++;
            for (String kind : references.kinds) byKind.merge(kind,1,Integer::sum);
        }
        return Map.of("matchRule","exact numeric equality to serialized native location; no rounding, fuzzy join, or causal attribution",
                "scope","unmapped endpoints and both endpoints of disconnected requests",
                "checkedEndpoints",endpoints.size(),"unmatchedEndpoints",unmatched,
                "endpointMatchesByKind",byKind,"countsOverlap",true,"files",files);
    }

    private static Coordinate coordinate(Map<String,Object> endpoint) {
        var point=(List<?>)endpoint.get("coordinate");
        return new Coordinate(((Number)point.get(0)).doubleValue(),((Number)point.get(1)).doubleValue());
    }
    private static Map<String,Object> scan(Path artifacts, Source source, Map<Coordinate,References> wanted) {
        Map<String,Object> file=new LinkedHashMap<>(); file.put("file",source.file());
        Path path=artifacts.resolve(source.file());
        if (!Files.isRegularFile(path)) { file.put("status","missing"); return file; }
        // Stage matches per file so a malformed later row cannot leave a partial file looking verified.
        Map<Coordinate,References> staged=new HashMap<>();
        try (var reader=Files.newBufferedReader(path)) {
            file.put("sha256",FreightProfileChecks.sha256(path));
            String line=reader.readLine(); if(line==null) throw new IOException("CSV-Kopf fehlt");
            var header=fields(line); int lineNumber=1;
            for (String required : List.of(source.id(),source.zone())) if(!header.contains(required)) throw new IOException("CSV-Spalte fehlt: "+required);
            if(source.location().isEmpty()) {
                if(!header.containsAll(List.of("loc_x","loc_y")))throw new IOException("Depot-Koordinaten fehlen");
            } else if(!header.contains(source.location()))throw new IOException("Orts-Spalte fehlt: "+source.location());
            while ((line=reader.readLine())!=null) {
                lineNumber++; if(line.isBlank())continue;
                var values=fields(line);
                Coordinate point;
                if(source.location().isEmpty())point=new Coordinate(Double.parseDouble(value(header,values,"loc_x")),Double.parseDouble(value(header,values,"loc_y")));
                else {
                    String location=value(header,values,source.location());
                    int colon=location.indexOf(':');
                    if(!location.startsWith("(") || colon<0)throw new IOException("Ungültiger Ort in Zeile "+lineNumber);
                    String[] xy=location.substring(1,colon).split(",",-1);
                    if(xy.length!=2)throw new IOException("Ungültige Koordinate in Zeile "+lineNumber);
                    point=new Coordinate(Double.parseDouble(xy[0].trim()),Double.parseDouble(xy[1].trim()));
                }
                if(!Double.isFinite(point.lon()) || !Double.isFinite(point.lat()))throw new IOException("Nichtendliche Koordinate in Zeile "+lineNumber);
                if(!wanted.containsKey(point))continue;
                Map<String,Object> example=new LinkedHashMap<>();example.put("file",source.file());example.put("line",lineNumber);
                example.put("kind",source.kind());example.put("recordId",value(header,values,source.id()));
                example.put("zoneId",value(header,values,source.zone()));example.put("zoneIdSpace",source.zoneSpace());
                if(header.contains("activityType"))example.put("activityType",value(header,values,"activityType"));
                staged.computeIfAbsent(point,ignored->new References()).add(source.kind(),example);
            }
            staged.forEach((point,refs)->{
                var target=wanted.get(point);target.count+=refs.count;target.kinds.addAll(refs.kinds);
                refs.examples.stream().limit(MAX_EXAMPLES-target.examples.size()).forEach(target.examples::add);
            });
            file.put("status","read");file.put("rows",lineNumber-1);
        } catch(IOException | IllegalArgumentException e) {
            file.put("status","unreadable");file.put("error",e.getMessage());
        }
        return file;
    }
    private static String value(List<String> header,List<String> values,String column)throws IOException {
        int index=header.indexOf(column);
        if(index<0 || index>=values.size())throw new IOException("CSV-Wert fehlt: "+column);
        return values.get(index);
    }
    /** The generated native inputs have one physical line per record; quoted semicolons are retained. */
    private static List<String> fields(String line)throws IOException {
        List<String> result=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if(ch=='"') {
                if(quoted && i+1<line.length() && line.charAt(i+1)=='"'){field.append('"');i++;}
                else quoted=!quoted;
            } else if(ch==';' && !quoted){result.add(field.toString());field.setLength(0);}
            else field.append(ch);
        }
        if(quoted)throw new IOException("Mehrzeilige oder unvollständig gequotete CSV-Zeile");
        result.add(field.toString());return result;
    }
}
