package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts an official BA municipality-level outbound-commuter export to coordinate OD. */
public final class BaCommuterAdapter {

    public static final String SOURCE = "upload-ba-pendler-vg250";

    public record Relation(String homeAgs, String homeName, String workAgs, String workName,
                           long commuters) {
    }

    public record Export(List<Relation> relations, long exportTotalCommuters,
                         long municipalityCommuters, int aggregateRows,
                         String geographicDate, String dataDate) {
    }

    public record Adapted(CommuterMatrix.ParseResult matrix, long exportTotalCommuters,
                          long municipalityCommuters, int aggregateRows,
                          int unresolvedMunicipalities) {

        public double coverage() {
            return exportTotalCommuters <= 0 ? 0
                    : (double) matrix.totalCommuters() / exportTotalCommuters;
        }
    }

    private BaCommuterAdapter() {
    }

    public static boolean isBaExport(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int checked = 0;
            while ((line = reader.readLine()) != null && checked++ < 40) {
                List<String> values = split(line);
                if (isHeader(values)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Export parse(Path file) throws IOException {
        List<Relation> relations = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        long exportTotal = 0;
        int aggregateRows = 0;
        boolean headerFound = false;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                List<String> values = split(line);
                if (!headerFound) {
                    if (isHeader(values)) {
                        headerFound = true;
                    } else if (values.size() >= 2 && !values.getFirst().isBlank()) {
                        metadata.put(normalize(values.getFirst()), values.get(1).trim());
                    }
                    continue;
                }
                if (values.size() < 5 || values.getFirst().isBlank()) {
                    continue;
                }
                String homeAgs = values.get(1).trim();
                String workAgs = values.get(3).trim();
                Long commuters = germanInteger(values.get(4));
                if (!homeAgs.matches("\\d{8}") || commuters == null || commuters <= 0) {
                    continue;
                }
                if ("0".equals(workAgs)) {
                    exportTotal = commuters;
                    continue;
                }
                if (!workAgs.matches("\\d{8}")) {
                    aggregateRows++;
                    continue;
                }
                relations.add(new Relation(homeAgs, values.getFirst().trim(), workAgs,
                        values.get(2).trim(), commuters));
            }
        }
        if (!headerFound) {
            throw new IllegalArgumentException("Kein BA-Pendler-Tabellenkopf gefunden");
        }
        String topic = metadata.getOrDefault("thema", "");
        if (!normalize(topic).contains("auspendlergemeinden")) {
            throw new IllegalArgumentException(
                    "Erwartet wird ein BA-Export 'Auspendler Gemeinden'");
        }
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("BA-Export enthält keine Gemeinde-Relationen");
        }
        Map<String, Relation> unique = new LinkedHashMap<>();
        for (Relation relation : relations) {
            String key = relation.homeAgs() + ":" + relation.workAgs();
            Relation previous = unique.putIfAbsent(key, relation);
            if (previous != null && previous.commuters() != relation.commuters()) {
                throw new IllegalArgumentException("Widersprüchliche BA-Relation " + key);
            }
        }
        long municipalityTotal = unique.values().stream().mapToLong(Relation::commuters).sum();
        long distinctHomes = unique.values().stream().map(Relation::homeAgs).distinct().count();
        if (distinctHomes != 1) {
            throw new IllegalArgumentException(
                    "BA-Upload unterstützt genau eine Wohnortgemeinde pro Export");
        }
        return new Export(List.copyOf(unique.values()), exportTotal, municipalityTotal,
                aggregateRows, metadata.getOrDefault("gebietsstand", "unbekannt"),
                metadata.getOrDefault("datenstand", "unbekannt"));
    }

    public static Adapted adapt(Export export,
                                Map<String, MunicipalityCentroidSource.Point> municipalities) {
        List<CommuterMatrix.Flow> flows = new ArrayList<>();
        int unresolved = 0;
        for (Relation relation : export.relations()) {
            MunicipalityCentroidSource.Point home = municipalities.get(relation.homeAgs());
            MunicipalityCentroidSource.Point work = municipalities.get(relation.workAgs());
            if (home == null || work == null) {
                unresolved++;
                continue;
            }
            flows.add(new CommuterMatrix.Flow(home.lat(), home.lon(), work.lat(), work.lon(),
                    relation.commuters(), CommuterMatrix.HomeScope.EXTERNAL));
        }
        if (flows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Kein BA-Gemeindeschlüssel konnte gegen BKG VG250 aufgelöst werden");
        }
        double total = flows.stream().mapToDouble(CommuterMatrix.Flow::commuters).sum();
        return new Adapted(new CommuterMatrix.ParseResult(List.copyOf(flows), total),
                export.exportTotalCommuters(), export.municipalityCommuters(),
                export.aggregateRows(), unresolved);
    }

    private static boolean isHeader(List<String> values) {
        return values.size() >= 5
                && normalize(values.get(0)).equals("wohnort")
                && normalize(values.get(1)).equals("regionalschluessel")
                && normalize(values.get(2)).equals("arbeitsort")
                && normalize(values.get(3)).equals("regionalschluessel")
                && normalize(values.get(4)).equals("insgesamt");
    }

    private static Long germanInteger(String raw) {
        String value = raw.trim().replace(".", "").replace(" ", "");
        return value.matches("\\d+") ? Long.parseLong(value) : null;
    }

    private static List<String> split(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ';' && !quoted) {
                result.add(stripBom(value.toString()));
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        result.add(stripBom(value.toString()));
        return result;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replaceAll("[^a-z0-9]", "");
    }
}
