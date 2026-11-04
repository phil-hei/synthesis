package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import de.synthesis.scenario.Scenario;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Source inventory only: no network-access, vehicle-compatibility or operational-energy certification. */
public final class EnergyInfrastructure {
    public static final String CONTRACT="synthesis-energy-infrastructure-v1";
    public static final String SNAPSHOT="energy-stations.json";
    public static final long MAX_BYTES=20_000_000;
    private static final String DETAILS="/reference/bnetza-charging-details-2026-09-01";
    private static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface public interface OsmLoader { OverpassClient.EnergyResponse load() throws Exception; }

    public record Snapshot(JsonNode data, JsonNode metadata, String sha256) { }

    public static void clear(Path artifacts)throws IOException {
        EnergyNetworkAccess.clear(artifacts);
        for(String file:List.of("stations.geojson","quality.json","osm-source.json"))Files.deleteIfExists(artifacts.resolve("energy").resolve(file));
    }
    public static ObjectNode snapshot(JsonNode raw,Scenario.Region region,String endpoint) throws IOException {
        validateRaw(raw);
        ObjectNode out=JSON.createObjectNode();out.put("contract","synthesis-energy-osm-snapshot-v1");
        out.set("region",JSON.valueToTree(region));out.put("retrievedAt",Instant.now().toString());
        out.put("sourceUrl",endpoint);out.set("data",raw);return out;
    }
    public static Snapshot readSnapshot(Path file,Scenario.Region region)throws IOException {
        if(Files.size(file)>MAX_BYTES)throw new IOException("Stationssnapshot überschreitet 20 MB");
        byte[] bytes=Files.readAllBytes(file);JsonNode input=JSON.readTree(bytes);
        if(input==null || !input.path("contract").asText().equals("synthesis-energy-osm-snapshot-v1"))throw new IOException("Unbekannter Stationssnapshot-Vertrag");
        if(!sameRegion(input.path("region"),region))throw new IOException("Stationssnapshot passt nicht zur Region; OSM-Quellenstand erneuern");
        validateRaw(input.path("data"));return new Snapshot(input.path("data"),input,hash(bytes));
    }
    public static void writeSnapshot(Path file,JsonNode input)throws IOException {
        byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(input);
        if(bytes.length>MAX_BYTES)throw new IOException("Stationssnapshot überschreitet 20 MB");
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),".energy-",".json");
        try {Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    public static void validateRaw(JsonNode raw)throws IOException {
        if(raw==null || !raw.isObject() || !raw.path("elements").isArray() || raw.path("elements").size()>100_000)
            throw new IOException("Overpass-Stationsdaten benötigen ein elements-Array mit höchstens 100.000 Objekten");
        if(!raw.path("remark").asText().isBlank())throw new IOException("Overpass meldet eine möglicherweise unvollständige Antwort: "+raw.path("remark").asText());
        Set<String> ids=new HashSet<>();
        for(JsonNode node:raw.path("elements")) {
            String type=node.path("type").asText();
            if(!Set.of("node","way","relation").contains(type) || !node.path("id").isIntegralNumber() || !node.path("id").canConvertToLong() || node.path("id").asLong()<=0)
                throw new IOException("Stationsobjekt ohne gültige OSM-Objektart/ID");
            if(!ids.add(type+":"+node.path("id").asText()))throw new IOException("Doppelte OSM-Stationsidentität");
        }
    }
    private static boolean sameRegion(JsonNode value,Scenario.Region region) {
        if(region==null)return false;
        Map<String,Double> expected=Map.of("minLat",region.minLat,"minLon",region.minLon,"maxLat",region.maxLat,"maxLon",region.maxLon);
        return expected.entrySet().stream().allMatch(e->value.path(e.getKey()).isNumber() && Double.compare(value.path(e.getKey()).asDouble(),e.getValue())==0);
    }

    public static ObjectNode generate(Scenario scenario,Path artifacts,Path uploads,OsmLoader loader,Consumer<String> log)throws IOException {
        clear(artifacts);var config=scenario.energyInfrastructure;
        if(config==null || !config.enabled)return null;
        if(scenario.region==null || !config.useBnetza && !config.useOsm)throw new IOException("Stationsbestand benötigt Region und mindestens eine Quelle");
        Path dir=artifacts.resolve("energy");Files.createDirectories(dir);
        List<ObjectNode> stations=new ArrayList<>();ArrayNode sources=JSON.createArrayNode();List<String> failures=new ArrayList<>();
        Map<String,Integer> excluded=new TreeMap<>();
        if(config.useBnetza) {
            try {loadBnetza(scenario,stations,sources,excluded);}catch(Exception e){failures.add("BNetzA: "+e.getMessage());sources.add(sourceFailure("bnetza",e));}
        }
        if(config.useOsm) {
            try {
                Path saved=uploads.resolve(SNAPSHOT);
                if(!Files.isRegularFile(saved)) {
                    OverpassClient.EnergyResponse response=loader.load();
                    writeSnapshot(saved,snapshot(response.data(),scenario.region,response.endpoint()));
                    log.accept("OSM-Stationsquelle abgerufen und als regionaler Eingabestand gespeichert");
                }else log.accept("Gespeicherten OSM-Stationsstand wiederverwendet");
                // Export the exact input, so a run can be frozen without contacting a service again.
                Files.copy(saved,dir.resolve("osm-source.json"),StandardCopyOption.REPLACE_EXISTING);
                Snapshot snapshot=readSnapshot(dir.resolve("osm-source.json"),scenario.region);
                loadOsm(snapshot,scenario,stations,sources,excluded);
            }catch(Exception e){failures.add("OSM: "+e.getMessage());sources.add(sourceFailure("osm",e));}
        }
        markPossibleDuplicates(stations);
        stations.sort(Comparator.comparing(s->s.path("id").asText()));
        ObjectNode collection=JSON.createObjectNode();collection.put("type","FeatureCollection");collection.put("contract",CONTRACT);
        collection.set("region",JSON.valueToTree(scenario.region));collection.put("referenceYear",scenario.referenceYear);
        collection.set("configuration",JSON.valueToTree(config));
        ArrayNode features=collection.putArray("features");
        Map<String,Integer> byCarrier=new TreeMap<>(),byAccess=new TreeMap<>(),flags=new TreeMap<>();int registerPoints=0;
        for(ObjectNode station:stations) {
            ObjectNode feature=features.addObject();feature.put("type","Feature");feature.put("id",station.path("id").asText());
            ObjectNode geometry=feature.putObject("geometry");geometry.put("type","Point");geometry.putArray("coordinates").add(station.path("longitude").asDouble()).add(station.path("latitude").asDouble());feature.set("properties",station);
            byAccess.merge(station.path("access").asText(),1,Integer::sum);
            for(JsonNode offer:station.path("offers"))byCarrier.merge(offer.path("carrier").asText(),1,Integer::sum);
            for(JsonNode flag:station.path("qualityFlags"))flags.merge(flag.asText(),1,Integer::sum);
            if(station.path("source").asText().equals("bnetza"))registerPoints+=station.path("chargingPoints").asInt();
        }
        ObjectNode quality=JSON.createObjectNode();quality.put("contract",CONTRACT);quality.put("status",failures.isEmpty()?"inventory-only":"failed");
        quality.put("stationRecords",stations.size());quality.set("recordsByCarrier",JSON.valueToTree(byCarrier));quality.set("recordsByAccess",JSON.valueToTree(byAccess));
        if(config.useBnetza && failures.stream().noneMatch(f->f.startsWith("BNetzA:")))quality.put("observedRegisterChargingPoints",registerPoints);else quality.putNull("observedRegisterChargingPoints");
        quality.set("sources",sources);quality.set("qualityFlagCounts",JSON.valueToTree(flags));quality.set("excluded",JSON.valueToTree(excluded));quality.set("errors",JSON.valueToTree(failures));
        quality.put("coverageCertified",false);quality.put("networkAccessVerified",false);quality.put("vehicleCompatibilityVerified",false);quality.put("energyStateSimulated",false);
        quality.put("countingPolicy","Station records are source observations, not certified unique physical sites. Register charging points counted once from BNetzA only; connector counts and OSM observations are not added.");
        quality.put("scope","Public-source infrastructure inventory. Unknown/restricted access remains separate. Private and explicitly inactive stations excluded. No live availability, road access, HGV suitability or charging/refuelling behaviour implied.");
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("stations.geojson").toFile(),collection);
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("quality.json").toFile(),quality);
        if(!failures.isEmpty())throw new IOException("Stationsquellen fehlgeschlagen; siehe energy/quality.json: "+String.join("; ",failures));
        log.accept(stations.size()+" Stationsmeldungen erfasst; "+registerPoints+" BNetzA-Ladepunkte; Zugang, Dubletten und Datenlücken in energy/quality.json");
        return quality;
    }
    private static ObjectNode sourceFailure(String source,Exception e){ObjectNode n=JSON.createObjectNode();n.put("id",source);n.put("status","failed");n.put("message",String.valueOf(e.getMessage()));return n;}

    private static void loadBnetza(Scenario scenario,List<ObjectNode> stations,ArrayNode sources,Map<String,Integer> excluded)throws IOException,NoSuchAlgorithmException {
        var dataset=MobileCityChargingSource.loadBundled();Map<String,MobileCityChargingSource.ChargingPoint> selected=new TreeMap<>();
        for(var point:dataset.points())if(inside(scenario.region,point.lon(),point.lat())) {
            if(point.commissionedOn().getYear()>scenario.referenceYear){increment(excluded,"commissioned-after-reference-year");continue;}
            selected.put(point.facilityId(),point);
        }
        JsonNode meta;
        try(var input=EnergyInfrastructure.class.getResourceAsStream(DETAILS+".json")) {
            if(input==null)throw new IOException("BNetzA-Anschlussmetadaten fehlen");meta=JSON.readTree(input);
        }
        if(!meta.path("referenceNormalizedSha256").asText().equals(String.valueOf(dataset.metadata().get("normalizedContentSha256"))))throw new IOException("BNetzA-Referenzen unterscheiden sich");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");int facilities=0,points=0;
        try(var raw=EnergyInfrastructure.class.getResourceAsStream(DETAILS+".json.gz")) {
            if(raw==null)throw new IOException("BNetzA-Anschlussdaten fehlen");
            try(var input=new DigestInputStream(new GZIPInputStream(raw),digest);var parser=JSON.getFactory().createParser(input)) {
                if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IOException("BNetzA-Anschlussschema falsch");
                while(parser.nextToken()!=JsonToken.END_ARRAY) {
                    JsonNode detail=JSON.readTree(parser);if(detail==null)throw new IOException("BNetzA-Anschlussdaten abgeschnitten");facilities++;points+=detail.path("points").size();
                    var point=selected.remove(detail.path("facilityId").asText());if(point==null)continue;
                    if(detail.path("points").size()!=point.chargingPoints())throw new IOException("BNetzA-Ladepunktzahl inkonsistent");
                    ObjectNode station=base("bnetza:"+point.facilityId(),detail.path("name").asText(),point.lon(),point.lat(),"bnetza","public","register-coordinate");
                    station.put("referenceDate","2026-09-01");station.put("commissionedOn",point.commissionedOn().toString());station.put("chargingPoints",point.chargingPoints());station.put("facilityNominalPowerKw",point.nominalPowerKw());station.put("powerSharing","unknown");
                    station.set("openingHours",detail.path("openingHours"));station.put("observedOperatingStatus","in-service-at-snapshot");
                    ObjectNode offer=station.withArray("offers").addObject();offer.put("carrier","electricity");offer.set("points",detail.path("points"));offer.putArray("pressureBars");offer.putArray("connectors");
                    if(scenario.referenceYear!=2026)flag(station,"scenario-year-differs-from-snapshot");
                    flag(station,"live-availability-unknown");flag(station,"power-sharing-unknown");
                    if(detail.path("openingHours").path("kind").asText().equals("Keine Angabe"))flag(station,"opening-hours-unknown");
                    for(JsonNode cp:detail.path("points")) {
                        if(!cp.path("powerAssociationKnown").asBoolean())flag(station,"connector-power-association-unknown");
                        for(JsonNode connector:cp.path("connectors"))if(connector.path("type").asText().equals("unknown"))flag(station,"unknown-connector");
                    }
                    stations.add(station);
                }
                while(parser.nextToken()!=null)throw new IOException("Unerwartete weitere BNetzA-Daten");
            }
        }
        if(!HexFormat.of().formatHex(digest.digest()).equals(meta.path("contentSha256").asText()) || facilities!=meta.path("facilities").asInt() || points!=meta.path("chargingPoints").asInt() || !selected.isEmpty())throw new IOException("BNetzA-Anschlussbestand nicht vollständig/hashgleich");
        ObjectNode source=sources.addObject();source.put("id","bnetza");source.put("status","loaded");source.set("metadata",meta);source.put("scope","Surviving public register stock, filtered by coordinates and commissioning year. Historical completeness and live availability unknown.");
    }
    private static ObjectNode base(String id,String name,double lon,double lat,String source,String access,String coordinateKind) {
        ObjectNode s=JSON.createObjectNode();s.put("id",id);s.put("name",name);s.put("longitude",lon);s.put("latitude",lat);s.put("source",source);s.put("access",access);s.put("coordinateKind",coordinateKind);s.put("hgvAccess","unknown");s.put("observedOperatingStatus","unknown");s.putNull("chargingPoints");s.putNull("facilityNominalPowerKw");s.put("powerSharing","unknown");s.putArray("offers");s.putArray("qualityFlags");s.putArray("possibleDuplicateIds");
        flag(s,"network-access-unverified");flag(s,"hgv-suitability-unknown");return s;
    }
    private static void loadOsm(Snapshot snapshot,Scenario scenario,List<ObjectNode> stations,ArrayNode sources,Map<String,Integer> excluded) {
        for(JsonNode item:snapshot.data().path("elements")) {
            JsonNode tags=item.path("tags");String amenity=tags.path("amenity").asText();
            if(!Set.of("fuel","charging_station").contains(amenity)){increment(excluded,"other-amenity");continue;}
            if(List.of("disused","abandoned","construction").stream().anyMatch(k->tags.path(k).asText().equals("yes"))){increment(excluded,"explicitly-inactive");continue;}
            String access=tags.path("access").asText();
            if(Set.of("private","no").contains(access)){increment(excluded,"private-or-no-access");continue;}
            String normalized=Set.of("yes","permissive").contains(access)?"public":Set.of("customers","destination","permit").contains(access)?"restricted":"unknown";
            if(tags.has("access:conditional"))normalized="unknown";
            JsonNode coordinates=item.path("type").asText().equals("node")?item:item.path("center");
            if(!coordinates.path("lon").isNumber() || !coordinates.path("lat").isNumber()){increment(excluded,"missing-coordinate");continue;}
            double lon=coordinates.path("lon").asDouble(),lat=coordinates.path("lat").asDouble();
            if(!Double.isFinite(lon) || !Double.isFinite(lat) || lon< -180 || lon>180 || lat< -90 || lat>90){increment(excluded,"invalid-coordinate");continue;}
            if(!inside(scenario.region,lon,lat)){increment(excluded,"outside-region");continue;}
            String id="osm:"+item.path("type").asText()+":"+item.path("id").asText();
            ObjectNode station=base(id,tags.path("name").asText(id),lon,lat,"osm",normalized,item.path("type").asText().equals("node")?"mapped-node":"bounding-box-center");
            station.put("sourceObjectType",item.path("type").asText());station.set("sourceObjectId",item.path("id"));station.set("sourceVersion",item.path("version"));station.set("sourceTimestamp",item.path("timestamp"));station.put("sourceSha256",snapshot.sha256());
            station.put("hgvAccess",tags.path("hgv").asText("unknown"));
            station.set("openingHours",JSON.createObjectNode().put("kind",tags.path("opening_hours").asText("unknown")));
            station.put("sourceAccess",access);station.put("accessConditional",tags.path("access:conditional").asText());station.put("evseReference",tags.path("ref:EU:EVSE").asText(tags.path("ref:EVSE").asText()));
            ObjectNode rawRestrictions=station.putObject("sourceVehicleRestrictions");for(String key:List.of("motorcar","motor_vehicle","bicycle","maxheight","maxwidth","maxlength","maxweight","hgv:conditional"))if(tags.has(key))rawRestrictions.set(key,tags.path(key));
            ArrayNode offers=station.withArray("offers");
            if(amenity.equals("charging_station")) {
                ObjectNode offer=offers.addObject();offer.put("carrier","electricity");offer.putArray("points");offer.putArray("pressureBars");ArrayNode connectors=offer.putArray("connectors");
                tags.properties().forEach(e->{if(e.getKey().startsWith("socket:") && !e.getKey().substring(7).contains(":")) {
                    ObjectNode connector=connectors.addObject();String socket=e.getKey().substring(7);connector.put("type",socket.equals("type2_combo")?"ccs2":socket.equals("type2_cable")?"type2":socket);connector.put("sourceType",e.getKey());connector.put("sourceCount",e.getValue().asText());
                    Integer count=positiveInteger(e.getValue().asText());if(count==null)connector.putNull("count");else connector.put("count",count);
                    Double power=powerKw(tags.path(e.getKey()+":output").asText());if(power==null)connector.putNull("powerKw");else connector.put("powerKw",power);
                }});
                Integer capacity=positiveInteger(tags.path("capacity").asText());if(capacity!=null)station.put("chargingPoints",capacity);
                flag(station,"charging-point-identities-unknown");if(connectors.isEmpty())flag(station,"unknown-connector");
            }
            for(String fuel:List.of("cng","lng","lpg"))if(tags.path("fuel:"+fuel).asText().equals("yes"))fuelOffer(offers,fuel,List.of());
            List<Integer> pressure=new ArrayList<>();if(tags.path("fuel:h35").asText().equals("yes"))pressure.add(350);if(tags.path("fuel:h70").asText().equals("yes"))pressure.add(700);
            if(!pressure.isEmpty() || tags.path("fuel:hydrogen").asText().equals("yes")){fuelOffer(offers,"hydrogen",pressure);flag(station,"hydrogen-vehicle-compatibility-unverified");if(pressure.isEmpty())flag(station,"hydrogen-pressure-unknown");}
            if(offers.isEmpty()){increment(excluded,"no-supported-energy-tags");continue;}
            if(!normalized.equals("public"))flag(station,"public-access-unconfirmed");
            if(!item.path("type").asText().equals("node"))flag(station,"representative-center-not-entrance");
            flag(station,"live-availability-unknown");flag(station,"temporal-validity-unverified");stations.add(station);
        }
        ObjectNode source=sources.addObject();source.put("id","osm");source.put("status","loaded");source.put("sha256",snapshot.sha256());source.put("referenceDate",snapshot.data().path("osm3s").path("timestamp_osm_base").asText("unknown"));source.put("retrievedAt",snapshot.metadata().path("retrievedAt").asText());source.put("sourceUrl",snapshot.metadata().path("sourceUrl").asText());source.put("license","ODbL");source.put("attribution","© OpenStreetMap contributors");source.put("objects",snapshot.data().path("elements").size());
    }
    private static void fuelOffer(ArrayNode offers,String carrier,List<Integer> pressure){ObjectNode n=offers.addObject();n.put("carrier",carrier);n.putArray("points");n.putArray("connectors");n.set("pressureBars",JSON.valueToTree(pressure));}
    private static Integer positiveInteger(String value){try{int v=Integer.parseInt(value);return v>0?v:null;}catch(NumberFormatException e){return null;}}
    private static Double powerKw(String value){try{String v=value.trim();double divisor=v.endsWith("kW")?1:v.endsWith("W")?1000:Double.NaN;double n=Double.parseDouble(v.replaceFirst("\\s*(kW|W)$",""))/divisor;return Double.isFinite(n) && n>0?n:null;}catch(NumberFormatException e){return null;}}
    private static boolean inside(Scenario.Region r,double lon,double lat){return lon>=r.minLon && lon<=r.maxLon && lat>=r.minLat && lat<=r.maxLat;}
    private static void increment(Map<String,Integer> counts,String key){counts.merge(key,1,Integer::sum);}
    private static void flag(ObjectNode s,String flag){ArrayNode flags=s.withArray("qualityFlags");for(JsonNode n:flags)if(n.asText().equals(flag))return;flags.add(flag);}
    private static void markPossibleDuplicates(List<ObjectNode> stations) {
        STRtree index=new STRtree();Map<String,Set<String>> evseRefs=new HashMap<>();
        for(ObjectNode station:stations)if(station.path("source").asText().equals("bnetza")) {
            double lon=station.path("longitude").asDouble(),lat=station.path("latitude").asDouble();index.insert(new Envelope(lon,lon,lat,lat),station);
            for(JsonNode offer:station.path("offers"))for(JsonNode point:offer.path("points"))if(point.path("evseId").isTextual() && !point.path("evseId").asText().isBlank())evseRefs.computeIfAbsent(point.path("evseId").asText(),ignored->new TreeSet<>()).add(station.path("id").asText());
        }
        index.build();
        for(ObjectNode station:stations)if(station.path("source").asText().equals("osm")) {
            Set<String> matches=new TreeSet<>();for(String ref:station.path("evseReference").asText().split(";"))matches.addAll(evseRefs.getOrDefault(ref.trim(),Set.of()));
            double lon=station.path("longitude").asDouble(),lat=station.path("latitude").asDouble(),dx=.00045/Math.max(.01,Math.cos(Math.toRadians(lat)));
            for(Object candidate:index.query(new Envelope(lon-dx,lon+dx,lat-.00045,lat+.00045))) {
                ObjectNode other=(ObjectNode)candidate;double x=(other.path("longitude").asDouble()-lon)*Math.cos(Math.toRadians(lat))*111_320,y=(other.path("latitude").asDouble()-lat)*111_320;
                if(Math.hypot(x,y)<=50)matches.add(other.path("id").asText());
            }
            if(!matches.isEmpty()){flag(station,"possible-cross-source-duplicate");station.put("possibleDuplicateCount",matches.size());matches.stream().limit(10).forEach(id->station.withArray("possibleDuplicateIds").add(id));}
        }
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
