package de.synthesis.generation.pipeline;

import de.synthesis.reference.Mid2017;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests der Zwei-Hebel-Kalibrierung ohne externen logiTopp-Lauf. Das Fake-MNL
 * bildet die im Großraum-Experiment gemessene Richtung ab: {@code asc_put} verschiebt
 * beide Log-Odds, {@code b_tt_put} die Fernweg-Log-Odds deutlich stärker.
 */
class ModeDistanceCalibratorTest {

    private static final double TARGET_OVERALL = 0.10;
    private static final double TARGET_LONG = Mid2017.MODAL_SPLIT_BY_DISTANCE_BAND.getLast()
            .modalSplit().get("publicTransport");
    private static final double TARGET_ASC = -1.22;
    private static final double TARGET_BETA = -0.11;
    private static final String BASE_MNL = """
            # Struktur und Fremdparameter müssen erhalten bleiben
            asc_car_d = 0.25
            asc_put = -2.10
            b_tt_car_d = -0.20
            b_tt_put = -0.05
            """;

    @Test
    void convergesWithSeparateLevelAndDistanceSlopeLevers() throws Exception {
        Path base = Files.createTempFile("mode-distance-base", ".txt");
        Path out = Files.createTempDirectory("mode-distance-out")
                .resolve("mode_choice_calibrated.txt");
        Files.writeString(base, BASE_MNL);

        ModeDistanceCalibrator calibrator = new ModeDistanceCalibrator(
                base, out, TARGET_OVERALL, TARGET_LONG, 8, 0.01);
        ModeDistanceCalibrator.Result result = calibrator.calibrate(
                ModeDistanceCalibratorTest::fakeSimulation);

        assertTrue(result.converged(), "beide ÖV-Ziele müssen konvergieren: " + result.log());
        assertTrue(result.finalError() < result.startError(),
                "Zwei-Ziel-RMSE muss sinken");
        assertEquals(TARGET_OVERALL,
                result.finalMetrics().overallPublicTransportShare(), 0.01);
        assertEquals(TARGET_LONG,
                result.finalMetrics().longPublicTransportShare(), 0.01);
        assertEquals(TARGET_ASC, result.finalAscPut(), 0.12);
        assertEquals(TARGET_BETA, result.finalBttPut(), 0.01);

        List<String> lines = Files.readAllLines(out);
        assertTrue(lines.getFirst().startsWith("#"), "Kommentare bleiben erhalten");
        assertTrue(lines.contains("asc_car_d = 0.25"), "Pkw-Anker bleibt unverändert");
        assertTrue(lines.contains("b_tt_car_d = -0.20"), "Pkw-Zeitkoeffizient bleibt unverändert");
    }

    @Test
    void clampsBothParameterUpdatesAndPersistsLastSimulatedState() throws Exception {
        Path base = Files.createTempFile("mode-distance-clamp", ".txt");
        Path out = Files.createTempDirectory("mode-distance-clamp-out").resolve("mnl.txt");
        Files.writeString(base, BASE_MNL);
        int iterations = 3;

        ModeDistanceCalibrator calibrator = new ModeDistanceCalibrator(
                base, out, TARGET_OVERALL, TARGET_LONG, iterations, 1e-6);
        ModeDistanceCalibrator.Result result = calibrator.calibrate(mnl ->
                new SimulationResultIngestor.ModeDistanceMetrics(
                        1_000, 100, 0.0, 1.0, 0.2));

        assertEquals(-2.10 + (iterations - 1) * ModeDistanceCalibrator.ASC_CLAMP,
                result.finalAscPut(), 1e-9);
        assertEquals(-0.05 - (iterations - 1) * ModeDistanceCalibrator.BETA_CLAMP,
                result.finalBttPut(), 1e-9);
        assertTrue(Files.readString(out).contains("asc_put = " + result.finalAscPut()),
                "Ergebnisparameter müssen dem tatsächlich simulierten Dateistand entsprechen");
        assertTrue(Files.readString(out).contains("b_tt_put = " + result.finalBttPut()),
                "Ergebnisparameter müssen dem tatsächlich simulierten Dateistand entsprechen");
    }

    @Test
    void rawMetricsAggregateLegDistancesBeforeChoosingTheBand() throws Exception {
        Path csv = Files.createTempFile("mode-distance-demand", ".csv");
        Files.writeString(csv, """
                personOid;tripId;legId;tripMode;distanceInKm
                1;1;0;4;12.0
                1;1;1;4;9.0
                2;1;0;1;25.0
                3;1;0;4;5.0
                """);

        SimulationResultIngestor.ModeDistanceMetrics metrics =
                SimulationResultIngestor.readModeDistanceMetrics(csv);

        assertEquals(3, metrics.trips());
        assertEquals(2, metrics.longTrips(), "12+9 km muss als ein >=20-km-Weg zählen");
        assertEquals(2.0 / 3.0, metrics.overallPublicTransportShare(), 1e-9);
        assertEquals(0.5, metrics.longPublicTransportShare(), 1e-9);
        assertTrue(Double.isFinite(metrics.jointRmseToMid2017()));
    }

    private static SimulationResultIngestor.ModeDistanceMetrics fakeSimulation(Path mnl)
            throws Exception {
        double asc = parameter(mnl, "asc_put");
        double beta = parameter(mnl, "b_tt_put");

        // Optimum liegt beim empirisch belegten Zweierpunkt; b_tt_put wirkt auf
        // Fernwege wesentlich stärker als auf den Gesamtanteil.
        double overallLogOdds = logit(TARGET_OVERALL)
                + 0.83 * (asc - TARGET_ASC)
                + 27.0 * (beta - TARGET_BETA);
        double longLogOdds = logit(TARGET_LONG)
                + 0.83 * (asc - TARGET_ASC)
                + 105.0 * (beta - TARGET_BETA);
        double overall = logistic(overallLogOdds);
        double longShare = logistic(longLogOdds);
        return new SimulationResultIngestor.ModeDistanceMetrics(
                10_000, 1_300, overall, longShare,
                Math.sqrt((square(overall - TARGET_OVERALL)
                        + square(longShare - TARGET_LONG)) / 2.0));
    }

    private static double parameter(Path file, String key) throws Exception {
        return Files.readAllLines(file).stream()
                .filter(line -> line.startsWith(key + " "))
                .map(line -> Double.parseDouble(line.substring(line.indexOf('=') + 1).trim()))
                .findFirst()
                .orElseThrow();
    }

    private static double logit(double share) {
        return Math.log(share / (1.0 - share));
    }

    private static double logistic(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    private static double square(double value) {
        return value * value;
    }
}
