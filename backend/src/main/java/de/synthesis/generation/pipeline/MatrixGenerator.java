package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes travel time, cost and distance matrices per mode and writes them in the
 * tutorial's matrix repository layout (Visum .mtx.bz2 plus time-/cost-matrix-
 * configuration.yaml). Car/bike/foot impedance comes from an optional {@link Impedance}
 * provider (e.g. GraphHopper routing); without it — or for any pair the provider
 * cannot route — a beeline x detour estimate is used. Public transport and costs are
 * derived from the car distance/time.
 */
public class MatrixGenerator {

    private static final double CAR_DETOUR = 1.35;
    /**
     * Erwartete Luftlinie eines Weges INNERHALB einer Zelle: E[Distanz zweier gleichverteilter
     * Punkte im Einheitsquadrat] ≈ 0,5214 x Kantenlänge. Ersetzt den früheren festen
     * 0,3-km-Boden, der nicht mit der Zellgröße skalierte (Median-Kollaps bei groben Zonen,
     * Befund docs/06 "Phase 1b", 2026-07-12).
     */
    private static final double INTRAZONAL_BEELINE_FACTOR = 0.5214;
    private static final double CAR_SPEED_KMH = 28;
    private static final double BIKE_SPEED_KMH = 14;
    private static final double WALK_SPEED_KMH = 4.7;
    private static final double PT_TIME_FACTOR = 1.7;
    private static final double PT_WAIT_MIN = 8;
    private static final double CAR_COST_PER_KM = 0.32;
    private static final double PT_BASE_FARE = 1.80;
    private static final double PT_COST_PER_KM = 0.15;

    /** Mode for which a routing provider can supply door-to-door distance and time. */
    public enum TravelMode {CAR, BIKE, FOOT}

    /** Zuletzt erzeugte Pkw-Reisezeitmatrix (Minuten) – Basis fürs SUMO-Netz-Feedback. */
    private float[][] carTravelTimeMin;

    public float[][] carTravelTimeMin() {
        return carTravelTimeMin;
    }

    /** Supplies real routing impedance; returns {@code null} to fall back to beeline. */
    public interface Impedance {
        /**
         * @return {distanceMeters, timeMinutes} for the mode, or {@code null} if the
         *         pair is unroutable / should use the beeline estimate
         */
        double[] impedance(Zone from, Zone to, TravelMode mode);
    }

    public List<String> generate(Path matrixDir, List<Zone> zones) throws IOException {
        return generate(matrixDir, zones, null);
    }

    /**
     * @param impedance optional real-routing provider; {@code null} = pure beeline
     */
    public List<String> generate(Path matrixDir, List<Zone> zones, Impedance impedance)
            throws IOException {
        int n = zones.size();
        float[][] distance = new float[n][n];
        float[][] carTime = new float[n][n];
        float[][] bikeTime = new float[n][n];
        float[][] walkTime = new float[n][n];
        float[][] ptTime = new float[n][n];
        float[][] ptAccess = new float[n][n];
        float[][] carCost = new float[n][n];
        float[][] ptCost = new float[n][n];

        float[][] bikeDistance = new float[n][n];
        float[][] airDistance = new float[n][n];
        for (Zone from : zones) {
            for (Zone to : zones) {
                // Intrazonal (Zentroid==Zentroid, Luftlinie 0): erwartete Binnendistanz der Zelle
                // statt 0 — sonst ist z. B. Laufen innerhalb der Zone "gratis" (0 min) und die
                // Distanzmatrix kollabiert auf einen festen Wert. Alle Modi erben konsistent.
                double beelineKm = from.index == to.index
                        ? INTRAZONAL_BEELINE_FACTOR * Math.sqrt(from.areaKm2)
                        : GridZoning.distanceKm(from, to);
                int i = from.index;
                int j = to.index;

                // Auto: Routing, sonst Luftlinie x Umwegfaktor
                double[] car = route(impedance, from, to, TravelMode.CAR);
                double carKm;
                if (car != null) {
                    carKm = car[0] / 1000;
                } else if (from.index == to.index) {
                    carKm = intrazonalCarDistanceKm(from);
                } else {
                    carKm = beelineKm * CAR_DETOUR;
                }
                double carMin = car != null ? car[1] : carKm / CAR_SPEED_KMH * 60 + 3;

                double[] bike = route(impedance, from, to, TravelMode.BIKE);
                double bikeKm = bike != null ? bike[0] / 1000 : beelineKm * 1.25;
                double bikeMin = bike != null ? bike[1] : bikeKm / BIKE_SPEED_KMH * 60 + 2;

                double[] foot = route(impedance, from, to, TravelMode.FOOT);
                double walkKm = foot != null ? foot[0] / 1000 : beelineKm * 1.2;
                double walkMin = foot != null ? foot[1] : walkKm / WALK_SPEED_KMH * 60;

                // Distanzmatrizen in METERN (validiert gegen demandsimulation.csv des Tutorials)
                distance[i][j] = (float) (carKm * 1000);
                bikeDistance[i][j] = (float) (bikeKm * 1000);
                airDistance[i][j] = (float) (beelineKm * 1000);
                carTime[i][j] = (float) carMin;
                bikeTime[i][j] = (float) bikeMin;
                walkTime[i][j] = (float) walkMin;
                ptTime[i][j] = (float) (carMin * PT_TIME_FACTOR + PT_WAIT_MIN);
                ptAccess[i][j] = 6;
                carCost[i][j] = (float) (carKm * CAR_COST_PER_KM);
                ptCost[i][j] = (float) (PT_BASE_FARE + beelineKm * PT_COST_PER_KM);
            }
        }

        this.carTravelTimeMin = carTime;

        Map<String, float[][]> files = new LinkedHashMap<>();
        files.put("travel_times/CAR_TTC.mtx.bz2", carTime);
        files.put("travel_times/BIKE_TTC.mtx.bz2", bikeTime);
        files.put("travel_times/PED_TTC.mtx.bz2", walkTime);
        files.put("travel_times/PUT_TTC.mtx.bz2", ptTime);
        files.put("travel_times/PUT_ACT.mtx.bz2", ptAccess);
        files.put("travel_times/PUT_EGT.mtx.bz2", ptAccess);
        files.put("travelcosts/CAR_COST.mtx.bz2", carCost);
        files.put("travelcosts/PUT_COST.mtx.bz2", ptCost);
        files.put("travelcosts/BS_COST.mtx.bz2", ptCost);
        // Tutorial-Layout: Distanzmatrizen liegen unter other/
        files.put("other/CAR_DIS.mtx.bz2", distance);
        files.put("other/BIKE_DIS.mtx.bz2", bikeDistance);
        files.put("other/AIR_DIS.mtx.bz2", airDistance);
        files.put("DIS_Car.mtx.bz2", distance);

        for (Map.Entry<String, float[][]> entry : files.entrySet()) {
            VisumMatrixWriter.write(matrixDir.resolve(entry.getKey()), zones, entry.getValue());
        }
        writeTimeConfig(matrixDir.resolve("time-matrix-configuration.yaml"));
        writeCostConfig(matrixDir.resolve("cost-matrix-configuration.yaml"));
        return files.keySet().stream().toList();
    }

