package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridZoningTest {

    @Test
    void autoCellSizeFindsFinestFiftyMeterStepWithinZoneLimit() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.613;
        region.minLon = 7.825;
        region.maxLat = 49.107;
        region.maxLon = 8.575;

        double effective = GridZoning.autoCellSize(region, 100, 600);
        int zones = GridZoning.generate(region, effective, 600).size();

        assertTrue(effective > 2_000 && effective < 3_000,
                "Großraum soll praktisch fein statt auf 10 km gerastert werden: " + effective);
        assertTrue(zones <= 600);
        assertEquals(0, (int) effective % 50);
        try {
            GridZoning.generate(region, effective - 50, 600);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Die nächstfeinere 50-m-Stufe müsste das Zonenlimit überschreiten");
    }
}
