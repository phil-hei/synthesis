package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
                "DestinationZone;DistributionCenter\n");
        Files.writeString(results.resolve("parcel-states.csv"), "State;ParcelID\n");
        Files.writeString(results.resolve("planned-tours.csv"),
                "stopNo;vehicleTag;fromDepot;vehicleType;fromX;fromY;toX;toY;distance\n");
        Files.writeString(zoneRepository.resolve("zones.geojson"),
                "{\"type\":\"FeatureCollection\",\"features\":[]}");

        new SimulationResultIngestor(mapper).ingest(results, artifacts, 12);

        JsonNode kpis = mapper.readTree(artifacts.resolve("simulation/kpis.json").toFile());
        assertThat(kpis.at("/demand/trips").asLong()).isEqualTo(2);
        assertThat(kpis.at("/demand/avgTripKm").asDouble()).isEqualTo(10.55);
        assertThat(kpis.at("/demand/distanceBands/under1Km").asDouble()).isZero();
        assertThat(kpis.at("/demand/distanceBands/km1ToUnder2").asDouble()).isEqualTo(0.5);
        assertThat(kpis.at("/demand/distanceBands/km20AndMore").asDouble()).isEqualTo(0.5);
        assertThat(kpis.at("/demand/distanceBandRmseToMid2017").asDouble()).isEqualTo(0.2601);
        assertThat(kpis.at("/mid2017/distanceBands/under1Km").asDouble()).isEqualTo(0.21);
        assertThat(kpis.at("/mid2017/distanceBands/km20AndMore").asDouble()).isEqualTo(0.13);
        assertThat(kpis.at("/mid2017/distanceBandsSource").asText())
                .contains("Tabelle A W12");
    }
}
