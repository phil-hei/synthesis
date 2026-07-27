package de.synthesis;

import de.synthesis.reference.Mid2017;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisPropertiesTest {

    @Test
    void distanceCalibrationUsesOfficialNearTripShareByDefault() {
        SynthesisProperties properties = new SynthesisProperties();

        assertThat(properties.getSimulation().getDistanceCalibrationTargetNearShare())
                .isEqualTo(Mid2017.NEAR_TRIP_SHARE)
                .isEqualTo(0.21);
    }
}
