package de.synthesis.data.importing;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, non-executing CSV/OOXML reader. Unrecognised Excel formats remain raw. */
public class TabularReader {
    public static final int MAX_BYTES = 5_000_000, MAX_ROWS = 10_000, MAX_COLUMNS = 200;
    private static final int MAX_XML_BYTES = 30_000_000;
    public record Row(int sourceRow, List<String> cells) {}
    public record Table(String id, String name, List<Row> rows) {}

    public List<Table> read(byte[] bytes, String filename) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("Datei fehlt oder überschreitet 5 MB");
        String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (ext.endsWith(".xlsx")) return xlsx(bytes);
            if (!ext.endsWith(".csv") && !ext.endsWith(".tsv"))
                throw invalid("Unterstützt werden CSV, TSV und XLSX; XLS bitte als XLSX speichern");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException e) { throw invalid("CSV muss UTF-8 sein (auch mit BOM)"); }
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            char delimiter = ext.endsWith(".tsv") ? '\t' : detectDelimiter(text);
            return List.of(new Table("csv", "CSV", csv(text, delimiter)));
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw invalid("Datei ist beschädigt oder verwendet ein nicht unterstütztes Tabellenformat"); }
    }

    private char detectDelimiter(String text) {
        int best = 0; char selected = ';';
        for (char d : new char[]{';', ',', '\t'}) {
            int count = 0; boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { i++; continue; }
                    quoted = !quoted;
                }
                if (!quoted && (c == '\n' || c == '\r')) break;
                if (!quoted && c == d) count++;
            }
            if (count > best) { best = count; selected = d; }
        }
        return selected;
    }

    private List<Row> csv(String text, char delimiter) {
        List<Row> rows = new ArrayList<>(); List<String> cells = new ArrayList<>();
        StringBuilder value = new StringBuilder(); boolean quoted = false, closed = false;
        int line = 1, start = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { value.append(c); if (c == '\n') line++; }
            } else if (c == '"') {
                if (!value.isEmpty() || closed) throw invalid("Ungültige CSV-Anführungszeichen in Zeile " + line);
                quoted = true;
            } else if (c == delimiter || c == '\n' || c == '\r') {
                cells.add(value.toString()); value.setLength(0); closed = false;
                if (c != delimiter) {
                    addRow(rows, start, cells); cells = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    start = ++line;
                }
            } else {
                if (closed) throw invalid("Zeichen nach geschlossenem CSV-Feld in Zeile " + line);
                value.append(c);
            }
            if (value.length() > 20_000 || cells.size() > MAX_COLUMNS) throw invalid("Tabellen-/Zellgrenze überschritten");
        }
        if (quoted) throw invalid("Nicht geschlossenes CSV-Feld");
        if (!value.isEmpty() || !cells.isEmpty() || closed) { cells.add(value.toString()); addRow(rows, start, cells); }
        if (rows.isEmpty()) throw invalid("Keine Tabellenzeilen gefunden");
        return rows;
    }

    private void addRow(List<Row> rows, int index, List<String> cells) {
        if (cells.size() > MAX_COLUMNS || rows.size() >= MAX_ROWS + 1) throw invalid("Maximal 10.000 Datenzeilen und 200 Spalten");
        rows.add(new Row(index, List.copyOf(cells)));
    }

    private List<Table> xlsx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>(); int total = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++count > 1000) throw invalid("Zu viele XLSX-Bestandteile");
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) throw invalid("Ungültiger XLSX-Pfad");
                if (name.endsWith("vbaProject.bin")) throw invalid("Makros werden nicht unterstützt");
                byte[] data = zip.readNBytes(MAX_XML_BYTES - total + 1); total += data.length;
                if (total > MAX_XML_BYTES) throw invalid("Entpackte XLSX überschreitet 30 MB");
                if (entries.put(name, data) != null) throw invalid("Doppelter XLSX-Bestandteil");
            }
        }
        Document workbook = xml(required(entries, "xl/workbook.xml"));
        Document relations = xml(required(entries, "xl/_rels/workbook.xml.rels"));
        Map<String, String> paths = new HashMap<>();
        for (Element r : elements(relations, "Relationship")) {
            if ("External".equals(r.getAttribute("TargetMode"))) continue;
            String target = r.getAttribute("Target");
            String path = target.startsWith("/") ? target.substring(1) : Path.of("xl").resolve(target).normalize().toString();
            if (!path.startsWith("xl/")) throw invalid("Ungültige XLSX-Referenz");
            if (r.getAttribute("Type").endsWith("/worksheet")) paths.put(r.getAttribute("Id"), path);
        }
        List<String> strings = new ArrayList<>();
        if (entries.containsKey("xl/sharedStrings.xml"))
            for (Element si : elements(xml(entries.get("xl/sharedStrings.xml")), "si")) strings.add(text(si, "t"));
        List<String> styles = styles(entries); boolean date1904 = false;
        for (Element pr : elements(workbook, "workbookPr")) date1904 = Set.of("1", "true").contains(pr.getAttribute("date1904"));
        List<Table> tables = new ArrayList<>(); int rowCount = 0;
        for (Element sheet : elements(workbook, "sheet")) {
            if (tables.size() >= 20) throw invalid("Maximal 20 Tabellenblätter");
            String rel = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            if (rel.isEmpty()) rel = sheet.getAttributeNS("http://purl.oclc.org/ooxml/officeDocument/relationships", "id");
            if (!paths.containsKey(rel)) continue;
            Document doc = xml(required(entries, paths.get(rel)));
            if (!elements(doc, "mergeCell").isEmpty()) throw invalid("Verbundene Excel-Zellen zuerst auflösen");
            List<Row> rows = new ArrayList<>(); int lastRow = 0;
            for (Element row : elements(doc, "row")) {
                int rowNo = Integer.parseInt(row.getAttribute("r"));
                if (rowNo <= lastRow) throw invalid("Ungültige Excel-Zeilenreihenfolge"); lastRow = rowNo;
                List<String> cells = new ArrayList<>(); int lastColumn = -1;
                for (Element cell : elements(row, "c")) {
                    if (!elements(cell, "f").isEmpty()) throw invalid("Formelzellen bitte zuerst in Werte umwandeln");
                    int column = column(cell.getAttribute("r"));
                    if (column <= lastColumn) throw invalid("Doppelte Excel-Zelle"); lastColumn = column;
                    while (cells.size() <= column) cells.add("");
                    String type = cell.getAttribute("t"), value = text(cell, "v");
                    if (type.equals("s")) value = strings.get(Integer.parseInt(value));
                    else if (type.equals("inlineStr")) value = text(cell, "t");
                    else if (type.equals("e")) throw invalid("Excel-Fehlerzelle in Zeile " + rowNo);
                    else if (type.isEmpty() || type.equals("n")) {
                        int style = cell.hasAttribute("s") ? Integer.parseInt(cell.getAttribute("s")) : 0;
                        if (!value.isEmpty() && style < styles.size()) value = formatNumeric(value, styles.get(style), date1904);
                    }
                    if (value.length() > 20_000) throw invalid("Excel-Zelle ist zu lang");
                    cells.set(column, value);
                }
                addRow(rows, rowNo, cells);
                if (++rowCount > MAX_ROWS + 20) throw invalid("Maximal 10.000 Excel-Datenzeilen insgesamt");
            }
            if (!rows.isEmpty() && rows.stream().anyMatch(r -> r.cells().stream().anyMatch(s -> !s.isBlank()))) {
                int width = rows.stream().mapToInt(r -> r.cells().size()).max().orElse(0);
                List<Row> padded = rows.stream().map(r -> {
                    List<String> cells = new ArrayList<>(r.cells()); while (cells.size() < width) cells.add("");
                    return new Row(r.sourceRow(), List.copyOf(cells));
                }).toList();
                tables.add(new Table("sheet-" + tables.size(), sheet.getAttribute("name"), padded));
            }
        }
        if (tables.isEmpty()) throw invalid("Keine unterstützten Excel-Tabellen gefunden");
        return tables;
    }

    private List<String> styles(Map<String, byte[]> entries) throws Exception {
        if (!entries.containsKey("xl/styles.xml")) return List.of("");
        Document doc = xml(entries.get("xl/styles.xml")); Map<Integer, String> formats = new HashMap<>();
        for (Element f : elements(doc, "numFmt")) formats.put(Integer.parseInt(f.getAttribute("numFmtId")), f.getAttribute("formatCode"));
        List<String> result = new ArrayList<>();
        for (Element group : elements(doc, "cellXfs")) for (Element xf : elements(group, "xf")) {
            int id = Integer.parseInt(xf.getAttribute("numFmtId"));
            result.add(formats.getOrDefault(id, switch (id) {
                case 14, 15, 16, 17 -> "yyyy-mm-dd";
                case 18, 19, 20, 21, 45 -> "hh:mm:ss";
                case 22 -> "yyyy-mm-dd hh:mm:ss";
                case 46 -> "[h]:mm:ss";
                default -> "";
            }));
        }
        return result;
    }

    private String formatNumeric(String value, String format, boolean date1904) {
        if (format.matches("0{2,15}")) return String.format(Locale.ROOT, "%0" + format.length() + "d", Long.parseLong(value));
        String f = format.toLowerCase(Locale.ROOT).replaceAll("\"[^\"]*\"|\\\\.", "");
        if (f.contains("[h]") || f.contains("[m]") || f.contains("[s]"))
            throw invalid("Excel-Dauern als ausdrückliche Sekunden-/Minutenwerte exportieren");
        f = f.replaceAll("\\[[^]]*]", "");
        boolean date = f.contains("y") || f.contains("d"), time = f.contains("h") || f.contains("s");
        if (!date && !time) return value;
        double serial = Double.parseDouble(value); long days = (long) Math.floor(serial);
        if (!Double.isFinite(serial) || days < 0 || days > 2_000_000) throw invalid("Ungültiges Excel-Datum");
        long seconds = Math.round((serial - days) * 86400);
        if (time && !date && days != 0) throw invalid("Zeit enthält zusätzliche Tage; Datum und Zeit getrennt exportieren");
        if (seconds == 86400) { days++; seconds = 0; }
        if (!date1904 && date && days == 60) throw invalid("Ungültiges Excel-Datum 29.02.1900");
        LocalDate day = date1904 ? LocalDate.of(1904, 1, 1).plusDays(days)
                : LocalDate.of(1899, 12, 31).plusDays(days > 60 ? days - 1 : days);
        String clock = LocalTime.ofSecondOfDay(seconds).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        return date ? day + (time ? "T" + clock : "") : clock;
    }

    private int column(String ref) {
        if (!ref.matches("[A-Z]+[1-9][0-9]*")) throw invalid("Ungültiger Excel-Zellbezug");
        int n = 0; for (char c : ref.toCharArray()) { if (!Character.isLetter(c)) break; n = n * 26 + c - 'A' + 1;
            if (n > MAX_COLUMNS) throw invalid("Maximal 200 Spalten"); }
        return n - 1;
    }
    private byte[] required(Map<String, byte[]> entries, String key) {
        if (!entries.containsKey(key)) throw invalid("XLSX-Bestandteil fehlt"); return entries.get(key);
    }
    private Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        return builder.parse(new ByteArrayInputStream(bytes));
    }
    private static List<Element> elements(Node node, String name) {
        NodeList list = node instanceof Document d ? d.getElementsByTagNameNS("*", name)
                : ((Element) node).getElementsByTagNameNS("*", name);
        List<Element> result = new ArrayList<>(); for (int i = 0; i < list.getLength(); i++) result.add((Element) list.item(i)); return result;
    }
    private static String text(Element element, String name) {
        StringBuilder s = new StringBuilder(); for (Element e : elements(element, name)) s.append(e.getTextContent()); return s.toString();
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
