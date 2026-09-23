package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Expands sampled agents to the allocated zone population; trip rates remain per person/day. */
final class MobileCityPopulation {
    final long[][] ages;
    final long[] cars;
    final List<Long> imputedZones = new ArrayList<>();
    final double[][][] rates;
    static final String[] GROUPS = {"Children", "Adults", "Seniors"};
    static final String[] PURPOSES = {"Education", "Work", "Shopping", "Leisure"};

    MobileCityPopulation(List<Zone> zones, Path dir, double fraction, int year) throws IOException {
        int n = zones.size(); ages = new long[n][3]; cars = new long[n]; rates = new double[n][3][4];
        long[][] sample = new long[n][3]; long[] sampleCars = new long[n];
        Map<Long, Integer> home = new HashMap<>(); Map<Long, Zone> byId = new HashMap<>();
        zones.forEach(z -> byId.put(z.id,z));
        rows(dir.resolve("household.csv"), row -> {
            if (Integer.parseInt(row[2]) != year) throw new IOException("Haushaltsjahr stimmt nicht mit Bezugsjahr überein");
            Zone zone = byId.get(Long.parseLong(row[3]));
            if (zone != null && !zone.external) home.put(Long.parseLong(row[1]),zone.index);
        });
        Map<Long, int[]> persons = new HashMap<>();
        rows(dir.resolve("person.csv"), row -> {
            Integer zone = home.get(Long.parseLong(row[2]));
            if (zone == null) return;
            int age = Integer.parseInt(row[3]); int group = age < 18 ? 0 : age < 65 ? 1 : 2;
            sample[zone][group]++; persons.put(Long.parseLong(row[0]),new int[]{zone,group});
        });
        rows(dir.resolve("car.csv"), row -> {
            Integer zone = home.get(Long.parseLong(row[0])); if (zone != null) sampleCars[zone]++;
        });
        long[][][] trips = new long[n][3][4]; Map<Long,Integer> previous = new HashMap<>();
        rows(dir.resolve("activity.csv"), row -> {
            long person = Long.parseLong(row[0]); int destination = purpose(row[1]);
            Integer before = previous.put(person,destination);
            int[] ref = persons.get(person);
            int purpose = "7".equals(row[1]) && before != null ? before : destination;
            if (before != null && ref != null && purpose >= 0) trips[ref[0]][ref[1]][purpose]++;
        });
        long[] global = new long[3]; long totalCars = 0;
        for (Zone z : zones) { for (int g=0;g<3;g++) global[g]+=sample[z.index][g]; totalCars+=sampleCars[z.index]; }
        long sampled = Arrays.stream(global).sum();
        if (sampled == 0 && zones.stream().anyMatch(z -> !z.external && z.population > 0))
            throw new IOException("Keine Stichprobenpersonen für die MobileCity-Hochrechnung vorhanden");
        double[][] fallbackRates = new double[3][4];
        for (int g=0;g<3;g++) for (int p=0;p<4;p++) {
            double weightedTrips=0, weight=0;
            for (Zone z : zones) {
                long size=Arrays.stream(sample[z.index]).sum();
                if (size==0) continue;
                double expansion=z.population>0 ? z.population/(double)size : 1/fraction;
                weightedTrips+=trips[z.index][g][p]*expansion;
                weight+=sample[z.index][g]*expansion;
            }
            fallbackRates[g][p]=weight>0 ? weightedTrips/weight/7 : 0;
        }
        double[] exactPopulation = new double[n];
        for (Zone z : zones) {
            if (z.external) continue;
            long size = Arrays.stream(sample[z.index]).sum();
            exactPopulation[z.index] = z.population > 0 ? z.population : size / fraction;
        }
        long[] targetPopulation = rounded(exactPopulation);
        double[] exactCars = new double[n];
        for (Zone z : zones) {
            if (z.external) continue;
            int i = z.index;
            long size = Arrays.stream(sample[i]).sum();
            exactCars[i] = targetPopulation[i]
                    * (size > 0 ? sampleCars[i] / (double) size
                    : sampled > 0 ? totalCars / (double) sampled : 0);
        }
        long[] targetCars = rounded(exactCars);
        for (Zone z : zones) {
            if (z.external) continue;
            int i=z.index; long size=Arrays.stream(sample[i]).sum();
            long target=targetPopulation[i];
            long[] proportions=size>0 ? sample[i] : global;
            if (size==0 && target>0) imputedZones.add(z.id);
            double denom=Arrays.stream(proportions).sum();
            double[] exact=new double[3];
            for (int g=0;g<3;g++) exact[g]=denom>0 ? target*proportions[g]/denom : 0;
            ages[i]=rounded(exact);
            cars[i]=targetCars[i];
            for (int g=0;g<3;g++) for (int p=0;p<4;p++) rates[i][g][p]=sample[i][g]>0
                    ? trips[i][g][p]/(double)sample[i][g]/7 : fallbackRates[g][p];
        }
    }

    double[][] tripRates(double[] fractions) {
        double[][] result=new double[3][4];
        for (int g=0;g<3;g++) {
            double total=0;
            for (int i=0;i<ages.length;i++) {
                double count=ages[i][g]*fractions[i]; total+=count;
                for (int p=0;p<4;p++) result[g][p]+=rates[i][g][p]*count;
            }
            if (total>0) for (int p=0;p<4;p++) result[g][p]/=total;
        }
        return result;
    }
    static long[] rounded(double[] exact) {
        long[] result=new long[exact.length]; long assigned=0;
        for (int i=0;i<exact.length;i++) { result[i]=(long)Math.floor(exact[i]); assigned+=result[i]; }
        long target=Math.round(Arrays.stream(exact).sum());
        List<Integer> order=new ArrayList<>(); for (int i=0;i<exact.length;i++) order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(i -> -(exact[i]-Math.floor(exact[i]))).thenComparingInt(i -> i));
        for (int k=0;k<target-assigned;k++) result[order.get(k)]++;
        return result;
    }
    private static int purpose(String code) {
        return switch(code) { case "31","32","33" -> 0; case "1" -> 1; case "41","42" -> 2; case "51","52","6","12" -> 3; default -> -1; };
    }
    @FunctionalInterface private interface Row { void accept(String[] row) throws IOException; }
    private static void rows(Path path, Row consume) throws IOException {
        try (var reader=Files.newBufferedReader(path)) {
            reader.readLine(); String line;
            while ((line=reader.readLine())!=null) if (!line.isBlank()) consume.accept(line.split(";",-1));
        }
    }
}
