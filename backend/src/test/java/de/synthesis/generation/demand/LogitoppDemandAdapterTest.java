package de.synthesis.generation.demand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LogitoppDemandAdapterTest {

    @Test
    void normalizesReorderedMultiLegRowsAndAcceptsTrailingDelimiter(@TempDir Path tempDir)
            throws Exception {
        Path csv = tempDir.resolve("demand.csv");
        Files.writeString(csv, """
                toY;tripId;targetZone;legId;personOid;tripEnd;fromX;tripMode;mainMode;sourceZone;distanceInKm;toX;tripBegin;legMode;fromY;extra;
                48.40;t1;100004;2;p1;35;8.10;3,4,3;4;100003;1.0;8.40;10;3;48.10;x;
                48.40;t1;100003;1;p1;30;8.10;3,4,3;4;100002;9.0;8.40;10;4;48.10;x;
                48.40;t1;100002;0;p1;20;8.10;3,4,3;4;100001;12.0;8.40;10;3;48.10;x;
                """);

        TravelDemand demand = new LogitoppDemandAdapter().read(csv);

        assertThat(demand.sourceRows()).isEqualTo(3);
        assertThat(demand.tripCount()).isEqualTo(1);
        assertThat(demand.legCount()).isEqualTo(3);
        TravelDemand.PersonTrip trip = demand.trips().getFirst();
        assertThat(trip.key()).isEqualTo("p1:t1");
        assertThat(trip.mode()).isEqualTo(TravelDemand.Mode.PUBLIC_TRANSPORT);
        assertThat(trip.modeCode()).isEqualTo("4");
        assertThat(trip.rawTripModeCode()).isEqualTo("3,4,3");
        assertThat(trip.distanceKm()).isEqualTo(22.0);
        assertThat(trip.plannedDepartureMinute()).isEqualTo(10.0);
        assertThat(trip.plannedArrivalMinute()).isEqualTo(35.0);
        assertThat(trip.originZoneId()).isEqualTo("100001");
        assertThat(trip.destinationZoneId()).isEqualTo("100004");
        assertThat(trip.fromLon()).isEqualTo(8.10);
        assertThat(trip.toLon()).isEqualTo(8.40);
        assertThat(trip.legs()).extracting(TravelDemand.Leg::legId).containsExactly(0, 1, 2);
        assertThat(trip.legs()).extracting(TravelDemand.Leg::mode)
                .containsExactly(TravelDemand.Mode.PEDESTRIAN,
                        TravelDemand.Mode.PUBLIC_TRANSPORT,
                        TravelDemand.Mode.PEDESTRIAN);
    }

    @Test
    void retainsUnknownModeExplicitlyAndAllowsSundayArrivalSpillover(@TempDir Path tempDir)
            throws Exception {
        Path csv = tempDir.resolve("demand.csv");
        Files.writeString(csv, header()
                + "p1;t1;0;99;98;98;1.2;100001;100002;8.1;48.1;8.2;48.2;10072;10094\n");

        TravelDemand.PersonTrip trip =
                new LogitoppDemandAdapter().read(csv).trips().getFirst();

        assertThat(trip.mode()).isEqualTo(TravelDemand.Mode.OTHER);
        assertThat(trip.modeCode()).isEqualTo("98");
        assertThat(trip.rawTripModeCode()).isEqualTo("99");
        assertThat(trip.legs().getFirst().mode()).isEqualTo(TravelDemand.Mode.OTHER);
        assertThat(trip.plannedArrivalMinute()).isEqualTo(10094);
    }

    @Test
    void rejectsMalformedInputWithFieldLineAndTrip(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("missing.csv");
        Files.writeString(missing, header().replace("sourceZone;", ""));
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(missing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 1")
                .hasMessageContaining("sourceZone")
                .hasMessageContaining("Pflichtspalte fehlt");

        Path shortRow = tempDir.resolve("short-row.csv");
        Files.writeString(shortRow, header() + "p1;t1;0\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(shortRow))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 2")
                .hasMessageContaining("Feld row")
                .hasMessageContaining("erwartet 15 Spalten");

        Path duplicateLeg = tempDir.resolve("duplicate.csv");
        String row = "p1;t1;0;1;1;1;2.0;100001;100002;8.1;48.1;8.2;48.2;10;12\n";
        Files.writeString(duplicateLeg, header() + row + row);
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(duplicateLeg))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 3")
                .hasMessageContaining("legId")
                .hasMessageContaining("Trip p1:t1")
                .hasMessageContaining("doppeltes Leg");

        Path missingLegZero = tempDir.resolve("missing-leg-zero.csv");
        Files.writeString(missingLegZero, header()
                + "p1;t1;1;1;1;1;2.0;100001;100002;8.1;48.1;8.2;48.2;10;12\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(missingLegZero))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Feld legId")
                .hasMessageContaining("Trip p1:t1")
                .hasMessageContaining("legId=0 fehlt");

        Path inconsistentEnvelope = tempDir.resolve("inconsistent-envelope.csv");
        Files.writeString(inconsistentEnvelope, header()
                + "p1;t1;0;4;4;3;1.0;100001;100002;8.1;48.1;8.3;48.3;10;20\n"
                + "p1;t1;1;4;4;4;1.0;100002;100003;8.2;48.2;8.3;48.3;10;30\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(inconsistentEnvelope))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Feld fromX/fromY/toX/toY")
                .hasMessageContaining("Trip p1:t1")
                .hasMessageContaining("Aktivitätsgeometrie");

        Path nonFinite = tempDir.resolve("non-finite.csv");
        Files.writeString(nonFinite, header()
                + "p1;t1;0;1;1;1;NaN;100001;100002;8.1;48.1;8.2;48.2;10;12\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(nonFinite))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 2")
                .hasMessageContaining("distanceInKm")
                .hasMessageContaining("Trip p1:t1")
                .hasMessageContaining("nicht endlich");

        Path arrivalOutsideHorizon = tempDir.resolve("arrival-outside-horizon.csv");
        Files.writeString(arrivalOutsideHorizon, header()
                + "p1;t1;0;1;1;1;1.0;100001;100002;8.1;48.1;8.2;48.2;10079;11521\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(arrivalOutsideHorizon))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 2")
                .hasMessageContaining("tripEnd")
                .hasMessageContaining("höchstens einem Tag Überlauf");

        Path overflowingDistance = tempDir.resolve("overflowing-distance.csv");
        Files.writeString(overflowingDistance, header()
                + "p1;t1;0;1;1;1;1.7976931348623157E308;100001;100002;"
                + "8.1;48.1;8.2;48.2;10;12\n"
                + "p1;t1;1;1;1;1;1.7976931348623157E308;100002;100003;"
                + "8.1;48.1;8.2;48.2;10;13\n");
        assertThatThrownBy(() -> new LogitoppDemandAdapter().read(overflowingDistance))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zeile 3")
                .hasMessageContaining("distanceInKm")
                .hasMessageContaining("Leg-Distanzsumme ist nicht endlich");
    }

    @Test
    void readsRealRastattFixtureWhenAvailable() throws Exception {
        Path csv = Path.of("../validation/mobitopp-rastatt/output/synthesis/results/"
                + "demandsimulationResult.csv");
        assumeTrue(Files.isRegularFile(csv), "Rastatt-Demand-Fixture nicht vorhanden");

        TravelDemand demand = new LogitoppDemandAdapter().read(csv);
        Map<String, Long> modes = demand.trips().stream().collect(Collectors.groupingBy(
                trip -> trip.modeCode(), Collectors.counting()));

        assertThat(demand.tripCount()).isPositive();
        assertThat(demand.legCount()).isEqualTo(demand.sourceRows());
        assertThat(modes.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(demand.tripCount());
        assertThat(modes).containsKeys("0", "1", "2", "3", "4");
    }

    private static String header() {
        return "personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;"
                + "targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd\n";
    }
}
