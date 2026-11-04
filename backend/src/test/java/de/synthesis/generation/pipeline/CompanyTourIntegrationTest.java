package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.companytour.*;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.generation.demand.TrafficDemand;
import de.synthesis.generation.demand.TrafficDemandArtifacts;
import de.synthesis.generation.demand.ParcelTourAdapter;
import de.synthesis.vehicle.VehicleCatalog;
import de.synthesis.scenario.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompanyTourIntegrationTest {
    @TempDir Path dir;
    @Test void outsideStartEndAndReentryUseRegionalVisitsWithoutTruncatingSharedDemand() throws Exception {
        var scenario=NativeVehicleChainsIntegrationTest.scenario("standard.heavyTruck.diesel",List.of());scenario.id="boundary-company";
        scenario.vehicleOperations=de.synthesis.vehicle.VehicleOperationsConfiguration.empty();
        scenario.region.minLon=8.202;scenario.region.maxLon=8.204;scenario.region.minLat=48.849;scenario.region.maxLat=48.851;
        var props=new SynthesisProperties();props.setDataDir(dir.resolve("inputs").toString());var json=new ObjectMapper().findAndRegisterModules();var repo=new ScenarioRepository(json,props);repo.save(scenario);
        var service=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,new TourImportProperties()));
        var upload=service.upload(scenario.id,CompanyTourExecutionTest.bytes(CompanyTourExecutionTest.CSV),"plan.csv");service.confirm(scenario.id,upload.importId(),upload.mapping());
        var c=CompanyTourExecutionTest.context(upload.draft(),"delivery-1");
        var external=new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,true,c.windowPolicy(),c.tours(),c.locations(),true);
        assertTrue(service.context(scenario.id,upload.importId(),external).execution().ready());service.activate(scenario.id,upload.importId(),true);
        var input=CompanyTourImportService.loadForGeneration(repo,json,repo.findById(scenario.id).orElseThrow());
        var demand=new TravelDemand(List.of(EnergyOperationsIntegrationTest.trip("private",1,8.2022,8.2038)),1);
        var shared=new TrafficDemand(TrafficDemand.CONTRACT,"available",demand,0,List.of(),List.of(),input,VehicleCatalog.requireValid(scenario.vehicleConfiguration),List.of(),"available");
        TrafficDemandArtifacts.write(dir,shared,json);var snapshot=TrafficDemandArtifacts.read(dir,json);
        assertEquals(8.201,snapshot.companies().legs().getFirst().from().lon());assertEquals(8.205,snapshot.companies().legs().get(1).to().lon());assertEquals(4,snapshot.movements().size());
        var sumo=new SynthesisProperties.Sumo();sumo.setDay(0);sumo.setMaxTrips(-1);
        var runner=new SumoRunner(sumo,VehicleCatalog.requireValid(scenario.vehicleConfiguration),42).useEnergyAndOperations(scenario);
        assumeTrue(runner.isAvailable(),"Native SUMO binaries required");
        var result=runner.run(dir,EnergyOperationsIntegrationTest.OSM,snapshot,null,ignored->{});
        assertEquals(1,result.carTrips());assertEquals(3,result.companyTourLegs());assertEquals(3,result.vehicles());
        var projection=json.readTree(dir.resolve("company-tours/regional-projection.json").toFile());assertEquals("confirmed-straight-line-plan-interpolation",projection.path("method").asText());assertEquals(3,projection.path("originalLegs").asInt());
        assertEquals(0,projection.path("checks").get(1).path("regionalLeg").path("serviceSeconds").asInt(),"No customer service at the boundary exit");
        var chains=json.readTree(dir.resolve("vehicles/native-chains.json").toFile());assertEquals("complete",chains.path("status").asText());assertEquals(1,chains.path("physicalVehicles").asInt());assertEquals(2,chains.path("nativeVisits").asInt());assertTrue(chains.path("regionalVisitTimingValid").asBoolean());
        assertEquals(chains.path("vehicles").get(0).path("vehicleId"),chains.path("vehicles").get(1).path("vehicleId"));assertNotEquals(chains.path("vehicles").get(0).path("nativeVehicleId"),chains.path("vehicles").get(1).path("nativeVehicleId"));
        var report=json.readTree(dir.resolve("company-tours/execution.json").toFile());assertEquals("regional-complete",report.path("status").asText());assertEquals(0,report.path("completedTours").asInt());assertEquals(1,report.path("completedRegionalTours").asInt());assertFalse(report.path("outsideExecutionSimulated").asBoolean());
        var frozen=report.deepCopy();runner.run(dir,EnergyOperationsIntegrationTest.OSM,snapshot,null,ignored->{});assertEquals(frozen,json.readTree(dir.resolve("company-tours/execution.json").toFile()));
    }
    @Test void localGeocodingChoicesFeedSharedDemandAndNativeExecution() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",exchange->{String query=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);
            double lon=query.contains("Beispielweg 2")?8.205:query.contains("Beispielweg 1")?8.203:8.201;
            byte[] body=("[{\"lon\":\""+lon+"\",\"lat\":\"48.85\",\"display_name\":\"Synthetic fixture\",\"licence\":\"Fixture\"}]").getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            var scenario=NativeVehicleChainsIntegrationTest.scenario("standard.heavyTruck.diesel",List.of());scenario.id="geocoded-company";scenario.vehicleOperations=de.synthesis.vehicle.VehicleOperationsConfiguration.empty();
            var json=new ObjectMapper().findAndRegisterModules();var props=new SynthesisProperties();props.setDataDir(dir.resolve("inputs").toString());var repo=new ScenarioRepository(json,props);repo.save(scenario);
            var config=new TourImportProperties();config.setGeocodingEnabled(true);config.setGeocodingEndpoint("http://127.0.0.1:"+server.getAddress().getPort());var service=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,config));
            var uploaded=service.upload(scenario.id,CompanyTourExecutionTest.bytes(CompanyTourExecutionTest.CSV),"plan.csv");service.confirm(scenario.id,uploaded.importId(),uploaded.mapping());
            var complete=CompanyTourExecutionTest.context(uploaded.draft(),"delivery-1");var t=complete.tours().getFirst();var empty=new CompanyTourExecution.Point(null,null,"unresolved");
            var pending=new CompanyTourExecution.Context(complete.companyId(),complete.simulationDate(),false,true,true,false,complete.windowPolicy(),List.of(new CompanyTourExecution.TourPlan(t.tourId(),true,t.vehicleId(),t.startTime(),t.returnTime(),empty,empty,t.stopOrder())),java.util.Map.of(),false,false,java.util.Map.of(t.tourId(),new CompanyTourExecution.DepotAddresses("Fixture depot","Fixture depot")));
            service.context(scenario.id,uploaded.importId(),pending);var lookup=service.geocode(scenario.id,uploaded.importId(),null);assertEquals(4,lookup.answers().size());
            assertFalse(service.list(scenario.id).getFirst().execution().ready(),"Search must not apply or confirm candidates");
            var positions=new java.util.HashMap<String,CompanyTourExecution.Point>();for(var answer:lookup.answers())positions.put(answer.targetId(),answer.candidates().getFirst().point());
            var chosen=new CompanyTourExecution.Context(complete.companyId(),complete.simulationDate(),false,true,true,true,complete.windowPolicy(),List.of(new CompanyTourExecution.TourPlan(t.tourId(),true,t.vehicleId(),t.startTime(),t.returnTime(),positions.get("start:"+t.tourId()),positions.get("end:"+t.tourId()),t.stopOrder())),java.util.Map.of(t.stopOrder().get(0),positions.get("stop:"+t.stopOrder().get(0)),t.stopOrder().get(1),positions.get("stop:"+t.stopOrder().get(1))),false,false,pending.depotAddresses());
            assertTrue(service.context(scenario.id,uploaded.importId(),chosen).execution().ready());service.activate(scenario.id,uploaded.importId(),true);
            var batch=CompanyTourImportService.loadForGeneration(repo,json,repo.findById(scenario.id).orElseThrow());var shared=new TrafficDemand(TrafficDemand.CONTRACT,"available",new TravelDemand(List.of(EnergyOperationsIntegrationTest.trip("private",1,8.201,8.205)),1),0,List.of(),List.of(),batch,VehicleCatalog.requireValid(scenario.vehicleConfiguration),List.of(),"available");TrafficDemandArtifacts.write(dir,shared,json);
            var cfg=new SynthesisProperties.Sumo();cfg.setDay(0);cfg.setMaxTrips(-1);var runner=new SumoRunner(cfg,shared.vehicles(),42).useEnergyAndOperations(scenario);assumeTrue(runner.isAvailable(),"Native SUMO required");runner.run(dir,EnergyOperationsIntegrationTest.OSM,TrafficDemandArtifacts.read(dir,json),null,ignored->{});
            assertEquals("complete",json.readTree(dir.resolve("company-tours/execution.json").toFile()).path("status").asText());assertEquals(4,shared.movements().size());
        } finally {server.stop(0);}
    }
    @Test void importedTruckTourAndReturnUseOneVehicleWithPrivateTrafficAndExplicitSource() throws Exception {
        var scenario = NativeVehicleChainsIntegrationTest.scenario("standard.heavyTruck.diesel", List.of());
        scenario.id = "native-company";
        var fleet = scenario.vehicleConfiguration;
        scenario.vehicleConfiguration = new de.synthesis.vehicle.VehicleConfiguration(fleet.schemaVersion(), "catalogOnly", false, List.of(),
                fleet.vehicleTypes(),fleet.catalogRefs(),fleet.trafficAssignments(),fleet.fleetAssignments(),fleet.initialVehicles());
        scenario.vehicleOperations = de.synthesis.vehicle.VehicleOperationsConfiguration.empty();
        scenario.freight.enabled = true;
        scenario.freight.relations = List.of(new FreightDemandGenerator.Relation("observed", "truck", "internal",
                8.201,48.85,8.205,48.85,2,6,8,"synthetic","2026-10-19"));
        var dataProperties = new SynthesisProperties(); dataProperties.setDataDir(dir.resolve("inputs").toString());
        var mapper = new ObjectMapper().findAndRegisterModules(); var repo = new ScenarioRepository(mapper, dataProperties); repo.save(scenario);
        var service = new CompanyTourImportService(repo, mapper, new TourMappingPlanner(mapper, new TourImportProperties()), dataProperties);
        var uploaded = service.upload(scenario.id, CompanyTourExecutionTest.bytes(CompanyTourExecutionTest.CSV), "plan.csv");
        service.confirm(scenario.id, uploaded.importId(), uploaded.mapping());
        service.context(scenario.id, uploaded.importId(), CompanyTourExecutionTest.context(uploaded.draft(), "delivery-1"));
        service.activate(scenario.id, uploaded.importId(), true);
        var batch = CompanyTourImportService.loadForGeneration(repo, mapper, repo.findById(scenario.id).orElseThrow());
        var properties = new SynthesisProperties.Sumo(); properties.setDay(0); properties.setMaxTrips(1);
        var runner = new SumoRunner(properties, VehicleCatalog.requireValid(scenario.vehicleConfiguration), 42)
                .useFreight(scenario).useEnergyAndOperations(scenario);
        assumeTrue(runner.isAvailable(), "Native SUMO binaries required");
        var demand = new TravelDemand(List.of(EnergyOperationsIntegrationTest.trip("first", 1, 8.201, 8.205),
                EnergyOperationsIntegrationTest.trip("second", 2, 8.201, 8.205)), 2);
        Path parcelPlan=dir.resolve("parcel-plan.csv");
        Files.writeString(parcelPlan,"tourId;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur\nparcel;2;8.201;48.85;8.205;48.85;100;2;1\n");
        var shared = new TrafficDemand(TrafficDemand.CONTRACT, "available", demand, 0,
                FreightDemandGenerator.generate(scenario), ParcelTourAdapter.readAll(parcelPlan), batch,
                VehicleCatalog.requireValid(scenario.vehicleConfiguration), List.of(), "available");
        TrafficDemandArtifacts.write(dir, shared, mapper);
        var result = runner.run(dir, EnergyOperationsIntegrationTest.OSM, TrafficDemandArtifacts.read(dir, mapper), null, ignored -> {});
        assertEquals(2, result.carTrips(), "No personal-demand cap with additional observed company tours");
        assertEquals(3, result.companyTourLegs()); assertEquals(2, result.deliveryTrips()); assertEquals(2, result.heavyFreightTrips());
        assertEquals(7, result.vehicles()); assertEquals(9, shared.movements().size());
        assertEquals(4, mapper.readTree(dir.resolve(TrafficDemandArtifacts.SUMMARY).toFile()).path("byDemandSource").size());
        Files.delete(parcelPlan); // Every later export uses the prepared snapshot, not the source plan.
        var json = new ObjectMapper(); var report = json.readTree(dir.resolve("company-tours/execution.json").toFile());
        assertEquals("complete", report.path("status").asText()); assertEquals(1, report.path("completedTours").asInt());
        var chain = json.readTree(dir.resolve("vehicles/native-chains.json").toFile());
        assertEquals(1, chain.path("physicalVehicles").asInt()); assertEquals(3, chain.path("completedSegments").asInt());
        var route = json.readTree(dir.resolve("sumo/traffic-routes.geojson").toFile()); int count = 0;
        for (var feature : route.path("features")) if (feature.path("properties").path("demandSource").asText().equals("company-tours")) count++;
        assertEquals(3, count);
        var stop = report.path("tours").get(0).path("stops").get(0);
        assertTrue(stop.path("stopEnded").asDouble() >= stop.path("arrival").asDouble() + 60);
        var first = report.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) chain.path("vehicles").get(0)).put("nativeCompleted", false);
        json.writeValue(dir.resolve("vehicles/native-chains.json").toFile(), chain);
        new CompanyTourRun(batch).finish(dir);
        var unfinished = json.readTree(dir.resolve("company-tours/execution.json").toFile());
        assertEquals("review-required", unfinished.path("status").asText());
        assertEquals(0, unfinished.path("completedTours").asInt(), "Complete stops alone cannot certify a missing vehicle return at day end");
        runner.run(dir, EnergyOperationsIntegrationTest.OSM, TrafficDemandArtifacts.read(dir, mapper), null, ignored -> {});
        assertEquals(first, json.readTree(dir.resolve("company-tours/execution.json").toFile()));
        assertThrows(java.io.IOException.class, () -> runner.run(dir, "<invalid/>", TrafficDemandArtifacts.read(dir, mapper), null, ignored -> {}));
        assertEquals("not-executed", json.readTree(dir.resolve("company-tours/execution.json").toFile()).path("status").asText());
        assertFalse(Files.exists(dir.resolve("company-tours/routing.json")), "No successful routing from a previous attempt survives");
    }
}
