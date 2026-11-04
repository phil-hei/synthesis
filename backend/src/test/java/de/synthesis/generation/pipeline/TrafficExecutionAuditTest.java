package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficExecutionAuditTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    Map<String,TrafficRoutes.Category> categories(){return Map.of(
            "a",new TrafficRoutes.Category("private","person-demand","car",""),
            "b",new TrafficRoutes.Category("private","person-demand","bike",""),
            "c",new TrafficRoutes.Category("logistics","logitopp-parcels","van",""),
            "d",new TrafficRoutes.Category("logistics","heavy-freight","truck",""));}
    void inputs() throws Exception {
        Files.writeString(dir.resolve("trips.xml"),"<routes><vType id='car' vClass='passenger'/><vType id='bike' vClass='bicycle'/><vType id='van' vClass='delivery'/><trip id='a' type='car' from='x' to='y'/><trip id='b' type='bike'/><trip id='c' type='van'/></routes>");
        Files.writeString(dir.resolve("freight-trips.xml"),"<routes><vType id='truck' vClass='truck'/><trip id='d' type='truck'/></routes>");
        Files.writeString(dir.resolve("persons.xml"),"<routes><person id='p'><personTrip from='x' to='y'/></person><person id='q'><personTrip/></person></routes>");
    }
    @Test void reportsEverySourceAndAlsoCoordinateWarningsOnRetainedRoutes() throws Exception {
        inputs();Files.writeString(dir.resolve("routes.rou.xml"),"<routes><vehicle id='a'/><vehicle id='d'/><person id='q'/></routes>");
        Files.writeString(dir.resolve("sumo.log"),"Warning: No connection between edge 'x' and edge 'y' found.\nWarning: Mandatory edge 'y' not reachable by vehicle 'b'.\nWarning: No edge found near position 1,2 within the route for trip 'c'.\nWarning: No edge found near position 1,2 within the route for trip 'a'.\nWarning: No route for trip in person 'p'.\n");
        assertThrows(IOException.class,()->TrafficExecutionAudit.routing(dir,categories(),true,null));
        var x=json.readTree(dir.resolve("traffic-routing.json").toFile());
        assertEquals(6,x.path("requested").asInt());assertEquals(3,x.path("unrouted").asInt());assertEquals(1,x.path("retainedRoutesWithCoordinateWarnings").asInt());
        assertEquals(1,x.path("reasons").path("person-demand/bicycle/no-directed-connection").asInt());
        assertEquals(1,x.path("reasons").path("logitopp-parcels/delivery/coordinate-unmatched").asInt());
        assertEquals(1,x.path("reasons").path("person-demand/pedestrian/native-no-route").asInt());
    }
    @Test void abortedNativeRoutingCannotBeCountedAsExhaustiveLoss() throws Exception {
        inputs();Files.writeString(dir.resolve("routes.rou.xml"),"<routes><vehicle id='a'/></routes>");
        TrafficExecutionAudit.routing(dir,categories(),false,"router exit 1");
        var x=json.readTree(dir.resolve("traffic-routing.json").toFile());
        assertFalse(x.path("nativeCompleted").asBoolean());assertTrue(x.path("unrouted").isNull());assertTrue(x.path("routed").isNull());
        assertEquals("failed",x.path("status").asText());
    }
    @Test void executionSeparatesDayEndDepartureDropsVaporizationAndTeleportCoverage() throws Exception {
        inputs();Files.writeString(dir.resolve("routes.rou.xml"),"<routes><vehicle id='a'/><vehicle id='b'/><vehicle id='c'/><vehicle id='d'/><person id='p'/><person id='q'/></routes>");
        Files.writeString(dir.resolve("tripinfo.xml"),"<tripinfos><tripinfo id='a' depart='0' arrival='4'/><tripinfo id='b' depart='0' arrival='-1'/><tripinfo id='c' depart='-1' arrival='-1'/><tripinfo id='d' depart='0' arrival='3' vaporized='collision'/><personinfo id='p' depart='85740'><walk arrival='-1'/></personinfo><personinfo id='q' depart='0'><walk arrival='0'/></personinfo></tripinfos>");
        Files.writeString(dir.resolve("statistics.xml"),"<statistics><teleports total='3'/></statistics>");
        Files.writeString(dir.resolve("sumo.log"),"Warning: Teleporting vehicle 'b'; waited too long.\n");
        assertThrows(IOException.class,()->TrafficExecutionAudit.execution(dir,categories(),true));
        var x=json.readTree(dir.resolve("traffic-execution.json").toFile());
        assertEquals(1,x.path("vehicles").path("completed").asInt());assertEquals(1,x.path("vehicles").path("unfinished").asInt());
        assertEquals(1,x.path("vehicles").path("notDeparted").asInt());assertEquals(1,x.path("vehicles").path("vaporized").asInt());
        assertEquals(1,x.path("persons").path("completed").asInt());assertEquals(1,x.path("persons").path("unfinished").asInt());
        assertFalse(x.path("teleportAccountingComplete").asBoolean());
        assertTrue(x.path("requests").get(3).path("teleportEvents").isNull(),"unseen heavy teleport count must remain unknown when logs are suppressed");
    }
    @Test void statisticsVehicleTotalIncludesBikesWithLegacyFallback() throws Exception {
        var runner=new SumoRunner(new de.synthesis.SynthesisProperties.Sumo());
        Path stats=dir.resolve("statistics.xml");
        Files.writeString(stats,"<statistics><vehicles inserted=\"3316\"/><vehicleTripStatistics count=\"3255\"/><bikeTripStatistics count=\"61\"/></statistics>");
        assertEquals(3316,runner.parseStatistics(stats,0,0,0,0,0,0,0,Map.of()).vehicles());
        Files.writeString(stats,"<statistics><vehicleTripStatistics count=\"3255\"/><bikeTripStatistics count=\"61\"/></statistics>");
        assertEquals(3316,runner.parseStatistics(stats,0,0,0,0,0,0,0,Map.of()).vehicles());
        Files.writeString(stats,"<statistics><bikeTripStatistics count='61'/></statistics>");
        assertEquals(61,runner.parseStatistics(stats,0,0,0,0,0,0,0,Map.of()).vehicles());
    }
}
