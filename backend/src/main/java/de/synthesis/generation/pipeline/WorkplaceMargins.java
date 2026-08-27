package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Imports the official Regionalstatistik flat-file table 13111-08-02-5-B.
 * ERW033 is the number of employees at their place of residence and PEN092 the
 * outbound subset. Their ratio is the regional marginal for external WORK goals.
 */
public final class WorkplaceMargins {

    public static final String NAME = "workplace-margins.csv";
    public static final String SOURCE = "regionalstatistik-13111-08-02-5-B";
    public static final String BA_SOURCE = "ba-gemeindedaten-2025";
    public static final String BA_RESOURCE = "/reference/ba-workplace-margins-2025.csv";
    public static final LocalDate BA_REFERENCE_DATE = LocalDate.of(2025, 6, 30);

    public record Observation(String municipalityCode, String municipalityName,
                              LocalDate date, long residentWorkers, long outboundWorkers) {
    }

    public record Export(List<Observation> observations) {
    }

    public record Margin(String source, LocalDate date, int municipalities, long residentWorkers,
                         long outboundWorkers, double externalShare,
                         int unresolvedMunicipalities) {

        public Margin(LocalDate date, int municipalities, long residentWorkers,
                      long outboundWorkers, double externalShare,
                      int unresolvedMunicipalities) {
            this(SOURCE, date, municipalities, residentWorkers, outboundWorkers,
                    externalShare, unresolvedMunicipalities);
        }
    }

    private record Key(String municipalityCode, String municipalityName, LocalDate date) {
    }

    private static final class Measures {
        Long residentWorkers;
        Long outboundWorkers;
    }

    private WorkplaceMargins() {
    }

    public static Export parseOfficial(Path file) throws IOException {
        String content = decode(Files.readAllBytes(file));
        List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 2) {
            throw new IllegalArgumentException("Regionalstatistik-Flat-File enthält keine Datenzeilen");
        }
        List<String> header = csvRow(lines.getFirst());
        int statisticsCode = column(header, "statistics_code");
        int timeCode = column(header, "time_code");
        int time = column(header, "time");
        int variableCode = column(header, "1_variable_code");
        int attributeCode = column(header, "1_variable_attribute_code");
        int attributeLabel = column(header, "1_variable_attribute_label");
        int value = column(header, "value");
        int measureCode = column(header, "value_variable_code");

