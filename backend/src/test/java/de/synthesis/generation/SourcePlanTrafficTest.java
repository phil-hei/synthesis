package de.synthesis.generation;

import de.synthesis.data.*;
import de.synthesis.generation.demand.*;
import de.synthesis.generation.pipeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SourcePlanTrafficTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(longs={7,42,777})
    void sourceRevisionSwitchAndExclusionRetainCompanyDemandAndAllCoreBytes(long seed)throws Exception {
        var cases=new ArrayList<Map<String,Object>>();
        for(boolean north:List.of(false,true))try(var f=new SourcePlanTrafficFixture(directory.resolve(north?"north":"south"),north,seed,false)){
            f.run();var core=DataSourcesTest.coreArtifacts(f.out());for(var name:DataPackLibraryTest.REPLAY_FILES)assertTrue(core.containsKey(name));
            var legacy=f.demand();assertEquals(8,legacy.freight().size());assertEquals(3,legacy.companies().legs().size());assertEquals(11,legacy.movements().size());assertEquals("not-generated",legacy.personDemandStatus());byte[] common=Files.readAllBytes(f.out().resolve(TrafficDemandArtifacts.INPUT));
            f.select("dataset",f.a.id());byte[] scenario=Files.readAllBytes(f.repo.scenarioDir(f.scenario.id).resolve("scenario.json"));f.run();assertArrayEquals(scenario,Files.readAllBytes(f.repo.scenarioDir(f.scenario.id).resolve("scenario.json")));assertEquals(core,DataSourcesTest.coreArtifacts(f.out()));assertArrayEquals(common,Files.readAllBytes(f.out().resolve(TrafficDemandArtifacts.INPUT)));
            var report=f.freightReport();assertEquals(FreightSources.CONTRACT,report.path("contract").asText());assertEquals(8,report.path("generatedVehicleTrips").asInt());assertEquals(4,report.path("contributions").size());assertEquals("not-verified-by-generation-ledger",report.path("targetEvidence").path("status").asText());
            for(var row:report.path("contributions")){assertEquals(f.a.id(),row.path("source").path("revision").asText());assertTrue(row.path("sourceRow").asInt()>=2);assertEquals("unknown",row.path("evidence").asText());assertEquals(row.path("inputVehicleTrips"),row.path("generatedVehicleTrips"));}
            String profile=f.selectProfile();f.run();assertEquals(profile,f.freightReport().path("profile").asText());assertEquals(core,DataSourcesTest.coreArtifacts(f.out()));assertArrayEquals(common,Files.readAllBytes(f.out().resolve(TrafficDemandArtifacts.INPUT)));
            f.select("dataset",f.b.id());f.run();var changed=f.demand();assertEquals(9,changed.freight().size());assertEquals(12,changed.movements().size());assertEquals(legacy.companies(),changed.companies());assertEquals(core,DataSourcesTest.coreArtifacts(f.out()));assertEquals(legacy.freight().stream().filter(t->!t.relationId().equals("internal")).toList(),changed.freight().stream().filter(t->!t.relationId().equals("internal")).toList());
            assertEquals(3,f.freightReport().path("byMovement").path("internal").asInt());
            f.select("excluded","not-read");f.run();var excluded=f.demand();assertTrue(excluded.freight().isEmpty());assertEquals(3,excluded.movements().size());assertEquals(legacy.companies(),excluded.companies());assertEquals(core,DataSourcesTest.coreArtifacts(f.out()));assertEquals("excluded",f.freightReport().path("status").asText());assertEquals(0,f.freightReport().path("generatedVehicleTrips").asInt());
            var evidence=new TreeMap<String,Object>();evidence.putAll(Map.of("region",north?"translated-north":"south","seed",seed,"legacyAndRevisionATrips",11,"revisionBTrips",12,"excludedHeavyTrips",3,"companyLegs",3,"coreFilesCompared",core.size(),"byteEqualLegacyAndRevisionA",true,"companyIdentityUnchanged",true,"privateDemandStatus","not-generated"));
            var hashes=new TreeMap<String,String>();for(var file:core.entrySet())hashes.put(file.getKey(),de.synthesis.data.importing.ImportSupport.hash(Base64.getDecoder().decode(file.getValue())));evidence.put("coreArtifactHashes",hashes);evidence.put("revisionAResolution",report);evidence.put("companyImportIdentities",f.company.imports().stream().map(c->Map.of("importId",c.importId(),"mappingHash",c.mappingHash(),"executionHash",c.executionHash())).toList());cases.add(evidence);
        }
        Path proof=Path.of("target/source-plan-traffic/export-"+seed+".json");Files.createDirectories(proof.getParent());new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(proof.toFile(),cases);
    }
    @Test void explicitDemonstrationIsAssumedInBothSourceReportsAndLegacyFreightExports()throws Exception {
        try(var f=new SourcePlanTrafficFixture(directory,false,42,false)){
            var s=f.repo.findById(f.scenario.id).orElseThrow();s.dataPlan.enabled=true;var p=new SourcePlan.Policy();p.source="model";p.internalTrips=2;p.demonstrationAccepted=true;p.assumptionStatement="Künstlicher Vertragsfall mit angenommenen Orten und Abfahrten";s.dataPlan.aspects.put("freight.vehicleOd",p);f.repo.save(s);f.run();
            var r=f.freightReport();assertEquals("assumed",r.path("contributions").get(0).path("evidence").asText());assertEquals("assumed",r.path("departureProfile").path("evidence").asText());assertEquals("not-approved",r.path("scientificUse").asText());assertEquals(2,r.path("generatedVehicleTrips").asInt());assertEquals("assumed",f.json.readTree(f.out().resolve("freight/sources.json").toFile()).path("quality").asText());assertEquals("assumed",f.json.readTree(f.out().resolve("freight/summary.json").toFile()).path("sourceQuality").asText());assertEquals("assumed",f.json.readTree(f.out().resolve("manifest.json").toFile()).path("dataQuality").path("heavyFreight").asText());assertEquals(3,f.demand().companies().legs().size());
            var inputs=SourceResolver.prepare(f.repo.findById(s.id).orElseThrow(),f.repo,f.json);var resolved=f.repo.findById(s.id).orElseThrow();SourceResolver.prepare(resolved,f.repo,f.json);assertThrows(IllegalArgumentException.class,()->FreightSources.report(inputs,resolved,List.of()));
        }
    }
    @Test void selectedRevisionAndRelationsStayFrozenAfterQueuedSourceAndPolicyChanges()throws Exception {
        try(var f=new SourcePlanTrafficFixture(directory,false,42,false)){
            f.select("dataset",f.a.id());f.props.getOverpass().setLiveDataEnabled(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var overpass=new OverpassClient(f.props,f.json){@Override public List<Poi> fetchPois(de.synthesis.scenario.Scenario.Region r)throws Exception{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test release missing");return List.of();}@Override public List<Place> fetchPlaces(de.synthesis.scenario.Scenario.Region r){return List.of();}@Override public List<Place> fetchPlaces(de.synthesis.scenario.Scenario.Region r,boolean expanded){return List.of();}};
            var service=new GenerationService(f.repo,overpass,f.props,f.json);
            try{var job=service.start(f.scenario.id);assertTrue(entered.await(10,TimeUnit.SECONDS));f.select("dataset",f.b.id());Files.writeString(f.repo.uploadsDir(f.scenario.id).resolve("data-imports/revisions/"+f.a.id()+".json"),"corrupt after queue-time binding");release.countDown();DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);assertEquals(8,f.demand().freight().size());assertEquals(f.a.id(),f.freightReport().path("contributions").get(0).path("source").path("revision").asText());assertEquals(f.b.id(),f.repo.findById(f.scenario.id).orElseThrow().dataPlan.policy("freight.vehicleOd").revision);}
            finally{release.countDown();service.shutdown();}
        }
    }
}
