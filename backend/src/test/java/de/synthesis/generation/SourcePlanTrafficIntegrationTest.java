package de.synthesis.generation;

import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.SumoRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual source resolver -> common demand -> native SUMO, independent company contribution retained. */
class SourcePlanTrafficIntegrationTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void selectedRevisionAndHeavyExclusionReachNativeRoutingAndCompletion(boolean north)throws Exception {
        assumeTrue(new SumoRunner(new SynthesisProperties.Sumo()).isAvailable(),"Native SUMO runtime required for source-plan acceptance");var cases=new ArrayList<Map<String,Object>>();
        for(long seed:List.of(7L,42L,777L))try(var f=new SourcePlanTrafficFixture(directory.resolve("seed-"+seed),north,seed,true)){
            for(var revision:List.of(f.a,f.b)){
                if(revision.id().equals(f.a.id()))f.selectProfile();else f.select("dataset",revision.id());var job=f.run();assertEquals("DONE",job.stage("SUMO (Verkehr)").status,()->String.join(" | ",job.log));assertEquals("SKIPPED",job.stage("Simulation (logiTopp)").status);int heavy=revision.id().equals(f.a.id())?8:9;
                var execution=f.json.readTree(f.out().resolve("sumo/traffic-execution.json").toFile());assertEquals("complete",execution.path("status").asText(),execution.toString());assertTrue(execution.path("routingVerified").asBoolean());assertTrue(execution.path("demandRetained").asBoolean());assertEquals(heavy+3,execution.path("vehicles").path("requested").asInt());assertEquals(heavy+3,execution.path("vehicles").path("completed").asInt());assertEquals(0,execution.path("nativeVehicleTeleports").asInt());
                var freight=f.json.readTree(f.out().resolve("freight/execution.json").toFile());assertEquals(heavy,freight.path("completed").asInt());assertEquals(3,f.demand().companies().legs().size());
                var sourceRun=f.json.readTree(f.out().resolve("sources/resolution.json").toFile());assertEquals("DONE",sourceRun.at("/run/state").asText());assertEquals(execution.path("vehicles"),sourceRun.at("/targets/sumo/execution/vehicles"));assertEquals("complete",sourceRun.at("/targets/sumo/execution/status").asText());assertFalse(sourceRun.at("/targets/sumo/execution").has("requests"));assertEquals(de.synthesis.data.importing.ImportSupport.hash(Files.readAllBytes(f.out().resolve("sumo/traffic-execution.json"))),sourceRun.at("/targets/sumo/executionSha256").asText());
                var record=new TreeMap<String,Object>();record.putAll(Map.of("seed",seed,"revision",revision.id(),"generatedHeavyTrips",heavy,"requestedAndCompletedMovements",heavy+3,"executionStatus","complete","teleports",0,"privateDemandStatus",f.demand().personDemandStatus()));
                record.put("execution",execution);var hashes=new TreeMap<String,String>();for(var file:List.of("sumo/traffic-routing.json","sumo/traffic-execution.json","freight/execution.json","company-tours/input.json"))hashes.put(file,de.synthesis.data.importing.ImportSupport.hash(Files.readAllBytes(f.out().resolve(file))));record.put("artifactHashes",hashes);cases.add(record);
            }
            f.select("excluded",null);var job=f.run();assertEquals("DONE",job.stage("SUMO (Verkehr)").status,()->String.join(" | ",job.log));var execution=f.json.readTree(f.out().resolve("sumo/traffic-execution.json").toFile());assertEquals("complete",execution.path("status").asText());assertEquals(3,execution.path("vehicles").path("completed").asInt());assertTrue(f.demand().freight().isEmpty());assertEquals(3,f.demand().companies().legs().size());
            cases.add(Map.of("seed",seed,"source","excluded","generatedHeavyTrips",0,"requestedAndCompletedMovements",3,"execution",execution));
        }
        Path proof=Path.of("target/source-plan-traffic/native-"+(north?"north":"south")+".json");Files.createDirectories(proof.getParent());new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(proof.toFile(),Map.of("region",north?"translated-north":"south","nativeRuntime",true,"cases",cases,"heavyExclusionPreservesCompanyLegs",3));
    }
}
