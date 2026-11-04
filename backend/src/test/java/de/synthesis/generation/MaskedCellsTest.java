package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

class MaskedCellsTest {
    @TempDir Path directory;DataSourcesTest base;
    static final long X=4200000,Y=2900000;
    static final String CSV="id;easting;northing;resolution;state;value\na;4200000;2900000;1000;value;30\nzero;4201000;2900000;1000;value;0\nmissing;4202000;2900000;1000;missing;NA\nsecret;4203000;2900000;1000;suppressed;-1\nnot-surveyed;4204000;2900000;1000;not-collected;\n";
    @BeforeEach void setup(){base=new DataSourcesTest();base.dir=directory;base.setup();}
    Metadata meta(String schema){return new Metadata("Künstliches Zellraster","Synthetische Zellwerte mit expliziten Masken","2026-01-01",schema.equals(MaskedCells.WORK)?"jobs":"persons",DataSourcesTest.box(5,45,16,56),"unknown","Testfixture");}
    DataImportService.RevisionInfo publish(String schema,String csv)throws Exception{var d=base.upload(schema,csv,"cells.csv");return base.service.publish(base.scenario.id,d.id(),new DataImportService.Request(base.service.suggest(d),meta(schema)));}
    SourcePlan.Policy select(String schema,String id){String aspect=schema.equals(MaskedCells.WORK)?"opportunities.work":"population.distribution";var p=new SourcePlan.Policy();p.source="dataset";p.revision=id;p.gaps="providers";p.gapProviders=List.of(new SourceProfiles.Candidate(schema.equals(MaskedCells.WORK)?"synthetic":"uniform",false,"next"));base.scenario.dataPlan.enabled=true;base.scenario.dataPlan.aspects.put(aspect,p);return p;}
    List<Zone> zones(int count){var list=new ArrayList<Zone>();for(int i=0;i<count;i++){var z=DataSourcesTest.workZone(i,100001+i,8,9);z.laeaE=X+i*1000;z.laeaN=Y;z.laeaRes=1000;z.areaKm2=1;z.col=i;var ll=Epsg3035.toLatLon(z.laeaE+500,z.laeaN+500);z.centroidLat=ll[0];z.centroidLon=ll[1];list.add(z);}return list;}
    DataImportService.Revision read(String id)throws Exception{return DataImportService.readRevision(base.repo,base.json,base.scenario.id,id);}
    List<PopulationSources.Provider> providers(List<Zone> zones,List<OverpassClient.Poi> pois){return PopulationSources.builtins(base.scenario,zones,pois,List.of(),new PopulationSources.Info("snapshot","OSM","test","","","hash","ODbL",""),directory.resolve("absent"),null,directory.resolve("retained"),new CensusPopulationSource(base.properties.getCensus()),directory.resolve("census"),s->{});}
    @Test void normalizationPreservesZeroAndDistinctMasksAndRawMarkers()throws Exception{
        var revision=publish(MaskedCells.WORK,CSV);var data=read(revision.id()).data();assertEquals(0.0,data.rows().get(1).values().get("value"));
        for(var row:data.rows().subList(2,5))assertFalse(row.values().containsKey("value"));
        assertEquals("NA",data.rows().get(2).values().get("rawValue"));assertEquals(-1.0,data.rows().get(3).values().get("rawValue"));assertEquals("not-collected",data.rows().get(4).values().get("state"));
        var changed=publish(MaskedCells.WORK,CSV.replace(";suppressed;-1",";missing;-1"));assertNotEquals(revision.id(),changed.id());assertEquals(base.json.valueToTree(data),base.json.valueToTree(read(revision.id()).data()));
    }
    @Test void malformedQuantitiesContradictionsAndOverlappingCellsCannotPublish()throws Exception{
        for(String tail:List.of("value;", "value;NA", "value;-1", "value;NaN", "suppressed;0", "missing;12", "unknown;")){
            var draft=base.upload(MaskedCells.WORK,"id;easting;northing;resolution;state;value\nx;4200000;2900000;1000;"+tail+"\n","invalid.csv");var request=new DataImportService.Request(base.service.suggest(draft),meta(MaskedCells.WORK));assertEquals("invalid",base.service.preview(base.scenario.id,draft.id(),request).status());assertThrows(IllegalArgumentException.class,()->base.service.publish(base.scenario.id,draft.id(),request));
        }
        var draft=base.upload(MaskedCells.WORK,"id;easting;northing;resolution;state;value\nx;4200000;2900000;1000;value;1\ny;4200500;2900000;1000;missing;NA\n","overlap.csv");assertEquals("invalid",base.service.preview(base.scenario.id,draft.id(),new DataImportService.Request(base.service.suggest(draft),meta(MaskedCells.WORK))).status());
    }
    @Test void masksNeverCreateZeroCoverageOrRenormalizeSourceCells()throws Exception{
        var revision=publish(MaskedCells.WORK,CSV);var zones=zones(5);var report=MaskedCells.allocate(read(revision.id()),zones);
        assertEquals(Set.of(0,1),report.covered());assertEquals(3,report.gaps().size());assertEquals(30.0,report.report().get("knownInput"));assertEquals(30.0,report.report().get("knownApplied"));assertEquals(1,report.report().get("missingCells"));assertEquals(1,report.report().get("suppressedCells"));assertEquals(1,report.report().get("notCollectedCells"));
        var half=zones(1).getFirst();half.laeaRes=500;var partial=MaskedCells.allocate(read(revision.id()),List.of(half));assertEquals(7.5,partial.report().get("knownApplied"));assertEquals(22.5,partial.report().get("knownOutside"));
    }
    @Test void workGapModelsOnlyReplaceUnknownZonesAndFailureIsAtomic()throws Exception{
        var revision=publish(MaskedCells.WORK,CSV);var policy=select(MaskedCells.WORK,revision.id());var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones(5);
        policy.gapProviders=List.of(new SourceProfiles.Candidate("osm-pois",false,"next"));assertThrows(ProviderChain.Failure.class,()->WorkplaceComposition.apply(inputs,base.scenario.dataPlan,zones,List.of(),42,"none",""));assertEquals(List.of(60.0,60.0,60.0,60.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList());
        policy.gapProviders=List.of(new SourceProfiles.Candidate("synthetic",false,"next"));var report=WorkplaceComposition.apply(inputs,base.scenario.dataPlan,zones,List.of(),42,"none","");assertEquals(30.0,zones.getFirst().attractivities.get("Work"));assertEquals(0.0,zones.get(1).attractivities.get("Work"));for(var z:zones.subList(2,5))assertTrue(z.attractivities.get("Work")>0);
        assertEquals(30.0,report.get("appliedJobs"));assertEquals("known-source-cells-only",report.get("quantityBasis"));var graph=(SourceProvenance)report.get("provenance");assertEquals(3,graph.nodes().stream().filter(n->n.id().equals("gap/weights")).findFirst().orElseThrow().scope().zones().size());
    }
    @Test void regionalRegistersCanFillMasksButNotOverwriteKnownZeroCells()throws Exception{
        var cells=publish(MaskedCells.WORK,CSV);var other=publish(MaskedCells.WORK,CSV.replace(";missing;NA",";value;5").replace(";suppressed;-1",";value;7").replace(";not-collected;",";value;9").replace(";value;0",";value;99"));
        var profile=new SourceProfiles(base.repo,base.json).save(base.scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Raster regional",Map.of("opportunities.work",new SourceProfiles.Chain(List.of(new SourceProfiles.Candidate("revision:"+cells.id(),false,"next"),new SourceProfiles.Candidate("revision:"+other.id(),false,"next")),"none","fill-gaps"))));
        var p=select(MaskedCells.WORK,cells.id());p.source="profile";p.profile=profile.id();p.gaps="none";var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);var zones=zones(5);var report=WorkplaceComposition.apply(inputs,base.scenario.dataPlan,zones,List.of(),42,"none","");
        assertEquals(List.of(30.0,0.0,5.0,7.0,9.0),zones.stream().map(z->z.attractivities.get("Work")).toList());assertEquals("not-required",((Map<?,?>)report.get("gapResolution")).get("status"));
    }
    @Test void populationRequiresConsistentTotalAndPreservesKnownCountsAndZeros()throws Exception{
        var revision=publish(MaskedCells.POP,CSV);select(MaskedCells.POP,revision.id());var zones=zones(5);base.scenario.population.totalPersons=null;
        assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of())));
        base.scenario.population.totalPersons=20;assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of())));
        base.scenario.population.totalPersons=60;var result=PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of()));assertEquals(30,result.value().weights()[0],1e-9);assertEquals(0,result.value().weights()[1]);assertEquals(60,Arrays.stream(result.value().weights()).sum(),1e-8);
        var generator=new PopulationGenerator(zones,new Random(42),42);generator.distributePopulation(60,null,result.value().weights(),false);assertEquals(30,zones.getFirst().population,1e-9);assertEquals(0,zones.get(1).population);assertEquals(60,zones.stream().mapToDouble(z->z.population).sum(),1e-8);
        var repeat=PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of()));assertArrayEquals(result.value().weights(),repeat.value().weights());assertEquals(result.value().diagnostics(),repeat.value().diagnostics());
    }
    @Test void zeroResidualIsAssumedAndOutsideMasksDoNotForceRegionalFilling()throws Exception{
        var revision=publish(MaskedCells.POP,CSV);select(MaskedCells.POP,revision.id());base.scenario.population.totalPersons=30;var zones=zones(5);var value=PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of())).value();assertEquals("not-required-zero-residual",((Map<?,?>)value.diagnostics().get("gapResolution")).get("status"));
        base.scenario.population.totalPersons=null;var known=zones(2);var full=PopulationSources.resolveRevision(read(revision.id()),base.scenario,known,providers(known,List.of())).value();assertEquals(30,full.estimatedTotal());assertEquals("not-required",((Map<?,?>)full.diagnostics().get("gapResolution")).get("status"));
    }
    @Test void partialZonesRetainUnappliedKnownQuantityAndFullGapScope()throws Exception{
        var revision=publish(MaskedCells.WORK,CSV);var policy=select(MaskedCells.WORK,revision.id());var z=zones(1).getFirst();z.laeaE=X+500;z.laeaRes=3000;z.areaKm2=9;
        var input=SourceResolver.prepare(base.scenario,base.repo,base.json);var report=WorkplaceComposition.apply(input,base.scenario.dataPlan,List.of(z),List.of(),42,"none","");assertEquals(0.0,report.get("appliedJobs"));assertEquals(15.0,(Double)report.get("unappliedPartialJobs"),1e-8);assertEquals(15.0,(Double)report.get("outsideJobs"),1e-8);assertTrue(z.attractivities.get("Work")>0);
    }
    @Test void allUnknownCellsCanUseAnExplicitManualAnchorWithoutInventingObservedZeros()throws Exception{
        var csv=CSV.replace(";value;30",";missing;NA").replace(";value;0",";not-collected;");var revision=publish(MaskedCells.POP,csv);select(MaskedCells.POP,revision.id());base.scenario.population.totalPersons=60;var zones=zones(5);
        var result=PopulationSources.resolveRevision(read(revision.id()),base.scenario,zones,providers(zones,List.of()));assertEquals(60,Arrays.stream(result.value().weights()).sum(),1e-8);assertTrue(Arrays.stream(result.value().weights()).allMatch(w->w>0));
        assertEquals(0,((Map<?,?>)result.value().diagnostics().get("maskedCells")).get("coveredZones"));var graph=(SourceProvenance)result.value().diagnostics().get("distributionProvenance");assertTrue(graph.nodes().stream().filter(n->n.id().equals("mask-fill")).allMatch(n->n.evidence().equals("assumed")));
    }
    @Test void fullOfflinePipelineUsesFrozenCellsAndRepeatsDemandBytes()throws Exception{
        var work=publish(MaskedCells.WORK,CSV);select(MaskedCells.WORK,work.id());var pop=publish(MaskedCells.POP,CSV);select(MaskedCells.POP,pop.id());base.scenario.population.totalPersons=60;
        var ll=Epsg3035.toLatLon(X+2500,Y+500);base.scenario.region=DataSourcesTest.box(ll[1]-.04,ll[0]-.005,ll[1]+.04,ll[0]+.005);base.scenario.zoning.mode="epsg3035";base.scenario.zoning.cellSizeMeters=1000;base.repo.save(base.scenario);
        var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);var out=base.repo.artifactsDir(base.scenario.id);var report=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals(60,report.path("population").path("totalPersons").asInt());assertTrue(report.path("population").path("diagnostics").path("maskedCells").path("fallbackZones").asInt()>0);assertEquals(1,report.path("workplaces").path("suppressedCells").asInt());
            var before=Files.readAllBytes(out.resolve("demand/traffic-demand.json"));job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);assertArrayEquals(before,Files.readAllBytes(out.resolve("demand/traffic-demand.json")));
        }finally{generation.shutdown();}
    }
}
