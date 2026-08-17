package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Erzeugt einen szenariogebundenen Zielwahl-Parameterstand mit der über drei Seeds
 * bestätigten Korrektur des Distanz-Abklingens. Der Projekt-Standard bleibt unverändert.
 */
public final class DestinationChoiceParameterAdjuster {

    /** Präregistrierter und über Seeds 42/43/44 bestätigter additiver Term. */
    public static final double DISTANCE_DECAY_OFFSET = 0.06;

    private static final String BASE_FILE =
            "destination_choice_utility_calculation_parameters.txt";
    private static final List<String> PURPOSE_FILES = List.of(
            "destination_choice_parameters_BUSINESS.txt",
            "destination_choice_parameters_LEISURE.txt",
            "destination_choice_parameters_SERVICE.txt",
            "destination_choice_parameters_SHOPPING.txt");
    private static final Set<String> TARGET_PARAMETERS = Set.of(
            "b_tt_car_d", "b_tt_car_p", "b_tt_put", "b_tt_ped", "b_tt_bike");

    private DestinationChoiceParameterAdjuster() {
    }

    /**
     * Kopiert den vollständigen Zielwahl-Vertrag und hängt an genau die fünf bestätigten
     * Reisezeit-Betas {@code +0.06} an. Fehlende oder doppelte Schlüssel brechen sichtbar ab.
     */
    public static Path applyDistanceDecayCorrection(Path sourceDir, Path outputDir)
            throws IOException {
        Path sourceBase = sourceDir.resolve(BASE_FILE);
        List<String> sourceLines = Files.readAllLines(sourceBase, StandardCharsets.UTF_8);
        List<String> adjusted = new ArrayList<>(sourceLines.size());
        Set<String> found = new LinkedHashSet<>();
        String term = String.format(Locale.ROOT, " %+.2f", DISTANCE_DECAY_OFFSET);

        for (String line : sourceLines) {
            String key = parameterKey(line);
            if (TARGET_PARAMETERS.contains(key)) {
                if (!found.add(key)) {
                    throw new IOException("Doppelter Zielwahl-Parameter: " + key);
                }
                adjusted.add(line.stripTrailing() + term);
            } else {
                adjusted.add(line);
            }
        }
        if (!found.equals(TARGET_PARAMETERS)) {
            Set<String> missing = new LinkedHashSet<>(TARGET_PARAMETERS);
            missing.removeAll(found);
            throw new IOException("Fehlende Zielwahl-Parameter für Distanz-Abklingen: " + missing);
        }

        Files.createDirectories(outputDir);
        Files.write(outputDir.resolve(BASE_FILE), adjusted, StandardCharsets.UTF_8);
        for (String filename : PURPOSE_FILES) {
            Files.copy(sourceDir.resolve(filename), outputDir.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return outputDir;
    }

    private static String parameterKey(String line) {
        int equals = line.indexOf('=');
        return equals > 0 ? line.substring(0, equals).strip() : "";
    }
}
