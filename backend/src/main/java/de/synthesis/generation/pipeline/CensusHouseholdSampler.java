package de.synthesis.generation.pipeline;

import java.util.*;

/** Reweights existing household compositions against person-age and household-size shares. */
final class CensusHouseholdSampler {
    static final double MAX_FIT_ERROR = 0.02;
    private final List<List<List<PopulationSample.Member>>> patterns = new ArrayList<>();
    private final double[][] counts;
    private final double[] sizes, baseWeights;
    private final CensusStructureSource.Margins target;
    private final PopulationSample sample;
    private double[] weights;
    private final double[] actualAges = new double[3], actualHouseholds = new double[6];
    private double fitError;
    private int iterations, completedBoundaryHouseholds;

    CensusHouseholdSampler(PopulationSample sample, CensusStructureSource.Margins target) {
        this.target = target; this.sample = sample;
        validateShares(target.ages(), 3); validateShares(target.households(), 6);
        Map<String, List<List<PopulationSample.Member>>> grouped = new LinkedHashMap<>();
        for (var household : sample.households()) {
            int[] ages = new int[3];
            for (var member : household) ages[group(member.age())]++;
            grouped.computeIfAbsent(Arrays.toString(ages), k -> new ArrayList<>()).add(household);
        }
        patterns.addAll(grouped.values());
        counts = new double[patterns.size()][9]; sizes = new double[patterns.size()];
        baseWeights = new double[patterns.size()];
        for (int i = 0; i < patterns.size(); i++) {
            var h = patterns.get(i).getFirst(); sizes[i] = h.size();
            for (var member : h) counts[i][group(member.age())]++;
            counts[i][3 + Math.min(6, h.size()) - 1] = 1;
            baseWeights[i] = patterns.get(i).size() / (double) sample.householdCount();
        }
        fit();
    }

    private static void validateShares(double[] values, int length) {
        if (values.length != length || Arrays.stream(values).anyMatch(v -> !Double.isFinite(v) || v < 0)
                || Math.abs(Arrays.stream(values).sum() - 1) > 1e-8)
            throw new IllegalArgumentException("Ungültige Zensus-Strukturanteile");
    }

    private void fit() {
        weights = baseWeights.clone();
        double[] best = weights.clone(); double bestError = error(weights);
        // Exact zero margins restrict the support, including boundary targets of 100%.
        for (int i = 0; i < weights.length; i++) {
            for (int k = 0; k < 9; k++) {
                double share = k < 3 ? target.ages()[k] : target.households()[k - 3];
                if (share == 0 && counts[i][k] > 0) weights[i] = 0;
            }
        }
        double support = Arrays.stream(weights).sum();
        if (support == 0) { weights = best; fitError = bestError; return; }
        for (int i = 0; i < weights.length; i++) weights[i] /= support;
        if (error(weights) < bestError) { best = weights.clone(); bestError = error(weights); }
        // Each constraint is E[ageCount - share * householdSize] = 0 or
        // E[sizeIndicator - householdShare] = 0. Exponential tilting preserves
        // household compositions; coordinate bisection solves each moment constraint.
        for (int iteration = 0; iteration < 100; iteration++) {
            double[] before = weights.clone();
            for (int k = 0; k < 9; k++) {
                double[] f = new double[patterns.size()]; double min = 0, max = 0;
                for (int i = 0; i < f.length; i++) {
                    f[i] = counts[i][k] - (k < 3 ? target.ages()[k] * sizes[i] : target.households()[k - 3]);
                    if (weights[i] > 0) { min = Math.min(min, f[i]); max = Math.max(max, f[i]); }
                }
                // Unsupported moments cannot be repaired by weighting. Retain the
                // residual and report it instead of inventing additional seed types.
                if (min == max || min >= 0 || max <= 0) continue;
                double lo = -30, hi = 30;
                for (int step = 0; step < 40; step++) {
                    double mid = (lo + hi) / 2, moment = 0;
                    for (int i = 0; i < f.length; i++) moment += weights[i] * Math.exp(mid * f[i]) * f[i];
                    if (moment > 0) hi = mid; else lo = mid;
                }
                double total = 0;
                for (int i = 0; i < weights.length; i++) { weights[i] *= Math.exp((lo + hi) / 2 * f[i]); total += weights[i]; }
                for (int i = 0; i < weights.length; i++) weights[i] /= total;
            }
            iterations = iteration + 1;
            double error = error(weights);
            // An unsupported group can fix the maximum residual at a constant floor.
            // Keep subsequent equal-error fits so supported margins can still improve.
            if (error <= bestError) { bestError = error; best = weights.clone(); }
            if (error < 0.001 || maxDifference(before, weights) < 1e-10) break;
        }
        weights = best; fitError = bestError;
    }

