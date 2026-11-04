package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionalWorkplacesTest {
    @TempDir Path directory;DataSourcesTest base;
    @BeforeEach void setup(){base=new DataSourcesTest();base.dir=directory;base.setup();}
    SourceProfiles.Candidate c(String id){return new SourceProfiles.Candidate(id,false,"next");}
    DataImportService.RevisionInfo point(String csv,double west,double east)throws Exception{
        var draft=base.upload("workplaces-v1",csv,"register.csv");var m=base.metadata("jobs");
        return base.service.publish(base.scenario.id,draft.id(),new DataImportService.Request(base.service.suggest(draft),new Metadata(m.label(),m.source(),m.referenceDate(),m.unit(),DataSourcesTest.box(west,48.84,east,48.90),m.licence(),m.attribution(),m.coverageDeclaration())));
    }
    SourceProfiles.Profile profile(List<SourceProfiles.Candidate> candidates)throws Exception{return new SourceProfiles(base.repo,base.json).save(base.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Regionale Register",Map.of("opportunities.work",new SourceProfiles.Chain(candidates,"none","fill-gaps"))));}
    SourcePlan.Policy select(SourceProfiles.Profile profile){var p=new SourcePlan.Policy();p.source="profile";p.profile=profile.id();p.gaps="none";base.scenario.dataPlan.enabled=true;base.scenario.dataPlan.aspects.put("opportunities.work",p);return p;}
    List<Zone> zones(){var result=List.of(DataSourcesTest.workZone(0,100001,8.20,8.22),DataSourcesTest.workZone(1,100002,8.22,8.24),DataSourcesTest.workZone(2,100003,8.24,8.26));for(var z:result){z.areaKm2=1;z.col=z.index;}return result;}
    Map<String,Object> apply(SourceResolver.Inputs inputs,List<Zone> zones){return WorkplaceComposition.apply(inputs,base.scenario.dataPlan,zones,List.of(),42,"none","");}
    List<Map<String,Object>> parts(Map<String,Object> report){return (List<Map<String,Object>>)report.get("parts");}
    @Test void oldProfileFixtureKeepsItsCapturedPreStrategyHash()throws Exception{
        try(var input=getClass().getResourceAsStream("/source-profile-legacy-v1.json")){
            var definition=base.json.readValue(input,SourceProfiles.Definition.class);var profile=new SourceProfiles(base.repo,base.json).save(base.scenario.id,definition);
            assertEquals("0821e2ff27272383c03d81d5bdf8b35850027296690c1a3386bc406bb95f2049",profile.id());
            assertEquals(profile,new SourceProfiles(base.repo,base.json).read(base.scenario.id,profile.id()));
            assertFalse(base.json.valueToTree(definition).path("aspects").path("opportunities.work").has("strategy"));
        }
    }
    @Test void overlappingRegistersPreserveZerosAndBalanceShadowedQuantities()throws Exception{
        var a=point("id;lon;lat;jobs\na;8.21;48.86;12\nb;8.23;48.86;0\n",8.19,8.24);
        var b=point("id;lon;lat;jobs\na;8.21;48.86;99\nb;8.23;48.86;88\nc;8.25;48.86;7\n",8.19,8.27);
        select(profile(List.of(c("revision:"+a.id()),c("revision:"+b.id()))));var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones();var report=apply(inputs,zones);
        assertEquals(List.of(12.0,0.0,7.0),zones.stream().map(z->z.attractivities.get("Work")).toList());assertEquals(List.of(4.0,4.0,4.0),zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList());
        var second=parts(report).get(1);assertEquals(194.0,second.get("inputJobs"));assertEquals(7.0,second.get("appliedJobs"));assertEquals(187.0,second.get("shadowedJobs"));assertFalse(report.containsKey("appliedJobs"));
        var graph=(SourceProvenance)report.get("provenance");assertEquals(List.of(new SourceProvenance.ZoneRef(100003,2)),graph.nodes().stream().filter(n->n.id().equals("register-2/point-allocation")).findFirst().orElseThrow().scope().zones());
        assertEquals(false,graph.nodes().getLast().parameters().get("quantitiesComparable"));
        select(profile(List.of(c("revision:"+b.id()),c("revision:"+a.id()))));report=apply(SourceResolver.prepare(base.scenario,base.repo,base.json),zones=zones());assertEquals(List.of(99.0,88.0,7.0),zones.stream().map(z->z.attractivities.get("Work")).toList());
        assertEquals("not-evaluated",((List<Map<String,Object>>)report.get("candidates")).getLast().get("status"));
    }
    @Test void finalGapFailureIsAtomicAndExplicitModelOnlyTouchesRemainingZones()throws Exception{
        var a=point("id;lon;lat;jobs\na;8.21;48.86;12\n",8.19,8.22);var b=point("id;lon;lat;jobs\nb;8.23;48.86;0\n",8.22,8.24);
        var policy=select(profile(List.of(c("revision:"+a.id()),c("revision:"+b.id()))));var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones();var before=zones.stream().map(z->new TreeMap<>(z.attractivities)).toList();
        assertThrows(ProviderChain.Failure.class,()->apply(inputs,zones));assertEquals(before,zones.stream().map(z->new TreeMap<>(z.attractivities)).toList());
        policy.gaps="providers";policy.gapProviders=List.of(c("osm-pois"));assertThrows(ProviderChain.Failure.class,()->apply(inputs,zones));assertEquals(before,zones.stream().map(z->new TreeMap<>(z.attractivities)).toList());
        policy.gapProviders=List.of(c("synthetic"));var report=apply(inputs,zones);assertEquals(12,zones.getFirst().attractivities.get("Work"));assertEquals(0,zones.get(1).attractivities.get("Work"));assertTrue(zones.getLast().attractivities.get("Work")>0);
        assertEquals(1,((List<?>)((Map<?,?>)report.get("gapResolution")).get("zoneWeights")).size());
    }
    @Test void missingRegionalInputsRequireExplicitModelAndFrozenRevisionsAreNotReadAgain()throws Exception{
        var policy=select(profile(List.of(c("revision:"+"0".repeat(64)))));assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        policy.gaps="providers";policy.gapProviders=List.of(c("synthetic"));var report=apply(SourceResolver.prepare(base.scenario,base.repo,base.json),zones());assertEquals(0,parts(report).size());assertEquals(3,report.get("fallbackZones"));
        var a=point("id;lon;lat;jobs\na;8.21;48.86;12\n",8.19,8.27);policy=select(profile(List.of(c("revision:"+a.id()))));var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);
        Files.delete(base.repo.uploadsDir(base.scenario.id).resolve("data-imports/revisions/"+a.id()+".json"));report=apply(inputs,zones());assertEquals(12.0,parts(report).getFirst().get("appliedJobs"));
    }
    @Test void municipalAllocationRetainsItsOutsideMassAfterPriorityMasking()throws Exception{
        var municipal=new MunicipalSourcesTest();municipal.directory=directory.resolve("municipal");municipal.setup();
        var w=municipal.weights(MunicipalSourcesTest.WEIGHTS,"2025-01-01");var totals=municipal.totals(MunicipalSourcesTest.TOTALS,"2025-01-01",w.id());
        var m=base.metadata("jobs");var primary=municipal.publish("workplaces-v1","id;lon;lat;jobs\na;8.21;48.86;12\n",new Metadata(m.label(),m.source(),m.referenceDate(),m.unit(),DataSourcesTest.box(8.19,48.84,8.22,48.90),m.licence(),m.attribution(),m.coverageDeclaration()));
        var profile=new SourceProfiles(municipal.repo,municipal.json).save(municipal.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Municipal",Map.of("opportunities.work",new SourceProfiles.Chain(List.of(c("revision:"+primary.id()),c("revision:"+totals.id())),"none","fill-gaps"))));
        var policy=municipal.scenario.dataPlan.policy("opportunities.work");policy.source="profile";policy.profile=profile.id();policy.gaps="providers";policy.gapProviders=List.of(c("synthetic"));
        var report=WorkplaceComposition.apply(SourceResolver.prepare(municipal.scenario,municipal.repo,municipal.json),municipal.scenario.dataPlan,municipal.zones(),List.of(),42,"none","");
        var part=parts(report).get(1);assertEquals(100.0,part.get("inputJobs"));assertEquals(0.0,part.get("appliedJobs"));assertEquals(75.0,part.get("shadowedJobs"));assertEquals(25.0,part.get("outsideJobs"));
    }
    @Test void polygonAndPointReferencesRemainSeparateAndUseTrueProjectedCells()throws Exception{
        var area=new MunicipalAreasTest();area.directory=directory.resolve("area");area.setup();
        var shape=area.publishAreas(area.geo(area.polygon(MunicipalAreasTest.X,MunicipalAreasTest.Y,2000,1000)));var initial=area.inputs(shape.id());var areaTotal=initial.datasets().get("opportunities.work");
        var bounds=DataSourcesTest.box(5,45,16,56);var ll=Epsg3035.toLatLon(MunicipalAreasTest.X+500,MunicipalAreasTest.Y+500);
        var geo=new Geography("de:AGS","2025-01-01",null,"explicit-point-weights-v1");var weight=area.base.publish("municipality-allocation-v1","id;ags;lon;lat;weight\nw;01001000;"+ll[1]+";"+ll[0]+";1\n",new Metadata("Points","Synthetic","2026-01-01","relative-weight",bounds,"unknown","",null,geo));
        var pointTotal=area.base.publish("municipal-workplaces-v1","id;ags;value\nt;01001000;10\n",new Metadata("Totals","Synthetic","2026-01-01","jobs",bounds,"unknown","",new CoverageDeclaration("municipality-aggregate","complete","Vollständige synthetische Testsumme"),new Geography("de:AGS","2025-01-01",weight.id(),"explicit-point-weights-v1")));
        var profile=new SourceProfiles(area.base.repo,area.json).save(area.base.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Area",Map.of("opportunities.work",new SourceProfiles.Chain(List.of(c("revision:"+pointTotal.id()),c("revision:"+areaTotal.id())),"none","fill-gaps"))));
        var policy=area.base.scenario.dataPlan.policy("opportunities.work");policy.source="profile";policy.profile=profile.id();policy.gaps="none";var zones=List.of(area.zone(0),area.zone(1));
        var inputs=SourceResolver.prepare(area.base.scenario,area.base.repo,area.json);assertEquals(2,inputs.references().size());var report=WorkplaceComposition.apply(inputs,area.base.scenario.dataPlan,zones,List.of(),42,"none","");
        assertEquals(10,zones.getFirst().attractivities.get("Work"),.001);assertEquals(50,zones.getLast().attractivities.get("Work"),.001);assertEquals(50,(Double)parts(report).get(1).get("shadowedJobs"),.001);
    }
    @Test void regionalContractsAndAllPotentialSourcesAreValidatedBeforeQueueing()throws Exception{
        for(var entry:Map.of("population.distribution",List.of(c("revision:"+"0".repeat(64))),"opportunities.work",List.of(c("builtin:synthetic"))).entrySet()){
            var definition=new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Bad",Map.of(entry.getKey(),new SourceProfiles.Chain(entry.getValue(),"none","fill-gaps")));assertThrows(IllegalArgumentException.class,()->SourceProfiles.validate(definition));
        }
        var a=point("id;lon;lat;jobs\na;8.21;48.86;12\n",8.19,8.27);String corrupt="f".repeat(64);Files.writeString(base.repo.uploadsDir(base.scenario.id).resolve("data-imports/revisions/"+corrupt+".json"),"corrupt");
        select(profile(List.of(c("revision:"+a.id()),c("revision:"+corrupt))));assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        select(profile(List.of(c("revision:"+a.id()),new SourceProfiles.Candidate("revision:"+corrupt,true,"fail"))));assertDoesNotThrow(()->SourceResolver.prepare(base.scenario,base.repo,base.json));
    }
    @Test void fullOfflinePipelineComposesRegistersAndRepeatsDemandBytes()throws Exception{
        var a=point("id;lon;lat;jobs\na;8.205;48.86;12\nzero;8.22;48.86;0\n",8.19,8.23);var b=point("id;lon;lat;jobs\noverlap;8.22;48.86;99\nextra;8.25;48.86;7\n",8.21,8.27);
        var policy=select(profile(List.of(c("revision:"+a.id()),c("revision:"+b.id()))));policy.gaps="providers";policy.gapProviders=List.of(c("osm-pois"));base.scenario.region.maxLon=8.26;base.repo.save(base.scenario);
        var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);var out=base.repo.artifactsDir(base.scenario.id);var report=base.json.readTree(out.resolve("sources/resolution.json").toFile()).path("workplaces");assertEquals(2,report.path("parts").size());assertEquals(7,report.path("parts").get(1).path("appliedJobs").asDouble());assertEquals(99,report.path("parts").get(1).path("shadowedJobs").asDouble());assertEquals("not-required",report.path("gapResolution").path("status").asText());
            byte[] before=Files.readAllBytes(out.resolve("demand/traffic-demand.json"));job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);assertArrayEquals(before,Files.readAllBytes(out.resolve("demand/traffic-demand.json")));
        }finally{generation.shutdown();}
    }
}
