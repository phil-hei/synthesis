package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.GenerationService;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadLocationsPipelineTest {
    @TempDir Path dir;
    @Test void pipelineUsesRoadLocationsAndRemovesStaleReportWhenDisabled()throws Exception {
        var properties=new SynthesisProperties();properties.setDataDir(dir.toString());properties.getSimulation().setEnabled(false);properties.getCensus().setEnabled(false);properties.getOverpass().setLiveDataEnabled(false);
        var mapper=new ObjectMapper().findAndRegisterModules();var repository=new ScenarioRepository(mapper,properties);
        var scenario=new Scenario();scenario.id="road-pipeline";scenario.targetModels=new ArrayList<>(List.of("logitopp"));
        scenario.population.totalPersons=100;scenario.population.externalTrips=false;scenario.population.behaviorSource="template";scenario.population.useRealRouting=true;scenario.population.roadLocatedActivities=true;
        scenario.zoning.mode="grid";scenario.zoning.cellSizeMeters=2000;
        scenario.region=new Scenario.Region();scenario.region.minLon=8.199;scenario.region.maxLon=8.207;scenario.region.minLat=48.849;scenario.region.maxLat=48.855;repository.save(scenario);
        Files.createDirectories(repository.uploadsDir(scenario.id));
        String source="<osm version='0.6'><node id='1' lat='48.85' lon='8.2'/><node id='2' lat='48.85' lon='8.206'/><node id='3' lat='48.854' lon='8.206'/><node id='4' lat='48.854' lon='8.2'/><node id='90' lat='48.848' lon='8.198'/><node id='91' lat='48.856' lon='8.208'/><way id='11'><nd ref='1'/><nd ref='2'/><nd ref='3'/><nd ref='4'/><nd ref='1'/><tag k='highway' v='residential'/></way><way id='90'><nd ref='1'/><nd ref='90'/><nd ref='91'/><nd ref='3'/><tag k='highway' v='residential'/></way></osm>";
        Files.writeString(repository.uploadsDir(scenario.id).resolve(RoadNetworkFile.NAME),source);
        try(var service=new ServiceScope(new GenerationService(repository,new OverpassClient(properties,mapper),properties,mapper))) {
            var first=service.service.start(scenario.id);await(first);
            assertEquals(de.synthesis.generation.GenerationJob.State.DONE,first.state,()->String.join(" | ",first.log));
            Path artifacts=repository.artifactsDir(scenario.id);var report=mapper.readTree(artifacts.resolve(RoadLocations.FILE).toFile());
            assertEquals(0,report.path("failedLocations").asInt());assertTrue(report.path("countsByKind").path("household-home").asInt()>0);assertTrue(report.path("countsByKind").has("opportunity:Work"));
            byte[] persons=Files.readAllBytes(artifacts.resolve("population/person.csv"));
            scenario.population.roadLocatedActivities=false;repository.save(scenario);var second=service.service.start(scenario.id);await(second);
            assertEquals(de.synthesis.generation.GenerationJob.State.DONE,second.state);assertFalse(Files.exists(artifacts.resolve(RoadLocations.FILE)));
            assertArrayEquals(persons,Files.readAllBytes(artifacts.resolve("population/person.csv")));
        }
    }
    private static void await(de.synthesis.generation.GenerationJob job)throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        while(job.finishedAt==null){assertTrue(System.nanoTime()<deadline,"pipeline timeout");Thread.sleep(25);}
    }
    private record ServiceScope(GenerationService service)implements AutoCloseable {public void close(){service.shutdown();}}
}
