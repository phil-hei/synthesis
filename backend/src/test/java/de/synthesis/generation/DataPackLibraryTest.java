package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DataPackLibraryTest {
    @TempDir Path directory;
    SourceProfilesAndPacksTest base;DataPackLibrary library;
    @BeforeEach void setup(){base=new SourceProfilesAndPacksTest();base.directory=directory;base.setup();library=new DataPackLibrary(base.repo,base.json,ImportRegistry.builtins(),base.properties);}
    byte[] archive(int jobs)throws Exception {
        byte[] raw=("id;lon;lat;jobs\na;8.21;48.86;"+jobs+"\nzero;8.22;48.86;0\n").getBytes(StandardCharsets.UTF_8);
        return base.archive("same-edition",List.of(base.entry("work","jobs.csv",raw,false)),Map.of("jobs.csv",raw));
    }
    byte[] scenarioBytes(String id)throws Exception{return Files.readAllBytes(base.repo.scenarioDir(id).resolve("scenario.json"));}
    Scenario other(String id)throws Exception{var s=base.json.readValue(base.json.writeValueAsBytes(base.scenario),Scenario.class);s.id=id;base.repo.save(s);return s;}
    Path retained(String id){return directory.resolve("source-library/packs/"+id+".zip");}
    SourceProfiles.Profile select(DataPacks.Pack pack)throws Exception {
        var profile=base.profiles.save(base.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Bibliotheksquelle",Map.of(SourceProfilesAndPacksTest.WORK,new SourceProfiles.Chain(List.of(base.candidate(base.provider(pack),false,"fail")),"none"))));base.select(profile);base.repo.save(base.scenario);return profile;
    }
    @Test void publicationAndExplicitCopyKeepScenarioSelectionAndContentIdentity()throws Exception {
        byte[] bytes=archive(12),before=scenarioBytes(base.scenario.id);var pack=library.publish(bytes);
        assertEquals(pack,library.publish(bytes));assertEquals(1,library.list().size());assertTrue(base.packs.list(base.scenario.id).isEmpty());assertArrayEquals(before,scenarioBytes(base.scenario.id));
        assertEquals(pack,library.install(base.scenario.id,pack.id()));assertArrayEquals(before,scenarioBytes(base.scenario.id));assertEquals(pack,base.packs.read(base.scenario.id,pack.id()));
        var another=other("second");byte[] untouched=scenarioBytes(another.id);assertEquals(pack,library.install(another.id,pack.id()));assertArrayEquals(untouched,scenarioBytes(another.id));assertEquals(pack.sources().getFirst().revision(),base.packs.resolve(another.id,pack.id(),"work").id());
    }
    @Test void installedPackCanBePromotedButIndividualImportsAreNotPublishedImplicitly()throws Exception {
        var pack=base.packs.install(base.scenario.id,archive(12));assertTrue(library.list().isEmpty());byte[] before=scenarioBytes(base.scenario.id);
        assertEquals(pack,library.promote(base.scenario.id,pack.id()));assertArrayEquals(before,scenarioBytes(base.scenario.id));
        Files.writeString(base.repo.uploadsDir(base.scenario.id).resolve("data-imports/revisions/"+pack.sources().getFirst().revision()+".json"),"corrupt revision");assertThrows(java.io.IOException.class,()->library.promote(base.scenario.id,pack.id()));
    }
    @Test void newVersionNeverRebindsExistingScenarioAndLocalCopySurvivesCorruption()throws Exception {
        var first=library.publish(archive(12));library.install(base.scenario.id,first.id());select(first);byte[] chosen=scenarioBytes(base.scenario.id);
        var changed=library.publish(archive(99));assertNotEquals(first.id(),changed.id());assertEquals(2,library.list().size());assertArrayEquals(chosen,scenarioBytes(base.scenario.id));assertEquals(1,base.packs.list(base.scenario.id).size());
        Files.writeString(retained(first.id()),"corrupt central copy");assertEquals(12.0,SourceResolver.prepare(base.scenario,base.repo,base.json).datasets().get(SourceProfilesAndPacksTest.WORK).data().rows().getFirst().values().get("value"));
        var next=other("new-version");library.install(next.id,changed.id());assertEquals(99.0,base.packs.resolve(next.id,changed.id(),"work").data().rows().getFirst().values().get("value"));
        var statuses=library.list();assertEquals(1,statuses.stream().filter(e->e.status().equals("invalid")).count());assertEquals(1,statuses.stream().filter(e->e.status().equals("available")).count());assertTrue(statuses.stream().filter(e->e.status().equals("invalid")).findFirst().orElseThrow().issue().contains("verändert"));assertThrows(java.io.IOException.class,()->library.install(next.id,first.id()));
    }
    DataPacks.Entry entry(String id,String schema,String file,String csv,Metadata meta){
        byte[] raw=csv.getBytes(StandardCharsets.UTF_8);var source=base.read(raw,file);var draft=new DataImportService.Draft("draft",file,schema,source);return new DataPacks.Entry(id,schema,file,ImportSupport.hash(raw),base.imports.suggest(draft),meta);
    }
    @Test void portableMunicipalPackageRequiresItsOwnReferenceAndMatchingTerritory()throws Exception {
        String weights=MunicipalSourcesTest.WEIGHTS,totals=MunicipalSourcesTest.TOTALS;
        var wm=new Metadata("Gewichte","Künstliche Zuordnung","2026-01-01","relative-weight",base.meta(false).coverage(),"unknown","Test",null,new Geography("de:AGS","2025-01-01",null,"explicit-point-weights-v1"));
        var w=entry("weights","municipality-allocation-v1","weights.csv",weights,wm);var source=base.read(weights.getBytes(StandardCharsets.UTF_8),"weights.csv");
        var revision=base.imports.prepareRevision(new DataImportService.Draft("draft","weights.csv",w.schema(),source),new DataImportService.Request(w.mapping(),wm));
        var tm=new Metadata("Gemeindewerte","Künstliche Gesamtwerte","2026-01-01","jobs",base.meta(false).coverage(),"unknown","Test",new CoverageDeclaration("municipality-aggregate","complete","Alle Werte für künstliche Schlüssel vollständig"),new Geography("de:AGS","2025-01-01",revision.id(),"explicit-point-weights-v1"));
        var t=entry("totals","municipal-workplaces-v1","totals.csv",totals,tm);
        byte[] missing=base.archive("missing-reference",List.of(t),Map.of("totals.csv",totals.getBytes(StandardCharsets.UTF_8)));assertTrue(assertThrows(IllegalArgumentException.class,()->library.publish(missing)).getMessage().contains("alle Referenzrevisionen"));assertTrue(library.list().isEmpty());
        var files=Map.of("totals.csv",totals.getBytes(StandardCharsets.UTF_8),"weights.csv",weights.getBytes(StandardCharsets.UTF_8));
        var complete=library.publish(base.archive("complete",List.of(t,w),files));var second=other("municipal-target");library.install(second.id,complete.id());assertEquals(2,base.imports.revisions(second.id).size());
        var badMeta=new Metadata(tm.label(),tm.source(),tm.referenceDate(),tm.unit(),tm.coverage(),tm.licence(),tm.attribution(),tm.coverageDeclaration(),new Geography("de:AGS","2024-01-01",revision.id(),"explicit-point-weights-v1"));var bad=entry("totals",t.schema(),t.file(),totals,badMeta);
        assertThrows(IllegalArgumentException.class,()->library.publish(base.archive("bad-territory",List.of(bad,w),files)));assertEquals(1,library.list().size());
    }
    @Test void invalidArchivesMissingVersionsAndPathsLeaveAllSelectionsUnchanged()throws Exception {
        byte[] before=scenarioBytes(base.scenario.id);assertThrows(IllegalArgumentException.class,()->library.publish(SourceProfilesAndPacksTest.zip(Map.of("../escape.csv",new byte[]{1}))));assertThrows(IllegalArgumentException.class,()->library.publish(new byte[DataPacks.MAX_ARCHIVE_BYTES+1]));assertThrows(IllegalArgumentException.class,()->library.install(base.scenario.id,"../escape"));assertThrows(NoSuchFileException.class,()->library.install(base.scenario.id,"0".repeat(64)));assertTrue(library.list().isEmpty());assertTrue(base.imports.revisions(base.scenario.id).isEmpty());assertArrayEquals(before,scenarioBytes(base.scenario.id));
    }
    @Test void restSeparatesGlobalPublicationScenarioCopyAndActivation()throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new DataLibraryController(library)).build();byte[] archive=archive(12);String id=ImportSupport.hash(archive);
        mvc.perform(multipart("/api/data-library/packs").file(new MockMultipartFile("file","pack.zip","application/zip",archive))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));mvc.perform(get("/api/data-library/packs")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("available"));assertTrue(base.packs.list(base.scenario.id).isEmpty());
        mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/packs/"+id+"/install")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/packs/"+id+"/library")).andExpect(status().isOk());mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/packs/"+"0".repeat(64)+"/install")).andExpect(status().isNotFound());
        Files.writeString(retained(id),"changed");mvc.perform(post("/api/scenarios/"+base.scenario.id+"/data/library/packs/"+id+"/install")).andExpect(status().isUnprocessableEntity());assertFalse(base.repo.findById(base.scenario.id).orElseThrow().dataPlan.enabled);
    }
    static final List<String> REPLAY_FILES=List.of("population/person.csv","population/household.csv","population/activity.csv","zone-repository/zones.csv","zone-repository/attractivities.csv");
    Map<String,byte[]> populatedOutputs(Path out)throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        for(var name:REPLAY_FILES){var file=out.resolve(name);assertTrue(Files.readAllLines(file).size()>1,"Comparison needs populated data: "+name);files.put(name,Files.readAllBytes(file));}
        return files;
    }
    @Test void localLibraryCopyGeneratesReproduciblyWithoutCentralFiles()throws Exception {
        var pack=library.publish(archive(12));library.install(base.scenario.id,pack.id());select(pack);Files.delete(retained(pack.id()));var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);Path out=base.repo.artifactsDir(base.scenario.id);var populated=populatedOutputs(out);var resolution=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals(12,resolution.path("workplaces").path("appliedJobs").asDouble());job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);for(var name:REPLAY_FILES)assertArrayEquals(populated.get(name),Files.readAllBytes(out.resolve(name)),name);
        }finally{generation.shutdown();}
    }
}
