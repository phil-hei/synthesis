package de.synthesis.generation.pipeline;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.TravelDemand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RoadLocationsIntegrationTest {
    @TempDir Path dir;
    @Test void nativeSidewalkGuessingNeedsConservativeSpeedCandidates()throws Exception {
        var config=new SynthesisProperties.Sumo();Path binary=Path.of(config.getBinDir()).resolve("netconvert");
        assumeTrue(Files.isExecutable(binary),"native SUMO missing");
        String osm="<osm version='0.6'><node id='1' lon='8.2' lat='48.85'/><node id='2' lon='8.201' lat='48.85'/><node id='3' lon='8.202' lat='48.85'/><node id='4' lon='8.203' lat='48.85'/><node id='5' lon='8.204' lat='48.85'/><node id='6' lon='8.205' lat='48.85'/>"
                +"<way id='101'><nd ref='1'/><nd ref='2'/><tag k='highway' v='secondary'/><tag k='maxspeed' v='50'/></way>"
                +"<way id='102'><nd ref='2'/><nd ref='3'/><tag k='highway' v='secondary'/><tag k='maxspeed' v='70'/></way>"
                +"<way id='103'><nd ref='3'/><nd ref='4'/><tag k='highway' v='secondary'/></way>"
                +"<way id='104'><nd ref='4'/><nd ref='5'/><tag k='highway' v='secondary'/><tag k='maxspeed' v='50d'/></way>"
                +"<way id='105'><nd ref='5'/><nd ref='6'/><tag k='highway' v='secondary'/><tag k='maxspeed' v=' 50 '/></way></osm>";
        Files.writeString(dir.resolve("speed.osm"),osm);
        var process=new ProcessBuilder(binary.toString(),"--osm-files","speed.osm","-o","speed.net.xml","--sidewalks.guess","--crossings.guess")
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("speed.log").toFile()).start();
        try {assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),()->{try{return Files.readString(dir.resolve("speed.log"));}catch(Exception e){return e.toString();}});}
        finally {if(process.isAlive())process.destroyForcibly();}
        int checked=0;
        for(var edge:TrafficRoutes.elements(TrafficRoutes.read(dir.resolve("speed.net.xml")),"edge")) {
            String id=edge.getAttribute("id");if(!id.matches("-?10[12345]"))continue;checked++;
            boolean pedestrian=false;
            for(var lane:TrafficRoutes.elements(edge,"lane")) {
                var allow=Arrays.asList(lane.getAttribute("allow").split(" "));var denied=Arrays.asList(lane.getAttribute("disallow").split(" "));
                pedestrian|=(lane.getAttribute("allow").isBlank() || allow.contains("pedestrian")) && !denied.contains("pedestrian");
            }
            assertEquals(id.endsWith("101"),pedestrian,"Only explicit 50 km/h secondary has a guessed sidewalk: "+id);
        }
        assertEquals(10,checked);
    }
    @Test void walkingIslandNeedsItsOwnComponentDespiteCarConnectivity()throws Exception {
        var config=new SynthesisProperties.Sumo();config.setMaxTrips(-1);assumeTrue(new SumoRunner(config).isAvailable(),"native SUMO missing");
        String osm=RoadLocationsTest.SERVICE_ISLAND_OSM.replace("v='service'","v='secondary'/><tag k='maxspeed' v='70'");
        var z=RoadLocationsTest.zone();z.maxLon=8.210;
        RoadLocations.Point from=new RoadLocations.Point(8.2003,48.85),to=new RoadLocations.Point(8.208,48.8525);
        for(boolean placed:List.of(false,true)) {
            Path output=dir.resolve(placed?"placed":"raw");
            if(placed)try(var engine=RoutingEngine.fromOsmXml(osm,true)) {
                var locations=engine.roadLocations(List.of(z));from=locations.locate(z,from.lon(),from.lat(),"home","1");to=locations.locate(z,to.lon(),to.lat(),"work","1");
            }
            List<TravelDemand.PersonTrip> trips=new ArrayList<>();int id=0;
            for(var mode:List.of(TravelDemand.Mode.CAR,TravelDemand.Mode.BIKE,TravelDemand.Mode.PEDESTRIAN))trips.add(new TravelDemand.PersonTrip(""+id++,"1",mode,"1","1",0,10,.4,"1","1",from.lon(),from.lat(),to.lon(),to.lat(),List.of(new TravelDemand.Leg(0,mode,"1",.4,"1","1"))));
            new SumoRunner(config).run(output,osm,new TravelDemand(trips,3),null,null,ignored->{});
            var mapper=new ObjectMapper();var routes=mapper.readTree(output.resolve("sumo/traffic-routing.json").toFile());var execution=mapper.readTree(output.resolve("sumo/traffic-execution.json").toFile());
            assertEquals(placed?0:1,routes.path("unrouted").asInt());
            assertEquals(2,execution.path("vehicles").path("completed").asInt());
            assertEquals(placed?1:0,execution.path("persons").path("completed").asInt());
            assertEquals(0,execution.path("nativeVehicleTeleports").asInt());
        }
    }
    @Test void graphSelectedLocationsCompleteCarBikeAndWalkInNativeSumo()throws Exception {
        var config=new SynthesisProperties.Sumo();config.setMaxTrips(-1);var runner=new SumoRunner(config);assumeTrue(runner.isAvailable(),"SUMO missing");
        var z=RoadLocationsTest.zone();z.maxLon=8.210;List<TravelDemand.PersonTrip> trips=new ArrayList<>();
        try(var engine=RoutingEngine.fromOsmXml(RoadLocationsTest.SERVICE_ISLAND_OSM,true)) {
            var locations=engine.roadLocations(List.of(z));var from=locations.locate(z,8.2003,48.8502,"home","1");var to=locations.locate(z,8.2085,48.852,"work","1");
            int id=0;for(var mode:List.of(TravelDemand.Mode.CAR,TravelDemand.Mode.BIKE,TravelDemand.Mode.PEDESTRIAN))trips.add(new TravelDemand.PersonTrip(""+id++,"1",mode,"1","1",0,10,.4,"1","1",from.lon(),from.lat(),to.lon(),to.lat(),List.of(new TravelDemand.Leg(0,mode,"1",.4,"1","1"))));
        }
        var result=runner.run(dir,RoadLocationsTest.SERVICE_ISLAND_OSM,new TravelDemand(trips,3),null,null,ignored->{});assertEquals(2,result.vehicles());
        var execution=new ObjectMapper().readTree(dir.resolve("sumo/traffic-execution.json").toFile());assertEquals(2,execution.path("vehicles").path("completed").asInt());assertEquals(1,execution.path("persons").path("completed").asInt());assertEquals(0,execution.path("nativeVehicleTeleports").asInt());
        var network=TrafficRoutes.read(dir.resolve("sumo/net.net.xml"));int serviceEdges=0;
        for(var edge:TrafficRoutes.elements(network,"edge"))if(edge.getAttribute("id").matches("-?12")) {
            serviceEdges++;
            for(var lane:TrafficRoutes.elements(edge,"lane")) {
                assertTrue(Arrays.asList(lane.getAttribute("allow").split(" ")).contains("delivery"));
                assertFalse(Arrays.asList(lane.getAttribute("allow").split(" ")).contains("passenger"),"Native SUMO prohibits passenger on public service roads");
            }
        }
        assertEquals(2,serviceEdges);
    }
}
