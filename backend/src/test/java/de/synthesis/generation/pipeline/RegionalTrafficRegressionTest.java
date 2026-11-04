package de.synthesis.generation.pipeline;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Replays recorded native results without rerunning a multi-GB network in the default unit suite. */
class RegionalTrafficRegressionTest {
 @TempDir Path dir;
 @Test void auditsFrozenRegionalNativeIdsInsteadOfTrustingOnlyVehicleTripStatistics()throws Exception{
  Path recorded=Path.of(System.getenv().getOrDefault("SYNTHESIS_REGIONAL_TRAFFIC_RESULTS","../.runtime/freight-e2e-2026-10-16-coverage/data/scenarios/regional-freight-profiles/artifacts/sumo"));
  assumeTrue(Files.exists(recorded.resolve("tripinfo.xml")),"frozen regional native results missing; set SYNTHESIS_REGIONAL_TRAFFIC_RESULTS");
  var json=new ObjectMapper();var categories=json.convertValue(json.readTree(recorded.resolve("traffic-categories.json").toFile()).path("vehicles"),new TypeReference<Map<String,TrafficRoutes.Category>>(){});
  for(String name:List.of("trips.xml","freight-trips.xml","persons.xml","routes.rou.xml","tripinfo.xml","statistics.xml","sumo.log"))Files.createLink(dir.resolve(name),recorded.resolve(name).toAbsolutePath());
  TrafficExecutionAudit.routing(dir,categories,false,null);TrafficExecutionAudit.execution(dir,categories,false);
  var routing=json.readTree(dir.resolve("traffic-routing.json").toFile());var execution=json.readTree(dir.resolve("traffic-execution.json").toFile());
  assertEquals(316,routing.path("unrouted").asInt());
  assertEquals(3316,execution.path("vehicles").path("completed").asInt());assertEquals(206,execution.path("persons").path("completed").asInt());
  assertEquals(1,execution.path("persons").path("unfinished").asInt());assertFalse(execution.path("demandRetained").asBoolean());
  int heavy=0;for(var r:execution.path("requests"))if(r.path("request").path("source").asText().equals("heavy-freight")){
   heavy++;assertEquals("completed",r.path("status").asText());assertEquals(0,r.path("teleportEvents").asInt(-1));
  }
  assertEquals(2336,heavy);assertTrue(execution.path("teleportAccountingComplete").asBoolean());
  assertThrows(java.io.IOException.class,()->TrafficExecutionAudit.requireRoutingComplete(dir,true));
 }
}
