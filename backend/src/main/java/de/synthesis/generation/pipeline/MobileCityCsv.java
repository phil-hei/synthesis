package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Named Epigene CSV blocks. Keeps all blocks and years when merging contributions. */
final class MobileCityCsv {
    final Map<String, Table> tables = new LinkedHashMap<>();

    static MobileCityCsv read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) throw new IOException("Leere MobileCity-Datei: " + file);
        String first = lines.getFirst().replace("\uFEFF", "");
        char delimiter = first.chars().filter(c -> c == ';').count() > first.chars().filter(c -> c == ',').count() ? ';' : ',';
        String separator = String.valueOf(delimiter).repeat(parse(first, delimiter).size() - 1);
        MobileCityCsv csv = new MobileCityCsv();
        List<List<String>> block = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.equals(separator)) { csv.add(block); block.clear(); }
            else if (!line.isBlank()) block.add(parse(line, delimiter));
        }
        csv.add(block);
        return csv;
    }

    private void add(List<List<String>> block) throws IOException {
        if (block.isEmpty()) return;
        if (block.size() < 2) throw new IOException("MobileCity-Block ohne Kopfzeile");
        String name = block.getFirst().getFirst();
        Table table = new Table(new ArrayList<>(block.get(1)), new ArrayList<>());
        for (List<String> row : block.subList(2, block.size())) {
            List<String> copy = new ArrayList<>(row);
            while (copy.size() < table.header.size()) copy.add("");
            table.rows.add(copy);
        }
        if (tables.putIfAbsent(name, table) != null) throw new IOException("Doppelter Block: " + name);
    }

    Table table(String name) throws IOException {
        Table table = tables.get(name);
        if (table == null) throw new IOException("MobileCity-Pflichtblock fehlt: " + name);
        return table;
    }

    void write(Path file) throws IOException {
        int width = Math.max(8, tables.values().stream().mapToInt(t -> t.header.size()).max().orElse(8));
        String separator = ",".repeat(width - 1);
        StringBuilder out = new StringBuilder(file.getFileName().toString()).append(separator).append('\n');
        for (var entry : tables.entrySet()) {
            out.append(separator).append('\n').append(quote(entry.getKey())).append('\n');
            append(out, entry.getValue().header);
            for (var row : entry.getValue().rows) append(out, row);
        }
        Files.writeString(file, out.toString());
    }

    static String quote(String value) {
        if (value == null) return "";
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("MobileCity unterstützt keine mehrzeiligen CSV-Zellen");
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** Epigene uses NumberStyles.Number, which does not accept scientific notation. */
    static String number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nicht endlicher CSV-Wert");
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder out, List<String> row) {
        out.append(String.join(",", row.stream().map(MobileCityCsv::quote).toList())).append('\n');
    }

    private static List<String> parse(String line, char delimiter) throws IOException {
        List<String> cells = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append(c); i++; }
                else quoted = !quoted;
            } else if (c == delimiter && !quoted) { cells.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        if (quoted) throw new IOException("Nicht geschlossene CSV-Anführungszeichen");
        cells.add(cell.toString()); return cells;
    }

    static final class Table {
        final List<String> header;
        final List<List<String>> rows;
        Table(List<String> header, List<List<String>> rows) { this.header = header; this.rows = rows; }
        List<String> row(String key) throws IOException {
            return rows.stream().filter(r -> r.getFirst().equals(key)).findFirst()
                    .orElseThrow(() -> new IOException("MobileCity-Schlüssel fehlt: " + key));
        }
        double atYear(String key, int year) throws IOException {
            List<String> row = row(key);
            TreeMap<Integer, Double> years = new TreeMap<>();
            for (int i = 1; i < header.size(); i++) {
                if (header.get(i).matches("\\d{4}") && !row.get(i).isBlank()) {
                    double value = Double.parseDouble(row.get(i));
                    if (!Double.isFinite(value)) throw new IOException("Nicht endlicher Wert: " + key);
                    years.put(Integer.parseInt(header.get(i)), value);
                }
            }
            if (years.isEmpty()) throw new IOException("Zeitreihe fehlt: " + key);
            var lo = years.floorEntry(year); var hi = years.ceilingEntry(year);
            if (lo == null) return years.firstEntry().getValue();
            if (hi == null || lo.getKey().equals(hi.getKey())) return lo.getValue();
            return lo.getValue() + (hi.getValue() - lo.getValue()) * (year - lo.getKey()) / (hi.getKey() - lo.getKey());
        }
        int ensureYear(int year) throws IOException {
            return ensureYear(year, false);
        }
        int ensureYear(int year, boolean integer) throws IOException {
            String name = Integer.toString(year); int index = header.indexOf(name);
            if (index >= 0) return index;
            index = 1;
            while (index < header.size() && Integer.parseInt(header.get(index)) < year) index++;
            List<Double> values = new ArrayList<>();
            for (var row : rows) values.add(atYear(row.getFirst(), year));
            header.add(index, name);
            for (int i = 0; i < rows.size(); i++) rows.get(i).add(index,
                    integer ? Long.toString(Math.round(values.get(i))) : number(values.get(i)));
            return index;
        }
        /** Add a year without turning absent observations for other districts into observations. */
        int ensureSparseYear(int year) {
            String name = Integer.toString(year); int index = header.indexOf(name);
            if (index >= 0) return index;
            index = 1;
            while (index < header.size() && Integer.parseInt(header.get(index)) < year) index++;
            header.add(index,name);
            for (var row : rows) row.add(index, "");
            return index;
        }
    }
}