        Map<Key, Measures> grouped = new HashMap<>();
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            List<String> row = csvRow(lines.get(lineNumber - 1));
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("Regionalstatistik-Zeile " + lineNumber
                        + " hat " + row.size() + " statt " + header.size() + " Spalten");
            }
            if (!"13111".equals(row.get(statisticsCode).trim())
                    || !"STAG".equals(row.get(timeCode).trim())
                    || !"GEMEIN".equals(row.get(variableCode).trim())) {
                continue;
            }
            String measure = row.get(measureCode).trim();
            if (!"ERW033".equals(measure) && !"PEN092".equals(measure)) {
                continue;
            }
            Long parsedValue = integer(row.get(value));
            if (parsedValue == null) {
                continue;
            }
            String code = row.get(attributeCode).trim();
            if (!code.matches("\\d{8}")) {
                throw new IllegalArgumentException("Ungültiger Gemeindeschlüssel in Zeile "
                        + lineNumber + ": " + code);
            }
            LocalDate date;
            try {
                date = LocalDate.parse(row.get(time).trim());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Ungültiger Stichtag in Zeile "
                        + lineNumber + ": " + row.get(time), e);
            }
            Key key = new Key(code, row.get(attributeLabel).trim(), date);
            Measures measures = grouped.computeIfAbsent(key, ignored -> new Measures());
            if ("ERW033".equals(measure)) {
                measures.residentWorkers = unique(measures.residentWorkers, parsedValue,
                        "ERW033", code, date);
            } else {
                measures.outboundWorkers = unique(measures.outboundWorkers, parsedValue,
                        "PEN092", code, date);
            }
        }

        List<Observation> observations = grouped.entrySet().stream()
                .filter(entry -> entry.getValue().residentWorkers != null
                        && entry.getValue().outboundWorkers != null)
                .map(entry -> observation(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> {
                    int byDate = b.date().compareTo(a.date());
                    return byDate != 0 ? byDate
                            : a.municipalityCode().compareTo(b.municipalityCode());
                })
                .toList();
        if (observations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Flat-File enthält keine vollständigen ERW033/PEN092-Gemeindewerte aus Statistik 13111");
        }
        return new Export(observations);
    }

    public static Margin selectLatestForRegion(Export export,
                                                Predicate<String> municipalityInRegion,
                                                Predicate<String> municipalityResolved) {
        LocalDate latest = export.observations().stream()
                .map(Observation::date).max(LocalDate::compareTo).orElseThrow();
        List<Observation> selected = export.observations().stream()
                .filter(observation -> observation.date().equals(latest))
                .filter(observation -> municipalityInRegion.test(observation.municipalityCode()))
                .toList();
        int unresolved = (int) export.observations().stream()
                .filter(observation -> observation.date().equals(latest))
                .map(Observation::municipalityCode).distinct()
                .filter(code -> !municipalityResolved.test(code)).count();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Regionalstatistik-Export enthält keine Gemeinde in der Szenarioregion");
        }
        long resident = selected.stream().mapToLong(Observation::residentWorkers).sum();
        long outbound = selected.stream().mapToLong(Observation::outboundWorkers).sum();
        if (resident <= 0 || outbound < 0 || outbound > resident) {
            throw new IllegalArgumentException("Unplausibler Arbeitsplatz-Randwert: Wohnort="
                    + resident + ", Auspendelnde=" + outbound);
        }
        return new Margin(SOURCE, latest, selected.size(), resident, outbound,
                (double) outbound / resident, unresolved);
    }

    /**
     * Resolves one municipality from the versioned BA 2025 reference table bundled with the
     * backend. Values hidden by official statistical confidentiality are deliberately absent.
     */
    public static Optional<Margin> bundledBaMargin(String municipalityCode) throws IOException {
        if (municipalityCode == null || !municipalityCode.matches("\\d{8}")) {
            throw new IllegalArgumentException("Ungültiger Gemeindeschlüssel: " + municipalityCode);
        }
        InputStream input = WorkplaceMargins.class.getResourceAsStream(BA_RESOURCE);
        if (input == null) {
            throw new IOException("Gebündelte BA-Gemeindedaten fehlen: " + BA_RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String headerLine = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    headerLine = line;
                    break;
                }
            }
            if (headerLine == null) {
                throw new IllegalArgumentException("Gebündelte BA-Gemeindedaten sind leer");
            }
            List<String> header = csvRow(headerLine);
            int codeColumn = column(header, "municipalityCode");
            int residentColumn = column(header, "residentWorkers");
            int outboundColumn = column(header, "outboundWorkers");
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> row = csvRow(line);
                if (row.size() != header.size()) {
                    throw new IllegalArgumentException(
                            "Gebündelte BA-Gemeindedaten haben eine ungültige Spaltenzahl");
                }
                if (!municipalityCode.equals(row.get(codeColumn).trim())) {
                    continue;
                }
                long resident = Long.parseLong(row.get(residentColumn).trim());
                long outbound = Long.parseLong(row.get(outboundColumn).trim());
                if (resident <= 0 || outbound < 0 || outbound > resident) {
                    throw new IllegalArgumentException("Unplausible BA-Auspendlerquote für "
                            + municipalityCode);
                }
                return Optional.of(new Margin(BA_SOURCE, BA_REFERENCE_DATE, 1, resident,
                        outbound, (double) outbound / resident, 0));
            }
        }
        return Optional.empty();
    }

    public static void writeNormalized(Path file, Margin margin) throws IOException {
        Files.writeString(file, "source;date;municipalities;residentWorkers;outboundWorkers;externalShare\n"
                + margin.source() + ";" + margin.date() + ";" + margin.municipalities() + ";"
                + margin.residentWorkers() + ";" + margin.outboundWorkers() + ";"
                + String.format(Locale.ROOT, "%.12f", margin.externalShare()) + "\n");
    }

    public static Margin readNormalized(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.size() != 2) {
            throw new IllegalArgumentException("Normalisierter Arbeitsplatz-Randwert braucht genau eine Datenzeile");
        }
        List<String> header = csvRow(lines.getFirst());
        List<String> row = csvRow(lines.get(1));
        if (row.size() != header.size()) {
            throw new IllegalArgumentException("Arbeitsplatz-Randwert hat eine ungültige Spaltenzahl");
        }
        String source = row.get(column(header, "source"));
        if (!SOURCE.equals(source) && !BA_SOURCE.equals(source)) {
            throw new IllegalArgumentException("Unbekannte Arbeitsplatz-Randwertquelle");
        }
        LocalDate date = LocalDate.parse(row.get(column(header, "date")));
        int municipalities = Integer.parseInt(row.get(column(header, "municipalities")));
        long resident = Long.parseLong(row.get(column(header, "residentWorkers")));
        long outbound = Long.parseLong(row.get(column(header, "outboundWorkers")));
        double share = Double.parseDouble(row.get(column(header, "externalShare")));
        if (municipalities <= 0 || resident <= 0 || outbound < 0 || outbound > resident
                || !Double.isFinite(share) || share < 0 || share > 1
                || Math.abs(share - (double) outbound / resident) > 1e-9) {
            throw new IllegalArgumentException("Normalisierter Arbeitsplatz-Randwert ist unplausibel");
        }
        return new Margin(source, date, municipalities, resident, outbound, share, 0);
    }

    private static Observation observation(Key key, Measures measures) {
        if (measures.residentWorkers <= 0 || measures.outboundWorkers < 0
                || measures.outboundWorkers > measures.residentWorkers) {
            throw new IllegalArgumentException("Unplausible ERW033/PEN092-Werte für "
                    + key.municipalityCode() + " am " + key.date());
        }
        return new Observation(key.municipalityCode(), key.municipalityName(), key.date(),
                measures.residentWorkers, measures.outboundWorkers);
    }

    private static Long unique(Long previous, long value, String measure, String code, LocalDate date) {
        if (previous != null && previous != value) {
            throw new IllegalArgumentException("Widersprüchlicher " + measure + "-Wert für "
                    + code + " am " + date);
        }
        return value;
    }

    private static Long integer(String raw) {
        String value = raw.trim().replace(" ", "");
        if (value.isEmpty() || "-".equals(value) || ".".equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ungültiger Regionalstatistik-Wert: " + raw, e);
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", "");
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1).replace("\uFEFF", "");
        }
    }

    private static int column(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Regionalstatistik-Flat-File fehlt Spalte " + name);
    }

    private static List<String> csvRow(String line) {
        List<String> values = new ArrayList<>();
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
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Nicht geschlossene CSV-Anführungszeichen");
        }
        values.add(value.toString());
        return values;
    }
}
