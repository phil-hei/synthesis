package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.generation.demand.LogitoppDemandAdapter;
import de.synthesis.generation.demand.TravelDemand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SumoDemandSelectionTest {

    @Test
    void lateArrivalsExtendHorizonWithoutSelectingNextDayDepartures(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("overnight.csv");
        Files.writeString(csv, """
                personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd
                p;late;0;1;1;1;15;A;B;8.1;48.1;8.2;48.2;2877;2915
                p;next-day;0;1;1;1;15;B;A;8.2;48.2;8.1;48.1;2880;4000
                """);
        TravelDemand demand = new LogitoppDemandAdapter().read(csv);
        assertThat(SumoRunner.simulationEndSeconds(demand, 1, 86220)).isEqualTo(92100);
        var config = new SynthesisProperties.Sumo();config.setDay(1);config.setMaxTrips(-1);
        assertThat(new SumoRunner(config).selectDemand(demand,false).cars()).hasSize(1);
        assertThat(SumoRunner.simulationEndSeconds(new TravelDemand(java.util.List.of(),0),1,100)).isEqualTo(86400);
        assertThat(SumoRunner.simulationEndSeconds(new TravelDemand(java.util.List.of(),0),1,86399)).isEqualTo(89999);
    }

    @Test
    void defaultTransfersAllEligibleTripsBeyondFormerLimit(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("full-demand.csv");
        StringBuilder content = new StringBuilder("personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd\n");
        for (int i = 0; i < 4100; i++) {
            content.append("p").append(i).append(";t").append(i)
                    .append(";0;1;1;1;2;A;B;8.1;48.1;8.2;48.2;10;15\n");
        }
        Files.writeString(csv, content);
        TravelDemand demand = new LogitoppDemandAdapter().read(csv);
        SynthesisProperties.Sumo config = new SynthesisProperties.Sumo();
        SumoRunner.DemandSelection selection = new SumoRunner(config).selectDemand(demand, false);
        assertThat(selection.cars()).hasSize(4100);
        assertThat(selection.capped()).isZero();
        assertThat(selection.emitted()).isEqualTo(demand.tripCount());

        // An explicit zero keeps its existing meaning; only negative values disable the cap.
        config.setMaxTrips(0);
        SumoRunner.DemandSelection none = new SumoRunner(config).selectDemand(demand, false);
        assertThat(none.emitted()).isZero();
        assertThat(none.capped()).isEqualTo(4100);
    }

    @Test
    void accountsForEveryCanonicalTripAndUsesDeterministicModeCaps(@TempDir Path tempDir)
            throws Exception {
        Path csv = tempDir.resolve("demand.csv");
        Files.writeString(csv, """
                personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd
                p-late;car-2;0;1;1;1;2;A;B;8.1;48.1;8.9;48.9;20;25
                p-early;car-1;0;1;1;1;2;A;B;8.2;48.2;8.3;48.3;10;15
                p-bike;bike;0;0;0;0;2;B;A;8.3;48.3;8.2;48.2;30;35
                p-walk;walk;0;3;3;3;2;A;B;8.1;48.1;8.2;48.2;1450;1455
                p-passenger;passenger;0;2;2;2;2;A;B;8.1;48.1;8.2;48.2;40;45
                p-pt;pt;0;3,4,3;4;3;0.2;A;X;8.1;48.1;8.2;48.2;50;51
                p-pt;pt;1;3,4,3;4;4;1.6;X;Y;8.1;48.1;8.2;48.2;50;54
                p-pt;pt;2;3,4,3;4;3;0.2;Y;B;8.1;48.1;8.2;48.2;50;55
                p-other;other;0;99;99;99;2;A;B;8.1;48.1;8.2;48.2;60;65
                """);
        TravelDemand demand = new LogitoppDemandAdapter().read(csv);
        SynthesisProperties.Sumo config = new SynthesisProperties.Sumo();
        config.setDay(0);
        config.setMaxTrips(1);
        SumoRunner runner = new SumoRunner(config);

        SumoRunner.DemandSelection selection = runner.selectDemand(demand, false);

        assertThat(selection.cars()).hasSize(1);
        assertThat(selection.cars().getFirst().fromLon()).isEqualTo("8.1");
        assertThat(selection.bikes()).hasSize(1);
        assertThat(selection.walkers()).isEmpty();
        assertThat(selection.transitRiders()).isEmpty();
        assertThat(selection.emitted()).isEqualTo(2);
        assertThat(selection.otherDay()).isEqualTo(1);
        assertThat(selection.unsupported()).isEqualTo(3);
        assertThat(selection.capped()).isEqualTo(1);
        assertThat(selection.emitted() + selection.otherDay()
                + selection.unsupported() + selection.capped())
                .isEqualTo(demand.tripCount());

        SumoRunner.DemandSelection withPublicTransport = runner.selectDemand(demand, true);
        assertThat(withPublicTransport.transitRiders()).hasSize(1);
        assertThat(withPublicTransport.unsupported()).isEqualTo(2);
    }

    @Test
    void spreadsCappedTripsAcrossTheObservedDay(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("spread.csv");
        StringBuilder content = new StringBuilder("""
                personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd
                """);
        for (int i = 1; i <= 6; i++) {
            int begin = i * 10;
            content.append("p").append(i).append(";t").append(i)
                    .append(";0;1;1;1;2;A;B;8.1;48.1;8.2;48.2;")
                    .append(begin).append(';').append(begin + 5).append('\n');
        }
        Files.writeString(csv, content);
        SynthesisProperties.Sumo config = new SynthesisProperties.Sumo();
        config.setDay(0);
        config.setMaxTrips(2);
        SumoRunner runner = new SumoRunner(config);

        SumoRunner.DemandSelection selection =
                runner.selectDemand(new LogitoppDemandAdapter().read(csv), false);

        assertThat(selection.cars()).extracting(SumoRunner.Trip::departSec)
                .containsExactly(20 * 60, 50 * 60);
        assertThat(selection.capped()).isEqualTo(4);
    }
}
