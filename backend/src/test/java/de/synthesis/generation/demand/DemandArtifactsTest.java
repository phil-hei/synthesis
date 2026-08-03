package de.synthesis.generation.demand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemandArtifactsTest {

    private static final String HEADER =
            "personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;"
                    + "targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd\n";

    @Test
    void writesDeterministicDirectionalFifteenMinuteProfiles(@TempDir Path tempDir)
            throws Exception {
        List<String> rows = List.of(
                row("p0", "t0", "1", "A", "B", 0),
                row("p1", "t1", "1", "A", "B", 14.999),
                row("p2", "t2", "0", "B", "A", 15),
                row("p3", "t3", "1", "A", "A", 1439.999),
                row("p4", "t4", "4", "A", "B", 1440),
                row("p5", "t5", "99", "B", "A", 10079.999));
        Path firstSource = tempDir.resolve("first.csv");
        Path reorderedSource = tempDir.resolve("reordered.csv");
        Files.writeString(firstSource, HEADER + String.join("", rows));
        Files.writeString(reorderedSource, HEADER
                + rows.get(5) + rows.get(2) + rows.get(0)
                + rows.get(4) + rows.get(1) + rows.get(3));

        LogitoppDemandAdapter adapter = new LogitoppDemandAdapter();
        ObjectMapper mapper = new ObjectMapper();
        Path first = tempDir.resolve("out-first");
        Path second = tempDir.resolve("out-second");
        Path repeated = tempDir.resolve("out-repeated");
        DemandArtifacts.write(first, adapter.read(firstSource), firstSource, mapper);
        DemandArtifacts.write(second, adapter.read(reorderedSource), reorderedSource, mapper);
        DemandArtifacts.write(repeated, adapter.read(firstSource), firstSource, mapper);

        for (String artifact : List.of(
                "person-trips.csv", "legs.csv", "zone-flows.csv", "od-flows.csv")) {
            assertThat(Files.readAllBytes(first.resolve(artifact)))
                    .as(artifact + " unabhängig von Raw-Zeilenfolge")
                    .isEqualTo(Files.readAllBytes(second.resolve(artifact)));
        }
        for (String artifact : List.of(
                "person-trips.csv", "legs.csv", "zone-flows.csv", "od-flows.csv",
                "metadata.json")) {
            assertThat(Files.readAllBytes(first.resolve(artifact)))
                    .as(artifact + " bei Wiederholung byteidentisch")
                    .isEqualTo(Files.readAllBytes(repeated.resolve(artifact)));
        }
        assertThat(Files.readAllLines(first.resolve("legs.csv")).getFirst())
                .isEqualTo("personOid;tripId;legId;legMode;legModeCode;originZoneId;"
                        + "destinationZoneId;distanceKm");
        assertThat(Files.readAllLines(first.resolve("person-trips.csv")).getFirst())
                .isEqualTo("personOid;tripId;plannedDepartureMinute;plannedArrivalMinute;"
                        + "mode;modeCode;tripModeCode;originZoneId;destinationZoneId;"
                        + "fromLon;fromLat;toLon;toLat;distanceKm;legCount");

        List<String> od = Files.readAllLines(first.resolve("od-flows.csv"));
        assertThat(od).anyMatch(line -> line.startsWith("0;0;0;A;B;car;1;2"));
        assertThat(od).anyMatch(line -> line.startsWith("15;0;15;B;A;bike;0;1"));
        assertThat(od).anyMatch(line -> line.startsWith("1425;0;1425;A;A;car;1;1"));
        assertThat(od).anyMatch(line -> line.startsWith("1440;1;0;A;B;publicTransport;4;1"));
        assertThat(od).anyMatch(line -> line.startsWith("10065;6;1425;B;A;other;99;1"));

        long odTrips = od.stream().skip(1)
                .mapToLong(line -> Long.parseLong(line.substring(line.lastIndexOf(';') + 1)))
                .sum();
        List<String> zone = Files.readAllLines(first.resolve("zone-flows.csv"));
        assertThat(zone).anyMatch(line -> line.startsWith("0;0;0;A;car;1;2;0"));
        assertThat(zone).anyMatch(line -> line.startsWith("10080;7;0;A;other;99;0;1"));
        long departures = zone.stream().skip(1)
                .map(line -> line.split(";", -1))
                .mapToLong(cells -> Long.parseLong(cells[6]))
                .sum();
        long arrivals = zone.stream().skip(1)
                .map(line -> line.split(";", -1))
                .mapToLong(cells -> Long.parseLong(cells[7]))
                .sum();
        assertThat(odTrips).isEqualTo(6);
        assertThat(departures).isEqualTo(6);
        assertThat(arrivals).isEqualTo(6);

        JsonNode metadata = mapper.readTree(first.resolve("metadata.json").toFile());
        assertThat(metadata.path("contract").asText())
                .isEqualTo(TravelDemand.CONTRACT);
        assertThat(metadata.path("demandType").asText())
                .isEqualTo("simulatedPersonTrips");
        assertThat(metadata.path("timeSemantics").asText()).isEqualTo("planned");
        assertThat(metadata.path("aggregationIntervalMinutes").asInt()).isEqualTo(15);
        assertThat(metadata.path("trips").asLong()).isEqualTo(6);
        assertThat(metadata.path("legs").asLong()).isEqualTo(6);
        assertThat(metadata.at("/modes/other").asLong()).isEqualTo(1);
        assertThat(metadata.path("sourceSha256").asText()).hasSize(64);
    }

    private static String row(
            String person, String trip, String mode, String origin, String destination,
            double departure) {
        return person + ";" + trip + ";0;" + mode + ";" + mode + ";" + mode + ";1.0;"
                + origin + ";" + destination + ";8.1;48.1;8.2;48.2;"
                + departure + ";" + (departure + 1) + "\n";
    }
}
