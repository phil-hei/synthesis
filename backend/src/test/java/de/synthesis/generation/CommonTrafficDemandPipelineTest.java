package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.companytour.*;
import de.synthesis.generation.demand.*;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonTrafficDemandPipelineTest {
    @TempDir Path dir;
    @Test void freightAndObservedToursExportWithoutSumoAndNeverReuseOldPersonResults() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules(); var props = new SynthesisProperties(); props.setDataDir(dir.toString());
        props.getSimulation().setEnabled(false); props.getSumo().setEnabled(false); props.getCensus().setEnabled(false); props.getOverpass().setLiveDataEnabled(false);
        var repo = new ScenarioRepository(json, props); var s = CompanyTourExecutionTest.scenario();
        s.targetModels = new ArrayList<>(List.of("logitopp")); s.vehicleOperations = VehicleOperationsConfiguration.empty();
        var f=s.vehicleConfiguration;
        s.vehicleConfiguration=new VehicleConfiguration(f.schemaVersion(),"catalogOnly",f.allowBehaviorApproximation(),List.of(),f.vehicleTypes(),f.catalogRefs(),f.trafficAssignments(),f.fleetAssignments(),f.initialVehicles());
        s.population.totalPersons=12; s.population.behaviorSource="template"; s.population.useKitPopulation=false;
        s.population.externalTrips=false; s.population.useRealRouting=false; s.zoning.mode="grid"; s.zoning.cellSizeMeters=1000;
        s.freight.enabled=true; s.freight.day=2; // Export is independent of the configured SUMO Monday.
        s.freight.relations=List.of(new FreightDemandGenerator.Relation("fixture","truck","internal",8.201,48.85,8.205,48.85,2,6,8,"synthetic","2026-10-21"));
        repo.save(s);
        var imports = new CompanyTourImportService(repo,json,new TourMappingPlanner(json,new TourImportProperties()),props);
        var uploaded=imports.upload(s.id,CompanyTourExecutionTest.bytes(CompanyTourExecutionTest.CSV),"synthetic.csv");
        imports.confirm(s.id,uploaded.importId(),uploaded.mapping()); imports.context(s.id,uploaded.importId(),CompanyTourExecutionTest.context(uploaded.draft(),"van-1"));
        assertTrue(imports.activate(s.id,uploaded.importId(),true).active(),"No SUMO selection, vehicle-operations switch or SUMO type-allocation required");
        Path artifacts=repo.artifactsDir(s.id); Files.createDirectories(artifacts.resolve("simulation/results"));
        Files.writeString(artifacts.resolve("simulation/results/demandsimulationResult.csv"),"stale-invalid-person-input");
        Files.createDirectories(artifacts.resolve("demand")); Files.writeString(artifacts.resolve("demand/person-trips.csv"),"stale export");
        var generation=new GenerationService(repo,new OverpassClient(props,json),props,json);
        try {
            var first=generation.start(s.id); EnergyInfrastructurePipelineTest.await(first);
            assertEquals(GenerationJob.State.DONE,first.state,()->String.join(" | ",first.log));
            assertEquals("SKIPPED",first.stage("SUMO (Verkehr)").status); assertEquals("SKIPPED",first.stage("Simulation (logiTopp)").status);
            var input=TrafficDemandArtifacts.read(artifacts,json); assertEquals(5,input.movements().size());
            assertEquals(2,input.freight().size()); assertEquals(3,input.companies().legs().size()); assertEquals("not-generated",input.personDemandStatus());
            assertFalse(Files.exists(artifacts.resolve("demand/person-trips.csv"))); assertFalse(Files.exists(artifacts.resolve("sumo/net.net.xml")));
            var summary=json.readTree(artifacts.resolve(TrafficDemandArtifacts.SUMMARY).toFile()); assertEquals(5,summary.path("byTrafficCategory").path("logistics").asInt());
            var manifest=json.readTree(artifacts.resolve("manifest.json").toFile()); assertEquals(5,manifest.path("trafficDemand").path("summary").path("movements").asInt());
            assertEquals(1,input.tours().size()); assertEquals(3,input.tours().getFirst().sourceTripKeys().size());
            assertEquals("van-1",input.tours().getFirst().vehicleId()); assertFalse(input.vehicles().initialVehicles().isEmpty());
            byte[] common=Files.readAllBytes(artifacts.resolve(TrafficDemandArtifacts.INPUT)), persons=Files.readAllBytes(artifacts.resolve("population/person.csv"));
            var repeat=generation.start(s.id); EnergyInfrastructurePipelineTest.await(repeat); assertEquals(GenerationJob.State.DONE,repeat.state,repeat.error);
            assertArrayEquals(common,Files.readAllBytes(artifacts.resolve(TrafficDemandArtifacts.INPUT)));
            var optional=repo.findById(s.id).orElseThrow();optional.targetModels=new ArrayList<>(List.of("logitopp","sumo"));repo.save(optional);
            var skipped=generation.start(s.id);EnergyInfrastructurePipelineTest.await(skipped);
            assertEquals(GenerationJob.State.DONE,skipped.state,skipped.error);
            assertEquals("SKIPPED",skipped.stage("SUMO (Verkehr)").status,"Disabled SUMO must not validate its mismatching day before skipping");
            assertArrayEquals(common,Files.readAllBytes(artifacts.resolve(TrafficDemandArtifacts.INPUT)));
            var disabled=repo.findById(s.id).orElseThrow();disabled.freight.enabled=false;disabled.companyTours.enabled=false;repo.save(disabled);
            var last=generation.start(s.id);EnergyInfrastructurePipelineTest.await(last);assertEquals(GenerationJob.State.DONE,last.state,last.error);
            assertEquals(0,TrafficDemandArtifacts.read(artifacts,json).movements().size()); assertArrayEquals(persons,Files.readAllBytes(artifacts.resolve("population/person.csv")));
        } finally { generation.shutdown(); }
    }
}
