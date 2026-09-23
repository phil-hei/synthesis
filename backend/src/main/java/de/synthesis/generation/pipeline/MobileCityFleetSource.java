package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Private BEV shares from pinned KBA observations, spatially transferred using BKG boundaries. */
final class MobileCityFleetSource {
    static final String RESOURCE = "/reference/kba-private-fleet-2026-07";
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    record Stock(String district, String name, Long total, Long bev, Long phev, int row) {}
    record Share(double bev, Double phev, String source, double coverage, List<String> municipalities) {}
    /** Resolved shares plus the exact volatile BKG response used by this run. */
    record Result(Map<Integer, Share> shares, Map<String,Object> provenance, byte[] boundarySnapshot) {
        Result {
            shares = Collections.unmodifiableMap(new LinkedHashMap<>(shares));
            provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
            boundarySnapshot = boundarySnapshot == null ? null : boundarySnapshot.clone();
        }
        @Override public byte[] boundarySnapshot() {
            return boundarySnapshot == null ? null : boundarySnapshot.clone();
        }
    }
    @FunctionalInterface interface Boundaries { byte[] fetch(URI uri) throws IOException, InterruptedException; }
    private final Boundaries boundaries;
    MobileCityFleetSource() {
        this(uri -> {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                    .send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IOException("BKG HTTP "+response.statusCode());
            if(response.body().length>64*1024*1024) throw new IOException("BKG-Antwort zu groß");
            return response.body();
        });
    }
    MobileCityFleetSource(Boundaries boundaries) { this.boundaries=boundaries; }

