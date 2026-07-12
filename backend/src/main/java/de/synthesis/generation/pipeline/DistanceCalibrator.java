package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand-Kalibrierung der Distanz-Sensitivität der mobiTopp-Zielwahl: reduziert die
 * Nahdistanz-Überkonzentration, indem der Intrazonal-Bonus {@code b_intrazonal} in den
 * Zweck-Dateien (business/leisure/service/shopping) iterativ angepasst wird, bis der
 * simulierte {@code <1 km}-Wegeanteil einen Zielwert trifft.
 *
 * <p>Analog zum {@link ModeChoiceCalibrator} (ln-Update, Clamp), aber mit EINER Stellgröße
 * für EIN Ziel (den Nahdistanz-Anteil). Die Update-Konstante folgt aus der empirisch
 * gemessenen Elastizität (2026-07-12): {@code d(ln <1km-Anteil)/d b_intrazonal ≈ 0,32}, also
 * {@code Δb ≈ (1/0,32)·ln(Ziel/Ist) ≈ 3·ln(Ziel/Ist)} — b_intrazonal ↓ ⇒ Nahdistanz ↓, Ø ↑.
 *
 * <p><b>Grenze (dokumentiert, docs/06 „Phase 1b"):</b> kalibriert wird die Verteilungs-FORM
 * (Nahband), nicht der absolute Ø-Mittelwert — in einem kleinen Gebiet lassen sich keine
 * Fernwege erzeugen. Das ist ein Gebietsgrößen-Problem, kein Kalibrier-Ziel.
 */
public class DistanceCalibrator {

    /** Inverse der empirischen Elastizität {@code d(ln <1km)/db_intrazonal ≈ 0,32}. */
    public static final double INVERSE_ELASTICITY = 3.0;

    private static final String BASE_FILE = "destination_choice_utility_calculation_parameters.txt";
    private static final String[] PURPOSE_FILES = {
            "destination_choice_parameters_BUSINESS.txt",
            "destination_choice_parameters_LEISURE.txt",
            "destination_choice_parameters_SERVICE.txt",
            "destination_choice_parameters_SHOPPING.txt"};

    /** Liefert für ein Verzeichnis gepatchter Zielwahl-Dateien den simulierten {@code <1 km}-Anteil. */
    public interface SimRun {
        double nearShare(Path destinationChoiceDir) throws Exception;
    }

    public record Result(Path calibratedDir, int iterations, double startNearShare,
                         double finalNearShare, double targetNearShare, double appliedDelta,
                         List<String> log) {
    }

    private final Path baseParamsDir;
    private final Path outputDir;
    private final double targetNearShare;
    private final int maxIterations;
    private final double tolerance;
    private final double clampPerIteration = 2.0;

    /**
     * @param baseParamsDir   Verzeichnis der Projekt-Standard-Zielwahl-Dateien
     *                        (i. d. R. {@code <projekt>/config/shared/parameters})
     * @param outputDir       Zielverzeichnis der gepatchten Kopien (per Szenario)
     * @param targetNearShare Ziel-Anteil der Wege {@code < 1 km} (0..1)
     */
    public DistanceCalibrator(Path baseParamsDir, Path outputDir, double targetNearShare,
                              int maxIterations, double tolerance) {
        this.baseParamsDir = baseParamsDir;
        this.outputDir = outputDir;
        this.targetNearShare = targetNearShare;
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    public Result calibrate(SimRun sim) throws Exception {
        Files.createDirectories(outputDir);
        // Basis-Datei (Reisezeit-Betas) unverändert mitkopieren – die Run-Config referenziert sie.
        Files.copy(baseParamsDir.resolve(BASE_FILE), outputDir.resolve(BASE_FILE),
                StandardCopyOption.REPLACE_EXISTING);

        List<String> log = new ArrayList<>();
        double cumulativeDelta = 0;
        double startShare = -1;
        double share = Double.NaN;
        int iteration = 0;

        for (; iteration < maxIterations; iteration++) {
            writePurposeFiles(cumulativeDelta);          // stets vom Original + kumulativem Delta
            share = sim.nearShare(outputDir);
            if (startShare < 0) {
                startShare = share;
            }
            log.add(String.format("Iter %d: <1km-Anteil %.1f%% (Ziel %.1f%%, b_intrazonal-Δ %.2f)",
                    iteration + 1, share * 100, targetNearShare * 100, cumulativeDelta));
            if (Math.abs(share - targetNearShare) <= tolerance) {
                iteration++;
                break;
            }
            double delta = INVERSE_ELASTICITY
                    * Math.log(Math.max(targetNearShare, 0.003) / Math.max(share, 0.003));
            delta = Math.max(-clampPerIteration, Math.min(clampPerIteration, delta));
            cumulativeDelta += delta;
        }
        return new Result(outputDir, iteration, startShare, share, targetNearShare, cumulativeDelta, log);
    }

    /** Schreibt je Zweck-Datei eine Kopie mit {@code b_intrazonal := (Originalsumme) + delta}. */
    private void writePurposeFiles(double delta) throws IOException {
        for (String pf : PURPOSE_FILES) {
            List<String> lines = Files.readAllLines(baseParamsDir.resolve(pf), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (isIntrazonalLine(line)) {
                    out.add("b_intrazonal = " + (sumTerms(line) + delta));
                } else {
                    out.add(line);
                }
            }
            Files.write(outputDir.resolve(pf), out);
        }
    }

    /** Nur die Zeile des Schlüssels {@code b_intrazonal} (nicht {@code shift_intrazonal_on_attr} …). */
    private static boolean isIntrazonalLine(String line) {
        return line.strip().matches("b_intrazonal\\s*=.*");
    }

    /** Summe aller additiven Terme rechts von '=' (das Format erlaubt {@code 2.4 -0.2 -0.2}). */
    static double sumTerms(String line) {
        String rhs = line.substring(line.indexOf('=') + 1).trim();
        double sum = 0;
        for (String token : rhs.split("\\s+")) {
            if (!token.isEmpty()) {
                sum += Double.parseDouble(token);
            }
        }
        return sum;
    }
}
