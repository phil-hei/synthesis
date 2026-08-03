package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Normalized coordinate-based commuter OD contract. Coordinates deliberately
 * decouple imported municipality relations from ephemeral synthesis zone IDs.
 */
public final class CommuterMatrix {

    public static final String NAME = "commuters.csv";

    public record Flow(double homeLat, double homeLon, double workLat, double workLon,
                       double commuters) {
    }

    public record ParseResult(List<Flow> flows, double totalCommuters) {
    }

    public record MappedFlow(int homeZoneIndex, int workZoneIndex, double commuters) {
    }

    public record Mapping(Map<Integer, double[]> cumulativeByHome, List<MappedFlow> flows,
                          int inputRows, int mappedRows, int homeZones,
                          double mappedCommuters, double externalCommuters) {

        public int sampleWorkZone(int homeZoneIndex, Random random) {
            double[] cumulative = cumulativeByHome.get(homeZoneIndex);
            if (cumulative == null || cumulative.length == 0
                    || cumulative[cumulative.length - 1] <= 0) {
                return -1;
            }
            double roll = random.nextDouble() * cumulative[cumulative.length - 1];
            for (int i = 0; i < cumulative.length; i++) {
                if (roll < cumulative[i]) {
                    return i;
                }
            }
            return cumulative.length - 1;
        }
    }

    private CommuterMatrix() {
    }

