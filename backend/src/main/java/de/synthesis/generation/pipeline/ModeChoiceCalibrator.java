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
 * On-demand-Konstanten-Kalibrierung der mobiTopp-Moduswahl (MNL): passt die
 * alternativenspezifischen Konstanten (ASC) im MNL-Parameterfile iterativ so an,
 * dass der simulierte Modal-Split einen Zielwert (z. B. MiD-Regional-Anteile)
 * trifft. Die geschätzten Sensitivitäten bleiben unverändert (Parameter-Transfer +
 * lokale Kalibrierung). Auto-Fahrer (asc_car_d) ist die Referenz und bleibt fest;
 * pro Iteration ΔASC_m = ln(Ziel_m / Sim_m) für die übrigen Modi.
 */
public class ModeChoiceCalibrator {

    /** kpis.json-Modal-Split-Schlüssel → ASC-Parametername. Auto-Fahrer = Anker. */
    private static final Map<String, String> ADJUSTABLE = Map.of(
            "pedestrian", "asc_ped",
            "bike", "asc_bike",
            "passenger", "asc_car_p",
            "publicTransport", "asc_put");
    private static final String ANCHOR_MODE = "car"; // asc_car_d bleibt fest

    /** Default-Ziel: MiD-2017-Modal-Split (bundesweit); überschreibbar. */
    public static final Map<String, Double> MID_2017 = Map.of(
            "pedestrian", 0.22, "bike", 0.11, "car", 0.43, "passenger", 0.14, "publicTransport", 0.10);

    /** Liefert den simulierten Modal-Split für eine gegebene MNL-Parameterdatei. */
    public interface SimRun {
        Map<String, Double> run(Path mnlFile) throws Exception;
    }

    public record Result(Path calibratedFile, int iterations, double startRmse, double finalRmse,
                         Map<String, Double> finalSplit, List<String> log) {
    }

    private final Path baseMnlFile;
    private final Path outputFile;
    private final Map<String, Double> target;
    private final int maxIterations;
    private final double tolerance;

    public ModeChoiceCalibrator(Path baseMnlFile, Path outputFile, Map<String, Double> target,
                                int maxIterations, double tolerance) {
        this.baseMnlFile = baseMnlFile;
        this.outputFile = outputFile;
        this.target = target;
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    public Result calibrate(SimRun sim) throws Exception {
        List<String> lines = Files.readAllLines(baseMnlFile, StandardCharsets.UTF_8);
        Map<String, Double> asc = parseAsc(lines);
        List<String> log = new ArrayList<>();
        double startRmse = -1;
        double rmse = Double.MAX_VALUE;
        Map<String, Double> split = Map.of();
        int iteration = 0;

        for (; iteration < maxIterations; iteration++) {
            writeMnl(lines, asc);
            split = sim.run(outputFile);
            rmse = rmse(split);
            if (startRmse < 0) {
                startRmse = rmse;
            }
            log.add(String.format("Iter %d: Modal-Split %s, RMSE %.4f",
                    iteration + 1, format(split), rmse));
            if (rmse <= tolerance) {
                iteration++;
                break;
            }
            // ASC-Update: ΔASC_m = ln(Ziel_m / Sim_m), gedämpft/begrenzt; Anker fest
            for (Map.Entry<String, String> entry : ADJUSTABLE.entrySet()) {
                double simShare = Math.max(split.getOrDefault(entry.getKey(), 0.0), 0.003);
                double targetShare = Math.max(target.getOrDefault(entry.getKey(), 0.0), 0.003);
                double delta = Math.log(targetShare / simShare);
                delta = Math.max(-1.5, Math.min(1.5, delta)); // Stabilität
                asc.merge(entry.getValue(), delta, Double::sum);
            }
        }
        return new Result(outputFile, iteration, startRmse, rmse, split, log);
    }

    // --- Parameterdatei-Handhabung (Struktur erhalten) -------------------------

    private Map<String, Double> parseAsc(List<String> lines) {
        Map<String, Double> asc = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (ADJUSTABLE.containsValue(key)) {
                asc.put(key, Double.parseDouble(line.substring(eq + 1).trim()));
            }
        }
        return asc;
    }

    private void writeMnl(List<String> lines, Map<String, Double> asc) throws IOException {
        Files.createDirectories(outputFile.getParent());
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            int eq = line.indexOf('=');
            String key = eq < 0 ? "" : line.substring(0, eq).trim();
            if (asc.containsKey(key)) {
                out.add(key + " = " + asc.get(key));
            } else {
                out.add(line);
            }
        }
        Files.write(outputFile, out);
    }

    // --- Metriken --------------------------------------------------------------

    private double rmse(Map<String, Double> split) {
        double sum = 0;
        int n = 0;
        for (String mode : modes()) {
            double s = split.getOrDefault(mode, 0.0);
            double t = target.getOrDefault(mode, 0.0);
            sum += (s - t) * (s - t);
            n++;
        }
        return Math.sqrt(sum / n);
    }

    private List<String> modes() {
        List<String> m = new ArrayList<>(ADJUSTABLE.keySet());
        m.add(ANCHOR_MODE);
        return m;
    }

    private static String format(Map<String, Double> split) {
        StringBuilder sb = new StringBuilder("{");
        split.forEach((k, v) -> sb.append(k).append("=").append(Math.round(v * 100)).append("% "));
        return sb.append("}").toString();
    }
}
