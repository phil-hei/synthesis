package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.generation.pipeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkplaceSourcesTest {
    @TempDir Path directory;
    static SourceProfiles.Candidate candidate(String id){return new SourceProfiles.Candidate(id,false,"next");}
    List<Zone> zones(){var zones=List.of(DataSourcesTest.workZone(0,900001,8.20,8.22),DataSourcesTest.workZone(1,900002,8.22,8.24));for(int i=0;i<zones.size();i++){zones.get(i).col=i;zones.get(i).areaKm2=1;zones.get(i).population=30;}return zones;}
    List<OverpassClient.Poi> pois(){return List.of(new OverpassClient.Poi(48.86,8.21,Map.of("shop","supermarket","office","company")));}
    @Test void sharedEngineAcceptsIndependentTypedProviderContracts(){
        var resolved=ProviderChain.resolve(List.of(candidate("example:capacity-v2")),List.of(new ProviderChain.Provider<Integer>("example:capacity-v2",()->ProviderChain.Attempt.available(37))),v->{assertTrue(v>0);});
        assertEquals(37,resolved.value());assertEquals("selected",resolved.candidates().getFirst().get("status"));
        var failure=assertThrows(ProviderChain.Failure.class,()->ProviderChain.resolve(List.of(candidate("example")),List.of(new ProviderChain.Provider<Integer>("example",()->ProviderChain.Attempt.available(-1))),v->{if(v<0)throw new IllegalArgumentException("negative capacity");}));
        assertEquals("invalid",failure.candidates().getFirst().get("status"));
    }
    @Test void osmUsesNativeClassifierAndPreservesEveryOtherPurpose(){
        var zones=zones();AttractivityCalculator.fromPois(zones,pois());
        var before=zones.stream().map(z->new TreeMap<>(z.attractivities)).toList();
        var report=WorkplaceSources.apply(List.of(candidate("osm-pois"),candidate("synthetic")),zones,pois(),42,"scenario-upload:osm-data.json","a".repeat(64));
        assertEquals(before,zones.stream().map(z->new TreeMap<>(z.attractivities)).toList());
        assertEquals(48.0,zones.getFirst().attractivities.get("Work"));assertEquals(0.0,zones.getLast().attractivities.get("Work"));
        assertEquals("model-weight",report.get("unit"));assertEquals("osm-pois",report.get("provider"));
        var p=(SourceProvenance)report.get("provenance");assertEquals("unknown",p.nodes().getFirst().evidence());assertEquals("derived",p.nodes().getLast().evidence());
        assertEquals("a".repeat(64),p.nodes().getFirst().source().sourceHash());assertEquals(List.of(new SourceProvenance.ZoneRef(900001,0),new SourceProvenance.ZoneRef(900002,1)),p.nodes().getFirst().scope().zones());
        assertEquals("not-evaluated",((List<Map<String,Object>>)report.get("candidates")).getLast().get("status"));
    }
    @Test void syntheticSelectionIsDeterministicAndNeverReusesExcludedOsmWork(){
        var zones=zones();AttractivityCalculator.fromPois(zones,pois());var other=zones.stream().map(z->{var m=new TreeMap<>(z.attractivities);m.remove("Work");return m;}).toList();
        var candidates=List.of(new SourceProfiles.Candidate("osm-pois",true,"next"),candidate("synthetic"));
        var first=WorkplaceSources.apply(candidates,zones,pois(),42,"snapshot","a");var weights=zones.stream().map(z->z.attractivities.get("Work")).toList();
        assertEquals("synthetic",first.get("provider"));assertNotEquals(List.of(48.0,0.0),weights);
        assertEquals("resolved",first.get("status"),"Explicitly excluded sources are not failed primary inputs");
        assertEquals(other,zones.stream().map(z->{var m=new TreeMap<>(z.attractivities);m.remove("Work");return m;}).toList());
        var repeat=WorkplaceSources.apply(candidates,zones,pois(),42,"snapshot","a");assertEquals(first,repeat);
        WorkplaceSources.apply(candidates,zones,pois(),43,"snapshot","a");assertNotEquals(weights,zones.stream().map(z->z.attractivities.get("Work")).toList());
        assertTrue(((SourceProvenance)first.get("provenance")).nodes().stream().allMatch(n->n.evidence().equals("assumed")));
    }
    @Test void absentOrInvalidSourcesDoNotPartlyOverwriteWorkOrUseHiddenFallback(){
        var zones=zones();var before=zones.stream().map(z->new TreeMap<>(z.attractivities)).toList();
        assertThrows(ProviderChain.Failure.class,()->WorkplaceSources.apply(List.of(candidate("osm-pois")),zones,List.of(),42,"missing",""));
        assertEquals(before,zones.stream().map(z->new TreeMap<>(z.attractivities)).toList());
        zones.getLast().attractivities.put("Work",Double.NaN);
        var invalid=assertThrows(ProviderChain.Failure.class,()->WorkplaceSources.apply(List.of(candidate("osm-pois"),candidate("synthetic")),zones,pois(),42,"bad",""));
        assertEquals("invalid",invalid.candidates().getFirst().get("status"));assertEquals(60.0,zones.getFirst().attractivities.get("Work"));
        assertTrue(Double.isNaN(zones.getLast().attractivities.get("Work")));
    }
    @Test void pipelinePersistsProviderProvenanceAndFailureThenCleansStaleReports()throws Exception{
        var base=new DataSourcesTest();base.dir=directory;base.setup();
        var p=new SourcePlan.Policy();p.source="builtins";p.providers=List.of(candidate("osm-pois"),candidate("synthetic"));
        base.scenario.dataPlan.enabled=true;base.scenario.dataPlan.aspects.put("opportunities.work",p);base.repo.save(base.scenario);
        var service=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{
            var job=service.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            var out=base.repo.artifactsDir(base.scenario.id);var report=base.json.readTree(out.resolve("sources/resolution.json").toFile()).path("workplaces");
            assertEquals("synthetic",report.path("provider").asText());assertEquals("not-applicable",report.path("candidates").get(0).path("status").asText());
            assertEquals("resolved-with-fallback",report.path("status").asText());
            assertEquals("fallback",report.path("provenance").path("nodes").get(0).path("selectionRole").asText());
            assertEquals(SourceProvenance.CONTRACT,report.path("provenance").path("contract").asText());
            p.providers=List.of(candidate("osm-pois"));base.repo.save(base.scenario);job=service.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.FAILED,job.state);assertTrue(Files.exists(out.resolve("sources/workplace-failure.json")));var failedRun=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals("FAILED",failedRun.at("/run/state").asText());assertTrue(failedRun.path("workplaces").isEmpty());assertTrue(failedRun.path("failures").has("workplace"));
            base.scenario.dataPlan.enabled=false;base.repo.save(base.scenario);job=service.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);assertFalse(Files.exists(out.resolve("sources/workplace-failure.json")));
        }finally{service.shutdown();}
    }
}
