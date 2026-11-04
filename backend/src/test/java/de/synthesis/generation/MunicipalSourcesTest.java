package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

class MunicipalSourcesTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    SynthesisProperties properties;ScenarioRepository repo;DataImportService imports;Scenario scenario;
    static final String TOTALS="id;ags;value\ntotal-a;01001000;100\ntotal-b;02000000;0\n";
    static final String WEIGHTS="id;ags;lon;lat;weight\na;01001000;8.21;48.86;3\nb;01001000;8.31;48.86;1\nc;02000000;8.23;48.86;2\n";
    @BeforeEach void setup(){
        properties=new SynthesisProperties();properties.setDataDir(directory.toString());properties.getSimulation().setEnabled(false);properties.getCensus().setEnabled(false);properties.getOverpass().setLiveDataEnabled(false);
        repo=new ScenarioRepository(json,properties);imports=new DataImportService(repo,json,ImportRegistry.builtins());
        scenario=new Scenario();scenario.id="municipal";scenario.region=DataSourcesTest.box(8.2,48.85,8.26,48.89);
        scenario.population.totalPersons=50;scenario.population.useKitPopulation=false;scenario.population.useRealRouting=false;
        scenario.population.externalTrips=false;scenario.population.behaviorSource="template";scenario.zoning.cellSizeMeters=1000;
        scenario.targetModels=new ArrayList<>(List.of("logitopp"));repo.save(scenario);
    }
    Metadata metadata(String schema,String edition,String allocation){
        boolean totals=schema.equals("municipal-workplaces-v1");
        return new Metadata("Künstliche Gemeindequelle","Synthetische Vertragsprüfung","2026-01-01",totals?"jobs":"relative-weight",DataSourcesTest.box(8.1,48.7,8.4,49.1),"unknown","Testfixture",
                totals?new CoverageDeclaration("municipality-aggregate","complete","Vollständige Aggregate für genau die enthaltenen Gemeindeschlüssel"):null,
                new Geography("de:AGS",edition,allocation,"explicit-point-weights-v1"));
    }
    DataImportService.RevisionInfo publish(String schema,String data,Metadata metadata)throws Exception{
        var draft=imports.upload(scenario.id,schema,data.getBytes(StandardCharsets.UTF_8),"source.csv");return imports.publish(scenario.id,draft.id(),new DataImportService.Request(imports.suggest(draft),metadata));
    }
    DataImportService.RevisionInfo weights(String data,String edition)throws Exception{return publish("municipality-allocation-v1",data,metadata("municipality-allocation-v1",edition,null));}
    DataImportService.RevisionInfo totals(String data,String edition,String allocation)throws Exception{
        var revision=publish("municipal-workplaces-v1",data,metadata("municipal-workplaces-v1",edition,allocation));
        scenario.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="dataset";policy.revision=revision.id();scenario.dataPlan.aspects.put("opportunities.work",policy);return revision;
    }
    List<Zone> zones(){return List.of(DataSourcesTest.workZone(0,100001,8.20,8.22),DataSourcesTest.workZone(1,100002,8.22,8.24),DataSourcesTest.workZone(2,100003,8.24,8.26));}
    @Test void retainsKeysAndTotalsAndAllocatesWithoutRenormalizingOutsideAmounts()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");var total=totals(TOTALS,"2025-01-01",weights.id());
        var inputs=SourceResolver.prepare(scenario,repo,json);assertEquals(1,inputs.references().size());
        assertEquals("01001000",inputs.datasets().get("opportunities.work").data().rows().getFirst().values().get("ags"));
        var zones=zones();var report=SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones);
        assertEquals(100.0,report.get("inputJobs"));assertEquals(75.0,report.get("appliedJobs"));assertEquals(25.0,report.get("outsideJobs"));assertEquals(0,report.get("unresolvedJobs"));
        assertEquals(List.of(75.0,0.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList());
        assertEquals(List.of(4.0,4.0,4.0),zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList());
        var tree=json.valueToTree(report);assertEquals(3,tree.path("rowAssignments").size());
        assertEquals("outside-model",tree.path("rowAssignments").get(1).path("status").asText());
        var provenance=(SourceProvenance)report.get("provenance");assertEquals("assumed",provenance.nodes().get(2).evidence());
        assertEquals(total.id(),provenance.nodes().get(0).source().revision());assertEquals(weights.id(),provenance.nodes().get(1).source().revision());
        assertEquals(100.0,inputs.datasets().get("opportunities.work").data().rows().getFirst().values().get("value"));
    }
    @Test void noZeroCoverageIsInferredFromMunicipalBoundingBoxAndRejectionIsAtomic()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");totals(TOTALS,"2025-01-01",weights.id());var inputs=SourceResolver.prepare(scenario,repo,json);
        scenario.dataPlan.policy("opportunities.work").gaps="none";var zones=zones();
        assertThrows(IllegalArgumentException.class,()->SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones));
        assertEquals(List.of(60.0,60.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList());
    }
    @Test void mismatchedEditionUnresolvedKeyAndWrongDependencyContractBlockBeforeQueueing()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");totals(TOTALS,"2024-01-01",weights.id());
        assertTrue(assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json)).getMessage().contains("Gebietsstand"));
        totals(TOTALS.replace("01001000","09999999"),"2025-01-01",weights.id());
        assertTrue(assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json)).getMessage().contains("09999999"));
        totals(TOTALS,"2025-01-01",scenario.dataPlan.policy("opportunities.work").revision);
        assertTrue(assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json)).getMessage().contains("Fachvertrag"));
        repo.save(scenario);var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->generation.start(scenario.id));assertTrue(generation.status(scenario.id).isEmpty());}finally{generation.shutdown();}
    }
    @Test void malformedKeysDuplicateMunicipalitiesAndNonpositiveWeightsCannotPublish()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");
        assertThrows(IllegalArgumentException.class,()->totals(TOTALS.replace("01001000","1001000"),"2025-01-01",weights.id()));
        assertThrows(IllegalArgumentException.class,()->totals(TOTALS+"duplicate;01001000;1\n","2025-01-01",weights.id()));
        var duplicate=imports.upload(scenario.id,"municipal-workplaces-v1",(TOTALS+"duplicate;01001000;1\n").getBytes(StandardCharsets.UTF_8),"duplicate.csv");
        var preview=imports.preview(scenario.id,duplicate.id(),new DataImportService.Request(imports.suggest(duplicate),metadata("municipal-workplaces-v1","2025-01-01",weights.id())));
        assertEquals("invalid",preview.status());assertEquals(1,preview.rejectedRows());assertEquals(2,preview.mappedRows());
        assertThrows(IllegalArgumentException.class,()->weights(WEIGHTS.replace(";3\n",";0\n"),"2025-01-01"));
        assertThrows(IllegalArgumentException.class,()->weights(WEIGHTS.replace(";3\n",";-1\n"),"2025-01-01"));
        assertThrows(IllegalArgumentException.class,()->weights(WEIGHTS,"unknown"));
    }
    @Test void allOutsideAllocationRetainsLegacyOnlyWhenExplicitlyAllowed()throws Exception{
        var weights=weights(WEIGHTS.replace("8.21","8.31").replace("8.23","8.33"),"2025-01-01");totals(TOTALS,"2025-01-01",weights.id());
        var inputs=SourceResolver.prepare(scenario,repo,json);var zones=zones();
        var report=SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones);
        assertEquals(0.0,report.get("appliedJobs"));assertEquals(100.0,report.get("outsideJobs"));assertEquals(3,report.get("fallbackZones"));
        assertEquals(List.of(60.0,60.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList());
        scenario.dataPlan.policy("opportunities.work").gaps="none";
        assertThrows(IllegalArgumentException.class,()->SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones));
    }
    @Test void frozenReferencesRemainUsableAfterStoredDependencyIsChanged()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");totals(TOTALS,"2025-01-01",weights.id());var inputs=SourceResolver.prepare(scenario,repo,json);
        Path file=repo.uploadsDir(scenario.id).resolve("data-imports/revisions/"+weights.id()+".json");Files.writeString(file,Files.readString(file).replace("\"weight\":3.0","\"weight\":99.0"));
        assertThrows(java.io.IOException.class,()->SourceResolver.prepare(scenario,repo,json));
        assertEquals(75.0,SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones()).get("appliedJobs"));
    }
    @Test void genericDependencyCycleIsRejected()throws Exception{
        var draft=imports.upload(scenario.id,"workplaces-v1",DataSourcesTest.CSV.getBytes(StandardCharsets.UTF_8),"points.csv");
        var metadata=new Metadata("point","synthetic","2026-01-01","jobs",metadata("municipal-workplaces-v1","2025-01-01","0".repeat(64)).coverage(),"unknown","",new CoverageDeclaration("point-register","complete","Complete synthetic point register"));
        var revision=imports.publish(scenario.id,draft.id(),new DataImportService.Request(imports.suggest(draft),metadata));
        scenario.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="dataset";policy.revision=revision.id();scenario.dataPlan.aspects.put("opportunities.work",policy);
        var delegate=ImportRegistry.builtins().require("workplaces-v1");
        var cyclic=new AspectImportAdapter(){public Schema schema(){return delegate.schema();}public List<Issue> validate(DataRow row,Metadata meta){return delegate.validate(row,meta);}public List<Dependency> dependencies(Metadata meta){return List.of(new Dependency("cycle",revision.id(),"workplaces-v1"));}};
        assertTrue(assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json,new ImportRegistry(List.of(cyclic)))).getMessage().contains("Zyklische"));
    }
    @Test void fullPipelineWritesFrozenDependencyAndMunicipalQuantityLedger()throws Exception{
        var weights=weights(WEIGHTS,"2025-01-01");var totals=totals(TOTALS,"2025-01-01",weights.id());repo.save(scenario);
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{
            var job=generation.start(scenario.id);DataSourcesTest.await(job);assertEquals(GenerationJob.State.DONE,job.state,job.error);
            var report=json.readTree(repo.artifactsDir(scenario.id).resolve("sources/resolution.json").toFile());
            assertEquals(75.0,report.path("workplaces").path("appliedJobs").asDouble());assertEquals(25.0,report.path("workplaces").path("outsideJobs").asDouble());
            var lock=json.readTree(repo.artifactsDir(scenario.id).resolve("sources/lock.json").toFile());assertTrue(lock.path("references").has(weights.id()));
            assertEquals(totals.id(),lock.path("datasets").path("opportunities.work").path("id").asText());
        }finally{generation.shutdown();}
    }
    @Test void demonstrationRequiresExplicitExplorationAndAnalysisBlocksIt()throws Exception{
        scenario.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="model";policy.internalTrips=3;scenario.dataPlan.aspects.put("freight.vehicleOd",policy);
        assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json));
        policy.demonstrationAccepted=true;assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json));
        policy.assumptionStatement="Technical test of synthetic trip generation";
        var prepared=SourceResolver.prepare(scenario,repo,json);assertEquals("not-approved",prepared.decisions().getFirst().get("scientificUse"));assertEquals(1,scenario.freight.relations.size());
        scenario.dataPlan.qualityMode="analysis";assertTrue(assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json)).getMessage().contains("fachliche"));
        policy.source="excluded";assertDoesNotThrow(()->SourceResolver.prepare(scenario,repo,json));assertFalse(scenario.freight.enabled);
    }
}
