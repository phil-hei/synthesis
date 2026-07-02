package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the GTA-3c LLM mode-choice runner against a running local LLM (Ollama):
 * the per-distance-band aggregation yields a valid normalized modal split, and the same
 * scenario seed reproduces it. Self-skips when the endpoint is unreachable.
 */
class LlmModeChoiceTest {

    /** Demand row with legId=0 and the given distanceInKm at the expected column indices. */
    private static String row(double km) {
        String[] c = new String[17];
        java.util.Arrays.fill(c, "");
        c[1] = "0";          // legId
        c[16] = String.valueOf(km); // distanceInKm
        return String.join(";", c);
    }

    @Test
    void aggregatesValidModalSplitAndIsReproducible() throws Exception {
        SynthesisProperties.Llm config = new SynthesisProperties.Llm();
        assumeTrue(new LlmModeChoiceRunner(config, 1L).isAvailable(),
                "Lokales LLM nicht erreichbar – übersprungen");

        Path demand = Files.createTempFile("mc-demand", ".csv");
        try {
            Files.writeString(demand, "header\n" + row(0.5) + "\n" + row(3.0) + "\n" + row(15.0) + "\n");

            LlmModeChoiceRunner.Result r = new LlmModeChoiceRunner(config, 77L).run(demand, m -> { });
            assertEquals(3, r.trips(), "drei Trips eingelesen");
            assertEquals(3, r.bands().size(), "drei Distanzbänder belegt");

            Map<String, Double> split = r.modalSplit();
            double sum = 0;
            for (String mode : LlmModeChoiceRunner.MODES) {
                double v = split.getOrDefault(mode, -1.0);
                assertTrue(v >= 0 && v <= 1, "Anteil " + mode + " in [0,1]: " + v);
                sum += v;
            }
            assertEquals(1.0, sum, 0.02, "Modal-Split summiert zu 1");

            // Gleicher Seed → identischer Modal-Split
            LlmModeChoiceRunner.Result r2 = new LlmModeChoiceRunner(config, 77L).run(demand, m -> { });
            assertEquals(split, r2.modalSplit(), "gleicher Szenario-Seed muss reproduzierbar sein");
        } finally {
            Files.deleteIfExists(demand);
        }
    }
}
