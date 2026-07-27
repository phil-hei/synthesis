package de.synthesis.generation.pipeline;

import de.synthesis.reference.Mid2017;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests der Distanz-Kalibrierung – ohne echte Simulation. Ein Fake-SimRun bildet die
 * gemessene Elastizität nach: {@code <1km-Anteil = Basisanteil · exp(0,32·Δb_intrazonal)}
 * (empirisch 2026-07-12), sodass die ln-Updates des Kalibrators genau die Dynamik treffen,
 * für die sie entworfen sind.
 */
class DistanceCalibratorTest {

    private static final double ORIGINAL_B = 2.24786362258153; // b_intrazonal der SHOPPING-Fixture
    private static final double BASE_SHARE = 0.25;             // Nahanteil bei Δb = 0
    private static final double FAKE_ELASTICITY = 0.32;        // d(ln Nahanteil)/d b_intrazonal

    /** Legt Basis-Zielwahl-Dateien (5) mit b_intrazonal + Struktur-Rauschen an. */
    private static Path writeFixture() throws IOException {
        Path dir = Files.createTempDirectory("dc-base");
        Files.writeString(dir.resolve("destination_choice_utility_calculation_parameters.txt"),
                "b_tt_car_d = -0.0688 -0.05\nb_cost = -0.1283\n");
        for (String f : new String[]{"BUSINESS", "LEISURE", "SERVICE", "SHOPPING"}) {
            Files.writeString(dir.resolve("destination_choice_parameters_" + f + ".txt"),
                    "b_attr = 0.14 +0.25\n"
                            + "b_intrazonal = " + ORIGINAL_B + "\n"
                            + "shift_intrazonal_on_attr = -0.064\n"   // darf NICHT angefasst werden
                            + "max_attractivity = 1000000\n");
        }
        return dir;
    }

    private static double shoppingB(Path dcDir) throws IOException {
        for (String line : Files.readAllLines(dcDir.resolve("destination_choice_parameters_SHOPPING.txt"))) {
            if (line.strip().matches("b_intrazonal\\s*=.*")) {
                return DistanceCalibrator.sumTerms(line);
            }
        }
        throw new IllegalStateException("keine b_intrazonal-Zeile");
    }

    private static double fakeSim(Path dcDir) throws IOException {
        double delta = shoppingB(dcDir) - ORIGINAL_B;
        double share = BASE_SHARE * Math.exp(FAKE_ELASTICITY * delta);
        return Math.max(0.003, Math.min(0.997, share));
    }

    @Test
    void convergesTowardTargetNearShare() throws Exception {
        Path base = writeFixture();
        Path out = Files.createTempDirectory("dc-out").resolve("dc-calibrated");
        DistanceCalibrator calibrator = new DistanceCalibrator(
                base, out, Mid2017.NEAR_TRIP_SHARE, 10, 0.005);

        DistanceCalibrator.Result r = calibrator.calibrate(DistanceCalibratorTest::fakeSim);

        assertTrue(Math.abs(r.finalNearShare() - Mid2017.NEAR_TRIP_SHARE) <= 0.005,
                "muss den Ziel-Nahanteil erreichen, ist=" + r.finalNearShare());
        assertTrue(r.finalNearShare() < r.startNearShare(),
                "Nahanteil muss in Richtung Ziel sinken: " + r.startNearShare() + " -> " + r.finalNearShare());
        assertTrue(r.appliedDelta() < 0, "b_intrazonal wird reduziert (Δ<0), war " + r.appliedDelta());

        // Struktur erhalten: Basis-Datei kopiert, Nicht-Intrazonal-Zeilen unangetastet
        assertTrue(Files.exists(out.resolve("destination_choice_utility_calculation_parameters.txt")));
        var shopping = Files.readAllLines(out.resolve("destination_choice_parameters_SHOPPING.txt"));
        assertTrue(shopping.contains("shift_intrazonal_on_attr = -0.064"),
                "shift_intrazonal_on_attr darf nicht verändert werden");
        assertTrue(shopping.contains("max_attractivity = 1000000"), "übrige Zeilen erhalten");
    }

    @Test
    void increasesNearShareWhenItIsBelowOfficialTarget() throws Exception {
        Path base = writeFixture();
        Path out = Files.createTempDirectory("dc-out-low").resolve("dc-calibrated");
        double lowBaseShare = 0.15;
        DistanceCalibrator calibrator = new DistanceCalibrator(
                base, out, Mid2017.NEAR_TRIP_SHARE, 10, 0.005);

        DistanceCalibrator.Result r = calibrator.calibrate(dcDir -> {
            double delta = shoppingB(dcDir) - ORIGINAL_B;
            return lowBaseShare * Math.exp(FAKE_ELASTICITY * delta);
        });

        assertTrue(Math.abs(r.finalNearShare() - Mid2017.NEAR_TRIP_SHARE) <= 0.005,
                "muss den amtlichen Ziel-Nahanteil erreichen, ist=" + r.finalNearShare());
        assertTrue(r.finalNearShare() > r.startNearShare(),
                "Nahanteil muss in Richtung Ziel steigen: "
                        + r.startNearShare() + " -> " + r.finalNearShare());
        assertTrue(r.appliedDelta() > 0, "b_intrazonal wird erhöht (Δ>0), war " + r.appliedDelta());
    }

    @Test
    void clampsPerIteration() throws Exception {
        Path base = writeFixture();
        Path out = Files.createTempDirectory("dc-out2").resolve("dc-calibrated");
        int iterations = 3;
        // Extremes Ziel (0,003) → ln(0,003/0,25)·3 ≈ -13 würde ungebremst explodieren;
        // je Iteration ist der Schritt auf ±2,0 begrenzt.
        DistanceCalibrator calibrator = new DistanceCalibrator(base, out, 0.003, iterations, 1e-6);
        calibrator.calibrate(DistanceCalibratorTest::fakeSim);

        // Datei hält den Stand VOR dem letzten Update: (iterations-1) Schritte à -2,0
        double b = shoppingB(out);
        assertEquals(ORIGINAL_B + (iterations - 1) * -2.0, b, 1e-9,
                "b_intrazonal-Schritt muss je Iteration auf ±2,0 begrenzt sein");
    }
}