    Result resolve(Scenario scenario,List<Zone> zones,Path cache,Consumer<String> log) throws IOException {
        Map<Integer,Share> shares=new LinkedHashMap<>(); Map<String,Object> provenance=new LinkedHashMap<>();
        provenance.put("schemaVersion",1); provenance.put("electricDefinition","BEV only; PHEV retained in diagnostics, treated as non-BEV by binary MobileCity model");
        provenance.put("stockPolicy","Synthetic private car counts retained; municipal observations supply drivetrain shares only");
        provenance.put("scenarioYear",scenario.referenceYear);
        String problem=null; byte[] boundarySnapshot=null;
        if(scenario.mobileCity.useRegionalFleet) {
            provenance.put("kba",JSON.readTree(MobileCityFleetSource.class.getResourceAsStream(RESOURCE+".json")));
            try {
                if(scenario.referenceYear<2026) throw new IOException("KBA-Datenstand 2026 liegt nach dem Szenariojahr");
                Envelope bbox=new Envelope();
                for(var z:zones) if(!z.external) bbox.expandToInclude(MobileCityGeometry.zone(z).getEnvelopeInternal());
                if(bbox.isNull() || bbox.getMaxX()<5.5 || bbox.getMinX()>15.5 || bbox.getMaxY()<47 || bbox.getMinY()>55.2)
                    throw new IOException("Keine deutsche KBA-Abdeckung");
                URI uri=URI.create("https://sgx.geodatenzentrum.de/wfs_vg250?service=WFS&version=2.0.0&request=GetFeature"
                        +"&typeNames=vg250%3Avg250_gem&outputFormat=application%2Fjson&srsName=CRS%3A84&count=12000&bbox="
                        +bbox.getMinX()+","+bbox.getMinY()+","+bbox.getMaxX()+","+bbox.getMaxY()+",CRS:84");
                Files.createDirectories(cache); Path file=cache.resolve(hash(uri.toString().getBytes(StandardCharsets.UTF_8))+".geojson");
                byte[] bytes;
                if(Files.isRegularFile(file)) bytes=Files.readAllBytes(file);
                else {
                    bytes=boundaries.fetch(uri); validateBoundaries(JSON.readTree(bytes));
                    Path temp=Files.createTempFile(cache,"bkg-",".part");
                    try {Files.write(temp,bytes); Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    finally {Files.deleteIfExists(temp);}
                }
                JsonNode data=JSON.readTree(bytes); validateBoundaries(data);
                boundarySnapshot=bytes;
                provenance.put("boundarySource",uri.toString()); provenance.put("boundarySha256",hash(bytes));
                provenance.put("boundarySnapshotFile","fleet-boundaries.geojson");
                provenance.put("boundarySnapshotTime",data.path("timeStamp").asText());
                provenance.put("boundaryAttribution","© GeoBasis-DE / BKG, VG250; Datenlizenz Deutschland – Namensnennung 2.0");
                provenance.put("allocation","Area-weighted municipal private car stocks; uniform stock density within each municipality; not submunicipal observations");
                provenance.put("temporalPolicy","KBA stock shares observed 2026-07-01, held constant for scenario year; no forecast");
                shares.putAll(map(zones,data,load()));
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt(); problem="BKG-Abruf unterbrochen";
            } catch(IOException|IllegalArgumentException|TopologyException e) {problem=e.getMessage();}
        }
        List<Long> missing=new ArrayList<>();
        for(var z:zones) if(!z.external && !shares.containsKey(z.index)) {
            if(scenario.mobileCity.electricShare!=null)
                shares.put(z.index,new Share(scenario.mobileCity.electricShare,null,"user-assumption",0,List.of()));
            else missing.add(z.id);
        }
        provenance.put("sourceFailure",problem); provenance.put("missingZoneIds",missing);
        provenance.put("zones",zones.stream().filter(z->!z.external).map(z->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("zoneId",z.id);row.put("share",shares.get(z.index));return row;
        }).toList());
        log.accept("MobileCity-Pkw: "+shares.size()+" Zonen mit Antriebsanteil, "+missing.size()+" ohne belastbaren Wert");
        if(problem!=null) log.accept("MobileCity-Pkw-Quelle: "+problem);
        return new Result(shares,provenance,boundarySnapshot);
    }
    static void validateBoundaries(JsonNode data) throws IOException {
        if(!"FeatureCollection".equals(data.path("type").asText()) || !data.path("features").isArray())
            throw new IOException("BKG lieferte keine Gemeindegeometrien");
        if(!data.path("numberMatched").canConvertToInt() || data.path("numberMatched").asInt()!=data.path("features").size())
            throw new IOException("BKG-Gemeindeantwort unvollständig; keine Teilantwort verwenden");
    }
    static Map<Integer,Share> map(List<Zone> zones,JsonNode data,List<Stock> observations) {
        Map<String,List<Stock>> stocks=new HashMap<>();
        for(var s:observations) stocks.computeIfAbsent(s.district()+":"+normalize(s.name()),k->new ArrayList<>()).add(s);
        Map<String,List<Geometry>> parts=new LinkedHashMap<>(); Map<String,String> names=new HashMap<>();
        for(var f:data.path("features")) {
            String ags=f.path("properties").path("ags").asText();
            if(!ags.matches("\\d{8}")) throw new IllegalArgumentException("BKG ohne gültigen AGS");
            parts.computeIfAbsent(ags,k->new ArrayList<>()).add(MobileCityGeometry.read(f.path("geometry")));
            names.put(ags,f.path("properties").path("gen").asText());
        }
        record Area(String ags,Geometry geometry,Stock stock) {}
        List<Area> areas=new ArrayList<>();
        for(var entry:parts.entrySet()) {
            var candidates=stocks.getOrDefault(entry.getKey().substring(0,5)+":"+normalize(names.get(entry.getKey())),List.of());
            if(candidates.size()!=1) continue; // Never fuzzy-match or pick an ambiguous namesake.
            Stock stock=candidates.getFirst();
            if(stock.total()==null || stock.total()<=0 || stock.bev()==null || stock.phev()==null) continue;
            Geometry g=MobileCityGeometry.project(UnaryUnionOp.union(entry.getValue()));
            if(!g.isValid() || g.getArea()<=0) continue;
            areas.add(new Area(entry.getKey(),g,stock));
        }
        Map<Integer,Share> result=new LinkedHashMap<>();
        for(var z:zones) if(!z.external) {
            Geometry cell=MobileCityGeometry.project(MobileCityGeometry.zone(z));
            List<Geometry> covered=new ArrayList<>();List<String> codes=new ArrayList<>();
            double total=0,bev=0,phev=0,areaSum=0;
            for(var a:areas) if(cell.getEnvelopeInternal().intersects(a.geometry().getEnvelopeInternal())) {
                Geometry overlap=cell.intersection(a.geometry()); double area=overlap.getArea();
                if(area<=0) continue;
                double fraction=area/a.geometry().getArea();covered.add(overlap);codes.add(a.ags());areaSum+=area;
                total+=a.stock().total()*fraction;bev+=a.stock().bev()*fraction;phev+=a.stock().phev()*fraction;
            }
            double union=covered.isEmpty()?0:UnaryUnionOp.union(covered).getArea();
            double coverage=cell.getArea()>0?union/cell.getArea():0;
            if(coverage>=.999 && total>0 && areaSum-union<=Math.max(1,cell.getArea()*1e-6))
                result.put(z.index,new Share(bev/total,phev/total,"kba-2026-07-bkg-area-transfer",Math.min(1,coverage),codes));
        }
        return result;
    }
    static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replace("Ä","AE").replace("Ö","OE").replace("Ü","UE").replace("ß","SS")
                .replaceAll(",\\s*(ST\\.?|STADT|LANDESHAUPTSTADT|HANSESTADT)$","")
                .replaceAll("[^A-Z0-9]","");
    }
    static List<Stock> load() throws IOException {
        byte[] bytes;
        try(var in=MobileCityFleetSource.class.getResourceAsStream(RESOURCE+".csv")) {
            if(in==null) throw new IOException("KBA-Referenz fehlt"); bytes=in.readAllBytes();
        }
        var metadata=JSON.readTree(MobileCityFleetSource.class.getResourceAsStream(RESOURCE+".json"));
        if(!hash(bytes).equals(metadata.path("normalizedSha256").asText())) throw new IOException("KBA-Referenzhash falsch");
        List<Stock> result=new ArrayList<>();
        for(String line:new String(bytes,StandardCharsets.UTF_8).lines().skip(1).toList()) {
            String[] c=line.split(";",-1); if(c.length!=9) throw new IOException("KBA-Referenzschema falsch");
            Long total=count(c[5]),bev=count(c[6]),phev=count(c[7]);
            if(total!=null && bev!=null && phev!=null && bev+phev>total) throw new IOException("KBA-Antriebe über Gesamtbestand");
            result.add(new Stock(c[0],c[1],total,bev,phev,Integer.parseInt(c[8])));
        }
        return result;
    }
    private static Long count(String value) {return value.isBlank()?null:Long.valueOf(value);}
    static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
