package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SimulationResultIngestorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void classifiesExactDistanceBandBoundaries() {
        assertThat(SimulationResultIngestor.distanceBandIndex(0.9999)).isZero();
        assertThat(SimulationResultIngestor.distanceBandIndex(1.0)).isEqualTo(1);
        assertThat(SimulationResultIngestor.distanceBandIndex(2.0)).isEqualTo(2);
        assertThat(SimulationResultIngestor.distanceBandIndex(5.0)).isEqualTo(3);
        assertThat(SimulationResultIngestor.distanceBandIndex(10.0)).isEqualTo(4);
        assertThat(SimulationResultIngestor.distanceBandIndex(20.0)).isEqualTo(5);
        assertThat(SimulationResultIngestor.distanceBandRmse(
                new long[]{21, 14, 23, 16, 13, 13}, 100)).isZero();
    }

    @Test
    void writesAggregatedTripDistanceBandsAndMidReference(@TempDir Path tempDir) throws Exception {
        Path results = Files.createDirectories(tempDir.resolve("results"));
        Path artifacts = Files.createDirectories(tempDir.resolve("artifacts"));
        Files.createDirectories(artifacts.resolve("simulation"));
        Path zoneRepository = Files.createDirectories(artifacts.resolve("zone-repository"));

        Files.writeString(results.resolve("demandsimulationResult.csv"), """
                tripId;legId;personOid;distanceInKm;tripMode
                1;0;person-1;0.6;1
                1;1;person-1;0.5;4
                2;0;person-2;20.0;3
                """);
        Files.writeString(results.resolve("fleet.csv"), "owner;vehicleType\n");
        Files.writeString(results.resolve("parcel-orders-private.csv"),
                "ParcelID;RecipientID;DestinationZone;DistributionCenter\n");
        Files.writeString(results.resolve("parcel-states.csv"), "State;ParcelID;IsPickup\n");
        Files.writeString(results.resolve("planned-tours.csv"),
                "tourId;stopNo;vehicleTag;fromDepot;vehicleType;fromX;fromY;toX;toY;"
                        + "distance;deliveries;pickups\n");
        Files.writeString(zoneRepository.resolve("zones.geojson"),
                "{\"type\":\"FeatureCollection\",\"features\":[]}");

        new SimulationResultIngestor(mapper).ingest(
                results, artifacts, 12, new Scenario.ReturnDemand());

        JsonNode kpis = mapper.readTree(artifacts.resolve("simulation/kpis.json").toFile());
        assertThat(kpis.at("/demand/trips").asLong()).isEqualTo(2);
        assertThat(kpis.at("/demand/avgTripKm").asDouble()).isEqualTo(10.55);
        assertThat(kpis.at("/demand/distanceBands/under1Km").asDouble()).isZero();
        assertThat(kpis.at("/demand/distanceBands/km1ToUnder2").asDouble()).isEqualTo(0.5);
        assertThat(kpis.at("/demand/distanceBands/km20AndMore").asDouble()).isEqualTo(0.5);
        assertThat(kpis.at("/demand/distanceBandRmseToMid2017").asDouble()).isEqualTo(0.2601);
        assertThat(kpis.at("/demand/modalSplitByDistanceBand/km1ToUnder2/car").asDouble())
                .isEqualTo(1.0);
        assertThat(kpis.at("/demand/modalSplitByDistanceBand/km20AndMore/pedestrian").asDouble())
                .isEqualTo(1.0);
        assertThat(kpis.at("/demand/modeDistanceJointRmseToMid2017").asDouble())
                .isEqualTo(0.131);
        assertThat(kpis.at("/mid2017/distanceBands/under1Km").asDouble()).isEqualTo(0.21);
        assertThat(kpis.at("/mid2017/distanceBands/km20AndMore").asDouble()).isEqualTo(0.13);
        assertThat(kpis.at("/mid2017/modalSplitByDistanceBand/km20AndMore/car").asDouble())
                .isEqualTo(0.611);
        assertThat(kpis.at("/mid2017/modalSplitByDistanceBand/km20AndMore/publicTransport").asDouble())
                .isEqualTo(0.1761);
        assertThat(kpis.at("/mid2017/distanceBandsSource").asText())
                .contains("Tabelle A W12");
        assertThat(kpis.at("/mid2017/modeDistanceSource").asText())
                .contains("Tabelle A W10.5");
        assertThat(kpis.at("/mid2017/distanceBandsSourceUrl").asText())
                .endsWith("MiD2017_Tabellenband_Deutschland.pdf");
        assertThat(kpis.at("/logistics/contract").asText())
                .isEqualTo(SimulationResultIngestor.LOGISTICS_KPI_CONTRACT);
        assertThat(kpis.at("/logistics/deliveries/generated").asLong()).isZero();
        assertThat(kpis.at("/logistics/customerReturns/generated").asLong()).isZero();
    }

    @Test
    void writesDirectionalLogisticsKpisAndTourProperties(@TempDir Path tempDir) throws Exception {
        Fixture fixture = writeDirectionalFixture(tempDir);
        Scenario.ReturnDemand returns = enabledReturns(0.5);

        SimulationResultIngestor.Summary summary = new SimulationResultIngestor(mapper)
                .ingest(fixture.results(), fixture.artifacts(), 34, returns);

        assertThat(summary.parcels()).isEqualTo(6);
        assertThat(summary.delivered()).isEqualTo(3);
        assertThat(summary.deliveries()).isEqualTo(4);
        assertThat(summary.deliveredDeliveries()).isEqualTo(2);
        assertThat(summary.customerReturns()).isEqualTo(2);
        assertThat(summary.collectedReturns()).isEqualTo(1);

        JsonNode kpis = mapper.readTree(
                fixture.artifacts().resolve("simulation/kpis.json").toFile());
        assertThat(kpis.at("/parcels/total").asLong()).isEqualTo(6);
        assertThat(kpis.at("/parcels/delivered").asLong()).isEqualTo(3);
        assertThat(kpis.at("/logistics/contract").asText())
                .isEqualTo("synthesis-logistics-kpis-v1");
        assertThat(kpis.at("/logistics/deliveries/generated").asLong()).isEqualTo(4);
        assertThat(kpis.at("/logistics/deliveries/delivered").asLong()).isEqualTo(2);
        assertThat(kpis.at("/logistics/deliveries/failedReturning").asLong()).isEqualTo(1);
        assertThat(kpis.at("/logistics/deliveries/deliveryRate").asDouble()).isEqualTo(0.5);
        assertThat(kpis.at("/logistics/customerReturns/generated").asLong()).isEqualTo(2);
        assertThat(kpis.at("/logistics/customerReturns/plannedPickups").asLong()).isEqualTo(2);
        assertThat(kpis.at("/logistics/customerReturns/collected").asLong()).isEqualTo(1);
        assertThat(kpis.at("/logistics/customerReturns/collectionRate").asDouble()).isEqualTo(0.5);

        JsonNode depot = mapper.readTree(
                fixture.artifacts().resolve("simulation/depot-stats.json").toFile()).get(0);
        assertThat(depot.path("deliveriesAssigned").asLong()).isEqualTo(4);
        assertThat(depot.path("customerReturnsAssigned").asLong()).isEqualTo(2);
        assertThat(depot.path("delivered").asLong()).isEqualTo(2);
        assertThat(depot.path("pickupsPlanned").asLong()).isEqualTo(2);
        assertThat(depot.path("pickupsCollected").asLong()).isEqualTo(1);
        assertThat(depot.path("failedReturning").asLong()).isEqualTo(1);

        JsonNode tours = mapper.readTree(
                fixture.artifacts().resolve("simulation/tours.geojson").toFile());
        assertThat(tours.at("/features/0/properties/deliveries").asLong()).isEqualTo(2);
        assertThat(tours.at("/features/0/properties/pickups").asLong()).isEqualTo(1);
        assertThat(tours.at("/features/0/properties/failedDeliveries").asLong()).isEqualTo(1);
        assertThat(tours.at("/features/1/properties/failedDeliveries").asLong()).isZero();

        JsonNode density = mapper.readTree(
                fixture.artifacts().resolve("simulation/parcel-density.geojson").toFile());
        assertThat(density.at("/features/0/properties/parcels").asLong()).isEqualTo(6);
        assertThat(density.at("/features/0/properties/deliveries").asLong()).isEqualTo(4);
        assertThat(density.at("/features/0/properties/customerReturns").asLong()).isEqualTo(2);
    }

    @Test
    void rejectsReturnCountsOutsideRecipientRoundingRule(@TempDir Path tempDir) throws Exception {
        Fixture fixture = writeDirectionalFixture(tempDir);

        assertThatThrownBy(() -> new SimulationResultIngestor(mapper)
                .ingest(fixture.results(), fixture.artifacts(), 34, enabledReturns(1.0)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Retourenmenge verletzt die Ziehregel");
    }

    @Test
    void rejectsPickupResultsWhenReturnsAreDisabled(@TempDir Path tempDir) throws Exception {
        Fixture fixture = writeDirectionalFixture(tempDir);

        assertThatThrownBy(() -> new SimulationResultIngestor(mapper)
                .ingest(fixture.results(), fixture.artifacts(), 34,
                        new Scenario.ReturnDemand()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Retouren sind deaktiviert");
    }

    @Test
    void ingestsRealReturnFixtureWhenAvailable(@TempDir Path tempDir) throws Exception {
        Path results = Path.of("../validation/returns-spike-2026-07-29/"
                + "results-contract-v1-run2");
        assumeTrue(Files.exists(results.resolve("parcel-states.csv")),
                "Echtes Retouren-Fixture nicht vorhanden");
        Path artifacts = Files.createDirectories(tempDir.resolve("artifacts"));
        Files.createDirectories(artifacts.resolve("simulation"));
        Path zoneRepository = Files.createDirectories(artifacts.resolve("zone-repository"));
        Files.writeString(zoneRepository.resolve("zones.geojson"),
                "{\"type\":\"FeatureCollection\",\"features\":[]}");

        SimulationResultIngestor.Summary summary = new SimulationResultIngestor(mapper)
                .ingest(results, artifacts, 120, enabledReturns(1.0));

        assertThat(summary.deliveries()).isEqualTo(101);
        assertThat(summary.deliveredDeliveries()).isEqualTo(65);
        assertThat(summary.customerReturns()).isEqualTo(101);
        assertThat(summary.collectedReturns()).isEqualTo(67);
        JsonNode kpis = mapper.readTree(artifacts.resolve("simulation/kpis.json").toFile());
        assertThat(kpis.at("/logistics/customerReturns/plannedPickups").asLong())
                .isEqualTo(67);
        assertThat(kpis.at("/logistics/customerReturns/collected").asLong())
                .isEqualTo(67);
        assertThat(kpis.at("/logistics/deliveries/failedReturning").asLong()).isZero();

        JsonNode tours = mapper.readTree(artifacts.resolve("simulation/tours.geojson").toFile());
        long plannedDeliveries = 0;
        long plannedPickups = 0;
        for (JsonNode feature : tours.path("features")) {
            plannedDeliveries += feature.at("/properties/deliveries").asLong();
            plannedPickups += feature.at("/properties/pickups").asLong();
        }
        assertThat(plannedDeliveries).isEqualTo(65);
        assertThat(plannedPickups).isEqualTo(67);
    }

    private Fixture writeDirectionalFixture(Path tempDir) throws Exception {
        Path results = Files.createDirectories(tempDir.resolve("results"));
        Path artifacts = Files.createDirectories(tempDir.resolve("artifacts"));
        Files.createDirectories(artifacts.resolve("simulation"));
        Path zoneRepository = Files.createDirectories(artifacts.resolve("zone-repository"));

        Files.writeString(results.resolve("demandsimulationResult.csv"), """
                tripId;legId;personOid;distanceInKm;tripMode
                1;0;person-1;2.0;1
                """);
        Files.writeString(results.resolve("fleet.csv"), """
                owner;vehicleType
                Depot 1;TRUCK
                """);
        Files.writeString(results.resolve("parcel-orders-private.csv"), """
                ParcelID;RecipientID;DestinationZone;DistributionCenter
                d1;r1;Z1;Depot 1
                d2;r1;Z1;Depot 1
                p1;r1;Z1;Depot 1
                d3;r2;Z1;Depot 1
                d4;r2;Z1;Depot 1
                p2;r2;Z1;Depot 1
                """);
        Files.writeString(results.resolve("parcel-states.csv"), """
                State;ParcelID;IsPickup
                UNDEFINED;d1;false
                ONDELIVERY;d1;false
                DELIVERED;d1;false
                UNDEFINED;d2;false
                ONDELIVERY;d2;false
                RETURNING;d2;false
                UNDEFINED;d2;false
                DELIVERED;d3;false
                UNDEFINED;d4;false
                UNDEFINED;p1;true
                ONDELIVERY;p1;true
                DELIVERED;p1;true
                UNDEFINED;p2;true
                """);
        Files.writeString(results.resolve("vehicle_events.csv"), """
                event;to_deliver;success_delivery;to_pickup;success_pickup;returning;collected;veh_name;tour_id;dc;cepsp;distance
                stop;2;1;1;1;1;1;TRUCK_1;10;Depot 1;DHL;1000
                unload;0;0;0;0;1;1;TRUCK_1;10;Depot 1;DHL;0
                stop;1;1;1;0;0;0;TRUCK_1;20;Depot 1;DHL;2000
                unload;0;0;0;0;0;0;TRUCK_1;20;Depot 1;DHL;0
                """);
        Files.writeString(results.resolve("planned-tours.csv"), """
                tourId;stopNo;vehicleTag;fromDepot;vehicleType;fromX;fromY;toX;toY;distance;deliveries;pickups
                10;1;TRUCK_1;Depot 1;TRUCK;8.0;48.0;8.1;48.1;1000;d1,d2;p1
                20;1;TRUCK_1;Depot 1;TRUCK;8.0;48.0;8.2;48.2;2000;d3;p2
                """);
        Files.writeString(zoneRepository.resolve("zones.geojson"), """
                {
                  "type": "FeatureCollection",
                  "features": [{
                    "type": "Feature",
                    "properties": {"zoneId": "Z1"},
                    "geometry": {"type": "Polygon", "coordinates": []}
                  }]
                }
                """);
        return new Fixture(results, artifacts);
    }

    private static Scenario.ReturnDemand enabledReturns(double share) {
        Scenario.ReturnDemand returns = new Scenario.ReturnDemand();
        returns.enabled = true;
        returns.shareOfPrivateOrders = share;
        return returns;
    }

    private record Fixture(Path results, Path artifacts) {
    }
}
