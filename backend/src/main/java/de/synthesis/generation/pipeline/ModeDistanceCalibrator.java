package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experimentelle Zwei-Hebel-Kalibrierung der ÖV-Moduswahl über Distanz:
 * {@code asc_put} regelt das globale ÖV-Niveau, {@code b_tt_put} den relativen
 * ÖV-Anstieg bei langen Wegen. Beide Parameter werden gemeinsam angepasst, weil
 * {@code b_tt_put} allein Niveau und Distanzschräge vermischt (Failure E20).
 *
 * <p>Die Niveaukorrektur folgt der Abweichung der globalen Log-Odds. Für die
 * Distanzschräge wird die ASC-unempfindlichere Differenz
 * {@code logit(ÖV >=20 km) - logit(ÖV gesamt)} verwendet. Die inverse empirische
 * Elastizität von 0,0095 stammt aus dem präregistrierten Raster vom 2026-07-28
 * ({@code d(Log-Odds-Kontrast)/d b_tt_put ≈ 105}). Beide Schritte sind geklemmt.
 */
public class ModeDistanceCalibrator {

    static final double ASC_CLAMP = 0.75;
    static final double BETA_CLAMP = 0.04;
    static final double BETA_INVERSE_ELASTICITY = 0.0095;
    static final long MIN_LONG_TRIPS = 30;

    private static final String ASC_PUT = "asc_put";
    private static final String B_TT_PUT = "b_tt_put";
    private static final double MIN_SHARE = 0.003;

    /** Liefert die Modus-Distanz-Messung eines Simulationslaufs. */
    public interface SimRun {
        SimulationResultIngestor.ModeDistanceMetrics run(Path mnlFile) throws Exception;
    }

    public record Result(
            Path calibratedFile,
            int iterations,
            boolean converged,
            double targetOverallPtShare,
            double targetLongPtShare,
            double startError,
            double finalError,
            double finalAscPut,
            double finalBttPut,
            SimulationResultIngestor.ModeDistanceMetrics startMetrics,
            SimulationResultIngestor.ModeDistanceMetrics finalMetrics,
            List<String> log) {
    }

    private final Path baseMnlFile;
    private final Path outputFile;
    private final double targetOverallPtShare;
    private final double targetLongPtShare;
    private final int maxIterations;
    private final double tolerance;

    public ModeDistanceCalibrator(
            Path baseMnlFile,
            Path outputFile,
            double targetOverallPtShare,
            double targetLongPtShare,
            int maxIterations,
            double tolerance) {
        requireShare(targetOverallPtShare, "Ziel ÖV gesamt");
        requireShare(targetLongPtShare, "Ziel ÖV ab 20 km");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("Mindestens eine Kalibrierungsiteration erwartet");
        }
        if (!Double.isFinite(tolerance) || tolerance <= 0) {
            throw new IllegalArgumentException("Kalibrierungstoleranz muss positiv sein");
        }
        this.baseMnlFile = baseMnlFile;
        this.outputFile = outputFile;
        this.targetOverallPtShare = targetOverallPtShare;
        this.targetLongPtShare = targetLongPtShare;
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    public Result calibrate(SimRun sim) throws Exception {
        List<String> lines = Files.readAllLines(baseMnlFile, StandardCharsets.UTF_8);
        Map<String, Double> parameters = parseParameters(lines);
        requireParameter(parameters, ASC_PUT);
        requireParameter(parameters, B_TT_PUT);

        List<String> log = new ArrayList<>();
        SimulationResultIngestor.ModeDistanceMetrics start = null;
        SimulationResultIngestor.ModeDistanceMetrics current = null;
        double startError = Double.NaN;
        double error = Double.NaN;
        boolean converged = false;
        int iterations = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            writeMnl(lines, parameters);
            current = sim.run(outputFile);
            validateMetrics(current);
            error = jointError(current);
            if (start == null) {
                start = current;
                startError = error;
            }
            iterations = iteration + 1;
            log.add(String.format(
                    "Iter %d: ÖV gesamt %.2f%% (Ziel %.2f%%), ÖV >=20 km %.2f%% "
                            + "(Ziel %.2f%%), Zwei-Ziel-RMSE %.4f, asc_put %.3f, b_tt_put %.4f",
                    iterations,
                    current.overallPublicTransportShare() * 100,
                    targetOverallPtShare * 100,
                    current.longPublicTransportShare() * 100,
                    targetLongPtShare * 100,
                    error,
                    parameters.get(ASC_PUT),
                    parameters.get(B_TT_PUT)));

            converged = Math.abs(current.overallPublicTransportShare() - targetOverallPtShare)
                    <= tolerance
                    && Math.abs(current.longPublicTransportShare() - targetLongPtShare) <= tolerance;
            if (converged || iterations == maxIterations) {
                break;
            }

            double levelError = logit(targetOverallPtShare)
                    - logit(current.overallPublicTransportShare());
            double observedShape = logit(current.longPublicTransportShare())
                    - logit(current.overallPublicTransportShare());
            double targetShape = logit(targetLongPtShare) - logit(targetOverallPtShare);
            double shapeError = targetShape - observedShape;

            parameters.merge(ASC_PUT, clamp(levelError, ASC_CLAMP), Double::sum);
            parameters.merge(B_TT_PUT,
                    clamp(BETA_INVERSE_ELASTICITY * shapeError, BETA_CLAMP), Double::sum);
        }