    boolean usable() { return Double.isFinite(fitError) && fitError <= MAX_FIT_ERROR; }

    List<PopulationSample.Member> draw(Random random, int remaining) {
        List<PopulationSample.Member> members;
        if (!usable()) {
            members = sample.randomHousehold(random);
        } else {
            // Preserve the fitted distribution and the entire boundary household.
            double roll = random.nextDouble(); int chosen = weights.length - 1;
            for (int i = 0; i < weights.length; i++) {
                roll -= weights[i];
                if (roll < 0) { chosen = i; break; }
            }
            var pool = patterns.get(chosen);
            members = pool.get(random.nextInt(pool.size()));
        }
        if (members.size() > remaining) completedBoundaryHouseholds++;
        observe(members);
        return members;
    }

    void observe(List<PopulationSample.Member> members) {
        actualHouseholds[Math.min(6, members.size()) - 1]++;
        for (var member : members) actualAges[group(member.age())]++;
    }

    Map<String, Object> report() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", usable() ? "weighted" : "seed-fallback");
        r.put("reason", usable() ? "Anteile angenähert; endliche Stichprobe separat prüfen"
                : "Haushaltspool erreicht Zielanteile nicht innerhalb von 2 Prozentpunkten");
        r.put("ageLevel", target.ageLevel()); r.put("householdLevel", target.householdLevel());
        r.put("targetAgeShares", target.ages()); r.put("targetHouseholdShares", target.households());
        r.put("fittedAgeShares", expected(weights, true)); r.put("fittedHouseholdShares", expected(weights, false));
        r.put("expectedAgeShares", expected(usable() ? weights : baseWeights, true));
        r.put("expectedHouseholdShares", expected(usable() ? weights : baseWeights, false));
        r.put("baselineMaxShareError", error(baseWeights)); r.put("fitMaxShareError", fitError);
        r.put("iterations", iterations); r.put("samplePersons", Arrays.stream(actualAges).sum());
        r.put("sampleHouseholds", Arrays.stream(actualHouseholds).sum());
        r.put("sampleAgeShares", CensusStructureSource.shares(actualAges));
        r.put("sampleHouseholdShares", CensusStructureSource.shares(actualHouseholds));
        r.put("sampleMaxShareError", Arrays.stream(actualAges).sum() == 0 ? null : Math.max(
                maxDifference(CensusStructureSource.shares(actualAges), target.ages()),
                maxDifference(CensusStructureSource.shares(actualHouseholds), target.households())));
        r.put("truncatedHouseholds", 0);
        r.put("completedBoundaryHouseholds", completedBoundaryHouseholds);
        List<String> missing = new ArrayList<>();
        for (int k = 0; k < 9; k++) {
            boolean present = false;
            for (double[] c : counts) present |= c[k] > 0;
            if (!present && (k < 3 ? target.ages()[k] : target.households()[k - 3]) > 0)
                missing.add(k < 3 ? "age-" + k : "household-size-" + (k - 2) + (k == 8 ? "+" : ""));
        }
        r.put("unsupportedGroups", missing);
        return r;
    }

    private double error(double[] w) {
        return Math.max(maxDifference(expected(w, true), target.ages()), maxDifference(expected(w, false), target.households()));
    }
    private double[] expected(double[] w, boolean ages) {
        double[] result = new double[ages ? 3 : 6]; double total = 0;
        for (int i = 0; i < w.length; i++) {
            total += w[i] * (ages ? sizes[i] : 1);
            for (int k = 0; k < result.length; k++) result[k] += w[i] * counts[i][ages ? k : k + 3];
        }
        for (int i = 0; i < result.length; i++) result[i] /= total;
        return result;
    }
    private static double maxDifference(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }
    private static int group(int age) { return age < 18 ? 0 : age < 65 ? 1 : 2; }
}
