package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.TravelDemand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RoadSignalIntegrationTest {
    @TempDir Path dir;
    private static final String SOURCE="<osm version='0.6'><node id='1' lon='7.55' lat='51.52'/><node id='2' lon='7.551' lat='51.52'>%s</node><node id='3' lon='7.552' lat='51.52'/><node id='4' lon='7.55' lat='51.519'/><node id='5' lon='7.552' lat='51.521'/><node id='6' lon='7.551' lat='51.521'/><way id='12'><nd ref='1'/><nd ref='4'/><tag k='highway' v='residential'/></way><way id='13'><nd ref='3'/><nd ref='5'/><tag k='highway' v='residential'/></way><way id='14'><nd ref='2'/><nd ref='6'/><tag k='highway' v='residential'/></way><way id='11'><nd ref='1'/><nd ref='2'/><nd ref='3'/><tag k='highway' v='secondary'/><tag k='railway' v='tram'/><tag k='maxspeed' v='50'/><tag k='oneway' v='yes'/></way></osm>";
    private static final String SIGNAL="<tag k='railway' v='signal'/><tag k='railway:signal:main' v='DE-BOStrab:h0'/><tag k='railway:signal:direction' v='forward'/>";
    @Test void selectedLateCarFinishesAfterMidnight()throws Exception {
        bins();
        var trip=new TravelDemand.PersonTrip("late","1",TravelDemand.Mode.CAR,"1","1",
                1439+59./60,1450,.2,"1","1",7.5501,51.52,7.5519,51.52,
                List.of(new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",.2,"1","1")));
        var config=new SynthesisProperties.Sumo();config.setDay(0);config.setMaxTrips(-1);
        new SumoRunner(config,de.synthesis.vehicle.VehicleConfiguration.empty(),777).run(dir,SOURCE.formatted(""),new TravelDemand(List.of(trip),1),null,null,ignored->{});
        assertTrue(Files.readString(dir.resolve("sumo/statistics.xml")).contains("<seed value=\"777\""),
                "Actual native configuration must use the requested seed even without physical chains");
        var audit=new ObjectMapper().readTree(dir.resolve("sumo/traffic-execution.json").toFile());
        assertEquals(1,audit.path("vehicles").path("completed").asInt());
        assertEquals(0,audit.path("vehicles").path("unfinished").asInt());
        XmlRecords.read(dir.resolve("sumo/tripinfo.xml"),Set.of("tripinfo"),e->
                assertTrue(Double.parseDouble(e.getAttribute("arrival"))>86400));
    }
    @Test void signalImportEliminatesFalseBlockButRetainsRoadLightsAndEdgeIds()throws Exception {
        Path bins=bins();List<TravelDemand.PersonTrip> trips=new ArrayList<>();int id=0;
        for(var mode:List.of(TravelDemand.Mode.BIKE,TravelDemand.Mode.CAR,TravelDemand.Mode.PEDESTRIAN))trips.add(new TravelDemand.PersonTrip(""+id++,"1",mode,"1","1",0,10,.1,"1","1",7.5501,51.52,7.5519,51.52,List.of(new TravelDemand.Leg(0,mode,"1",.1,"1","1"))));
        String road="<tag k='highway' v='traffic_signals'/>";
        for(String variant:List.of("signal","dual","road")) {
            String osm=SOURCE.formatted(variant.equals("signal")?SIGNAL:variant.equals("dual")?SIGNAL+road:road);
            for(boolean opaque:List.of(true,false)) {
                Path out=dir.resolve(variant+(opaque?"-opaque":"-road"));
                var config=new SynthesisProperties.Sumo();config.setMaxTrips(-1);
                SumoRunner.PtProvider provider=opaque?net->null:SumoRunner.PtProvider.roadOnly(net->null);
                new SumoRunner(config).run(out,osm,new TravelDemand(trips,3),null,provider,ignored->{});
                var mapper=new ObjectMapper();var audit=mapper.readTree(out.resolve("sumo/traffic-execution.json").toFile());
                assertEquals(opaque && !variant.equals("road")?2:0,audit.path("nativeVehicleTeleports").asInt());
                assertEquals(2,audit.path("vehicles").path("completed").asInt());assertEquals(1,audit.path("persons").path("completed").asInt());
                var network=TrafficRoutes.read(out.resolve("sumo/net.net.xml"));var node=TrafficRoutes.elements(network,"junction").stream().filter(j->j.getAttribute("id").equals("2")).findFirst().orElseThrow();
                assertEquals(variant.equals("road") || !opaque && variant.equals("dual")?"traffic_light":opaque?"rail_signal":"priority",node.getAttribute("type"));
                var edges=TrafficRoutes.elements(network,"edge").stream().filter(e->!e.getAttribute("id").startsWith(":")).map(e->e.getAttribute("id")).collect(java.util.stream.Collectors.toSet());assertTrue(edges.containsAll(Set.of("11#0","11#1")),edges.toString());
            }
        }
    }
    @Test void levelCrossingRetainsItsControlType()throws Exception {
        Path bins=bins();String source="<osm version='0.6'><node id='1' lon='7.55' lat='51.52'/><node id='2' lon='7.551' lat='51.52'><tag k='railway' v='level_crossing'/><tag k='crossing:barrier' v='yes'/></node><node id='3' lon='7.552' lat='51.52'/><node id='4' lon='7.551' lat='51.519'/><node id='5' lon='7.551' lat='51.521'/><way id='11'><nd ref='1'/><nd ref='2'/><nd ref='3'/><tag k='highway' v='residential'/></way><way id='12'><nd ref='4'/><nd ref='2'/><nd ref='5'/><tag k='railway' v='rail'/></way></osm>";
        Files.writeString(dir.resolve("crossing.osm"),source);
        for(boolean discard:List.of(false,true)) {
            Path output=dir.resolve(discard?"fixed.xml":"original.xml");var args=new ArrayList<>(List.of(bins.resolve("netconvert").toString(),"--osm-files","crossing.osm","-o",output.toString()));if(discard)args.add("--railway.signals.discard");
            run(args);var node=TrafficRoutes.elements(TrafficRoutes.read(output),"junction").stream().filter(j->j.getAttribute("id").equals("2")).findFirst().orElseThrow();assertEquals("rail_crossing",node.getAttribute("type"));
        }
    }
    private Path bins(){var c=new SynthesisProperties.Sumo();assumeTrue(new SumoRunner(c).isAvailable(),"native SUMO missing");return Path.of(c.getBinDir());}
    private void run(List<String> command)throws Exception {
        var p=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("native.log").toFile()).start();
        try{assertTrue(p.waitFor(30,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),()->{try{return Files.readString(dir.resolve("native.log"));}catch(Exception e){return e.toString();}});}finally{if(p.isAlive())p.destroyForcibly();}
    }
}