        return new Result(
                outputFile,
                iterations,
                converged,
                targetOverallPtShare,
                targetLongPtShare,
                startError,
                error,
                parameters.get(ASC_PUT),
                parameters.get(B_TT_PUT),
                start,
                current,
                List.copyOf(log));
    }

    private static Map<String, Double> parseParameters(List<String> lines) {
        Map<String, Double> parameters = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (ASC_PUT.equals(key) || B_TT_PUT.equals(key)) {
                parameters.put(key, Double.parseDouble(line.substring(eq + 1).trim()));
            }
        }
        return parameters;
    }

    private void writeMnl(List<String> lines, Map<String, Double> parameters) throws IOException {
        Files.createDirectories(outputFile.getParent());
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            int eq = line.indexOf('=');
            String key = eq < 0 ? "" : line.substring(0, eq).trim();
            if (parameters.containsKey(key)) {
                out.add(key + " = " + parameters.get(key));
            } else {
                out.add(line);
            }
        }
        Files.write(outputFile, out, StandardCharsets.UTF_8);
    }

    private double jointError(SimulationResultIngestor.ModeDistanceMetrics metrics) {
        double overall = metrics.overallPublicTransportShare() - targetOverallPtShare;
        double distance = metrics.longPublicTransportShare() - targetLongPtShare;
        return Math.sqrt((overall * overall + distance * distance) / 2.0);
    }

    private static double logit(double share) {
        double bounded = Math.max(MIN_SHARE, Math.min(1.0 - MIN_SHARE, share));
        return Math.log(bounded / (1.0 - bounded));
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static void validateMetrics(SimulationResultIngestor.ModeDistanceMetrics metrics) {
        if (metrics == null || metrics.trips() <= 0) {
            throw new IllegalStateException("Modus-Distanz-Kalibrierung: keine Wege messbar");
        }
        if (metrics.longTrips() < MIN_LONG_TRIPS) {
            throw new IllegalStateException(
                    "Modus-Distanz-Kalibrierung benötigt mindestens " + MIN_LONG_TRIPS
                            + " Wege ab 20 km; gemessen: " + metrics.longTrips());
        }
        requireObservedShare(metrics.overallPublicTransportShare(), "ÖV gesamt");
        requireObservedShare(metrics.longPublicTransportShare(), "ÖV ab 20 km");
    }

    private static void requireObservedShare(double share, String name) {
        if (!Double.isFinite(share) || share < 0 || share > 1) {
            throw new IllegalStateException(name + " ist kein gültiger Anteil: " + share);
        }
    }

    private static void requireShare(double share, String name) {
        if (!Double.isFinite(share) || share <= 0 || share >= 1) {
            throw new IllegalArgumentException(name + " muss zwischen 0 und 1 liegen");
        }
    }

    private static void requireParameter(Map<String, Double> parameters, String key) {
        if (!parameters.containsKey(key) || !Double.isFinite(parameters.get(key))) {
            throw new IllegalArgumentException("MNL-Parameter fehlt oder ist ungültig: " + key);
        }
    }
}