    public static ParseResult parse(Path file) throws IOException {
        List<Flow> flows = new ArrayList<>();
        double total = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String header = null;
            int lineNumber = 0;
            while (header == null) {
                String candidate = reader.readLine();
                if (candidate == null) {
                    throw new IllegalArgumentException("Pendlermatrix ist leer");
                }
                lineNumber++;
                if (!candidate.isBlank() && !candidate.trim().startsWith("#")) {
                    header = candidate;
                }
            }
            String delimiter = delimiter(header);
            String[] columns = split(header, delimiter);
            int homeLat = column(columns, "homelat", "originlat", "wohnortlat");
            int homeLon = column(columns, "homelon", "originlon", "wohnortlon");
            int workLat = column(columns, "worklat", "destinationlat", "arbeitsortlat");
            int workLon = column(columns, "worklon", "destinationlon", "arbeitsortlon");
            int commuters = column(columns, "commuters", "flow", "pendler");

            String raw;
            while ((raw = reader.readLine()) != null) {
                lineNumber++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] values = split(line, delimiter);
                try {
                    Flow flow = new Flow(number(values, homeLat, delimiter),
                            number(values, homeLon, delimiter), number(values, workLat, delimiter),
                            number(values, workLon, delimiter), number(values, commuters, delimiter));
                    validate(flow);
                    flows.add(flow);
                    total += flow.commuters();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Ungültige Pendlermatrix in Zeile "
                            + lineNumber + ": " + e.getMessage(), e);
                }
            }
        }
        if (flows.isEmpty()) {
            throw new IllegalArgumentException("Pendlermatrix enthält keine verwertbaren Relationen");
        }
        return new ParseResult(List.copyOf(flows), total);
    }

    /**
     * Maps coordinate relations onto the current zone system. Workplaces outside
     * the study area are assigned to the nearest external anchor. Each anchor is
     * then moved to the commuter-weighted workplace centroid, preserving real
     * distance and direction instead of the synthetic fixed-radius ring.
     */
    public static Mapping map(ParseResult input, List<Zone> zones) {
        Map<Integer, double[]> weightsByHome = new HashMap<>();
        Map<Long, Double> aggregated = new LinkedHashMap<>();
        double[] externalLat = new double[zones.size()];
        double[] externalLon = new double[zones.size()];
        double[] externalWeight = new double[zones.size()];
        int mappedRows = 0;
        double mappedCommuters = 0;
        double externalCommuters = 0;

        for (Flow flow : input.flows()) {
            int home = GridZoning.zoneIndexOf(zones, flow.homeLat(), flow.homeLon());
            if (home < 0 || zones.get(home).external) {
                continue;
            }
            int work = GridZoning.zoneIndexOf(zones, flow.workLat(), flow.workLon());
            boolean outside = work < 0;
            if (outside) {
                work = nearestExternalZone(zones, flow.workLat(), flow.workLon());
            }
            if (work < 0) {
                continue;
            }
            weightsByHome.computeIfAbsent(home, ignored -> new double[zones.size()])[work]
                    += flow.commuters();
            long key = ((long) home << 32) | (work & 0xffffffffL);
            aggregated.merge(key, flow.commuters(), Double::sum);
            mappedRows++;
            mappedCommuters += flow.commuters();
            if (outside || zones.get(work).external) {
                externalCommuters += flow.commuters();
                externalLat[work] += flow.workLat() * flow.commuters();
                externalLon[work] += flow.workLon() * flow.commuters();
                externalWeight[work] += flow.commuters();
            }
        }

        for (int i = 0; i < zones.size(); i++) {
            if (externalWeight[i] <= 0) {
                continue;
            }
            Zone zone = zones.get(i);
            zone.centroidLat = externalLat[i] / externalWeight[i];
            zone.centroidLon = externalLon[i] / externalWeight[i];
            zone.minLat = zone.centroidLat - 0.02;
            zone.maxLat = zone.centroidLat + 0.02;
            zone.minLon = zone.centroidLon - 0.02;
            zone.maxLon = zone.centroidLon + 0.02;
            zone.attractivities.put("Work", externalWeight[i]);
        }

        weightsByHome.values().forEach(CommuterMatrix::toCumulative);
        List<MappedFlow> mapped = aggregated.entrySet().stream()
                .map(entry -> new MappedFlow((int) (entry.getKey() >> 32),
                        (int) (long) entry.getKey(), entry.getValue()))
                .toList();
        return new Mapping(Map.copyOf(weightsByHome), mapped, input.flows().size(), mappedRows,
                weightsByHome.size(), mappedCommuters, externalCommuters);
    }

    public static void writeMapped(Path file, Mapping mapping, List<Zone> zones) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("homeZoneId;workZoneId;commuters;workExternal\n");
            for (MappedFlow flow : mapping.flows()) {
                Zone home = zones.get(flow.homeZoneIndex());
                Zone work = zones.get(flow.workZoneIndex());
                writer.write(home.id + ";" + work.id + ";"
                        + String.format(Locale.ROOT, "%.3f", flow.commuters()) + ";"
                        + work.external + "\n");
            }
        }
    }

    private static int nearestExternalZone(List<Zone> zones, double lat, double lon) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Zone zone : zones) {
            if (!zone.external) {
                continue;
            }
            double distance = haversineKm(lat, lon, zone.centroidLat, zone.centroidLon);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = zone.index;
            }
        }
        return best;
    }

    private static void toCumulative(double[] weights) {
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i];
            weights[i] = sum;
        }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String delimiter(String header) {
        if (header.contains(";")) return ";";
        if (header.contains("\t")) return "\t";
        if (header.contains(",")) return ",";
        throw new IllegalArgumentException("Pendlermatrix braucht Semikolon-, Tab- oder Komma-Spalten");
    }

    private static String[] split(String line, String delimiter) {
        return line.split(Pattern.quote(delimiter), -1);
    }

    private static int column(String[] columns, String... aliases) {
        for (int i = 0; i < columns.length; i++) {
            String normalized = normalize(columns[i]);
            for (String alias : aliases) {
                if (normalized.equals(alias)) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Pendlermatrix fehlt Spalte " + aliases[0]);
    }

    private static String normalize(String value) {
        return unquote(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static double number(String[] values, int index, String delimiter) {
        if (index >= values.length) {
            throw new IllegalArgumentException("zu wenige Spalten");
        }
        String value = unquote(values[index]).trim();
        if (";".equals(delimiter)) {
            value = value.replace(',', '.');
        }
        return Double.parseDouble(value);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void validate(Flow flow) {
        if (!Double.isFinite(flow.homeLat()) || flow.homeLat() < -90 || flow.homeLat() > 90
                || !Double.isFinite(flow.workLat()) || flow.workLat() < -90 || flow.workLat() > 90
                || !Double.isFinite(flow.homeLon()) || flow.homeLon() < -180 || flow.homeLon() > 180
                || !Double.isFinite(flow.workLon()) || flow.workLon() < -180 || flow.workLon() > 180) {
            throw new IllegalArgumentException("ungültige Koordinaten");
        }
        if (!Double.isFinite(flow.commuters()) || flow.commuters() <= 0) {
            throw new IllegalArgumentException("Pendlerzahl muss größer 0 sein");
        }
    }
}
