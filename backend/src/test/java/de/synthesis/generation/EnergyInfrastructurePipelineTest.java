package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnergyInfrastructurePipelineTest {
    @TempDir Path dir;
    @Test void completePipelineExportsSourcesWithoutChangingPopulationAndClearsWhenDisabled()throws Exception {
        var properties=new SynthesisProperties();properties.setDataDir(dir.toString());properties.getSimulation().setEnabled(false);properties.getCensus().setEnabled(false);properties.getOverpass().setLiveDataEnabled(false);
        var mapper=new ObjectMapper().findAndRegisterModules();var repo=new ScenarioRepository(mapper,properties);
        var s=new Scenario();s.id="energy-pipeline";s.targetModels=new ArrayList<>(List.of("logitopp"));s.population.behaviorSource="template";s.population.useRealRouting=false;s.population.externalTrips=false;s.population.totalPersons=100;s.population.seed=42;s.zoning.mode="grid";s.zoning.cellSizeMeters=1000;
        s.region=new Scenario.Region();s.region.minLon=7.45;s.region.maxLon=7.47;s.region.minLat=51.50;s.region.maxLat=51.52;s.energyInfrastructure.enabled=true;s.energyInfrastructure.useBnetza=false;repo.save(s);
        var raw=mapper.readTree("{\"elements\":[{\"type\":\"node\",\"id\":123,\"lon\":7.46,\"lat\":51.51,\"tags\":{\"amenity\":\"fuel\",\"access\":\"yes\",\"fuel:cng\":\"yes\"}}]}");
        EnergyInfrastructure.writeSnapshot(repo.uploadsDir(s.id).resolve(EnergyInfrastructure.SNAPSHOT),EnergyInfrastructure.snapshot(raw,s.region,"fixture"));
        var generation=new GenerationService(repo,new OverpassClient(properties,mapper),properties,mapper);
        try {
            var first=generation.start(s.id);await(first);assertEquals(GenerationJob.State.DONE,first.state,()->String.join(" | ",first.log));
            assertEquals("DONE",first.stage("OSM-Daten (POIs)").status);
            Path out=repo.artifactsDir(s.id);var manifest=mapper.readTree(out.resolve("manifest.json").toFile());assertEquals(EnergyInfrastructure.CONTRACT,manifest.path("energyInfrastructure").path("contract").asText());assertTrue(Files.exists(out.resolve("energy/osm-source.json")));
            assertEquals(1,mapper.readTree(out.resolve("energy/stations.geojson").toFile()).path("features").size());byte[] persons=Files.readAllBytes(out.resolve("population/person.csv"));byte[] activities=Files.readAllBytes(out.resolve("population/activity.csv"));
            Files.createDirectories(out.resolve("vehicles")); Files.writeString(out.resolve("vehicles/energy-ledger.json"), "old"); Files.writeString(out.resolve("energy/network-access.json"), "old");
            Files.writeString(out.resolve("vehicles/native-chains.json"), "old"); Files.createDirectories(out.resolve("sumo")); Files.writeString(out.resolve("sumo/simulation.rou.xml"), "old");
            s.energyInfrastructure.enabled=false;repo.save(s);var second=generation.start(s.id);await(second);assertEquals(GenerationJob.State.DONE,second.state);
            assertArrayEquals(persons,Files.readAllBytes(out.resolve("population/person.csv")));assertArrayEquals(activities,Files.readAllBytes(out.resolve("population/activity.csv")));assertFalse(Files.exists(out.resolve("energy/quality.json")));assertFalse(Files.exists(out.resolve("energy/network-access.json")));assertFalse(Files.exists(out.resolve("vehicles/energy-ledger.json")));assertFalse(mapper.readTree(out.resolve("manifest.json").toFile()).has("energyInfrastructure"));
            assertFalse(Files.exists(out.resolve("vehicles/native-chains.json"))); assertFalse(Files.exists(out.resolve("sumo/simulation.rou.xml")));
            s.energyInfrastructure.enabled=true;repo.save(s);Files.delete(repo.uploadsDir(s.id).resolve(EnergyInfrastructure.SNAPSHOT));var failed=generation.start(s.id);await(failed);
            assertEquals(GenerationJob.State.FAILED,failed.state);assertEquals("FAILED",failed.stage("OSM-Daten (POIs)").status);assertEquals("failed",mapper.readTree(out.resolve("energy/quality.json").toFile()).path("status").asText());
        }finally{generation.shutdown();}
    }
    static void await(GenerationJob job)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(job.finishedAt==null){assertTrue(System.nanoTime()<deadline,"pipeline timeout");Thread.sleep(25);}
    }
}
