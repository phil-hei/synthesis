package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MSA-Dämpfung: k=1 ungedämpft, danach 1/k-Schritte, Konvergenz bei konstanten Faktoren. */
class FeedbackMathTest {

    @Test
    void firstIterationAdoptsFactorsThenDampens() {
        double[][] smoothed = {{1, 1}, {1, 1}};
        double[][] factors = {{1, 2.0}, {1.5, 1}};

        double delta1 = FeedbackMath.msaUpdate(smoothed, factors, 1);
        assertEquals(2.0, smoothed[0][1], 1e-9, "k=1 übernimmt Faktor ungedämpft");
        assertEquals(1.5, smoothed[1][0], 1e-9);
        assertEquals((1.0 + 0.5) / 2, delta1, 1e-9, "Δ = Mittel über Relationen ≠ 1");

        // k=2 mit denselben Faktoren: keine Änderung mehr (Gleichgewicht erreicht)
        double delta2 = FeedbackMath.msaUpdate(smoothed, factors, 2);
        assertEquals(0, delta2, 1e-9, "konstante Faktoren → Δ = 0 (Konvergenz)");
        assertEquals(2.0, smoothed[0][1], 1e-9);
    }

    @Test
    void oscillationIsDampenedTowardMean() {
        // Ungedämpft würde der Faktor zwischen 1 und 2 springen; MSA nähert sich dem Mittel
        double[][] smoothed = {{1}};
        double[][] high = {{2.0}};
        double[][] low = {{1.0}};
        double last = 1;
        for (int k = 1; k <= 20; k++) {
            FeedbackMath.msaUpdate(smoothed, k % 2 == 1 ? high : low, k);
            last = smoothed[0][0];
        }
        assertTrue(last > 1.3 && last < 1.7, "MSA dämpft Oszillation Richtung Mittelwert: " + last);
    }
}
