package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadLocationsTest {
    @TempDir Path dir;
    @Test void overlappingExternalBoxCannotMoveAnOpportunityIntoTheStudyArea()throws Exception {
        var internal=zone();var external=zone();external.index=1;external.id=100002;external.external=true;
        external.maxLon+=.02;
        var locations=new RoadLocations(List.of(internal,external),"hash");
        locations.add(1,0,0,new RoadLocations.Point(8.201,48.852),new RoadLocations.Point(8.205,48.852));locations.finish();
        assertThrows(java.io.IOException.class,()->locations.locateOpportunity(external,8.208,48.852,"opportunity:Service","outside"));
    }
    @Test void externalOpportunitiesUseSameZoneRoadsWithBoundedDisplacementWhileAnchorsStayUnchanged()throws Exception {
        var z=zone();z.external=true;var locations=new RoadLocations(List.of(z),"hash");
        double lon=(z.minLon+z.maxLon)/2,lat=(z.minLat+z.maxLat)/2;
        locations.add(1,0,0,new RoadLocations.Point(lon-.001,lat+.001),new RoadLocations.Point(lon+.001,lat+.001));locations.finish();
        assertEquals(new RoadLocations.Point(lon,lat),locations.locate(z,lon,lat,"fixed-work","p"));
        var point=locations.locateOpportunity(z,lon,lat,"opportunity:Service","p");
        assertTrue(z.contains(point.lat(),point.lon()));assertEquals(lat+.001,point.lat(),1e-6);
        locations.write(dir.resolve("external.json"));var report=new ObjectMapper().readTree(dir.resolve("external.json").toFile());
        assertEquals("external-opportunity-placed",report.path("assignments").get(1).path("status").asText());
        assertTrue(report.path("assignments").get(1).path("displacementMetres").asDouble()<1500);
        assertEquals(new RoadLocations.Point(lon,lat),locations.locate(z,lon,lat,"opportunity:Service","anchor"));
        assertEquals(point,locations.locateOpportunity(z,lon,lat,"opportunity:Service","again"));
        var missing=new RoadLocations(List.of(z),"hash");
        assertThrows(java.io.IOException.class,()->missing.locateOpportunity(z,lon,lat,"opportunity:Service","p"));
        z.minLat=lat-.03;z.maxLat=lat+.03;var far=new RoadLocations(List.of(z),"hash");
        far.add(1,0,0,new RoadLocations.Point(lon-.001,lat+.02),new RoadLocations.Point(lon+.001,lat+.02));far.finish();
        assertThrows(java.io.IOException.class,()->far.locateOpportunity(z,lon,lat,"opportunity:Service","p"));
    }
    static final String OSM="<osm version='0.6'><node id='1' lat='48.85' lon='8.2'/><node id='2' lat='48.85' lon='8.206'/><node id='3' lat='48.854' lon='8.206'/><node id='4' lat='48.854' lon='8.2'/><node id='5' lat='48.852' lon='8.201'/><node id='6' lat='48.852' lon='8.205'/><way id='11'><nd ref='1'/><nd ref='2'/><nd ref='3'/><nd ref='4'/><nd ref='1'/><tag k='highway' v='residential'/></way><way id='12'><nd ref='1'/><nd ref='5'/><nd ref='6'/><nd ref='2'/><tag k='highway' v='service'/><tag k='access' v='private'/></way></osm>";
    static final String SERVICE_ISLAND_OSM="<osm version='0.6'><node id='1' lon='8.2' lat='48.85'/><node id='2' lon='8.206' lat='48.85'/><node id='3' lon='8.206' lat='48.854'/><node id='4' lon='8.2' lat='48.854'/><node id='5' lon='8.208' lat='48.852'/><node id='6' lon='8.209' lat='48.852'/><node id='7' lon='8.209' lat='48.853'/><node id='8' lon='8.208' lat='48.853'/><way id='11'><nd ref='1'/><nd ref='2'/><nd ref='3'/><nd ref='4'/><nd ref='1'/><tag k='highway' v='residential'/></way><way id='12'><nd ref='2'/><nd ref='5'/><tag k='highway' v='service'/></way><way id='13'><nd ref='5'/><nd ref='6'/><nd ref='7'/><nd ref='8'/><nd ref='5'/><tag k='highway' v='residential'/></way></osm>";
    static Zone zone(){var z=new Zone();z.index=0;z.id=100001;z.minLon=8.199;z.maxLon=8.207;z.minLat=48.849;z.maxLat=48.855;z.centroidLon=8.203;z.centroidLat=48.852;z.population=100;z.attractivities.put("Work",100.);z.attractivities.put("Shopping",100.);return z;}
    @Test void placementPreservesDemographicsDestinationZonesAndRandomStream()throws Exception {
        for(long seed:List.of(7L,42L,113L)) {
            var z=zone();var a=new Random(seed);var b=new Random(seed);var plain=new Scenario();plain.population.useKitPopulation=false;
            var enabled=new Scenario();enabled.population.useKitPopulation=false;enabled.population.roadLocatedActivities=true;
            try(var engine=RoutingEngine.fromOsmXml(OSM,true)) {
                var locations=engine.roadLocations(List.of(z));
                new PopulationGenerator(List.of(z),a).generate(dir.resolve("plain"),plain);
                new PopulationGenerator(List.of(z),b).useRoadLocations(locations).generate(dir.resolve("placed"),enabled);
                assertArrayEquals(Files.readAllBytes(dir.resolve("plain/person.csv")),Files.readAllBytes(dir.resolve("placed/person.csv")));
                for(String file:List.of("household.csv","car.csv","fixedDestination.csv","fixedDestination-logitopp.csv")) {
                    var before=Files.readAllLines(dir.resolve("plain/"+file));var after=Files.readAllLines(dir.resolve("placed/"+file));assertEquals(before.size(),after.size());
                    int position=file.startsWith("fixed")?2:file.equals("car.csv")?6:4;
                    for(int i=1;i<before.size();i++) {
                        var x=before.get(i).split(";",-1);var y=after.get(i).split(";",-1);
                        for(int col=0;col<x.length;col++)if(col!=position)assertEquals(x[col],y[col],file+" field "+col);
                        if(!file.equals("car.csv")){String p=y[position];var xy=p.substring(1,p.indexOf(':')).split(",");assertTrue(z.contains(Double.parseDouble(xy[1]),Double.parseDouble(xy[0])));}
                    }
                }
                new LegacyExportGenerator(List.of(z),a).writeOpportunities(dir.resolve("plain/opportunity.csv"));
                new LegacyExportGenerator(List.of(z),b).useRoadLocations(locations).writeOpportunities(dir.resolve("placed/opportunity.csv"));
                assertEquals(a.nextLong(),b.nextLong(),"Location placement must consume no random draws");
                locations.write(dir.resolve("report.json"));var report=new ObjectMapper().readTree(dir.resolve("report.json").toFile());
                assertEquals(0,report.path("failedLocations").asInt());
                assertTrue(report.path("sourceOsmSha256").asText().matches("[a-f0-9]{64}"));
                for(var row:report.path("assignments"))assertEquals(11,row.path("osmWayId").asLong(),"private service road cannot host synthetic addresses");
            }
        }
    }
    @Test void serviceOnlyConnectedIslandCannotHostLocationsButAResidentialBridgeConnectsIt()throws Exception {
        var z=zone();z.maxLon=8.210;
        for(boolean bridge:List.of(false,true)) {
            String osm=bridge?SERVICE_ISLAND_OSM.replace("v='service'","v='residential'/><tag k='bridge' v='yes'"):SERVICE_ISLAND_OSM;
            try(var engine=RoutingEngine.fromOsmXml(osm,true)) {
                // GH routes across public service roads: its built-in subnetwork check cannot catch this island.
                var path=engine.roadPath(48.85,8.203,48.8525,8.208,MatrixGenerator.TravelMode.CAR);
                assertNotNull(path);assertTrue(path.segments().stream().anyMatch(segment->segment.osmWayId()==12));
                var locations=engine.roadLocations(List.of(z));
                locations.locate(z,8.2085,48.852,"home","1");locations.write(dir.resolve("island.json"));
                var report=new ObjectMapper().readTree(dir.resolve("island.json").toFile());
                assertEquals(bridge?13:11,report.path("assignments").get(0).path("osmWayId").asLong());
                assertEquals(bridge?1:2,report.path("passengerConnectivity").path("components").asInt());
                assertEquals(!bridge,report.path("passengerConnectivity").path("excludedCandidateEdges").asInt()>0);
            }
        }
    }
    @Test void walkingMainComponentExcludesCarReachableIslandBehindFastRoad()throws Exception {
        var z=zone();z.maxLon=8.210;
        for(String speed:List.of("50","70")) {
            String osm=SERVICE_ISLAND_OSM.replace("v='service'","v='secondary'/><tag k='maxspeed' v='"+speed+"'/><tag k='bridge' v='yes'");
            try(var engine=RoutingEngine.fromOsmXml(osm,true)) {
                assertNotNull(engine.roadPath(48.85,8.203,48.8525,8.208,MatrixGenerator.TravelMode.FOOT));
                var locations=engine.roadLocations(List.of(z));locations.locate(z,8.2085,48.852,"home","1");locations.write(dir.resolve("walking.json"));
                var report=new ObjectMapper().readTree(dir.resolve("walking.json").toFile());
                assertEquals(speed.equals("50")?13:11,report.path("assignments").get(0).path("osmWayId").asLong());
                assertEquals(1,report.path("passengerConnectivity").path("components").asInt());
                assertEquals(speed.equals("50")?1:2,report.path("pedestrianConnectivity").path("components").asInt());
            }
        }
        assertTrue(RoadLocations.pedestrianConnectorTags(Map.of("highway","footway")));
        assertTrue(RoadLocations.pedestrianConnectorTags(Map.of("highway","service","vehicle","no")));
        assertFalse(RoadLocations.pedestrianConnectorTags(Map.of("highway","secondary","maxspeed","70")));
        assertFalse(RoadLocations.pedestrianConnectorTags(Map.of("highway","footway","access","private")));
    }
    @Test void serializedPointsStayInsideRotatedLaeaZoneAndExternalAnchorsRemainUntouched()throws Exception {
        var z=zone();z.laeaRes=1000;double[] en=Epsg3035.fromLatLon(51.5,7.5);z.laeaE=(long)Math.floor(en[0]/1000)*1000;z.laeaN=(long)Math.floor(en[1]/1000)*1000;
        var corners=new ArrayList<double[]>();for(int x:List.of(0,1000))for(int y:List.of(0,1000))corners.add(Epsg3035.toLatLon(z.laeaE+x,z.laeaN+y));
        z.minLat=corners.stream().mapToDouble(p->p[0]).min().orElseThrow();z.maxLat=corners.stream().mapToDouble(p->p[0]).max().orElseThrow();z.minLon=corners.stream().mapToDouble(p->p[1]).min().orElseThrow();z.maxLon=corners.stream().mapToDouble(p->p[1]).max().orElseThrow();
        var center=Epsg3035.toLatLon(z.laeaE+500,z.laeaN+500);z.centroidLat=center[0];z.centroidLon=center[1];
        var start=Epsg3035.toLatLon(z.laeaE-100,z.laeaN+500);var end=Epsg3035.toLatLon(z.laeaE+1100,z.laeaN+500);
        var external=zone();external.index=1;external.id=900001;external.external=true;
        var locations=new RoadLocations(List.of(z,external),"hash");locations.add(1,0,0,new RoadLocations.Point(start[1],start[0]),new RoadLocations.Point(end[1],end[0]));locations.finish();
        var random=new Random(42);for(int i=0;i<200;i++) {
            var p=locations.locate(z,z.minLon+random.nextDouble()*(z.maxLon-z.minLon),z.minLat+random.nextDouble()*(z.maxLat-z.minLat),"home",""+i);
            assertTrue(z.contains(p.lat(),p.lon()));
        }
        assertEquals(new RoadLocations.Point(7.1234567,51.1234567),locations.locate(external,7.1234567,51.1234567,"work","x"));
    }
    @Test void missingRoadFailsWithReportAndRestrictedCandidatesAreRejected()throws Exception {
        var locations=new RoadLocations(List.of(zone()),"hash");
        assertThrows(java.io.IOException.class,()->locations.locate(zone(),8.2,48.85,"home","h1"));
        locations.write(dir.resolve("report.json"));var report=new ObjectMapper().readTree(dir.resolve("report.json").toFile());assertEquals("failed",report.path("status").asText());
        assertFalse(RoadLocations.eligibleTags(Map.of()));
        assertTrue(RoadLocations.eligibleTags(Map.of("highway","secondary","maxspeed","50")));
        assertTrue(RoadLocations.eligibleTags(Map.of("highway","residential")));
        for(String speed:List.of("50d","50f"," 50 ","1e1","NaN","Infinity"))
            assertFalse(RoadLocations.eligibleTags(Map.of("highway","secondary","maxspeed",speed)));
        for(var tags:List.of(Map.<String,Object>of("highway","motorway"),Map.<String,Object>of("highway","service"),Map.<String,Object>of("highway","secondary"),Map.<String,Object>of("highway","secondary","maxspeed","70"),Map.<String,Object>of("highway","residential","maxspeed","50","maxspeed:forward","70"),Map.<String,Object>of("highway","residential","maxspeed","signals"),Map.<String,Object>of("highway","residential","access:conditional","yes @ Mo-Fr"),Map.<String,Object>of("highway","residential","bridge","yes"),Map.<String,Object>of("highway","residential","foot","no")))assertFalse(RoadLocations.eligibleTags(tags));
    }
}
