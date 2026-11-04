package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.scenario.Scenario;
import de.synthesis.vehicle.*;
import de.synthesis.vehicle.VehicleConfiguration.*;
import de.synthesis.vehicle.VehicleOperationsConfiguration.Binding;
import de.synthesis.vehicle.VehicleType.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeVehicleChainsIntegrationTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEADER = "tourId;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur\n";

    static Scenario scenario(String standardId, List<String> sourceKeys) {
        var scenario = new Scenario(); scenario.targetModels = List.of("sumo");
        scenario.region = new Scenario.Region(); scenario.region.minLon = 8.19; scenario.region.maxLon = 8.21;
        scenario.region.minLat = 48.84; scenario.region.maxLat = 48.86;
        var base = VehicleCatalog.standards().stream().filter(t -> t.id().equals(standardId)).findFirst().orElseThrow();
        var type = new VehicleType("test.vehicle", base.version(), base.name(), base.category(), base.provenance(), base.physical(),
                base.capacity(), base.powertrain(), base.driving(), base.routing(), base.operation(), base.emissions(), base.noise());
        var ref = new TypeRef(type.id(), type.version());
        scenario.vehicleConfiguration = VehicleCatalog.requireValid(new VehicleConfiguration(VehicleConfiguration.CONTRACT, "sumoTraffic", false,
                List.of(), List.of(type), List.of(), List.of(new TrafficAssignment(switch (type.routing().sumoVehicleClass()) {
                    case "delivery" -> "deliveryVan"; case "truck" -> "heavyTruck"; default -> "car";
                }, ref, 1.0)), List.of(),
                List.of(new VehicleInstance("delivery-1", ref, null, "first-assigned-trip-origin", new VehicleState(0., 0., 0, 1, null, 20.)))));
        scenario.vehicleOperations = new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT, true, .1,
                sourceKeys.stream().map(key -> new Binding(key, "delivery-1")).toList(), true);
        return scenario;
    }
    SumoRunner runner(Scenario scenario) {
        var props = new SynthesisProperties.Sumo(); props.setDay(0); props.setMaxTrips(-1);
        var runner = new SumoRunner(props, scenario.vehicleConfiguration, 42).useFreight(scenario).useEnergyAndOperations(scenario);
        assumeTrue(runner.isAvailable(), "native SUMO missing"); return runner;
    }
    JsonNode chains() throws Exception { return JSON.readTree(dir.resolve("vehicles/native-chains.json").toFile()); }

    @Test void deliveryTourAndReturnUseOneNativeVehicleCarryServiceDelayAndKeepOtherTraffic() throws Exception {
        var scenario = scenario("standard.lieferwagen.diesel", List.of(VehicleOperationsConfiguration.parcelKey("tour", "0"),
                VehicleOperationsConfiguration.parcelKey("tour", "1"), VehicleOperationsConfiguration.parcelKey("tour", "return")));
        var runner = runner(scenario); Path tours = dir.resolve("tours.csv");
        Files.writeString(tours, HEADER + "tour;2;8.201;48.85;8.203;48.85;5;1;2\n" + "tour;2;8.203;48.85;8.205;48.85;6;1;1\n");
        var demand = new TravelDemand(List.of(EnergyOperationsIntegrationTest.trip("unassigned", 1, 8.201, 8.205)), 1);
        var result = runner.run(dir, EnergyOperationsIntegrationTest.OSM, demand, tours, null, ignored -> {});
        assertEquals(3, result.deliveryTrips()); assertEquals(1, result.carTrips()); assertEquals(2, result.vehicles());
        var report = chains(); assertEquals("complete", report.path("status").asText());
        assertEquals(1, report.path("physicalVehicles").asInt()); assertEquals(3, report.path("completedSegments").asInt());
        var vehicle = report.path("vehicles").get(0); var legs = vehicle.path("segments");
        assertEquals(3, legs.size()); assertEquals(120, legs.get(0).path("serviceSeconds").asInt());
        assertTrue(legs.get(0).path("stopEnded").asDouble() >= legs.get(0).path("arrival").asDouble() + 120);
        assertEquals(legs.get(0).path("stopEnded"), legs.get(1).path("depart"));
        assertTrue(legs.get(1).path("departureDelay").asDouble() > 0, "Service delays must propagate, not spawn an independent next vehicle");
        assertEquals(0, vehicle.path("teleports").asInt());
        assertEquals(2, TrafficRoutes.vehicleIds(dir.resolve("sumo/simulation.rou.xml")).size());
        var execution = JSON.readTree(dir.resolve("sumo/traffic-execution.json").toFile());
        assertEquals(4, execution.path("vehicles").path("completed").asInt());
        assertEquals(2, execution.path("nativeVehicles").path("completed").asInt());
        assertTrue(execution.path("demandRetained").asBoolean());
        assertEquals(4, JSON.readTree(dir.resolve("sumo/traffic-routes.geojson").toFile()).path("features").size());
        var first = (com.fasterxml.jackson.databind.node.ObjectNode)report.deepCopy(); first.remove(List.of("nativeNetworkSha256", "nativeRoutesSha256", "stopOutputSha256"));
        runner.run(dir, EnergyOperationsIntegrationTest.OSM, demand, tours, null, ignored -> {});
        var replay = (com.fasterxml.jackson.databind.node.ObjectNode)chains(); replay.remove(List.of("nativeNetworkSha256", "nativeRoutesSha256", "stopOutputSha256"));
        assertEquals(first, replay);
        scenario.vehicleOperations = new VehicleOperationsConfiguration(scenario.vehicleOperations.schemaVersion(), true, .1, scenario.vehicleOperations.bindings(), false);
        result = runner(scenario).run(dir, EnergyOperationsIntegrationTest.OSM, demand, tours, null, ignored -> {});
        assertEquals(4, result.vehicles()); assertFalse(Files.exists(dir.resolve("sumo/simulation.rou.xml")));
        assertFalse(Files.exists(dir.resolve("vehicles/native-chains.json")));
    }

    @Test void heavyFreightRelationsKeepLogicalCountsWhenBoundToOneReturningTruck() throws Exception {
        var scenario = scenario("standard.heavyTruck.diesel", List.of(VehicleOperationsConfiguration.freightKey("freight_out_0"), VehicleOperationsConfiguration.freightKey("freight_back_0")));
        scenario.freight.enabled = true;
        scenario.freight.relations = List.of(
                new FreightDemandGenerator.Relation("out", "truck", "outbound", 8.201, 48.85, 8.205, 48.85, 1, 0, .01, "fixture", "2026-10-21"),
                new FreightDemandGenerator.Relation("back", "truck", "inbound", 8.205, 48.85, 8.201, 48.85, 1, .1, .11, "fixture", "2026-10-21"));
        var result = runner(scenario).run(dir, EnergyOperationsIntegrationTest.OSM, new TravelDemand(List.of(), 0), null, null, ignored -> {});
        assertEquals(2, result.heavyFreightTrips()); assertEquals(1, result.vehicles());
        assertEquals(2, chains().path("completedSegments").asInt());
        var execution = JSON.readTree(dir.resolve("freight/execution.json").toFile()); assertEquals(2, execution.path("completed").asInt());
        assertEquals(0, execution.path("notDeparted").size()); assertTrue(execution.path("nativeVehicleChainsSimulated").asBoolean());
    }

    @Test void rejectsSpatialGapsBeforeSimulationAndLeavesAnActionableReport() throws Exception {
        var scenario = scenario("standard.pkw.diesel", List.of(VehicleOperationsConfiguration.personKey("p", "a"), VehicleOperationsConfiguration.personKey("p", "b")));
        var demand = new TravelDemand(List.of(EnergyOperationsIntegrationTest.trip("a", 1, 8.201, 8.203), EnergyOperationsIntegrationTest.trip("b", 10, 8.205, 8.201)), 2);
        var error = assertThrows(IOException.class, () -> runner(scenario).run(dir, EnergyOperationsIntegrationTest.OSM, demand, null, null, ignored -> {}));
        assertTrue(error.getMessage().contains("Ortsbruch")); assertEquals("failed", chains().path("status").asText());
        assertFalse(Files.exists(dir.resolve("sumo/tripinfo.xml")));
    }

    @Test void dayEndStopDoesNotInventDepartureOfTheNextSegment() throws Exception {
        var scenario = scenario("standard.lieferwagen.diesel", List.of(VehicleOperationsConfiguration.parcelKey("tour", "0"), VehicleOperationsConfiguration.parcelKey("tour", "1")));
        Path tours = dir.resolve("tours.csv");
        Files.writeString(tours, HEADER + "tour;2;8.201;48.85;8.203;48.85;5;1;2000\n" + "tour;2;8.203;48.85;8.205;48.85;6;1;2000\n");
        runner(scenario).run(dir, EnergyOperationsIntegrationTest.OSM, new TravelDemand(List.of(), 0), tours, null, ignored -> {});
        var report = chains(); assertEquals("incomplete", report.path("status").asText());
        assertEquals(1, report.path("completedSegments").asInt()); assertEquals(1, report.path("notDepartedSegments").asInt());
        assertEquals(-1, report.path("vehicles").get(0).path("segments").get(1).path("depart").asDouble());
        assertEquals(0, report.path("completedPhysicalVehicles").asInt());
    }

    @Test void additionalBusStopsDoNotEnterTheFreightChainAccounting() throws Exception {
        var scenario = scenario("standard.heavyTruck.diesel", List.of());
        scenario.freight.enabled = true; scenario.freight.relations = List.of(new FreightDemandGenerator.Relation("out", "truck", "outbound", 8.201, 48.85, 8.205, 48.85, 1, .1, .11, "fixture", "2026-10-21"));
        scenario.vehicleOperations = new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT, true, .1,
                List.of(new Binding(VehicleOperationsConfiguration.freightKey("freight_out_0"), "delivery-1")), true);
        var pt = SumoRunner.PtProvider.roadOnly(net -> {
            var edge = TrafficRoutes.elements(TrafficRoutes.read(net), "edge").stream().filter(e -> !e.getAttribute("id").startsWith(":"))
                    .filter(e -> TrafficRoutes.elements(e, "lane").stream().anyMatch(l -> Double.parseDouble(l.getAttribute("length")) > 100 && !l.getAttribute("allow").equals("pedestrian"))).findFirst().orElseThrow();
            var lane = TrafficRoutes.elements(edge, "lane").stream().filter(l -> !l.getAttribute("allow").equals("pedestrian")).findFirst().orElseThrow();
            Path types = dir.resolve("bus-types.xml"), stops = dir.resolve("bus-stops.xml"), bus = dir.resolve("bus.xml");
            Files.writeString(types, "<additional><vType id='fixture.bus' vClass='bus' emissionClass='Zero'/></additional>");
            Files.writeString(stops, "<additional/>");
            Files.writeString(bus, "<routes><vehicle id='fixture.bus' type='fixture.bus' depart='0' line='fixture'><route edges='" + edge.getAttribute("id")
                    + "'/><stop lane='" + lane.getAttribute("id") + "' endPos='30' duration='1'/><stop lane='" + lane.getAttribute("id") + "' endPos='60' duration='1'/></vehicle></routes>");
            return new TransitRunner.PtInfrastructure(types, stops, bus, 0, 1);
        });
        runner(scenario).run(dir, EnergyOperationsIntegrationTest.OSM, new TravelDemand(List.of(), 0), null, pt, ignored -> {});
        assertEquals("complete", chains().path("status").asText()); assertEquals(1, chains().path("completedSegments").asInt());
        assertTrue(Files.readString(dir.resolve("sumo/chain-stops.xml")).contains("fixture.bus"));
    }
}