    /**
     * Der in {@code CAR_DIS} repräsentierte intrazonale Pkw-Wert. Die
     * Distanz-Kalibrierung kann ihren {@code b_intrazonal}-Hebel nur gegen ein
     * {@code <1 km}-Ziel verwenden, wenn dieser Wert selbst im Zielband liegt.
     */
    public static double intrazonalCarDistanceKm(Zone zone) {
        return INTRAZONAL_BEELINE_FACTOR * Math.sqrt(zone.areaKm2) * CAR_DETOUR;
    }

    private static double[] route(Impedance impedance, Zone from, Zone to, TravelMode mode) {
        // Externe Zonen liegen außerhalb des gerouteten Netzes: GraphHopper würde sie auf
        // den nächsten Randknoten snappen (zu kurz) → für sie immer Luftlinie verwenden.
        // Intrazonal (from==to) wäre Zentroid→sich selbst = 0 m → Binnendistanz-Schätzer nutzen.
        if (impedance == null || from.external || to.external || from.index == to.index) {
            return null;
        }
        return impedance.impedance(from, to, mode);
    }

    /** Every mode of the target project must appear in both configs (validated finding). */
    private void writeTimeConfig(Path file) throws IOException {
        Map<String, String> modeToMatrix = new LinkedHashMap<>();
        modeToMatrix.put("BIKE", "visum_matrix: $travel_times/BIKE_TTC.mtx.bz2");
        modeToMatrix.put("BIKESHARING", "visum_matrix: $travel_times/BIKE_TTC.mtx.bz2");
        modeToMatrix.put("CAR", "visum_matrix: $travel_times/CAR_TTC.mtx.bz2");
        modeToMatrix.put("PASSENGER", "visum_matrix: $travel_times/CAR_TTC.mtx.bz2");
        modeToMatrix.put("PEDESTRIAN", "visum_matrix: $travel_times/PED_TTC.mtx.bz2");
        modeToMatrix.put("PUBLICTRANSPORT", "visum_matrix: $travel_times/PUT_TTC.mtx.bz2");
        writeConfig(file, modeToMatrix);
    }

    private void writeCostConfig(Path file) throws IOException {
        Map<String, String> modeToMatrix = new LinkedHashMap<>();
        modeToMatrix.put("BIKE", "constant_matrix: \"0.0\"");
        modeToMatrix.put("PEDESTRIAN", "constant_matrix: \"0.0\"");
        modeToMatrix.put("BIKESHARING", "visum_matrix: $travelcosts/BS_COST.mtx.bz2");
        modeToMatrix.put("CAR", "visum_matrix: $travelcosts/CAR_COST.mtx.bz2");
        modeToMatrix.put("PASSENGER", "visum_matrix: $travelcosts/CAR_COST.mtx.bz2");
        modeToMatrix.put("PUBLICTRANSPORT", "visum_matrix: $travelcosts/PUT_COST.mtx.bz2");
        writeConfig(file, modeToMatrix);
    }

    /** Layout as in the tutorial: mode -> all weeks -> everyday -> time span -> matrix entry. */
    private void writeConfig(Path file, Map<String, String> modeToMatrix) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            for (Map.Entry<String, String> entry : modeToMatrix.entrySet()) {
                w.write(entry.getKey() + ":\n");
                w.write("  all weeks:\n");
                w.write("    everyday:\n");
                w.write("      0:00 to 24:00:\n");
                w.write("        " + entry.getValue() + "\n");
            }
        }
    }
}
