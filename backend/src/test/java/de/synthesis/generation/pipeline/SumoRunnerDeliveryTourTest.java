package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SumoRunnerDeliveryTourTest {

    @Test
    void closesNormalTourFromLastStopBackToDepot(@TempDir Path tempDir) throws Exception {
        Path tours = tempDir.resolve("planned-tours.csv");
        Files.writeString(tours, """
                tourId;isReturning;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur
                7;false;2;8.000;48.000;8.010;48.010;500;10;4
                7;false;2;8.010;48.010;8.020;48.020;520;12;5
                """);

        SumoRunner runner = new SumoRunner(new SynthesisProperties.Sumo());
        List<SumoRunner.Trip> legs = runner.readDeliveryLegs(tours);

        assertThat(legs).hasSize(3);
        SumoRunner.Trip closure = legs.getLast();
        assertThat(closure.type()).isEqualTo("lkw");
        assertThat(closure.fromLon()).isEqualTo("8.020");
        assertThat(closure.fromLat()).isEqualTo("48.020");
        assertThat(closure.toLon()).isEqualTo("8.000");
        assertThat(closure.toLat()).isEqualTo("48.000");
        assertThat(closure.departSec()).isEqualTo(525 * 60);
    }

    @Test
    void doesNotDuplicateReturningOrAlreadyClosedTour(@TempDir Path tempDir) throws Exception {
        Path tours = tempDir.resolve("planned-tours.csv");
        Files.writeString(tours, """
                tourId;isReturning;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur
                8;true;2;8.020;48.020;8.000;48.000;550;10;0
                9;false;1;8.000;48.000;8.000;48.000;600;10;0
                """);

        SumoRunner runner = new SumoRunner(new SynthesisProperties.Sumo());

        assertThat(runner.readDeliveryLegs(tours)).hasSize(2);
    }

    @Test
    void skipsTruncatedRowsInsteadOfAccessingMissingColumns(@TempDir Path tempDir) throws Exception {
        Path tours = tempDir.resolve("planned-tours.csv");
        Files.writeString(tours, """
                tourId;isReturning;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur
                10;false;2;8.000
                """);

        SumoRunner runner = new SumoRunner(new SynthesisProperties.Sumo());

        assertThat(runner.readDeliveryLegs(tours)).isEmpty();
    }
}
