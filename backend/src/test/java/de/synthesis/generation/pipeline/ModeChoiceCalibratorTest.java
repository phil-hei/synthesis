package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests für die ASC-Kalibrierung – ohne echte Simulation: ein Fake-SimRun
 * bildet die MNL-Mechanik als Softmax über die ASCs ab, sodass die ln-Updates
 * des Kalibrators exakt die Dynamik treffen, für die sie entworfen sind.
 */
class ModeChoiceCalibratorTest {

    private static final String BASE_MNL = """
            # Moduswahl-Parameter (Testfixture)
            b_time = -0.05
            asc_ped = 0.0
            asc_bike = 0.0
            asc_car_d = 0.25
            asc_car_p = 0.0
            asc_put = 0.0
            """;

    /** Verhaltens-Bias je Modus: macht den Start-Split fußlastig (wie Rastatt roh). */
    private static final Map<String, Double> BIAS = Map.of(
            "pedestrian", 1.2, "bike", -0.3, "car", 0.8, "passenger", -0.7, "publicTransport", -1.0);

    private static final Map<String, String> ASC_KEY = Map.of(
            "pedestrian", "asc_ped", "bike", "asc_bike", "car", "asc_car_d",
            "passenger", "asc_car_p", "publicTransport", "asc_put");

    /** Softmax-Modal-Split aus den ASCs der geschriebenen MNL-Datei. */
    private static Map<String, Double> softmaxSim(Path mnlFile) throws Exception {
        Map<String, Double> asc = new LinkedHashMap<>();
        for (String line : Files.readAllLines(mnlFile)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                asc.put(line.substring(0, eq).trim(), Double.parseDouble(line.substring(eq + 1).trim()));
            }
        }
        Map<String, Double> split = new LinkedHashMap<>();
        double sum = 0;
        for (Map.Entry<String, String> e : ASC_KEY.entrySet()) {
            double u = Math.exp(asc.getOrDefault(e.getValue(), 0.0) + BIAS.get(e.getKey()));
            split.put(e.getKey(), u);
            sum += u;
        }
        for (String mode : split.keySet()) {
            split.put(mode, split.get(mode) / sum);
        }
        return split;
    }

    @Test
    void convergesTowardTargetAndKeepsAnchorFixed() throws Exception {
        Path base = Files.createTempFile("mnl-base", ".txt");
        Path out = Files.createTempDirectory("calib").resolve("sub").resolve("mnl-calibrated.txt");
        try {
            Files.writeString(base, BASE_MNL);
            ModeChoiceCalibrator calibrator = new ModeChoiceCalibrator(
                    base, out, ModeChoiceCalibrator.MID_2017, 10, 0.02);

            ModeChoiceCalibrator.Result result = calibrator.calibrate(ModeChoiceCalibratorTest::softmaxSim);

            assertTrue(result.finalRmse() < result.startRmse(),
                    "RMSE muss sinken: " + result.startRmse() + " -> " + result.finalRmse());
            assertTrue(result.finalRmse() <= 0.02,
                    "muss innerhalb 10 Iterationen konvergieren, RMSE=" + result.finalRmse());
            // Anker Auto-Fahrer bleibt unangetastet, Nicht-ASC-Zeilen bleiben erhalten
            List<String> lines = Files.readAllLines(out);
            assertTrue(lines.contains("asc_car_d = 0.25"), "Anker asc_car_d unverändert");
            assertTrue(lines.contains("b_time = -0.05"), "Sensitivitäten bleiben unverändert");
            assertTrue(lines.get(0).startsWith("#"), "Dateistruktur (Kommentare) erhalten");
        } finally {
            Files.deleteIfExists(base);
        }
    }

    @Test
    void clampsExtremeAdjustmentsPerIteration() throws Exception {
        Path base = Files.createTempFile("mnl-base", ".txt");
        Path out = Files.createTempDirectory("calib").resolve("mnl-calibrated.txt");
        try {
            Files.writeString(base, BASE_MNL);
            int iterations = 3;
            ModeChoiceCalibrator calibrator = new ModeChoiceCalibrator(
                    base, out, ModeChoiceCalibrator.MID_2017, iterations, 0.0001);

            // Sim ignoriert die ASCs und meldet ÖV praktisch 0 → ln(0.10/0.003) ≈ 3.5 würde
            // ungebremst explodieren; das Update muss je Iteration auf +1.5 begrenzt sein.
            calibrator.calibrate(mnl -> Map.of(
                    "pedestrian", 0.22, "bike", 0.11, "car", 0.43, "passenger", 0.14,
                    "publicTransport", 0.0));

            double ascPut = Files.readAllLines(out).stream()
                    .filter(l -> l.startsWith("asc_put"))
                    .map(l -> Double.parseDouble(l.substring(l.indexOf('=') + 1).trim()))
                    .findFirst().orElseThrow();
            // Datei enthält den Stand VOR dem letzten Update: (iterations-1) Schritte à max +1.5
            assertEquals((iterations - 1) * 1.5, ascPut, 1e-9,
                    "ΔASC muss je Iteration auf ±1.5 begrenzt sein");
        } finally {
            Files.deleteIfExists(base);
        }
    }
}
