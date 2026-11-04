package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.OverpassClient;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SourceProfilesAndPacksTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    ScenarioRepository repo;Scenario scenario;SynthesisProperties properties;
    DataPacks packs;SourceProfiles profiles;DataImportService imports;
    static final String WORK="opportunities.work";
    @BeforeEach void setup(){
        properties=new SynthesisProperties();properties.setDataDir(directory.toString());
        properties.getSimulation().setEnabled(false);properties.getCensus().setEnabled(false);properties.getOverpass().setLiveDataEnabled(false);
        repo=new ScenarioRepository(json,properties);imports=new DataImportService(repo,json,ImportRegistry.builtins());
        packs=new DataPacks(repo,json,ImportRegistry.builtins());profiles=new SourceProfiles(repo,json);
        scenario=new Scenario();scenario.id="profile-test";scenario.region=DataSourcesTest.box(8.2,48.85,8.24,48.89);
        scenario.targetModels=new ArrayList<>(List.of("logitopp"));scenario.population.totalPersons=50;
        scenario.population.useRealRouting=false;scenario.population.useKitPopulation=false;scenario.population.externalTrips=false;
        scenario.population.behaviorSource="template";scenario.zoning.cellSizeMeters=1000;repo.save(scenario);
    }
    Metadata meta(boolean abroad){return new Metadata("Betriebspunkte","vollständige synthetische Testdaten","2026-01-01","jobs",
            abroad?DataSourcesTest.box(2.1,48.7,2.5,49.1):DataSourcesTest.box(8.1,48.7,8.5,49.1),"unknown","Testquelle",
            new CoverageDeclaration("point-register","complete","Alle Arbeitsstätten im deklarierten synthetischen Testgebiet"));}
    DataPacks.Entry entry(String id,String file,byte[] raw,boolean abroad){
        var source=read(raw,file);var draft=new DataImportService.Draft("draft",file,"workplaces-v1",source);
        return new DataPacks.Entry(id,"workplaces-v1",file,ImportSupport.hash(raw),imports.suggest(draft),meta(abroad));
    }
    SourceReader.Source read(byte[] bytes,String filename){return new SourceReader(json,SourceReader.ExtractionSettings.defaults()).read(bytes,filename);}
    byte[] archive(String edition,List<DataPacks.Entry> entries,Map<String,byte[]> files)throws IOException{
        var manifest=new DataPacks.Manifest(DataPacks.CONTRACT,"local-work",edition,"Lokaler Arbeitsplatzbestand",entries);
        var values=new LinkedHashMap<>(files);values.put("manifest.json",json.writeValueAsBytes(manifest));return zip(values);
    }
    static byte[] zip(Map<String,byte[]> files)throws IOException{
        var result=new ByteArrayOutputStream();try(var out=new ZipOutputStream(result)){
            for(var file:files.entrySet()){var entry=new ZipEntry(file.getKey());entry.setTime(0L);out.putNextEntry(entry);out.write(file.getValue());out.closeEntry();}
        }return result.toByteArray();
    }
    DataPacks.Pack install(boolean abroad,boolean asJson,String edition,int jobs)throws IOException{
        String file=asJson?"sites.json":"sites.csv";
        byte[] raw=(asJson?"{\"items\":[{\"kennung\":\"a\",\"laenge\":"+(abroad?2.31:8.21)+",\"breite\":48.86,\"arbeitsplaetze\":"+jobs+"}]}":
                "kennung;laenge;breite;arbeitsplaetze\na;"+(abroad?2.31:8.21)+";48.86;"+jobs+"\n").getBytes(StandardCharsets.UTF_8);
        return packs.install(scenario.id,archive(edition,List.of(entry("work",file,raw,abroad)),Map.of(file,raw)));
    }
    String provider(DataPacks.Pack pack){return "pack:"+pack.id()+":work";}
    SourceProfiles.Candidate candidate(String reference,boolean excluded,String failure){return new SourceProfiles.Candidate(reference,excluded,failure);}
    SourceProfiles.Profile save(List<SourceProfiles.Candidate> candidates,String fallback)throws IOException{
        return profiles.save(scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Arbeitsplatzprofil",Map.of(WORK,new SourceProfiles.Chain(candidates,fallback))));
    }
    void select(SourceProfiles.Profile profile){scenario.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="profile";policy.profile=profile.id();scenario.dataPlan.aspects.put(WORK,policy);}

    @Test void twoPackFormatsMaterializeSameContractAndInstallationNeverActivatesOrRebinds()throws Exception{
        byte[] before=Files.readAllBytes(repo.scenarioDir(scenario.id).resolve("scenario.json"));
        var csv=install(false,false,"edition-1",12);var alternate=install(false,true,"edition-2",12);
        assertNotEquals(csv.id(),alternate.id());assertEquals(2,packs.list(scenario.id).size());
        assertEquals(packs.resolve(scenario.id,csv.id(),"work").data().rows(),packs.resolve(scenario.id,alternate.id(),"work").data().rows());
        assertArrayEquals(before,Files.readAllBytes(repo.scenarioDir(scenario.id).resolve("scenario.json")));
        assertEquals(csv,install(false,false,"edition-1",12),"Identical package bytes must be idempotent");
        assertNotEquals(csv.id(),install(false,false,"edition-1",99).id(),"Changed data must change identity even at the same edition");
        var profile=save(List.of(candidate(provider(csv),false,"fail")),"none");select(profile);repo.save(scenario);
        install(false,true,"edition-3",99);
        assertEquals(profile.id(),repo.findById(scenario.id).orElseThrow().dataPlan.policy(WORK).profile);
        assertEquals(12.0,SourceResolver.prepare(scenario,repo,json).datasets().get(WORK).data().rows().getFirst().values().get("value"));
    }
    @Test void orderedGeographicSelectionMatchesPreviewAndFrozenPipelineReportOutsideGermany()throws Exception{
        var de=install(false,false,"de",12);var fr=install(true,true,"fr",7);
        var profile=save(List.of(candidate(provider(de),false,"fail"),candidate(provider(fr),false,"fail"),candidate("revision:"+"0".repeat(64),true,"fail")),"none");
        select(profile);scenario.region=DataSourcesTest.box(2.30,48.85,2.34,48.89);repo.save(scenario);
        var frozen=SourceResolver.prepare(scenario,repo,json);assertEquals(fr.sources().getFirst().revision(),frozen.datasets().get(WORK).id());
        var decision=frozen.decisions().stream().filter(d->WORK.equals(d.get("aspect"))).findFirst().orElseThrow();
        var tree=json.valueToTree(decision);assertEquals("not-applicable",tree.path("candidates").get(0).path("status").asText());
        assertEquals("selected",tree.path("candidates").get(1).path("status").asText());assertEquals("excluded",tree.path("candidates").get(2).path("status").asText());
        assertEquals("resolved-with-fallback",tree.path("status").asText());
        var controller=new DataImportController(imports,ImportRegistry.builtins(),repo,json);
        assertEquals(json.valueToTree(frozen.decisions()),json.valueToTree(controller.check(scenario.id,json.readValue(json.writeValueAsBytes(scenario),Scenario.class)).get("decisions")));
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{
            var job=generation.start(scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            var report=json.readTree(repo.artifactsDir(scenario.id).resolve("sources/resolution.json").toFile());
            assertEquals(json.valueToTree(frozen.decisions()),report.path("decisions"));assertEquals(7.0,report.path("workplaces").path("appliedJobs").asDouble());
            var lock=json.readTree(repo.artifactsDir(scenario.id).resolve("sources/lock.json").toFile());
            assertEquals(profile.id(),lock.path("decisions").get(1).path("profile").asText());
        }finally{generation.shutdown();}
    }
    @Test void orderAndExclusionsAreVersionedAndUnselectedCandidatesAreNotRead()throws Exception{
        var first=install(false,false,"one",12);var second=install(false,true,"two",23);
        var old=save(List.of(candidate(provider(first),false,"fail"),candidate(provider(second),false,"fail")),"none");
        var reordered=save(List.of(candidate(provider(second),false,"fail"),candidate(provider(first),false,"fail")),"none");assertNotEquals(old.id(),reordered.id());
        select(old);assertEquals(12.0,SourceResolver.prepare(scenario,repo,json).datasets().get(WORK).data().rows().getFirst().values().get("value"));
        Files.writeString(repo.uploadsDir(scenario.id).resolve("data-sources/packs/"+second.id()+".zip"),"broken but lower priority");
        var prepared=SourceResolver.prepare(scenario,repo,json);var decision=json.valueToTree(prepared.decisions().get(1));
        assertEquals("not-evaluated",decision.path("candidates").get(1).path("status").asText());
        var excluded=save(List.of(candidate(provider(second),true,"fail"),candidate(provider(first),false,"fail")),"none");select(excluded);
        assertDoesNotThrow(()->SourceResolver.prepare(scenario,repo,json));
    }
    @Test void onlyMissingSourcesMayFallThroughAndInvalidDataAlwaysFailWithDiagnostics()throws Exception{
        var valid=install(false,false,"valid",12);String missing="revision:"+"0".repeat(64);
        select(save(List.of(candidate(missing,false,"fail"),candidate(provider(valid),false,"fail")),"none"));
        var strict=assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(scenario,repo,json));assertTrue(strict.getMessage().contains("fehlt"));
        select(save(List.of(candidate(missing,false,"next"),candidate(provider(valid),false,"fail")),"none"));assertDoesNotThrow(()->SourceResolver.prepare(scenario,repo,json));
        var corrupt=install(false,true,"corrupt",8);Files.writeString(repo.uploadsDir(scenario.id).resolve("data-sources/packs/"+corrupt.id()+".zip"),"changed");
        select(save(List.of(candidate(provider(corrupt),false,"next"),candidate(provider(valid),false,"fail")),"none"));
        var failure=assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(scenario,repo,json));
        assertEquals("invalid",json.valueToTree(failure.decisions().getLast()).path("candidates").get(0).path("status").asText());
        var mvc=MockMvcBuilders.standaloneSetup(new DataImportController(imports,ImportRegistry.builtins(),repo,json)).build();
        mvc.perform(post("/api/scenarios/"+scenario.id+"/data/plan/preview").contentType("application/json").content(json.writeValueAsBytes(scenario)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.decisions[1].candidates[0].status").value("invalid"));
        repo.save(scenario);var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->generation.start(scenario.id));assertTrue(generation.status(scenario.id).isEmpty());}finally{generation.shutdown();}
    }
    @Test void exhaustedProfileUsesOnlyExplicitFallbackAndReportsExcludedSources()throws Exception{
        var candidates=List.of(candidate("revision:"+"0".repeat(64),true,"fail"));
        select(save(candidates,"none"));assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(scenario,repo,json));
        select(save(candidates,"legacy"));var result=SourceResolver.prepare(scenario,repo,json);assertTrue(result.datasets().isEmpty());
        assertEquals("legacy",result.decisions().get(1).get("source"));
    }
    @Test void allPackEntriesValidateBeforePublicationAndArchivePathsHashesAndSizesAreBounded()throws Exception{
        byte[] raw=DataSourcesTest.CSV.getBytes(StandardCharsets.UTF_8);var good=entry("work","jobs.csv",raw,false);
        var bad=new DataPacks.Entry("bad","workplaces-v1","jobs.csv",good.sha256(),good.mapping(),new Metadata("bad","bad units","2026-01-01","tonnes/month",meta(false).coverage()));
        assertThrows(IllegalArgumentException.class,()->packs.install(scenario.id,archive("bad",List.of(good,bad),Map.of("jobs.csv",raw))));assertTrue(imports.revisions(scenario.id).isEmpty());assertTrue(packs.list(scenario.id).isEmpty());
        assertThrows(IllegalArgumentException.class,()->packs.install(scenario.id,zip(Map.of("../escape.csv",raw))));
        var wrongHash=new DataPacks.Entry("work",good.schema(),good.file(),"0".repeat(64),good.mapping(),good.metadata());
        assertThrows(IllegalArgumentException.class,()->packs.install(scenario.id,archive("bad-hash",List.of(wrongHash),Map.of("jobs.csv",raw))));
        byte[] expanded=new byte[TabularReader.MAX_BYTES+1];assertThrows(IllegalArgumentException.class,()->packs.install(scenario.id,zip(Map.of("huge.csv",expanded))));
        var node=json.valueToTree(new DataPacks.Manifest(DataPacks.CONTRACT,"test","1","test",List.of(good)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("downloadUrl","https://invalid.example/never-fetch");
        assertThrows(IOException.class,()->packs.install(scenario.id,zip(Map.of("manifest.json",json.writeValueAsBytes(node),"jobs.csv",raw))));
        assertTrue(imports.revisions(scenario.id).isEmpty());
    }
    @Test void sameProfileAlsoSuppliesPopulationAndHeavyVehicleConsumers()throws Exception{
        String population="id;lon;lat;population\np;8.21;48.86;43\n";
        String freight="id;fromLon;fromLat;toLon;toLat;trips;vehicleClass;movement;beginHour;endHour\nf;8.21;48.86;8.22;48.87;3;truck;internal;6;8\n";
        var candidates=new TreeMap<String,SourceProfiles.Chain>();
        for(String schema:List.of("population-grid-v1","vehicle-od-v1")){
            boolean pop=schema.startsWith("population");var raw=(pop?population:freight).getBytes(StandardCharsets.UTF_8);
            var draft=imports.upload(scenario.id,schema,raw,"input.csv");
            var metadata=new Metadata("Testquelle","Synthetischer Vertragsfall","2026-01-01",pop?"persons":"vehicle-trips/day",meta(false).coverage());
            var revision=imports.publish(scenario.id,draft.id(),new DataImportService.Request(imports.suggest(draft),metadata));
            candidates.put(pop?"population.distribution":"freight.vehicleOd",new SourceProfiles.Chain(List.of(candidate("revision:"+revision.id(),false,"fail")),"none"));
        }
        var profile=profiles.save(scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Zwei Fachverträge",candidates));
        scenario.dataPlan.enabled=true;scenario.population.totalPersons=null;
        for(String aspect:candidates.keySet()){var policy=new SourcePlan.Policy();policy.source="profile";policy.profile=profile.id();scenario.dataPlan.aspects.put(aspect,policy);}
        repo.save(scenario);var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{
            var job=generation.start(scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            var manifest=json.readTree(repo.artifactsDir(scenario.id).resolve("manifest.json").toFile());assertEquals(43,manifest.path("populationEstimate").path("totalPersons").asInt());
            var demand=de.synthesis.generation.demand.TrafficDemandArtifacts.read(repo.artifactsDir(scenario.id),json);assertEquals(3,demand.freight().size());
        }finally{generation.shutdown();}
    }
    @Test void profileHashesAndConfigurationAreValidatedAndRestInstallationDoesNotActivate()throws Exception{
        var pack=install(false,false,"one",12);var candidate=candidate(provider(pack),false,"fail");
        assertThrows(IllegalArgumentException.class,()->save(List.of(candidate,candidate),"none"));assertThrows(IllegalArgumentException.class,()->save(List.of(candidate),"implicit"));
        var profile=save(List.of(candidate),"none");Path file=repo.uploadsDir(scenario.id).resolve("data-sources/profiles/"+profile.id()+".json");
        Files.writeString(file,Files.readString(file).replace("Arbeitsplatzprofil","Anderes Profil"));assertThrows(IOException.class,()->profiles.read(scenario.id,profile.id()));
        byte[] archive=Files.readAllBytes(repo.uploadsDir(scenario.id).resolve("data-sources/packs/"+pack.id()+".zip"));
        var mvc=MockMvcBuilders.standaloneSetup(new DataImportController(imports,ImportRegistry.builtins(),repo,json)).build();
        mvc.perform(multipart("/api/scenarios/"+scenario.id+"/data/packs").file(new MockMultipartFile("file","test.zip","application/zip",archive)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(pack.id()));
        assertFalse(repo.findById(scenario.id).orElseThrow().dataPlan.enabled);
    }
}
