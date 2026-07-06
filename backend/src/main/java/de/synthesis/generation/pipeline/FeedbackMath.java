package de.synthesis.generation.pipeline;

/**
 * Dämpfung der Nachfrage↔Netz-Rückkopplung nach der Method of Successive
 * Averages (MSA): die Stau-Faktoren einer Iteration werden mit Schrittweite 1/k
 * in den geglätteten Zustand gemischt. Das verhindert das für ungedämpfte
 * Gleichgewichtsiterationen typische Oszillieren (Stau → alle weichen aus →
 * kein Stau → alle zurück …) und konvergiert gegen einen stabilen Zustand.
 */
public final class FeedbackMath {

    private FeedbackMath() {
    }

    /**
     * MSA-Update in-place: {@code smoothed += (factors - smoothed) / k}.
     *
     * @param k Iterationszähler (1-basiert; k=1 übernimmt die Faktoren ungedämpft)
     * @return mittlere absolute Änderung der geglätteten Faktoren – das
     *         Konvergenzmaß der Schleife (über Relationen mit Faktor ≠ 1)
     */
    public static double msaUpdate(double[][] smoothed, double[][] factors, int k) {
        double deltaSum = 0;
        int relations = 0;
        for (int i = 0; i < smoothed.length; i++) {
            for (int j = 0; j < smoothed[i].length; j++) {
                double updated = smoothed[i][j] + (factors[i][j] - smoothed[i][j]) / k;
                double delta = Math.abs(updated - smoothed[i][j]);
                smoothed[i][j] = updated;
                if (factors[i][j] != 1 || updated != 1) {
                    deltaSum += delta;
                    relations++;
                }
            }
        }
        return relations == 0 ? 0 : deltaSum / relations;
    }
}
