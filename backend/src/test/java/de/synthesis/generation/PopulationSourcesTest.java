package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.data.importing.ImportSupport;
import de.synthesis.generation.pipeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PopulationSourcesTest {
    @TempDir Path directory;
    DataSourcesTest base;
    @BeforeEach void setup()throws Exception{base=new DataSourcesTest();base.dir=directory;base.setup();Files.createDirectories(base.repo.uploadsDir(base.scenario.id));}
    static SourceProfiles.Candidate candidate(String id){return new SourceProfiles.Candidate(id,false,"next");}
    SourcePlan.Policy policy(String... ids){
        base.scenario.dataPlan.enabled=true;
        var p=new SourcePlan.Policy();p.source="builtins";p.providers=new ArrayList<>(Arrays.stream(ids).map(PopulationSourcesTest::candidate).toList());
        base.scenario.dataPlan.aspects.put("population.distribution",p);return p;
    }
    static PopulationSources.Info info(){return new PopulationSources.Info("test","Test","v1","2026-01-01","","","unknown","");}
    static PopulationSources.Value value(){return new PopulationSources.Value("uniform-noise",info(),10,"density-estimate",null,true,Map.of());}
    static PopulationSources.Provider provider(String id,PopulationSources.Loader load){return new PopulationSources.Provider(id,load);}

    @Test void selectionIsLazyAndExclusionsCannotBeReachedViaFallback(){
        AtomicInteger reads=new AtomicInteger();
        var candidates=List.of(new SourceProfiles.Candidate("upload-grid",true,"fail"),candidate("osm-places"),candidate("uniform"));
        var resolved=PopulationSources.resolve(candidates,List.of(
            provider("upload-grid",()->{fail("Excluded input must not be opened");return null;}),
            provider("osm-places",()->{reads.incrementAndGet();return PopulationSources.Attempt.available(value());}),
            provider("uniform",()->{fail("Later input must not be opened");return null;})));
        assertEquals(1,reads.get());assertEquals(List.of("excluded","selected","not-evaluated"),resolved.candidates().stream().map(c->c.get("status")).toList());
    }
    @Test void unavailableCanAdvanceButCorruptAndInterruptedInputsCannot(){
        var next=List.of(candidate("upload-grid"),candidate("uniform"));
        var fallback=provider("uniform",()->PopulationSources.Attempt.available(value()));
        var resolved=PopulationSources.resolve(next,List.of(provider("upload-grid",()->{throw new IOException("missing");}),fallback));
        assertEquals("unavailable",resolved.candidates().getFirst().get("status"));
        var fail=assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(List.of(new SourceProfiles.Candidate("upload-grid",false,"fail"),candidate("uniform")),List.of(provider("upload-grid",()->{throw new IOException("offline");}),fallback)));
        assertEquals(1,fail.candidates().size());
        var invalid=assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(next,List.of(provider("upload-grid",()->{throw new CensusPopulationSource.InvalidSourceException("hash mismatch");}),fallback)));
        assertEquals("invalid",invalid.candidates().getFirst().get("status"));
        try{
            assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(next,List.of(provider("upload-grid",()->{throw new InterruptedException();}),fallback)));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
        assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(List.of(candidate("upload-grid")),List.of(provider("upload-grid",()->PopulationSources.Attempt.missing("not-applicable","outside")))));
    }
    @Test void validatesBeforeQueueAndPreflightDoesNotPretendToResolveSpatialData()throws Exception{
        var p=policy("uniform");
        var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);
        var d=inputs.decisions().stream().filter(c->c.get("aspect").equals("population.distribution")).findFirst().orElseThrow();
        assertEquals("pending-resolution",d.get("status"));
        p.providers.add(candidate("uniform"));assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        p.providers=List.of(new SourceProfiles.Candidate("uniform",true,"next"));assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        p.providers=List.of(candidate("unknown"));assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(base.scenario,base.repo,base.json));
        p.providers=List.of(candidate("uniform"));base.scenario.dataPlan.aspects.put("opportunities.work",p);
        base.repo.save(base.scenario);var service=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.start(base.scenario.id));assertTrue(service.status(base.scenario.id).isEmpty());}finally{service.shutdown();}
    }
    @ParameterizedTest @ValueSource(strings={"uniform","poi-density","osm-places","upload-grid","census-2022"})
    void defaultNativeOrderPreservesCoreBytesAcrossAllFiveBranches(String branch)throws Exception{
        if(branch.equals("poi-density"))snapshot(false);
        if(branch.equals("osm-places"))snapshot(true);
        if(branch.equals("upload-grid"))Files.writeString(base.repo.uploadsDir(base.scenario.id).resolve("population-grid.csv"),"lat;lon;population\n48.86;8.21;80\n48.87;8.22;0\n");
        if(branch.equals("census-2022"))census();
        base.scenario.population.totalPersons=null;base.repo.save(base.scenario);
        var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{
            var old=generation.start(base.scenario.id);DataSourcesTest.await(old);assertEquals(GenerationJob.State.DONE,old.state,old.error);
            var baseline=DataSourcesTest.coreArtifacts(base.repo.artifactsDir(base.scenario.id));
            for(var name:DataPackLibraryTest.REPLAY_FILES){
                assertTrue(baseline.containsKey(name),"Required compatibility artifact: "+name);
                assertTrue(new String(Base64.getDecoder().decode(baseline.get(name)),java.nio.charset.StandardCharsets.UTF_8).lines().count()>1,"Comparison must contain data: "+name);
            }

            var demand=Files.readAllBytes(base.repo.artifactsDir(base.scenario.id).resolve("demand/traffic-demand.json"));
            base.scenario.dataPlan.enabled=true;base.repo.save(base.scenario);
            var legacy=generation.start(base.scenario.id);DataSourcesTest.await(legacy);assertEquals(GenerationJob.State.DONE,legacy.state,legacy.error);
            assertEquals(baseline,DataSourcesTest.coreArtifacts(base.repo.artifactsDir(base.scenario.id)),"Reporting-only legacy: "+branch);
            assertArrayEquals(demand,Files.readAllBytes(base.repo.artifactsDir(base.scenario.id).resolve("demand/traffic-demand.json")));
            policy(PopulationSources.IDS.toArray(String[]::new));base.repo.save(base.scenario);
            var changed=generation.start(base.scenario.id);DataSourcesTest.await(changed);assertEquals(GenerationJob.State.DONE,changed.state,changed.error);
            assertEquals(baseline,DataSourcesTest.coreArtifacts(base.repo.artifactsDir(base.scenario.id)),branch);
            assertArrayEquals(demand,Files.readAllBytes(base.repo.artifactsDir(base.scenario.id).resolve("demand/traffic-demand.json")),branch);
            var r=base.json.readTree(base.repo.artifactsDir(base.scenario.id).resolve("sources/resolution.json").toFile()).path("population");
            assertEquals(branch.equals("uniform")?"uniform-noise":branch,r.path("distributionSource").asText());
            assertEquals(r.path("totalPersons").asInt(),r.path("allocatedPersons").asInt());
            assertEquals(SourceProvenance.CONTRACT,r.path("distributionProvenance").path("contract").asText());
            assertTrue(r.path("candidates").findValuesAsText("status").contains("selected"));
            assertTrue(Files.exists(base.repo.artifactsDir(base.scenario.id).resolve("sources/population-grid.csv"))==branch.equals("upload-grid"));
            var hashes=new TreeMap<String,String>();for(var artifact:baseline.entrySet())hashes.put(artifact.getKey(),ImportSupport.hash(Base64.getDecoder().decode(artifact.getValue())));
            var fixtures=new TreeMap<String,String>();for(var file:List.of(base.repo.uploadsDir(base.scenario.id).resolve(OsmDataSnapshot.NAME),base.repo.uploadsDir(base.scenario.id).resolve("population-grid.csv"),directory.resolve("census-cache/fixture-2022/Zensus2022_Bevoelkerungszahl.zip")))if(Files.isRegularFile(file))fixtures.put(file.getFileName().toString(),CensusPopulationSource.sha256(file));
            Path proof=Path.of("target/source-compatibility/population-"+branch+".json");Files.createDirectories(proof.getParent());
            base.json.writerWithDefaultPrettyPrinter().writeValue(proof.toFile(),Map.of("branch",branch,"passed",true,"seed",base.scenario.population.seed,"fixtureHashes",fixtures,"populatedMinimum",DataPackLibraryTest.REPLAY_FILES,"artifactHashes",hashes,"comparedModes",List.of("plan-disabled","explicit-legacy","standard-builtins"),"baselineScope","same current implementation; not isolated pre-refactor code"));

        }finally{generation.shutdown();}
    }
    @Test void uploadHashIsFrozenAndMalformedValuesCannotSilentlyReachDensity()throws Exception{
        var upload=base.repo.uploadsDir(base.scenario.id).resolve("population-grid.csv");
        Files.writeString(upload,"lat;lon;population\n48.86;8.21;80\n48.87;8.22;0\n");policy("upload-grid","uniform");
        var inputs=SourceResolver.prepare(base.scenario,base.repo,base.json);
        assertEquals(CensusPopulationSource.sha256(upload),inputs.populationUploadHash());
        Files.writeString(upload,"lat;lon;population\n48.86;8.21;90\n");
        var loaders=loaders(inputs.populationUploadHash());
        var invalid=assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(base.scenario.dataPlan.policy("population.distribution").providers,loaders));
        assertEquals("invalid",invalid.candidates().getFirst().get("status"));assertFalse(Files.exists(directory.resolve("retained.csv")));
        for(String value:List.of("NaN","Infinity","-1","unknown")){
            Files.writeString(upload,"lat;lon;population\n48.86;8.21;80\n48.87;8.22;"+value+"\n");
            assertThrows(PopulationSources.ResolutionException.class,()->PopulationSources.resolve(base.scenario.dataPlan.policy("population.distribution").providers,loaders(CensusPopulationSource.sha256(upload))));
        }
        Files.delete(upload);assertEquals("missing",SourceResolver.prepare(base.scenario,base.repo,base.json).populationUploadHash());
        Files.writeString(upload,"lat;lon;population\n48.86;8.21;90\n");
        assertEquals("uniform-noise",PopulationSources.resolve(base.scenario.dataPlan.policy("population.distribution").providers,loaders("missing")).value().distribution());
    }
    @Test void manualTotalAndDistributionHaveSeparateProvenanceAndFailedRunsLeaveDiagnostics()throws Exception{
        snapshot(true);policy("uniform","osm-places");base.repo.save(base.scenario);
        var generation=new GenerationService(base.repo,new OverpassClient(base.properties,base.json),base.properties,base.json);
        try{
            var job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            Path out=base.repo.artifactsDir(base.scenario.id);
            var r=base.json.readTree(out.resolve("sources/resolution.json").toFile()).path("population");
            assertEquals("user-defined",r.path("totalSource").asText());assertEquals(50,r.path("totalPersons").asInt());assertEquals("uniform-noise",r.path("distributionSource").asText());
            assertEquals("manual-total",r.path("totalProvenance").path("nodes").get(0).path("operation").asText());
            assertEquals("not-evaluated",r.path("candidates").get(1).path("status").asText());
            policy("upload-grid");base.repo.save(base.scenario);job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.FAILED,job.state);
            var failure=base.json.readTree(out.resolve("sources/population-failure.json").toFile());assertEquals("unavailable",failure.path("candidates").get(0).path("status").asText());
            var failedRun=base.json.readTree(out.resolve("sources/resolution.json").toFile());assertEquals("FAILED",failedRun.at("/run/state").asText());assertFalse(failedRun.has("population"));assertTrue(failedRun.path("failures").has("population"));
            base.scenario.dataPlan.enabled=false;base.repo.save(base.scenario);job=generation.start(base.scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            assertFalse(Files.exists(out.resolve("sources/population-failure.json")));assertFalse(Files.exists(out.resolve("sources/population-grid.csv")));
        }finally{generation.shutdown();}
    }
    @Test void explicitCensusRejectsBothArchiveAndExtractedGridCorruption()throws Exception{
        census();var source=new CensusPopulationSource(base.properties.getCensus());var cache=directory.resolve("census-cache");
        assertTrue(source.resolveChecked(base.scenario.region,cache,s->{}).isPresent());
        var root=cache.resolve(base.properties.getCensus().getVersion());
        var csv=root.resolve("Zensus2022_Bevoelkerungszahl_100m-Gitter.csv");Files.writeString(csv,"lat;lon;population\n48.86;8.21;999\n");
        assertThrows(CensusPopulationSource.InvalidSourceException.class,()->source.resolveChecked(base.scenario.region,cache,s->{}));
        var archive=root.resolve("Zensus2022_Bevoelkerungszahl.zip");Files.writeString(archive,"corrupt");
        assertThrows(CensusPopulationSource.InvalidSourceException.class,()->source.resolveChecked(base.scenario.region,cache,s->{}));
        assertEquals("corrupt",Files.readString(archive),"Explicit failure must not overwrite retained evidence");
    }
    List<PopulationSources.Provider> loaders(String hash){
        var zones=GridZoning.generate(base.scenario.region,base.scenario.zoning.cellSizeMeters,600);
        return PopulationSources.builtins(base.scenario,zones,List.of(),List.of(),info(),base.repo.uploadsDir(base.scenario.id).resolve("population-grid.csv"),hash,directory.resolve("retained.csv"),new CensusPopulationSource(base.properties.getCensus()),directory.resolve("census-cache"),s->{});
    }
    void snapshot(boolean places)throws Exception{
        OsmDataSnapshot.write(base.repo.uploadsDir(base.scenario.id).resolve(OsmDataSnapshot.NAME),List.of(new OverpassClient.Poi(48.86,8.21,Map.of("shop","supermarket"))),places?List.of(new OverpassClient.Place("Testort","town",48.86,8.21,1200)):List.of(),base.json);
    }
    void census()throws Exception{
        var c=base.properties.getCensus();c.setEnabled(true);c.setVersion("fixture-2022");c.setGridEntry("population.csv");
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){zip.putNextEntry(new ZipEntry("population.csv"));zip.write(DataSourcesTest.bytes("lat;lon;population\n48.86;8.21;80\n"));zip.closeEntry();}
        byte[] archive=output.toByteArray();c.setArchiveSha256(ImportSupport.hash(archive));
        var path=directory.resolve("census-cache/fixture-2022");Files.createDirectories(path);Files.write(path.resolve("Zensus2022_Bevoelkerungszahl.zip"),archive);
    }
}
