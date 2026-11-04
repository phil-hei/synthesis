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

class MixedSourcesTest {
    @TempDir Path directory;
    DataSourcesTest base;
    static final String WORK="opportunities.work",POP="population.distribution";
    @BeforeEach void setup(){base=new DataSourcesTest();base.dir=directory;base.setup();}
    SourceProfiles.Candidate candidate(String id){return new SourceProfiles.Candidate(id,false,"next");}
    SourceProfiles.Profile profile(String aspect,List<SourceProfiles.Candidate> candidates)throws Exception{
        return new SourceProfiles(base.repo,base.json).save(base.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Gemischtes Testprofil",Map.of(aspect,new SourceProfiles.Chain(candidates,"none"))));
    }
    SourcePlan.Policy select(String aspect,SourceProfiles.Profile profile){var p=new SourcePlan.Policy();p.source="profile";p.profile=profile.id();p.gaps="none";base.scenario.dataPlan.enabled=true;base.scenario.dataPlan.aspects.put(aspect,p);return p;}
    List<Zone> zones(){var zones=List.of(DataSourcesTest.workZone(0,100001,8.20,8.22),DataSourcesTest.workZone(1,100002,8.22,8.24),DataSourcesTest.workZone(2,100003,8.24,8.26));for(var z:zones){z.areaKm2=1;z.col=z.index;}return zones;}
    List<OverpassClient.Poi> pois(){return List.of(new OverpassClient.Poi(48.86,8.25,Map.of("office","company")));}
    DataImportService.RevisionInfo partial()throws Exception{
        var d=base.upload("workplaces-v1","id;lon;lat;jobs\na;8.21;48.86;12\nb;8.23;48.86;0\n","partial.csv");var m=base.metadata("jobs");
        return base.service.publish(base.scenario.id,d.id(),new DataImportService.Request(base.service.suggest(d),new Metadata(m.label(),m.source(),m.referenceDate(),m.unit(),DataSourcesTest.box(8.19,48.84,8.24,48.90),m.licence(),m.attribution(),m.coverageDeclaration())));
    }
    SourcePlan.Policy dataset(String revision){var p=new SourcePlan.Policy();p.source="dataset";p.revision=revision;p.gaps="providers";p.gapProviders=List.of(candidate("osm-pois"),candidate("synthetic"));base.scenario.dataPlan.enabled=true;base.scenario.dataPlan.aspects.put(WORK,p);return p;}
    Map<String,Object> apply(SourceResolver.Inputs inputs,List<Zone> zones,List<OverpassClient.Poi> pois){return WorkplaceComposition.apply(inputs,base.scenario.dataPlan,zones,pois,42,"snapshot","a".repeat(64));}

    @Test void validatesNativeContractsAndPreventsImplicitLegacyReentry()throws Exception{
        var good=profile(POP,List.of(candidate("builtin:uniform")));assertEquals(good,new SourceProfiles(base.repo,base.json).read(base.scenario.id,good.id()));
        for(String aspect:List.of("freight.vehicleOd",POP)){
            String provider=aspect.equals(POP)?"builtin:osm-pois":"builtin:uniform";
            assertThrows(IllegalArgumentException.class,()->profile(aspect,List.of(candidate(provider))));
        }
        var invalid=new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Bad",Map.of(POP,new SourceProfiles.Chain(List.of(candidate("builtin:uniform")),"legacy")));
        assertThrows(IllegalArgumentException.class,()->new SourceProfiles(base.repo,base.json).save(base.scenario.id,invalid));
        var work=profile(WORK,List.of(candidate("builtin:synthetic")));var policy=select(WORK,work);policy.gaps="legacy";
        assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        policy.gaps="providers";assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
    }
    @Test void mixedPopulationUsesFrozenRevisionAfterMissingAndInapplicableCandidates()throws Exception{
        var population=base.publish("population-grid-v1","id;lon;lat;population\np;8.21;48.86;80\n");
        var p=profile(POP,List.of(candidate("revision:"+"0".repeat(64)),candidate("builtin:census-2022"),candidate("revision:"+population.id()),candidate("builtin:uniform")));
        select(POP,p);var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var frozen=inputs.chains().get(POP);
        assertEquals(List.of("unavailable","pending","staged","not-evaluated"),frozen.candidates().stream().map(MixedSources.Candidate::status).toList());
        Files.delete(base.repo.uploadsDir(base.scenario.id).resolve("data-imports/revisions/"+population.id()+".json"));
        var zones=zones();var natives=PopulationSources.builtins(base.scenario,zones,List.of(),List.of(),new PopulationSources.Info("osm","OSM","v1","","","","ODbL",""),directory.resolve("absent"),null,directory.resolve("retained.csv"),new CensusPopulationSource(base.properties.getCensus()),directory.resolve("census"),s->{});
        var result=PopulationSources.resolveMixed(frozen,natives,zones);assertEquals("dataset:"+population.id(),result.value().distribution());assertEquals(80,result.value().estimatedTotal());
        assertEquals(List.of("unavailable","not-applicable","selected","not-evaluated"),result.candidates().stream().map(c->c.get("status")).toList());
        var report=PopulationSources.report(result,base.scenario,zones,50);var provenance=(SourceProvenance)report.get("distributionProvenance");assertEquals("dataset",provenance.nodes().getFirst().source().kind());assertEquals(population.id(),provenance.nodes().getFirst().source().revision());
        assertEquals("user-defined",report.get("totalSource"));
    }
    @Test void reachablePotentialFilesAreValidatedButExcludedAndTerminalSuccessorsAreNotRead()throws Exception{
        var population=base.publish("population-grid-v1","id;lon;lat;population\np;8.21;48.86;80\n");
        String corrupt="f".repeat(64);Files.writeString(base.repo.uploadsDir(base.scenario.id).resolve("data-imports/revisions/"+corrupt+".json"),"broken");
        var p=profile(POP,List.of(candidate("revision:"+population.id()),candidate("builtin:uniform"),candidate("revision:"+corrupt)));
        select(POP,p);var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);assertEquals("not-evaluated",inputs.chains().get(POP).candidates().getLast().status());
        p=profile(POP,List.of(candidate("builtin:uniform"),candidate("revision:"+corrupt)));select(POP,p);
        var invalid=assertThrows(SourceResolver.PlanException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));assertEquals("invalid",((List<Map<String,Object>>)invalid.decisions().getLast().get("candidates")).getLast().get("status"));
        p=profile(POP,List.of(candidate("builtin:uniform"),new SourceProfiles.Candidate("revision:"+corrupt,true,"fail")));select(POP,p);assertDoesNotThrow(()->SourceResolver.prepare(base.scenario,base.repo,base.json));
    }
    @Test void registerZerosAndNativeFillersHaveDisjointScopesAndComparableQuantitiesStaySeparate()throws Exception{
        var revision=partial();dataset(revision.id());var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones();zones.get(0).attractivities.put("Work",777.0);zones.get(1).attractivities.put("Work",888.0);zones.get(2).attractivities.put("Work",40.0);
        var report=apply(inputs,zones,pois());assertEquals(List.of(12.0,0.0,40.0),zones.stream().map(z->z.attractivities.get("Work")).toList());assertEquals(List.of(4.0,4.0,4.0),zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList());
        assertEquals(12.0,report.get("appliedJobs"));var gap=(Map<String,Object>)report.get("gapResolution");assertEquals(40.0,gap.get("sumWeights"));assertEquals("model-weight",gap.get("unit"));
        var p=(SourceProvenance)report.get("provenance");assertTrue(p.nodes().stream().noneMatch(n->n.id().equals("legacy-work")));
        var scope=p.nodes().stream().filter(n->n.id().equals("gap/weights")).findFirst().orElseThrow().scope();assertEquals(List.of(new SourceProvenance.ZoneRef(100003,2)),scope.zones());
        assertEquals(false,p.nodes().getLast().parameters().get("quantitiesComparable"));
    }
    @Test void failedGapSelectionIsAtomicAndFullCoverageDoesNotExecuteGapProviders()throws Exception{
        var revision=partial();var p=dataset(revision.id());p.gapProviders=List.of(candidate("osm-pois"));var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones();var before=zones.stream().map(z->new TreeMap<>(z.attractivities)).toList();
        assertThrows(ProviderChain.Failure.class,()->apply(inputs,zones,List.of()));assertEquals(before,zones.stream().map(z->new TreeMap<>(z.attractivities)).toList());
        var covered=zones.subList(0,2);covered.get(0).attractivities.put("Work",Double.NaN);
        var report=apply(inputs,covered,List.of());assertEquals("not-required",((Map<?,?>)report.get("gapResolution")).get("status"));assertEquals(12.0,covered.getFirst().attractivities.get("Work"));assertEquals(0.0,covered.getLast().attractivities.get("Work"));
    }
    @Test void entirelyPartialPointCoverageUsesExplicitFillersWithoutClaimingAppliedRegisterCounts()throws Exception{
        var revision=partial();dataset(revision.id());var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);
        var zone=DataSourcesTest.workZone(0,900001,8.20,8.26);zone.areaKm2=1;
        var report=apply(inputs,List.of(zone),List.of());assertEquals(0,report.get("coveredZones"));assertEquals(0.0,report.get("appliedJobs"));assertEquals(12.0,report.get("jobsInPartiallyCoveredZonesNotApplied"));
        assertTrue(zone.attractivities.get("Work")>0);assertEquals("synthetic",((Map<?,?>)report.get("gapResolution")).get("provider"));
    }
    @Test void municipalPointAllocationPreservesOutsideMassWhileFillingOnlyUnassignedZones()throws Exception{
        var municipal=new MunicipalSourcesTest();municipal.directory=directory.resolve("municipal");municipal.setup();
        var weights=municipal.weights(MunicipalSourcesTest.WEIGHTS,"2025-01-01");municipal.totals(MunicipalSourcesTest.TOTALS,"2025-01-01",weights.id());
        var p=municipal.scenario.dataPlan.policy(WORK);p.gaps="providers";p.gapProviders=List.of(candidate("osm-pois"));
        var inputs=SourceResolver.prepare(municipal.scenario,municipal.repo,municipal.json);var zones=municipal.zones();zones.getLast().attractivities.put("Work",40.0);
        var report=WorkplaceComposition.apply(inputs,municipal.scenario.dataPlan,zones,pois(),42,"snapshot","hash");
        assertEquals(List.of(75.0,0.0,40.0),zones.stream().map(z->z.attractivities.get("Work")).toList());assertEquals(100.0,report.get("inputJobs"));assertEquals(25.0,report.get("outsideJobs"));assertEquals(75.0,report.get("appliedJobs"));
    }
    @Test void polygonPartialZonesReceiveWholeGapWeightsWithoutDoubleCountingRegisterMass()throws Exception{
        var area=new MunicipalAreasTest();area.directory=directory.resolve("area");area.setup();
        var shape=area.publishAreas(area.geo(area.polygon(MunicipalAreasTest.X,MunicipalAreasTest.Y,1500,1000)));var inputs=area.inputs(shape.id());
        var p=area.base.scenario.dataPlan.policy(WORK);p.gaps="providers";p.gapProviders=List.of(candidate("synthetic"));
        var zones=List.of(area.zone(0),area.zone(1),area.zone(2));for(var z:zones){z.areaKm2=1;z.col=z.index;}
        var report=WorkplaceComposition.apply(inputs,area.base.scenario.dataPlan,zones,List.of(),42,"none","");
        assertEquals(100.0,report.get("inputJobs"));assertEquals(100.0,(Double)report.get("appliedJobs")+(Double)report.get("unappliedPartialJobs")+(Double)report.get("outsideJobs"),.001);
        var gap=(Map<String,Object>)report.get("gapResolution");assertEquals(2,((List<?>)gap.get("zoneWeights")).size());assertEquals(100*2.0/3,zones.getFirst().attractivities.get("Work"),.001);
        assertTrue((Double)report.get("unappliedPartialJobs")>0);assertEquals(zones.get(1).attractivities.get("Work")+zones.get(2).attractivities.get("Work"),(Double)gap.get("sumWeights"),.001);
    }
    @Test void mixedPackAndNativeCandidatesUseSameSelectionAndFrozenPayload()throws Exception{
        var fixture=new SourceProfilesAndPacksTest();fixture.directory=directory.resolve("pack");fixture.setup();var pack=fixture.install(false,false,"fixture",12);
        var profile=fixture.save(List.of(candidate("builtin:osm-pois"),candidate(fixture.provider(pack)),candidate("builtin:synthetic")),"none");fixture.select(profile);fixture.scenario.dataPlan.policy(WORK).gaps="none";
        var inputs=SourceResolver.prepare(fixture.scenario,fixture.repo,fixture.json);
        Files.delete(fixture.repo.uploadsDir(fixture.scenario.id).resolve("data-imports/revisions/"+pack.sources().getFirst().revision()+".json"));
        var zones=zones().subList(0,2);var report=WorkplaceComposition.apply(inputs,fixture.scenario.dataPlan,zones,List.of(),42,"none","");assertEquals(12.0,report.get("appliedJobs"));
        var statuses=((List<Map<String,Object>>)report.get("candidates")).stream().map(c->c.get("status")).toList();assertEquals(List.of("not-applicable","selected","not-evaluated"),statuses);
        zones.get(0).attractivities.put("Work",48.0);report=WorkplaceComposition.apply(inputs,fixture.scenario.dataPlan,zones,pois(),42,"snapshot","hash");assertEquals("osm-pois",report.get("provider"));
    }
    @Test void completePipelineUsesMixedPopulationAndRegisterGapsAndPersistsFrozenInputs()throws Exception{
        var population=base.publish("population-grid-v1","id;lon;lat;population\np;8.21;48.86;80\n");
        select(POP,profile(POP,List.of(candidate("builtin:census-2022"),candidate("revision:"+population.id()),candidate("builtin:uniform"))));
        var work=partial();var p=select(WORK,profile(WORK,List.of(candidate("revision:"+work.id()),candidate("builtin:synthetic"))));p.gaps="providers";p.gapProviders=List.of(candidate("synthetic"));
        base.scenario.region.maxLon=8.26;base.scenario.population.totalPersons=null;base.repo.save(base.scenario);var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            var out=base.repo.artifactsDir(base.scenario.id);var report=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals(80,report.path("population").path("totalPersons").asInt());assertEquals("synthetic",report.path("workplaces").path("gapResolution").path("provider").asText());
            var lock=base.json.readTree(out.resolve("sources/lock.json").toFile());assertEquals(2,lock.path("chains").size());assertEquals(population.id(),lock.path("chains").path(POP).path("candidates").get(1).path("revision").path("id").asText());
            var bytes=Files.readAllBytes(out.resolve("demand/traffic-demand.json"));job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);assertArrayEquals(bytes,Files.readAllBytes(out.resolve("demand/traffic-demand.json")));
            p.gapProviders=List.of(candidate("osm-pois"));base.repo.save(base.scenario);job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.FAILED,job.state);assertTrue(Files.exists(out.resolve("sources/workplace-failure.json")));var failedRun=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals("FAILED",failedRun.at("/run/state").asText());assertTrue(failedRun.path("workplaces").isEmpty());assertTrue(failedRun.path("failures").has("workplace"));
        }finally{generation.shutdown();}
    }
}
