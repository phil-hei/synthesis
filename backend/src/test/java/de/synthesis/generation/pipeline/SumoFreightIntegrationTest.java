package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.TravelDemand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Full native chain, generated OSM fixture: no network access or local logiTopp output required. */
class SumoFreightIntegrationTest {
    @TempDir Path temp;
    static final String OSM = """
      <osm version="0.6">
      <node id="1" lat="48.85" lon="8.2"/><node id="2" lat="48.85" lon="8.202"/>
      <node id="3" lat="48.85" lon="8.204"/><node id="4" lat="48.852" lon="8.203"/>
      <node id="5" lat="48.85" lon="8.206"/><node id="6" lat="48.848" lon="8.203"/>
      <node id="7" lat="48.851" lon="8.2"/><node id="8" lat="48.851" lon="8.206"/>
      <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
      <way id="20"><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/><tag k="hgv" v="no"/></way>
      <way id="30"><nd ref="2"/><nd ref="4"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
      <way id="40"><nd ref="3"/><nd ref="5"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
      <way id="50"><nd ref="5"/><nd ref="6"/><nd ref="1"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
      <way id="60"><nd ref="1"/><nd ref="7"/><nd ref="8"/><nd ref="5"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
      <way id="70"><nd ref="1"/><nd ref="7"/><nd ref="8"/><nd ref="5"/><tag k="highway" v="footway"/></way>
      </osm>
      """;
    // Explicit footway: pedestrian coverage must not depend on netconvert's version-specific sidewalk guess.
    @Test void runsMixedTrafficWithHeavyVehiclesAndRouteCategories() throws Exception {
        var config=new SynthesisProperties.Sumo(); config.setDay(0); config.setMaxTrips(1);
        var scenario=FreightDemandGeneratorTest.scenario();
        scenario.freight.relations=List.of(
            new FreightDemandGenerator.Relation("solo","truck","through",8.2002,48.85,8.2058,48.85,2,0,0.1,"Test","2026-10-10","10","40"),
            new FreightDemandGenerator.Relation("semi","trailer","inbound",8.2002,48.85,8.2058,48.85,1,0,0.1,"Test","2026-10-10"));
        SumoRunner runner=new SumoRunner(config).useFreight(scenario);
        assumeTrue(runner.isAvailable(),"SUMO runtime missing");
        var leg=new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",.4,"1","2");
        var demand=new TravelDemand(List.of(
            new TravelDemand.PersonTrip("1","1",TravelDemand.Mode.CAR,"1","1",0,3,.4,"1","2",8.2002,48.85,8.2058,48.85,List.of(leg)),
            new TravelDemand.PersonTrip("2","1",TravelDemand.Mode.CAR,"1","1",1,4,.4,"1","2",8.2002,48.85,8.2058,48.85,List.of(leg)),
            new TravelDemand.PersonTrip("3","1",TravelDemand.Mode.PEDESTRIAN,"1","1",2,5,.4,"1","2",8.2002,48.85,8.2058,48.85,List.of(new TravelDemand.Leg(0,TravelDemand.Mode.PEDESTRIAN,"1",.4,"1","2")))),3);
        var result=runner.run(temp,OSM,demand,null,null,ignored->{});
        assertEquals(2,result.carTrips(),"Freight disables caps for personal demand");
        assertEquals(3,result.heavyFreightTrips());
        assertEquals(1,result.walkTrips());
        assertEquals(5,result.vehicles());
        assertTrue(result.co2KgByType().getOrDefault("heavyTruck",0.0)>0);
        assertTrue(result.co2KgByType().getOrDefault("articulatedTruck",0.0)>0);
        var mapper=new ObjectMapper();
        var routes=mapper.readTree(temp.resolve("sumo/traffic-routes.geojson").toFile());
        assertEquals(5,routes.path("features").size());
        var execution = mapper.readTree(temp.resolve("freight/execution.json").toFile());
        assertEquals(3,execution.path("completed").asInt());
        var mapping=mapper.readTree(temp.resolve("freight/edge-mapping.json").toFile()).path("relations");
        assertTrue(mapping.toString().contains("explicit-directed-edges"));
        assertTrue(Files.exists(temp.resolve("sumo/freight-network.geojson")));
        assertEquals(0,execution.path("notDeparted").size());
        assertEquals(0,execution.path("unfinishedAtDayEnd").size());
        assertEquals(3,mapper.readTree(temp.resolve("sumo/traffic-categories.json").toFile()).path("routedByCategory").path("logistics").asInt());
        boolean carUsesShortcut = false;
        for(var vehicle:TrafficRoutes.elements(TrafficRoutes.read(temp.resolve("sumo/routes.rou.xml")),"vehicle")) {
            var routeEdges = List.of(TrafficRoutes.elements(vehicle,"route").getFirst().getAttribute("edges").split("\\s+"));
            if(vehicle.getAttribute("id").startsWith("freight_"))
                assertFalse(routeEdges.contains("20"),"Heavy routes must avoid hgv=no shortcut");
            else if ("pkw".equals(vehicle.getAttribute("type"))) carUsesShortcut |= routeEdges.contains("20");
        }
        assertTrue(carUsesShortcut,"Permission transfer must leave the shortcut open to passenger cars");
    }
    @Test void runsSevasSperreThroughNativeMixedTraffic() throws Exception {
        var config=new SynthesisProperties.Sumo(); config.setDay(0); config.setMaxTrips(1);
        var scenario=FreightDemandGeneratorTest.scenario();
        scenario.freight.relations=List.of(
            new FreightDemandGenerator.Relation("solo","truck","through",7.4002,51.5,7.4058,51.5,2,0,0.1,"Test","2026-10-10","10","40"),
            new FreightDemandGenerator.Relation("semi","trailer","inbound",7.4002,51.5,7.4058,51.5,1,0,0.1,"Test","2026-10-10"));
        scenario.freight.sevasEnabled = true;
        scenario.freight.sevasSnapshot = SevasFreightPermissionsTest.snapshot();
        SumoRunner runner=new SumoRunner(config).useFreight(scenario);
        assumeTrue(runner.isAvailable(),"SUMO runtime missing");
        var leg=new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",.4,"1","2");
        var demand=new TravelDemand(List.of(
            new TravelDemand.PersonTrip("1","1",TravelDemand.Mode.CAR,"1","1",0,3,.4,"1","2",7.4002,51.5,7.4058,51.5,List.of(leg)),
            new TravelDemand.PersonTrip("2","1",TravelDemand.Mode.CAR,"1","1",1,4,.4,"1","2",7.4002,51.5,7.4058,51.5,List.of(leg)),
            new TravelDemand.PersonTrip("3","1",TravelDemand.Mode.PEDESTRIAN,"1","1",2,5,.4,"1","2",7.4002,51.5,7.4058,51.5,List.of(new TravelDemand.Leg(0,TravelDemand.Mode.PEDESTRIAN,"1",.4,"1","2")))),3);
        var result=runner.run(temp,OSM.replace("48.852", "51.502").replace("48.851", "51.501").replace("48.848", "51.498").replace("48.85", "51.5").replace("8.2", "7.4").replace("<tag k=\"hgv\" v=\"no\"/>", ""),demand,null,null,ignored->{});
        assertEquals(2,result.carTrips(),"Freight disables caps for personal demand");
        assertEquals(3,result.heavyFreightTrips());
        assertEquals(1,result.walkTrips());
        assertEquals(5,result.vehicles());
        assertTrue(result.co2KgByType().getOrDefault("heavyTruck",0.0)>0);
        assertTrue(result.co2KgByType().getOrDefault("articulatedTruck",0.0)>0);
        var mapper=new ObjectMapper();
        var routes=mapper.readTree(temp.resolve("sumo/traffic-routes.geojson").toFile());
        assertEquals(5,routes.path("features").size());
        var execution = mapper.readTree(temp.resolve("freight/execution.json").toFile());
        assertEquals(3,execution.path("completed").asInt());
        var mapping=mapper.readTree(temp.resolve("freight/edge-mapping.json").toFile()).path("relations");
        assertTrue(mapping.toString().contains("explicit-directed-edges"));
        assertTrue(Files.exists(temp.resolve("sumo/freight-network.geojson")));
        assertEquals(0,execution.path("notDeparted").size());
        assertEquals(0,execution.path("unfinishedAtDayEnd").size());
        assertEquals(3,mapper.readTree(temp.resolve("sumo/traffic-categories.json").toFile()).path("routedByCategory").path("logistics").asInt());
        boolean carUsesShortcut = false;
        for(var vehicle:TrafficRoutes.elements(TrafficRoutes.read(temp.resolve("sumo/routes.rou.xml")),"vehicle")) {
            var routeEdges = List.of(TrafficRoutes.elements(vehicle,"route").getFirst().getAttribute("edges").split("\\s+"));
            if(vehicle.getAttribute("id").startsWith("freight_"))
                assertFalse(routeEdges.contains("20"),"Heavy routes must avoid hgv=no shortcut");
            else if ("pkw".equals(vehicle.getAttribute("type"))) carUsesShortcut |= routeEdges.contains("20");
        }
        assertEquals(1,mapper.readTree(temp.resolve("sumo/sevas-permissions.json").toFile()).path("counts").path("applied").asInt());
        assertTrue(carUsesShortcut,"Permission transfer must leave the shortcut open to passenger cars");
    }
    @Test void runsOptionalStandardProfilesWithMassDetourAndReviewReport() throws Exception {
        var config=new SynthesisProperties.Sumo();config.setDay(0);config.setMaxTrips(1);
        var scenario=FreightDemandGeneratorTest.scenario();scenario.freight.profileChecksEnabled=true;
        scenario.freight.relations=List.of(
            new FreightDemandGenerator.Relation("solo","truck","through",8.2002,48.85,8.2058,48.85,1,0,.1,"Native reference","2026-10-15","10","40"),
            new FreightDemandGenerator.Relation("semi","trailer","through",8.2002,48.85,8.2058,48.85,1,0,.1,"Native reference","2026-10-15","10","40"));
        var runner=new SumoRunner(config).useFreight(scenario);assumeTrue(runner.isAvailable(),"SUMO runtime missing");
        String source=OSM.replace("<tag k=\"hgv\" v=\"no\"/>","<tag k=\"maxweight\" v=\"30\"/>")
                .replace("<way id=\"30\">","<way id=\"30\"><tag k=\"hgv\" v=\"delivery\"/>");
        var leg=new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",.4,"1","2");
        var demand=new TravelDemand(List.of(
            new TravelDemand.PersonTrip("1","1",TravelDemand.Mode.CAR,"1","1",0,3,.4,"1","2",8.2002,48.85,8.2058,48.85,List.of(leg)),
            new TravelDemand.PersonTrip("2","1",TravelDemand.Mode.CAR,"1","1",1,4,.4,"1","2",8.2002,48.85,8.2058,48.85,List.of(leg))),2);
        var result=runner.run(temp,source,demand,null,null,ignored->{});
        assertEquals(2,result.carTrips());assertEquals(2,result.heavyFreightTrips());
        var mapper=new ObjectMapper();var report=mapper.readTree(temp.resolve("freight/route-checks.json").toFile());
        assertEquals(2,report.path("requested").asInt());assertFalse(report.path("accessCertified").asBoolean());
        assertEquals(1,report.path("routeStatuses").path("review-required").asInt());
        assertEquals(1,report.path("routeStatuses").path("no-explicit-violation-found").asInt());
        for (var vehicle:TrafficRoutes.elements(TrafficRoutes.read(temp.resolve("sumo/routes.rou.xml")),"vehicle")) {
            if(!vehicle.getAttribute("id").startsWith("freight_"))continue;
            var path=List.of(TrafficRoutes.elements(vehicle,"route").getFirst().getAttribute("edges").split("\\s+"));
            if (vehicle.getAttribute("type").equals("heavyTruck")) assertTrue(path.contains("20"));
            else { assertFalse(path.contains("20"));assertTrue(path.contains("30")); }
        }
        int heavyTypes=0;
        for(var type:TrafficRoutes.elements(TrafficRoutes.read(temp.resolve("sumo/routes.rou.xml")),"vType")) {
            if(!Set.of("heavyTruck","articulatedTruck").contains(type.getAttribute("id")))continue;
            heavyTypes++;
            assertEquals(4,Double.parseDouble(type.getAttribute("height")));
            assertEquals(2.55,Double.parseDouble(type.getAttribute("width")));
            assertEquals(type.getAttribute("id").equals("heavyTruck")?26000:40000,Double.parseDouble(type.getAttribute("mass")));
        }
        assertEquals(2,heavyTypes);
        String referenceDir=System.getProperty("synthesis.freight.referenceDir");
        if(referenceDir!=null) {
            Path dest=Path.of(referenceDir);Files.createDirectories(dest);
            for(String name:List.of("freight/route-checks.json","freight/profile-permissions.json","freight/execution.json","sumo/freight-trips.xml","sumo/routes.rou.xml","sumo/network.osm","sumo/sumo.log")) {
                Path target=dest.resolve(name);Files.createDirectories(target.getParent());Files.copy(temp.resolve(name),target,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    @Test void reportsUnroutableExplicitEndpointsAndCleansDisabledChecks() throws Exception {
        var config=new SynthesisProperties.Sumo();config.setDay(0);
        var scenario=FreightDemandGeneratorTest.scenario();scenario.freight.profileChecksEnabled=true;
        scenario.freight.relations=List.of(new FreightDemandGenerator.Relation("closed","truck","through",8.2002,48.85,8.2058,48.85,1,0,.1,"Native reference","2026-10-15","10","40"));
        var runner=new SumoRunner(config).useFreight(scenario);assumeTrue(runner.isAvailable(),"SUMO runtime missing");
        String source=OSM.replace("<tag k=\"hgv\" v=\"no\"/>","<tag k=\"maxheight\" v=\"3.8\"/>")
                .replace("<way id=\"30\">","<way id=\"30\"><tag k=\"maxheight\" v=\"3.8\"/>");
        // Longer edge 60 must also be blocked, otherwise it provides a valid alternate route.
        source=source.replace("<way id=\"60\">","<way id=\"60\"><tag k=\"maxheight\" v=\"3.8\"/>");
        var demand=new TravelDemand(List.of(),0);final String osm=source;
        assertThrows(java.io.IOException.class,()->runner.run(temp,osm,demand,null,null,ignored->{}));
        var report=new ObjectMapper().readTree(temp.resolve("sumo/network-coverage.json").toFile());
        assertEquals(1,report.path("disconnectedRequests").asInt());
        assertFalse(Files.exists(temp.resolve("sumo/routes.rou.xml")),"proven disconnected freight must fail before native routing");
        assertFalse(Files.exists(temp.resolve("freight/route-checks.json")),"native audit has not been run");
        Files.writeString(temp.resolve("freight/route-checks.json"),"stale audit from previous run");
        scenario.freight.profileChecksEnabled=false;
        new SumoRunner(config).useFreight(scenario).run(temp,source,demand,null,null,ignored->{});
        assertFalse(Files.exists(temp.resolve("freight/route-checks.json")));
        assertFalse(Files.exists(temp.resolve("freight/profile-permissions.json")));
    }
    @Test void fullDemandPreflightWritesAllFailuresAndRemovesStaleSuccessArtifacts() throws Exception {
        var config=new SynthesisProperties.Sumo();var scenario=FreightDemandGeneratorTest.scenario();
        var runner=new SumoRunner(config).useFreight(scenario);assumeTrue(runner.isAvailable(),"SUMO runtime missing");
        Path dir=temp.resolve("sumo");Files.createDirectories(dir);
        for(String name:List.of("summary.json","routes.rou.xml","tripinfo.xml","persons.xml"))Files.writeString(dir.resolve(name),"stale");
        var leg=new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",1,"1","2");
        var demand=new TravelDemand(List.of(new TravelDemand.PersonTrip("bad","1",TravelDemand.Mode.CAR,"1","1",0,3,1,"1","2",9.2,49,8.2058,48.85,List.of(leg))),1);
        var error=assertThrows(java.io.IOException.class,()->runner.run(temp,OSM,demand,null,null,ignored->{}));
        assertTrue(error.getMessage().contains("Straßennetz-Vorprüfung"));
        var report=new ObjectMapper().readTree(dir.resolve("network-coverage.json").toFile());
        assertEquals(1,report.path("unmappedEndpoints").asInt());
        assertEquals(13,report.path("requests").size(),"one personal and twelve freight requests must be visible");
        for(String name:List.of("summary.json","routes.rou.xml","tripinfo.xml","persons.xml"))assertFalse(Files.exists(dir.resolve(name)),name);
    }

    @Test void rejectsMismatchedSimulationDay() {
        var config=new SynthesisProperties.Sumo(); config.setDay(1);
        assertThrows(IllegalArgumentException.class,()->new SumoRunner(config).useFreight(FreightDemandGeneratorTest.scenario()));
    }
}
