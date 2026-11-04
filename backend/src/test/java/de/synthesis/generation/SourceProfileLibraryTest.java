package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SourceProfileLibraryTest {
    @TempDir Path directory;DataPackLibraryTest fixture;SourceProfilesAndPacksTest base;SourceProfileLibrary library;
    @BeforeEach void setup(){fixture=new DataPackLibraryTest();fixture.directory=directory;fixture.setup();base=fixture.base;library=new SourceProfileLibrary(base.repo,base.json,ImportRegistry.builtins(),base.properties,fixture.library);}
    SourceProfiles.Definition definition(List<SourceProfiles.Candidate> candidates){return new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Gemeinsame Arbeitsquellen",Map.of("opportunities.work",new SourceProfiles.Chain(candidates,"none")));}
    SourceProfiles.Definition definition(DataPacks.Pack p){return definition(List.of(base.candidate(base.provider(p),false,"fail")));}
    Path stored(String id){return directory.resolve("source-library/profiles/"+id+".json");}
    @Test void profileAndAllDependenciesCopyWithSameHashesWithoutActivation()throws Exception {
        var pack=fixture.library.publish(fixture.archive(12));var original=base.profiles.save(base.scenario.id,definition(pack));byte[] before=fixture.scenarioBytes(base.scenario.id);
        assertEquals(original,library.promote(base.scenario.id,original.id()));assertEquals(original,library.publish(definition(pack)));assertArrayEquals(before,fixture.scenarioBytes(base.scenario.id));
        var entry=library.list().getFirst();assertEquals("available",entry.status());assertEquals(List.of(pack),entry.requiredPacks());var target=fixture.other("target");byte[] unchanged=fixture.scenarioBytes(target.id);
        assertEquals(original,library.install(target.id,original.id()));assertArrayEquals(unchanged,fixture.scenarioBytes(target.id));assertEquals(original,base.profiles.read(target.id,original.id()));assertEquals(pack,base.packs.read(target.id,pack.id()));assertEquals(original,library.install(target.id,original.id()));
    }
    @Test void scenarioRevisionReferencesRemainLocalAndWrongPackageContributionsCannotBeShared()throws Exception {
        var local=base.packs.install(base.scenario.id,fixture.archive(12));var d=definition(List.of(base.candidate("revision:"+local.sources().getFirst().revision(),false,"fail")));var original=base.profiles.save(base.scenario.id,d);
        assertTrue(assertThrows(IllegalArgumentException.class,()->library.promote(base.scenario.id,original.id())).getMessage().contains("Paketquellen"));assertEquals(original,base.profiles.read(base.scenario.id,original.id()));
        fixture.library.promote(base.scenario.id,local.id());
        var wrong=new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Falscher Aspekt",Map.of("population.distribution",new SourceProfiles.Chain(List.of(base.candidate(base.provider(local),false,"fail")),"none")));
        assertThrows(IllegalArgumentException.class,()->library.publish(wrong));assertThrows(IllegalArgumentException.class,()->library.publish(definition(List.of(base.candidate("pack:"+local.id()+":absent",false,"fail")))));assertTrue(library.list().isEmpty());
    }
    @Test void nativeRecipesAndExcludedPackagesDoNotReadOrCopyUnselectedInputs()throws Exception {
        var d=definition(List.of(base.candidate("pack:"+"0".repeat(64)+":excluded",true,"fail"),base.candidate("builtin:synthetic",false,"next")));var p=library.publish(d);var entry=library.list().getFirst();assertTrue(entry.requiredPacks().isEmpty());library.install(base.scenario.id,p.id());assertTrue(base.packs.list(base.scenario.id).isEmpty());assertFalse(base.repo.findById(base.scenario.id).orElseThrow().dataPlan.enabled);
        var population=new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Native Bevölkerung",Map.of("population.distribution",new SourceProfiles.Chain(List.of(base.candidate("builtin:upload-grid",false,"next"),base.candidate("builtin:census-2022",false,"next")),"none")));
        assertDoesNotThrow(()->library.publish(population));assertFalse(Files.exists(base.repo.uploadsDir(base.scenario.id).resolve("population-grid.csv")));
    }
    @Test void allActiveDependenciesValidateBeforeAnyScenarioWrite()throws Exception {
        var a=fixture.library.publish(fixture.archive(12));var b=fixture.library.publish(fixture.archive(99));var p=library.publish(definition(List.of(base.candidate(base.provider(a),false,"fail"),base.candidate(base.provider(b),false,"next"))));var target=fixture.other("untouched");byte[] before=fixture.scenarioBytes(target.id);Files.delete(fixture.retained(b.id()));
        assertThrows(NoSuchFileException.class,()->library.install(target.id,p.id()));assertArrayEquals(before,fixture.scenarioBytes(target.id));assertTrue(base.packs.list(target.id).isEmpty());assertTrue(base.imports.revisions(target.id).isEmpty());assertTrue(base.profiles.list(target.id).isEmpty());assertEquals("invalid",library.list().getFirst().status());
        library.publish(definition(List.of(base.candidate("builtin:synthetic",false,"fail"))));assertEquals(1,library.list().stream().filter(e->e.status().equals("available")).count());
    }
    @Test void newCentralVersionsAndCorruptionCannotRebindRetainedScenarioProfile()throws Exception {
        var a=fixture.library.publish(fixture.archive(12));var b=fixture.library.publish(fixture.archive(99));var first=library.publish(definition(List.of(base.candidate(base.provider(a),false,"fail"),base.candidate(base.provider(b),false,"fail"))));library.install(base.scenario.id,first.id());base.select(first);base.repo.save(base.scenario);byte[] selected=fixture.scenarioBytes(base.scenario.id);
        var next=library.publish(definition(List.of(base.candidate(base.provider(b),false,"fail"),base.candidate(base.provider(a),false,"fail"))));assertNotEquals(first.id(),next.id());assertArrayEquals(selected,fixture.scenarioBytes(base.scenario.id));Files.writeString(stored(first.id()),"corrupt central profile");Files.writeString(fixture.retained(a.id()),"corrupt central package");
        assertEquals(first,base.profiles.read(base.scenario.id,first.id()));assertEquals(12.0,SourceResolver.prepare(base.scenario,base.repo,base.json).datasets().get("opportunities.work").data().rows().getFirst().values().get("value"));assertThrows(java.io.IOException.class,()->library.install(base.scenario.id,first.id()));assertTrue(library.list().stream().anyMatch(e->e.status().equals("invalid")));
    }
    @Test void restPublishesCopiesAndPromotesWithoutSelectingAndReportsMissingInputs()throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new SourceProfileLibraryController(library)).build();var pack=fixture.library.publish(fixture.archive(12));var d=definition(pack);String id=base.profiles.prepare(d).id();
        mvc.perform(post("/api/data-library/source-profiles").contentType("application/json").content(base.json.writeValueAsBytes(d))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));mvc.perform(get("/api/data-library/source-profiles")).andExpect(status().isOk()).andExpect(jsonPath("$[0].requiredPacks[0].id").value(pack.id()));
        mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/source-profiles/"+id+"/install")).andExpect(status().isOk());mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/source-profiles/"+id+"/library")).andExpect(status().isOk());assertFalse(base.repo.findById(base.scenario.id).orElseThrow().dataPlan.enabled);
        mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/source-profiles/"+"0".repeat(64)+"/install")).andExpect(status().isNotFound());Files.writeString(stored(id),"changed");mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/source-profiles/"+id+"/install")).andExpect(status().isUnprocessableEntity());
    }
    @Test void localProfileAndPackagesGeneratePopulatedIdenticalOutputsWithoutCentralCopies()throws Exception {
        var pack=fixture.library.publish(fixture.archive(12));var profile=library.publish(definition(pack));library.install(base.scenario.id,profile.id());base.select(profile);base.repo.save(base.scenario);Files.delete(stored(profile.id()));Files.delete(fixture.retained(pack.id()));
        var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);Path out=base.repo.artifactsDir(base.scenario.id);var before=fixture.populatedOutputs(out);job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);for(var name:DataPackLibraryTest.REPLAY_FILES)assertArrayEquals(before.get(name),Files.readAllBytes(out.resolve(name)),name);}finally{generation.shutdown();}
    }
}
