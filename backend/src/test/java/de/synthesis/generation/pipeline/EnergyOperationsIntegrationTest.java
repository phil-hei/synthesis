package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.TravelDemand;
import de.synthesis.scenario.Scenario;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EnergyOperationsIntegrationTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OSM = """
        <osm version="0.6"><node id="1" lon="8.2" lat="48.85"/><node id="2" lon="8.202" lat="48.85"/><node id="3" lon="8.204" lat="48.85"/><node id="4" lon="8.206" lat="48.85"/>
        <node id="5" lon="8.202" lat="48.852"/><way id="14"><nd ref="2"/><nd ref="5"/><tag k="highway" v="residential"/><tag k="maxheight" v="1 m"/></way>
        <way id="11"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>
        <way id="12"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
        <way id="13"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/></way></osm>
        """;
    static TravelDemand.PersonTrip trip(String id, double depart, double from, double to) {
        return new TravelDemand.PersonTrip("p", id, TravelDemand.Mode.CAR, "1", "1", depart, depart + 2, .2, "1", "1", from, 48.85, to, 48.85,
                List.of(new TravelDemand.Leg(0, TravelDemand.Mode.CAR, "1", .2, "1", "1")));
    }
    @Test void nativeStationProbesAndPhysicalLedgerKeepDemandSeparateAndReplayDeterministic() throws Exception {
        var props = new SynthesisProperties.Sumo(); props.setMaxTrips(-1); var config = new Scenario(); config.targetModels = new ArrayList<>(List.of("sumo"));
        var runner = new SumoRunner(props); assumeTrue(runner.isAvailable(), "native SUMO missing");
        config.vehicleConfiguration = VehicleContinuityTest.vehicles(.2, 20); config.vehicleOperations = VehicleContinuityTest.config();
        config.energyInfrastructure.enabled = true; config.energyInfrastructure.useBnetza = false; config.energyInfrastructure.verifyNetworkAccess = true;
        config.region = new Scenario.Region(); config.region.minLon = 8.19; config.region.maxLon = 8.21; config.region.minLat = 48.84; config.region.maxLat = 48.86;
        var raw = JSON.readTree("{\"elements\":[{\"type\":\"node\",\"id\":101,\"lon\":8.203,\"lat\":48.85,\"tags\":{\"amenity\":\"charging_station\",\"access\":\"yes\",\"socket:type2\":\"2\"}},{\"type\":\"node\",\"id\":102,\"lon\":8.203,\"lat\":48.85,\"tags\":{\"amenity\":\"charging_station\",\"access\":\"customers\",\"maxheight\":\"1 m\",\"socket:type2\":\"1\"}}]}");
        EnergyInfrastructure.generate(config, dir, dir.resolve("uploads"), () -> new OverpassClient.EnergyResponse(raw, "fixture"), ignored -> {});
        var demand = new TravelDemand(List.of(trip("a", 1, 8.201, 8.203), trip("b", 10, 8.203, 8.205)), 2);
        var resolved = VehicleCatalog.requireValid(config.vehicleConfiguration);
        runner = new SumoRunner(props, resolved, 42).useFreight(config).useEnergyAndOperations(config);
        runner.run(dir, OSM, demand, null, null, ignored -> {});
        var access = JSON.readTree(dir.resolve("energy/network-access.json").toFile());
        assertEquals("completed", access.path("status").asText()); assertTrue(access.path("mappedProfileChecks").asInt() > 0);
        assertTrue(access.path("blockedProfileChecks").asInt() > 0);
        assertTrue(access.path("profileAllowedRoutingProbes").asInt() < access.path("successfulRoutingProbes").asInt(), "Source height limits must also apply on the neighboring probe path"); assertFalse(access.path("physicalEntranceVerified").asBoolean());
        var ledger = JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile()); assertEquals(2, ledger.path("selectedSegments").asInt()); assertEquals(2, ledger.path("assignedSegments").asInt());
        var rows = ledger.path("vehicles").get(0).path("segments"); assertEquals(rows.get(0).path("balanceAfter"), rows.get(1).path("balanceBefore"));
        assertTrue(rows.get(1).path("balanceAfter").asDouble() < 20); assertFalse(ledger.path("nativeFleetIdentitySimulated").asBoolean());
        assertEquals(Set.of("v0", "v1"), TrafficRoutes.vehicleIds(dir.resolve("sumo/routes.rou.xml")), "Routing probes must never become transport demand");
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) ledger.deepCopy();
        first.remove(List.of("nativeNetworkSha256", "tripinfoSha256")); // Native XML comments contain creation timestamps.
        runner.run(dir, OSM, demand, null, null, ignored -> {});
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(dir.resolve("vehicles/energy-ledger.json").toFile());
        second.remove(List.of("nativeNetworkSha256", "tripinfoSha256")); assertEquals(first, second);
        config.vehicleOperations = VehicleOperationsConfiguration.empty(); config.energyInfrastructure.verifyNetworkAccess = false;
        new SumoRunner(props, resolved, 42).useFreight(config).useEnergyAndOperations(config).run(dir, OSM, demand, null, null, ignored -> {});
        assertFalse(Files.exists(dir.resolve("vehicles/energy-ledger.json"))); assertFalse(Files.exists(dir.resolve("energy/network-access.json")));
    }
}
