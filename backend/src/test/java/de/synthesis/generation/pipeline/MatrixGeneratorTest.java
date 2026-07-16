package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Prüft die Intrazonal-Mechanik der Matrizen: die Binnendistanz einer Zone skaliert mit der
 * Zellgröße (0,5214 x Kantenlänge, Erwartungswert im Quadrat) statt des früheren festen
 * 0,3-km-Bodens (Median-Kollaps-Befund docs/06 "Phase 1b"), interzonale Paare bleiben vom
 * Boden unberührt, und intrazonal wird nie geroutet (Zentroid→sich selbst wäre 0 m).
 */
class MatrixGeneratorTest {

    private static final double CAR_DETOUR = 1.35;
    private static final double CAR_SPEED_KMH = 28;

    private static Scenario.Region regionAround(double sizeKm) {
        Scenario.Region r = new Scenario.Region();
        r.minLat = 48.85;
        r.minLon = 8.20;
        r.maxLat = 48.85 + sizeKm / 111.32;
        r.maxLon = 8.20 + sizeKm / (111.32 * Math.cos(Math.toRadians(48.85)));
        return r;
    }

    private static double carMinutesFor(double distanceKm) {
        return distanceKm / CAR_SPEED_KMH * 60 + 3;
    }

    @Test
    void intrazonalScalesWithCellSize(@TempDir Path dir) throws IOException {
        float[][] fine = carTimes(dir.resolve("fine"), regionAround(2), 1000);
        float[][] coarse = carTimes(dir.resolve("coarse"), regionAround(4.6), 2300);

        double fineExpected = carMinutesFor(0.5214 * 1.0 * CAR_DETOUR);
        double coarseExpected = carMinutesFor(0.5214 * 2.3 * CAR_DETOUR);
        assertEquals(fineExpected, fine[0][0], 0.05, "Intrazonal-Zeit bei 1-km-Zellen");
        assertEquals(coarseExpected, coarse[0][0], 0.05, "Intrazonal-Zeit bei 2,3-km-Zellen");
        assertTrue(coarse[0][0] > fine[0][0], "Binnendistanz muss mit der Zellgröße wachsen");
        // der alte feste Boden ergab zellgrößen-unabhängig 0,3*1,35/28*60+3 = 3,87 min
        assertTrue(fine[0][0] > 4.0, "kein 0,3-km-Kollaps mehr");
    }

    @Test
    void interzonalPairsUnaffectedByFloor(@TempDir Path dir) throws IOException {
        Scenario.Region region = regionAround(2);
        List<Zone> zones = GridZoning.generate(region, 1000, 600);
        MatrixGenerator generator = new MatrixGenerator();
        generator.generate(dir, zones);

        Zone a = zones.get(0);
        Zone b = zones.get(1);
        double expected = carMinutesFor(GridZoning.distanceKm(a, b) * CAR_DETOUR);
        assertEquals(expected, generator.carTravelTimeMin()[a.index][b.index], 0.05,
                "interzonal = reine Luftlinie x Umwegfaktor, kein Boden");
    }

    @Test
    void intrazonalNeverAsksTheRouter(@TempDir Path dir) throws IOException {
        Scenario.Region region = regionAround(2);
        List<Zone> zones = GridZoning.generate(region, 1000, 600);
        MatrixGenerator generator = new MatrixGenerator();
        MatrixGenerator.Impedance impedance = (from, to, mode) -> {
            if (from.index == to.index) {
                fail("Routing darf intrazonal nicht befragt werden");
            }
            return new double[]{2000, 10};
        };
        generator.generate(dir, zones, impedance);

        double intrazonalExpected = carMinutesFor(0.5214 * Math.sqrt(zones.get(0).areaKm2) * CAR_DETOUR);
        assertEquals(intrazonalExpected, generator.carTravelTimeMin()[0][0], 0.05,
                "intrazonal nutzt den Binnendistanz-Schätzer, nicht den Router");
        assertEquals(10, generator.carTravelTimeMin()[0][1], 0.001,
                "interzonal nutzt die gerouteten Zeiten");
    }

    private static float[][] carTimes(Path dir, Scenario.Region region, double cellMeters)
            throws IOException {
        List<Zone> zones = GridZoning.generate(region, cellMeters, 600);
        assertTrue(zones.size() >= 2, "Testregion soll mehrere Zonen ergeben");
        assertEquals(cellMeters * cellMeters / 1_000_000, zones.get(0).areaKm2, 0.001,
                "Provider setzt die Zellfläche");
        MatrixGenerator generator = new MatrixGenerator();
        generator.generate(dir, zones);
        return generator.carTravelTimeMin();
    }
}
