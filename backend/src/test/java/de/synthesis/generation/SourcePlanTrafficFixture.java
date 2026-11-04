package de.synthesis.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.companytour.*;
import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.generation.demand.*;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import de.synthesis.vehicle.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

final class SourcePlanTrafficFixture implements AutoCloseable {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SynthesisProperties props=new SynthesisProperties();final ScenarioRepository repo;
    final Scenario scenario;final DataImportService imports;final GenerationService generation;final boolean north;
    final DataImportService.RevisionInfo a,b;final CompanyTourExecution.Batch company;
    SourcePlanTrafficFixture(Path root,boolean north,long seed,boolean sumo)throws Exception {
        this.north=north;props.setDataDir(root.toString());props.getSimulation().setEnabled(false);props.getSumo().setEnabled(sumo);props.getSumo().setDay(0);props.getSumo().setMaxTrips(-1);props.getCensus().setEnabled(false);props.getOverpass().setLiveDataEnabled(false);props.getTransit().setEnabled(false);
        props.getOverpass().setEndpoint("http://127.0.0.1:1/disabled-test-network");props.getOverpass().setMirrors(List.of());
        repo=new ScenarioRepository(json,props);scenario=CompanyTourExecutionTest.scenario();scenario.referenceYear=2026;
        scenario.region=north?DataSourcesTest.box(7.4,51.498,7.406,51.502):DataSourcesTest.box(8.2,48.848,8.206,48.852);
        scenario.targetModels=new ArrayList<>(sumo?List.of("logitopp","sumo"):List.of("logitopp"));
        scenario.vehicleOperations=VehicleOperationsConfiguration.empty();var f=scenario.vehicleConfiguration;
        scenario.vehicleConfiguration=new VehicleConfiguration(f.schemaVersion(),"catalogOnly",f.allowBehaviorApproximation(),List.of(),f.vehicleTypes(),f.catalogRefs(),f.trafficAssignments(),f.fleetAssignments(),f.initialVehicles());
        scenario.population.totalPersons=12;scenario.population.seed=seed;scenario.population.behaviorSource="template";scenario.population.useKitPopulation=false;scenario.population.externalTrips=false;scenario.population.useRealRouting=false;scenario.zoning.mode="grid";scenario.zoning.cellSizeMeters=1000;scenario.freight.day=0;
        scenario.freight.enabled=true;scenario.freight.relations=FreightDemandGenerator.parseCsv(nativeCsv());repo.save(scenario);
        Files.createDirectories(repo.uploadsDir(scenario.id));Files.writeString(repo.uploadsDir(scenario.id).resolve(RoadNetworkFile.NAME),resource("network.osm"));
        var companies=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,new TourImportProperties()),props);
        var upload=companies.upload(scenario.id,resource("company.csv").getBytes(StandardCharsets.UTF_8),"company.csv");companies.confirm(scenario.id,upload.importId(),upload.mapping());
        var c=CompanyTourExecutionTest.context(upload.draft(),"van-1");
        if(north){var tour=c.tours().getFirst();var points=new TreeMap<String,CompanyTourExecution.Point>();c.locations().forEach((id,p)->points.put(id,translated(p)));
            c=new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,true,c.windowPolicy(),List.of(new CompanyTourExecution.TourPlan(tour.tourId(),true,tour.vehicleId(),tour.startTime(),tour.returnTime(),translated(tour.start()),translated(tour.end()),tour.stopOrder())),points);}
        companies.context(scenario.id,upload.importId(),c);assertTrue(companies.activate(scenario.id,upload.importId(),true).active());
        company=CompanyTourImportService.loadForGeneration(repo,json,repo.findById(scenario.id).orElseThrow());assertEquals(3,company.legs().size());
        imports=new DataImportService(repo,json,ImportRegistry.builtins());a=publish("freight-a.csv");b=publish("freight-b.csv");generation=new GenerationService(repo,new OverpassClient(props,json),props,json);
    }
    static CompanyTourExecution.Point translated(CompanyTourExecution.Point p){return new CompanyTourExecution.Point(Math.round((p.lon()-.8)*1e6)/1e6,51.5,p.provenance());}
    String resource(String name)throws Exception {
        String variant=north&&!name.equals("company.csv")?"north/":"";
        try(var in=getClass().getResourceAsStream("/source-plan-traffic/"+variant+name)){return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
    }
    byte[] nativeCsv()throws Exception {
        String h=FreightDemandGenerator.HEADER+"\n";StringBuilder rows=new StringBuilder();for(String line:resource("freight-a.csv").split("\\R")){if(line.startsWith("id;")||line.isBlank())continue;String[] c=line.split(";");rows.append(String.join(";",c[0],c[6],c[7],c[1],c[2],c[3],c[4],c[5],c[8],c[9],"Synthetic migration fixture","2026-10-19")).append('\n');}return (h+rows).getBytes(StandardCharsets.UTF_8);
    }
    DataImportService.RevisionInfo publish(String filename)throws Exception {
        var d=imports.upload(scenario.id,"vehicle-od-v1",resource(filename).getBytes(StandardCharsets.UTF_8),filename);
        var m=new Metadata("Künstliche Schwerlastquelle "+filename,"Synthetischer Migrationsvertrag, keine regionale Beobachtung","2026-10-19","vehicle-trips/day",scenario.region,"unknown","Testfixture");
        return imports.publish(scenario.id,d.id(),new DataImportService.Request(imports.suggest(d),m));
    }
    void select(String source,String revision)throws Exception {
        var s=repo.findById(scenario.id).orElseThrow();s.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source=source;policy.revision=revision;s.dataPlan.aspects.put("freight.vehicleOd",policy);repo.save(s);
    }
    String selectProfile()throws Exception {
        var profile=new SourceProfiles(repo,json).save(scenario.id,new SourceProfiles.Definition(SourceProfiles.CONTRACT,"Schwerlast-Migrationsprofil",Map.of("freight.vehicleOd",new SourceProfiles.Chain(List.of(new SourceProfiles.Candidate("revision:"+a.id(),false,"fail"),new SourceProfiles.Candidate("revision:"+b.id(),false,"next")),"none"))));
        var s=repo.findById(scenario.id).orElseThrow();s.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="profile";policy.profile=profile.id();s.dataPlan.aspects.put("freight.vehicleOd",policy);repo.save(s);return profile.id();
    }
    GenerationJob run()throws Exception {var job=generation.start(scenario.id);long end=System.nanoTime()+120_000_000_000L;while(job.finishedAt==null){assertTrue(System.nanoTime()<end,"Migration pipeline timed out");Thread.sleep(10);}assertEquals(GenerationJob.State.DONE,job.state,job.error);return job;}
    Path out(){return repo.artifactsDir(scenario.id);}
    TrafficDemand demand()throws Exception{return TrafficDemandArtifacts.read(out(),json);}
    com.fasterxml.jackson.databind.JsonNode freightReport()throws Exception{return json.readTree(out().resolve("sources/resolution.json").toFile()).path("freight");}
    @Override public void close(){generation.shutdown();}
}
