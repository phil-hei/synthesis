package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Inject an Error without exhausting the test JVM: mirrors submit()'s captured fatal failure. */
class GenerationServiceFailureTest {
 @TempDir Path temp;
 @Test void resourceErrorTerminatesStageAndJobAndAllowsAnotherRun() throws Exception {
  var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
  var props=new SynthesisProperties();props.setDataDir(temp.toString());
  props.getCensus().setEnabled(false);props.getSimulation().setEnabled(false);
  var first=new AtomicBoolean(true);
  var client=new OverpassClient(props,mapper) {
   @Override public List<Poi> fetchPois(Scenario.Region r) {
    if(first.getAndSet(false))throw new OutOfMemoryError("injected regional XML allocation failure");
    return List.of();
   }
   @Override public List<Place> fetchPlaces(Scenario.Region r,boolean external) { return List.of(); }
  };
  var repo=new ScenarioRepository(mapper,props);var scenario=new Scenario();scenario.id="resource-failure";
  scenario.region=new Scenario.Region();scenario.region.minLat=48.83;scenario.region.maxLat=48.84;scenario.region.minLon=8.2;scenario.region.maxLon=8.21;
  scenario.population.totalPersons=12;scenario.population.behaviorSource="template";scenario.population.useKitPopulation=false;
  scenario.population.externalTrips=false;scenario.population.useRealRouting=false;scenario.targetModels=new ArrayList<>(List.of("logitopp"));repo.save(scenario);
  var service=new GenerationService(repo,client,props,mapper);
  try {
   var failed=service.start(scenario.id);await(failed);
   assertEquals(GenerationJob.State.FAILED,failed.state);assertNotNull(failed.finishedAt);assertNull(failed.currentStage);
   assertEquals("FAILED",failed.stage("OSM-Daten (POIs)").status);
   assertTrue(failed.error.contains("OutOfMemoryError"));assertTrue(failed.stage("OSM-Daten (POIs)").message.contains("injected regional XML"));
   assertTrue(failed.log.stream().anyMatch(l -> l.contains("FEHLER:") && l.contains("OutOfMemoryError")));
   var repeat=service.start(scenario.id);await(repeat);
   assertEquals(GenerationJob.State.DONE,repeat.state,repeat.error);
  } finally { service.shutdown(); }
 }
 private static void await(GenerationJob job) throws InterruptedException {
  long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
  while(job.finishedAt==null && System.nanoTime()<deadline) { job.log.size();Thread.sleep(10); }
  assertNotNull(job.finishedAt,"Pipeline worker must publish a finished state");
 }
}
