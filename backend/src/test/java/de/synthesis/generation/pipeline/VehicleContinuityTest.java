package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import de.synthesis.vehicle.*;
import de.synthesis.vehicle.VehicleType.*;
import de.synthesis.vehicle.VehicleConfiguration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleContinuityTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static VehicleConfiguration vehicles(Double consumption, double initial) {
        VehicleType base = VehicleCatalog.standards().stream().filter(t -> t.id().equals("standard.pkw.elektro")).findFirst().orElseThrow();
        var e = (ElectricPowertrain) base.powertrain();
        var type = new VehicleType("test.ev", "1", "Test EV", base.category(), base.provenance(), base.physical(), base.capacity(),
                new ElectricPowertrain(e.usableBatteryCapacityKwh(), e.maxChargePowerKw(), e.chargingEfficiency(), e.recuperationEfficiency(), consumption, e.assistanceMode(), e.assistanceCutoffKmh(), e.connectors()),
                base.driving(), base.routing(), base.operation(), base.emissions(), base.noise());
        var ref = new TypeRef(type.id(), type.version());
        return VehicleCatalog.requireValid(new VehicleConfiguration(VehicleConfiguration.CONTRACT, "sumoTraffic", false, List.of(), List.of(type), List.of(),
                List.of(new TrafficAssignment("car", ref, 1.0)), List.of(), List.of(new VehicleInstance("ev-1", ref, null, "first-trip-origin", new VehicleState(0., 0., 0, 1, initial, null)))));
    }
    static VehicleOperationsConfiguration config() {
        return new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT, true, .1, List.of(
                new VehicleOperationsConfiguration.Binding(VehicleOperationsConfiguration.personKey("p", "a"), "ev-1"),
                new VehicleOperationsConfiguration.Binding(VehicleOperationsConfiguration.personKey("p", "b"), "ev-1")));
    }
    VehicleContinuity setup(Double consumption, double initial) throws Exception {
        var ledger = new VehicleContinuity(config(), vehicles(consumption, initial));
        ledger.register("v0", VehicleOperationsConfiguration.personKey("p", "a"), "type", 0, "8", "49", "8.001", "49");
        ledger.register("v1", VehicleOperationsConfiguration.personKey("p", "b"), "type", 200, "8.001", "49", "8", "49");
        Files.createDirectories(dir.resolve("sumo")); Files.writeString(dir.resolve("sumo/net.net.xml"), "<net/>");
        Files.writeString(dir.resolve("sumo/routes.rou.xml"), "<routes><vehicle id='v0'><param key='synthesis.physicalVehicleId' value='ev-1'/></vehicle><vehicle id='v1'><param key='synthesis.physicalVehicleId' value='ev-1'/></vehicle></routes>");
        ledger.input(dir); return ledger;
    }
    @Test void carriesOneStateAcrossSegmentsAndNeverResetsOrClampsDeficits() throws Exception {
        var ledger = setup(.2, 1.0);
        Files.writeString(dir.resolve("sumo/tripinfo.xml"), "<tripinfos><tripinfo id='v0' depart='0' arrival='100' routeLength='3000'/><tripinfo id='v1' depart='200' arrival='300' routeLength='4000'/></tripinfos>");
        ledger.finish(dir, dir.resolve("sumo")); var report = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile());
        var rows = report.path("vehicles").get(0).path("segments"); assertEquals(.4, rows.get(0).path("balanceAfter").asDouble(), 1e-9);
        assertEquals(.4, rows.get(1).path("balanceBefore").asDouble(), 1e-9); assertEquals(-.4, rows.get(1).path("balanceAfter").asDouble(), 1e-9);
        assertEquals(.4, rows.get(1).path("energyDeficit").asDouble(), 1e-9); assertFalse(rows.get(1).path("stateValid").asBoolean());
        assertFalse(report.path("energyConstrainsTraffic").asBoolean()); assertTrue(report.toString().contains("energy-deficit"));
        VehicleContinuity.clear(dir); assertFalse(Files.exists(dir.resolve("vehicles/energy-ledger.json")));
    }
    @Test void unknownConsumptionAndUnfinishedPredecessorsCannotBecomeFreeTravel() throws Exception {
        var ledger = setup(null, 20);
        Files.writeString(dir.resolve("sumo/tripinfo.xml"), "<tripinfos><tripinfo id='v0' depart='0' arrival='-1' routeLength='100'/><tripinfo id='v1' depart='200' arrival='300' routeLength='200'/></tripinfos>");
        ledger.finish(dir, dir.resolve("sumo")); var report = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile());
        var rows = report.path("vehicles").get(0).path("segments"); assertTrue(rows.get(0).path("balanceAfter").isNull()); assertTrue(rows.get(1).path("balanceBefore").isNull());
        assertTrue(rows.get(1).path("issues").toString().contains("overlap-or-unfinished-predecessor")); assertEquals("review-required", report.path("status").asText());
    }
    @Test void combustionLedgerUsesLitresAndExplicitReferenceConsumption() throws Exception {
        setup(.2, 20); // Shared native observation fixtures.
        var base = VehicleCatalog.standards().stream().filter(t -> t.id().equals("standard.pkw.diesel")).findFirst().orElseThrow();
        var type = new VehicleType("test.diesel", "1", "Test Diesel", base.category(), base.provenance(), base.physical(), base.capacity(),
                new CombustionPowertrain("diesel", 50., .1), base.driving(), base.routing(), base.operation(), base.emissions(), base.noise());
        var ref = new TypeRef(type.id(), type.version());
        var input = VehicleCatalog.requireValid(new VehicleConfiguration(VehicleConfiguration.CONTRACT, "sumoTraffic", false, List.of(), List.of(type), List.of(),
                List.of(new TrafficAssignment("car", ref, 1.)), List.of(), List.of(new VehicleInstance("ev-1", ref, null, "first-trip-origin", new VehicleState(0., 0., 0, 1, null, 10.)))));
        var ledger = new VehicleContinuity(config(), input);
        ledger.register("v0", VehicleOperationsConfiguration.personKey("p", "a"), "type", 0, "8", "49", "8.001", "49");
        ledger.register("v1", VehicleOperationsConfiguration.personKey("p", "b"), "type", 200, "8.001", "49", "8", "49");
        Files.writeString(dir.resolve("sumo/tripinfo.xml"), "<tripinfos><tripinfo id='v0' depart='0' arrival='100' routeLength='1000'/><tripinfo id='v1' depart='200' arrival='300' routeLength='2000'/></tripinfos>");
        ledger.finish(dir, dir.resolve("sumo")); var vehicle = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile()).path("vehicles").get(0);
        assertEquals("L", vehicle.path("unit").asText()); assertEquals(9.7, vehicle.path("balanceAfter").asDouble(), 1e-9);
        assertTrue(vehicle.path("stateValid").asBoolean());
    }
    @Test void spatialGapsAndOverflowInvalidateTheChainWithoutFabricatingFiniteEnergy() throws Exception {
        setup(.2, 20); var ledger = new VehicleContinuity(config(), vehicles(Double.MAX_VALUE, 20));
        ledger.register("v0", VehicleOperationsConfiguration.personKey("p", "a"), "type", 0, "8", "49", "8.001", "49");
        ledger.register("v1", VehicleOperationsConfiguration.personKey("p", "b"), "type", 200, "8.1", "49", "8", "49");
        Files.writeString(dir.resolve("sumo/tripinfo.xml"), "<tripinfos><tripinfo id='v0' depart='0' arrival='100' routeLength='3000'/><tripinfo id='v1' depart='200' arrival='300' routeLength='2000'/></tripinfos>");
        ledger.finish(dir, dir.resolve("sumo")); var report = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile());
        assertTrue(report.toString().contains("reference-consumption-overflow")); assertTrue(report.toString().contains("location-discontinuity-over-25m"));
        assertTrue(report.path("vehicles").get(0).path("balanceAfter").isNull());
    }
    @Test void detectsOverlapPositionJumpsAndKeepsOpaqueIdentityKeysUnambiguous() throws Exception {
        var ledger = setup(.2, 20);
        Files.writeString(dir.resolve("sumo/tripinfo.xml"), "<tripinfos><tripinfo id='v0' depart='0' arrival='250' routeLength='100'/><tripinfo id='v1' depart='200' arrival='300' routeLength='200'/></tripinfos>");
        ledger.finish(dir, dir.resolve("sumo")); var report = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile());
        assertTrue(report.toString().contains("overlap-or-unfinished-predecessor"));
        assertNotEquals(VehicleOperationsConfiguration.personKey("a:b", "c"), VehicleOperationsConfiguration.personKey("a", "b:c"));
        var s = new Scenario(); s.targetModels = new ArrayList<>(List.of("sumo")); s.vehicleConfiguration = vehicles(.2, 20); s.vehicleOperations = config();
        assertTrue(VehicleOperationsConfiguration.validate(s).isEmpty());
        s.vehicleOperations = new VehicleOperationsConfiguration(config().schemaVersion(), true, .1, List.of(config().bindings().getFirst(), config().bindings().getFirst()));
        assertFalse(VehicleOperationsConfiguration.validate(s).isEmpty());
    }
}
