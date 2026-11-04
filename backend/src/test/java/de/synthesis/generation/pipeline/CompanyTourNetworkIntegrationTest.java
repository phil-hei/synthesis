package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.companytour.*;
import de.synthesis.generation.demand.*;
import de.synthesis.vehicle.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompanyTourNetworkIntegrationTest {
    @TempDir Path dir;
    static final String OSM="""
      <osm version="0.6"><node id="1" lon="8.2" lat="48.85"/><node id="2" lon="8.2" lat="48.852"/><node id="3" lon="8.206" lat="48.852"/><node id="4" lon="8.206" lat="48.85"/><node id="5" lon="8.2" lat="48.849"/><node id="6" lon="8.206" lat="48.849"/>
      <node id="7" lon="8.199" lat="48.85"/><node id="8" lon="8.207" lat="48.85"/>
      <way id="11"><nd ref="5"/><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="6"/><tag k="highway" v="residential"/></way>
      <way id="15"><nd ref="7"/><nd ref="1"/><tag k="highway" v="residential"/></way><way id="16"><nd ref="4"/><nd ref="8"/><tag k="highway" v="residential"/></way></osm>
      """;
    @Test void actualCurvedRoadVisitsRegionWhichStraightLineMissesAndNativeChainKeepsIt()throws Exception{
        var scenario=NativeVehicleChainsIntegrationTest.scenario("standard.heavyTruck.diesel",List.of());scenario.id="network-company";scenario.vehicleOperations=VehicleOperationsConfiguration.empty();
        scenario.region.minLon=8.201;scenario.region.maxLon=8.205;scenario.region.minLat=48.851;scenario.region.maxLat=48.853;
        var json=new ObjectMapper().findAndRegisterModules();var props=new SynthesisProperties();props.setDataDir(dir.resolve("data").toString());var repo=new ScenarioRepository(json,props);repo.save(scenario);
        var service=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,new TourImportProperties()));
        var upload=service.upload(scenario.id,CompanyTourExecutionTest.bytes("Tour;Liefertag;Vorgang;Strasse;PLZ;Ort;Ankunft;Abfahrt\nT1;20261019;A1;Testweg 1;01234;Teststadt;08:00;08:01\nT1;20261019;A2;Testweg 2;01234;Teststadt;09:00;09:01\n"),"plan.csv");service.confirm(scenario.id,upload.importId(),upload.mapping());
        var tour=upload.draft().tours().getFirst();var p0=new CompanyTourExecution.Point(8.2,48.8492,"fixture");var plans=List.of(new CompanyTourExecution.TourPlan(tour.tourId(),true,"delivery-1","07:30","10:00",p0,p0,tour.stops().stream().map(CompanyTourMapper.Stop::stopId).toList()));
        var locations=Map.of(tour.stops().get(0).stopId(),new CompanyTourExecution.Point(8.2,48.8498,"fixture"),tour.stops().get(1).stopId(),new CompanyTourExecution.Point(8.206,48.8498,"fixture"));
        var context=new CompanyTourExecution.Context("example","2026-10-19",false,true,true,true,"report-only",plans,locations,true,true);
        assertTrue(service.context(scenario.id,upload.importId(),context).execution().ready());service.activate(scenario.id,upload.importId(),true);var batch=CompanyTourImportService.loadForGeneration(repo,json,repo.findById(scenario.id).orElseThrow());
        assertEquals("pending-network",CompanyTourRegion.project(batch).method());assertTrue(CompanyTourRegion.project(batch).batch().legs().isEmpty());
        var sumo=new SynthesisProperties.Sumo();sumo.setDay(0);sumo.setMaxTrips(-1);var runner=new SumoRunner(sumo,VehicleCatalog.requireValid(scenario.vehicleConfiguration),42).useEnergyAndOperations(scenario);assumeTrue(runner.isAvailable(),"Native SUMO required");
        var shared=new TrafficDemand(TrafficDemand.CONTRACT,"available",new TravelDemand(List.of(),0),0,List.of(),List.of(),batch,VehicleCatalog.requireValid(scenario.vehicleConfiguration),List.of(),"available");
        var result=runner.run(dir,OSM,shared,null,ignored->{});assertTrue(result.companyTourLegs()>=2);assertEquals(3,shared.movements().size());
        var projection=json.readTree(dir.resolve("company-tours/regional-projection.json").toFile());assertEquals("directed-network-edge-free-flow-plan-time-scaling",projection.path("method").asText());
        var paths=json.readTree(dir.resolve("company-tours/network-paths.json").toFile());assertEquals("routed",paths.path("status").asText());assertEquals(3,paths.path("paths").size());assertFalse(paths.path("regionalRoutes").isEmpty());
        var chain=json.readTree(dir.resolve("vehicles/native-chains.json").toFile());assertEquals("complete",chain.path("status").asText());assertEquals(1,chain.path("physicalVehicles").asInt());
        assertEquals("regional-complete",json.readTree(dir.resolve("company-tours/execution.json").toFile()).path("status").asText());
        var first=projection.deepCopy();runner.run(dir,OSM,shared,null,ignored->{});assertEquals(first,json.readTree(dir.resolve("company-tours/regional-projection.json").toFile()));
        // Coverage failure is explicit, even when a previous successful run exists.
        var prepared=batch.imports().getFirst();var legs=new ArrayList<>(prepared.legs());var l=legs.getFirst();legs.set(0,new CompanyTourExecution.Leg(l.sourceKey(),l.importId(),l.tourId(),l.stopId(),l.vehicleId(),l.vehicleClass(),l.departSec(),l.plannedArrivalSec(),l.plannedEndSec(),l.serviceSeconds(),new CompanyTourExecution.Point(10.,50.,"fixture"),l.to(),l.windowPolicy(),l.windows(),l.returning()));
        var bad=new CompanyTourExecution.Batch(List.of(new CompanyTourExecution.Prepared(prepared.contract(),prepared.importId(),prepared.mappingHash(),prepared.executionHash(),prepared.companyId(),prepared.simulationDate(),prepared.sourceTours(),prepared.excludedTours(),legs,prepared.regionalScope(),true)));
        var missing=new TrafficDemand(TrafficDemand.CONTRACT,"available",new TravelDemand(List.of(),0),0,List.of(),List.of(),bad,shared.vehicles(),List.of(),"available");assertThrows(java.io.IOException.class,()->runner.run(dir,OSM,missing,null,ignored->{}));
        assertEquals("failed",json.readTree(dir.resolve("company-tours/network-paths.json").toFile()).path("status").asText());assertEquals("not-executed",json.readTree(dir.resolve("company-tours/execution.json").toFile()).path("status").asText());
    }
}
