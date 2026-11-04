package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EnergyInfrastructureTest {
    @TempDir Path dir;
    static final ObjectMapper JSON=new ObjectMapper();
    public static final String OSM="""
      {"osm3s":{"timestamp_osm_base":"2026-10-20T08:00:00Z"},"elements":[
      {"type":"node","id":1,"version":3,"timestamp":"2026-09-01T00:00:00Z","lon":7.5,"lat":51.5,"tags":{"amenity":"fuel","name":"Gas <img src=x onerror=alert(1)>","access":"yes","fuel:cng":"yes","fuel:lpg":"yes"}},
      {"type":"way","id":2,"center":{"lon":7.51,"lat":51.51},"tags":{"amenity":"fuel","access":"yes","fuel:h35":"yes","fuel:h70":"yes"}},
      {"type":"relation","id":3,"center":{"lon":7.52,"lat":51.52},"tags":{"amenity":"charging_station","access":"customers","capacity":"2","socket:type2_combo":"3","socket:type2_combo:output":"150 kW"}},
      {"type":"node","id":4,"lon":7.5,"lat":51.5,"tags":{"amenity":"charging_station","access":"private"}},
      {"type":"node","id":5,"tags":{"amenity":"fuel","fuel:cng":"yes"}},
      {"type":"node","id":6,"lon":7.5,"lat":51.5,"tags":{"amenity":"fuel","fuel:diesel":"yes"}},
      {"type":"node","id":7,"lon":7.53,"lat":51.53,"tags":{"amenity":"fuel","fuel:lng":"yes"}},
      {"type":"node","id":8,"lon":9,"lat":51.5,"tags":{"amenity":"fuel","access":"yes","fuel:cng":"yes"}},
      {"type":"node","id":9,"lon":7.5,"lat":51.5,"tags":{"amenity":"fuel","disused":"yes","fuel:cng":"yes"}}
      ]}
      """;
    public static Scenario scenario() {
        var s=new Scenario();s.region=new Scenario.Region();s.region.minLon=7.3;s.region.maxLon=7.85;s.region.minLat=51.4;s.region.maxLat=51.65;
        s.energyInfrastructure.enabled=true;s.energyInfrastructure.useBnetza=false;return s;
    }
    @Test void mixedOsmObjectsRetainUnknownsAndFrozenReplayIsByteIdentical()throws Exception {
        var s=scenario();AtomicInteger calls=new AtomicInteger();Path artifacts=dir.resolve("artifacts"),uploads=dir.resolve("uploads");
        var result=EnergyInfrastructure.generate(s,artifacts,uploads,()->{calls.incrementAndGet();return new OverpassClient.EnergyResponse(JSON.readTree(OSM),"https://example.test/interpreter");},ignored->{});
        assertEquals(4,result.path("stationRecords").asInt());assertEquals(2,result.path("recordsByAccess").path("public").asInt());assertEquals(1,result.path("recordsByAccess").path("unknown").asInt());assertTrue(result.path("observedRegisterChargingPoints").isNull());
        assertEquals(1,result.path("excluded").path("private-or-no-access").asInt());assertEquals(1,result.path("excluded").path("missing-coordinate").asInt());assertFalse(result.path("networkAccessVerified").asBoolean());
        var geo=JSON.readTree(artifacts.resolve("energy/stations.geojson").toFile());Map<String,JsonNode> features=new HashMap<>();geo.path("features").forEach(f->features.put(f.path("id").asText(),f.path("properties")));
        assertEquals("bounding-box-center",features.get("osm:way:2").path("coordinateKind").asText());assertEquals(List.of(350,700),JSON.convertValue(features.get("osm:way:2").path("offers").get(0).path("pressureBars"),List.class));
        assertEquals(2,features.get("osm:relation:3").path("chargingPoints").asInt(),"Three alternative plugs must not become three charging points");
        var connector=features.get("osm:relation:3").path("offers").get(0).path("connectors").get(0);assertEquals("ccs2",connector.path("type").asText());assertEquals(150,connector.path("powerKw").asInt());
        byte[] originalGeo=Files.readAllBytes(artifacts.resolve("energy/stations.geojson")),originalQuality=Files.readAllBytes(artifacts.resolve("energy/quality.json"));
        EnergyInfrastructure.generate(s,artifacts,uploads,()->{throw new AssertionError("frozen input must suppress network");},ignored->{});
        assertEquals(1,calls.get());assertArrayEquals(originalGeo,Files.readAllBytes(artifacts.resolve("energy/stations.geojson")));assertArrayEquals(originalQuality,Files.readAllBytes(artifacts.resolve("energy/quality.json")));
        s.region.maxLon=7.9;assertThrows(java.io.IOException.class,()->EnergyInfrastructure.generate(s,artifacts,uploads,()->{throw new AssertionError();},ignored->{}));assertEquals("failed",JSON.readTree(artifacts.resolve("energy/quality.json").toFile()).path("status").asText());
    }
    @Test void errorsNeverBecomeSuccessfulEmptyCoverageAndDisabledRemovesOldOutput()throws Exception {
        var s=scenario();Path out=dir.resolve("out");
        assertThrows(java.io.IOException.class,()->EnergyInfrastructure.generate(s,out,dir.resolve("uploads"),()->{throw new java.io.IOException("source unavailable");},ignored->{}));
        var report=JSON.readTree(out.resolve("energy/quality.json").toFile());assertEquals("failed",report.path("status").asText());assertFalse(report.path("coverageCertified").asBoolean());assertTrue(report.path("errors").get(0).asText().contains("source unavailable"));
        assertThrows(java.io.IOException.class,()->EnergyInfrastructure.snapshot(JSON.readTree("{\"elements\":[],\"remark\":\"runtime timeout\"}"),s.region,"fixture"));
        assertThrows(java.io.IOException.class,()->EnergyInfrastructure.snapshot(JSON.readTree("{\"elements\":[{\"type\":\"node\",\"id\":1},{\"type\":\"node\",\"id\":1}]}"),s.region,"fixture"));
        s.energyInfrastructure.enabled=false;assertNull(EnergyInfrastructure.generate(s,out,dir.resolve("uploads"),()->{throw new AssertionError();},ignored->{}));assertFalse(Files.exists(out.resolve("energy/quality.json")));assertFalse(Files.exists(out.resolve("energy/stations.geojson")));
    }
    @Test void realPinnedBnetzaDetailsKeepEvsePointsAndFacilityPowerSeparate()throws Exception {
        var s=scenario();s.region.minLon=9.659074;s.region.maxLon=9.659076;s.region.minLat=48.442397;s.region.maxLat=48.442399;s.energyInfrastructure.useBnetza=true;s.energyInfrastructure.useOsm=false;
        var report=EnergyInfrastructure.generate(s,dir,dir.resolve("uploads"),()->{throw new AssertionError();},ignored->{});
        assertEquals("inventory-only",report.path("status").asText());assertTrue(report.path("observedRegisterChargingPoints").asInt()>=2);
        JsonNode station=null;for(JsonNode f:JSON.readTree(dir.resolve("energy/stations.geojson").toFile()).path("features"))if(f.path("id").asText().equals("bnetza:1010338"))station=f.path("properties");
        assertNotNull(station);assertEquals(2,station.path("chargingPoints").asInt());assertEquals(22,station.path("facilityNominalPowerKw").asInt());assertEquals("unknown",station.path("powerSharing").asText());
        var points=station.path("offers").get(0).path("points");assertEquals(2,points.size());assertEquals("DEAEWE002501",points.get(0).path("evseId").asText());assertEquals(22,points.get(0).path("connectors").get(0).path("powerKw").asInt());assertEquals("type2",points.get(0).path("connectors").get(0).path("type").asText());
        int registerCount=report.path("observedRegisterChargingPoints").asInt();
        s.energyInfrastructure.useOsm=true;
        var osm=JSON.readTree("""
            {"elements":[{"type":"node","id":123,"lon":9.659075,"lat":48.442398,"tags":{"amenity":"charging_station","access":"yes","capacity":"99","ref:EU:EVSE":"DEAEWE002501"}}]}
            """);
        var combined=EnergyInfrastructure.generate(s,dir,dir.resolve("uploads"),()->new OverpassClient.EnergyResponse(osm,"fixture"),ignored->{});
        assertEquals(registerCount,combined.path("observedRegisterChargingPoints").asInt(),"An OSM capacity must never be added to the register count");
        assertEquals(report.path("stationRecords").asInt()+1,combined.path("stationRecords").asInt(),"Nearby source observations must not be silently merged");
        for(JsonNode f:JSON.readTree(dir.resolve("energy/stations.geojson").toFile()).path("features"))if(f.path("id").asText().equals("osm:node:123"))
            assertTrue(f.path("properties").path("possibleDuplicateIds").toString().contains("bnetza:1010338"));
    }
}
