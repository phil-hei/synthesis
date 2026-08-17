package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinationChoiceParameterAdjusterTest {

    private static final List<String> PURPOSE_FILES = List.of(
            "destination_choice_parameters_BUSINESS.txt",
            "destination_choice_parameters_LEISURE.txt",
            "destination_choice_parameters_SERVICE.txt",
            "destination_choice_parameters_SHOPPING.txt");

    @TempDir
    Path tempDir;

    @Test
    void appliesProvenOffsetToExactlyFiveTravelTimeBetas() throws Exception {
        Path source = prepareSource(true);
        Path output = tempDir.resolve("adjusted");

        assertEquals(output,
                DestinationChoiceParameterAdjuster.applyDistanceDecayCorrection(source, output));

        String adjusted = Files.readString(output.resolve(
                "destination_choice_utility_calculation_parameters.txt"));
        assertTrue(adjusted.contains("b_tt_car_d = -0.0688 -0.05 +0.06"));
        assertTrue(adjusted.contains("b_tt_car_p = -0.0981 -0.05 +0.06"));
        assertTrue(adjusted.contains("b_tt_put = -0.0306 -0.05 -0.01 +0.06"));
        assertTrue(adjusted.contains("b_tt_ped = -0.1149 -0.05 +0.06"));
        assertTrue(adjusted.contains("b_tt_bike = -0.0983 -0.03 +0.06"));
        assertTrue(adjusted.lines().anyMatch(line -> line.equals("b_tt_cs = -0.0005 -0.05")),
                "Nicht präregistrierte Reisezeit-Betas bleiben unverändert");
        assertEquals(5, adjusted.lines().filter(line -> line.endsWith("+0.06")).count());
        assertTrue(Files.readString(source.resolve(
                "destination_choice_utility_calculation_parameters.txt"))
                .lines().noneMatch(line -> line.endsWith("+0.06")),
                "Der geteilte Projekt-Standard darf nie verändert werden");
        for (String filename : PURPOSE_FILES) {
            assertEquals(Files.readString(source.resolve(filename)),
                    Files.readString(output.resolve(filename)));
        }
    }

    @Test
    void failsBeforeWritingWhenARequiredParameterIsMissing() throws Exception {
        Path source = prepareSource(false);
        Path output = tempDir.resolve("invalid-output");

        IOException error = assertThrows(IOException.class,
                () -> DestinationChoiceParameterAdjuster.applyDistanceDecayCorrection(
                        source, output));

        assertTrue(error.getMessage().contains("b_tt_bike"));
        assertTrue(Files.notExists(output));
    }

    private Path prepareSource(boolean includeBike) throws Exception {
        Path source = Files.createDirectories(tempDir.resolve(
                includeBike ? "source-complete" : "source-incomplete"));
        Files.writeString(source.resolve(
                "destination_choice_utility_calculation_parameters.txt"), """
                asc_car_d = 12.1687
                b_tt_car_d = -0.0688 -0.05
                b_tt_car_p = -0.0981 -0.05
                b_tt_put = -0.0306 -0.05 -0.01
                b_tt_ped = -0.1149 -0.05
                %s
                b_tt_cs = -0.0005 -0.05
                """.formatted(includeBike ? "b_tt_bike = -0.0983 -0.03" : ""));
        for (String filename : PURPOSE_FILES) {
            Files.writeString(source.resolve(filename), "b_intrazonal = 1.0\n");
        }
        return source;
    }
}
