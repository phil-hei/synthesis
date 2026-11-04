package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.EnergyInfrastructure;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnergyInfrastructureControllerTest {
    @TempDir Path dir;
    @Test void validatesBeforeReplacingAndRejectsActiveGenerationEdits()throws Exception {
        var properties=new SynthesisProperties();properties.setDataDir(dir.toString());var mapper=new ObjectMapper().findAndRegisterModules();var repo=new ScenarioRepository(mapper,properties);var s=new Scenario();s.id="station-input";s.region=new Scenario.Region();s.region.minLon=7;s.region.maxLon=8;s.region.minLat=51;s.region.maxLat=52;repo.save(s);
        var generation=mock(GenerationService.class);when(generation.status(s.id)).thenReturn(Optional.empty());var controller=new EnergyInfrastructureController(repo,generation,mapper);
        controller.upload(s.id,new MockMultipartFile("file","source.json","application/json","{\"elements\":[]}".getBytes()));Path saved=repo.uploadsDir(s.id).resolve(EnergyInfrastructure.SNAPSHOT);byte[] before=Files.readAllBytes(saved);
        var error=assertThrows(ResponseStatusException.class,()->controller.upload(s.id,new MockMultipartFile("file","bad.json","application/json","{\"elements\":[],\"remark\":\"timeout\"}".getBytes())));assertEquals(400,error.getStatusCode().value());assertArrayEquals(before,Files.readAllBytes(saved));
        var active=new GenerationJob(s.id,List.of());active.state=GenerationJob.State.QUEUED;when(generation.status(s.id)).thenReturn(Optional.of(active));assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.refresh(s.id)).getStatusCode().value());assertArrayEquals(before,Files.readAllBytes(saved));
        when(generation.status(s.id)).thenReturn(Optional.empty());controller.refresh(s.id);assertFalse(Files.exists(saved));
    }
}
