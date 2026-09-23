package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Schneidet einen (großen) GTFS-Feed streaming auf eine Regions-Bounding-Box zu, damit der
 * anschließende {@code gtfs2pt.py}-Lauf handhabbar bleibt. Ein Verbund-Feed (z. B. KVV ~120 MB
 * Zip, ~800 MB entpackt, dominiert von shapes.txt) wird so auf wenige hundert KB reduziert.
 *
 * <p>Bewusst ohne Fremd-Bibliothek und ohne den ganzen Feed im Speicher zu halten: es wird nur
 * mit Halte-/Fahrt-/Service-ID-Mengen gearbeitet, die Tabellen werden zeilenweise gefiltert.
 * {@code shapes.txt} wird komplett verworfen (gtfs2pt.py mappt die Fahrten selbst aufs Netz).
 *
 * <p>Referenzielle Integrität: Halte in der Bbox → Fahrten mit ≥1 solchen Halt → alle deren
 * stop_times → deren komplette Halte-Menge → trips → routes → agencies/calendar/calendar_dates.
 */
public class GtfsCropper {

    public record Result(int stopsInBbox, int trips, int stops, int routes) {
    }

    private final double minLat;
    private final double minLon;
    private final double maxLat;
    private final double maxLon;
    private final int windowStartSec;
    private final int windowEndSec;

    /** Zuschnitt nur räumlich (ganzer Betriebstag). */
    public GtfsCropper(double minLat, double minLon, double maxLat, double maxLon) {
        this(minLat, minLon, maxLat, maxLon, 0, 0);
    }

    /**
     * Zuschnitt räumlich + zeitlich. Das Zeitfenster (Sekunden ab Mitternacht) begrenzt die
     * Fahrten-Zahl und damit die Laufzeit des nachgelagerten gtfs2pt-Map-Matchings drastisch
     * ({@code windowEndSec <= 0} = kein Zeitfilter, ganzer Tag).
     */
    public GtfsCropper(double minLat, double minLon, double maxLat, double maxLon,
                       int windowStartSec, int windowEndSec) {
        this.minLat = minLat;
        this.minLon = minLon;
        this.maxLat = maxLat;
        this.maxLon = maxLon;
        this.windowStartSec = windowStartSec;
        this.windowEndSec = windowEndSec;
    }

    public Result crop(Path srcZip, Path outZip) throws IOException {
        try (ZipFile zip = new ZipFile(srcZip.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outZip))) {

            // 1. Halte innerhalb der Bbox
            Set<String> stopsInBbox = new HashSet<>();
            forEachRow(zip, "stops.txt", (h, r) -> {
                Double la = parse(get(h, r, "stop_lat"));
                Double lo = parse(get(h, r, "stop_lon"));
                if (la != null && lo != null && la >= minLat && la <= maxLat
                        && lo >= minLon && lo <= maxLon) {
                    stopsInBbox.add(get(h, r, "stop_id"));
                }
            });

            // 2. Fahrten, die mindestens einen Halt in der Bbox (und ggf. im Zeitfenster) berühren
            Set<String> keptTrips = new HashSet<>();
            Set<String> keptStops = new HashSet<>();
            boolean timeFilter = windowEndSec > 0;
            forEachRow(zip, "stop_times.txt", (h, r) -> {
                if (stopsInBbox.contains(get(h, r, "stop_id"))
                        && (!timeFilter || inWindow(get(h, r, "departure_time"),
                                get(h, r, "arrival_time")))) {
                    keptTrips.add(get(h, r, "trip_id"));
                }
            });

            // 3. Alle stop_times der behaltenen Fahrten schreiben; deren Halte-Menge sammeln
            copyFiltered(zip, "stop_times.txt", out, (h, r) -> {
                boolean keep = keptTrips.contains(get(h, r, "trip_id"));
                if (keep) {
                    keptStops.add(get(h, r, "stop_id"));
                }
                return keep;
            });

            // 4. Übergeordnete Stationen mitnehmen. GTFS-Transfers referenzieren häufig
            // parent_station statt der Plattform-ID aus stop_times.
            Set<String> parentStops = new HashSet<>();
            forEachRow(zip, "stops.txt", (h, r) -> {
                if (keptStops.contains(get(h, r, "stop_id"))) {
                    String parent = get(h, r, "parent_station");
                    if (!parent.isBlank()) parentStops.add(parent);
                }
            });
            keptStops.addAll(parentStops);

            // 5. Halte (von behaltenen Fahrten referenziert + deren parent_station)
            int stopCount = copyFiltered(zip, "stops.txt", out,
                    (h, r) -> keptStops.contains(get(h, r, "stop_id")));

            // 6. trips → routes/services sammeln, shape_id-Spalte streichen
            Set<String> keptRoutes = new HashSet<>();
            Set<String> keptServices = new HashSet<>();
            int tripCount = copyFilteredDroppingColumn(zip, "trips.txt", out, "shape_id", (h, r) -> {
                boolean keep = keptTrips.contains(get(h, r, "trip_id"));
                if (keep) {
                    keptRoutes.add(get(h, r, "route_id"));
                    keptServices.add(get(h, r, "service_id"));
                }
                return keep;
            });
            // Frequency-based feeds use one template trip plus headways. Keep that table or the
            // downstream adapter would undercount the daily service and the vehicle peak.
            copyFiltered(zip, "frequencies.txt", out,
                    (h, r) -> keptTrips.contains(get(h, r, "trip_id")));

            // 7. routes → agencies sammeln
            Set<String> keptAgencies = new HashSet<>();
            int routeCount = copyFiltered(zip, "routes.txt", out, (h, r) -> {
                boolean keep = keptRoutes.contains(get(h, r, "route_id"));
                if (keep) {
                    keptAgencies.add(get(h, r, "agency_id"));
                }
                return keep;
            });

            // 8. Abhängige Tabellen (fehlende Dateien werden übersprungen)
            copyFiltered(zip, "calendar.txt", out,
                    (h, r) -> keptServices.contains(get(h, r, "service_id")));
            copyFiltered(zip, "calendar_dates.txt", out,
                    (h, r) -> keptServices.contains(get(h, r, "service_id")));
            copyFiltered(zip, "agency.txt", out,
                    (h, r) -> keptAgencies.isEmpty() || keptAgencies.contains(get(h, r, "agency_id")));
            copyFiltered(zip, "transfers.txt", out,
                    (h, r) -> keptStops.contains(get(h, r, "from_stop_id"))
                            && keptStops.contains(get(h, r, "to_stop_id")));
            copyFiltered(zip, "feed_info.txt", out, (h, r) -> true);

            return new Result(stopsInBbox.size(), tripCount, stopCount, routeCount);
        }
    }

    // --- Streaming-Helfer -------------------------------------------------------

    private interface RowConsumer {
        void accept(List<String> header, List<String> row);
    }

    private interface RowPredicate {
        boolean test(List<String> header, List<String> row);
    }

    private void forEachRow(ZipFile zip, String name, RowConsumer consumer) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return;
        }
        try (BufferedReader reader = reader(zip, entry)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            List<String> header = parseCsv(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    consumer.accept(header, parseCsv(line));
                }
            }
        }
    }

    /** Kopiert Zeilen, die {@code keep} erfüllt, in eine neue Zip-Entry; gibt die Zeilenzahl zurück. */
    private int copyFiltered(ZipFile zip, String name, ZipOutputStream out,
                             RowPredicate keep) throws IOException {
        return copyFilteredDroppingColumn(zip, name, out, null, keep);
    }

    private int copyFilteredDroppingColumn(ZipFile zip, String name, ZipOutputStream out,
                                           String dropColumn, RowPredicate keep) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return 0;
        }
        int written = 0;
        ZipEntry outputEntry = new ZipEntry(name);
        outputEntry.setTime(0);
        out.putNextEntry(outputEntry);
        Writer writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
        try (BufferedReader reader = reader(zip, entry)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                out.closeEntry();
                return 0;
            }
            List<String> header = parseCsv(headerLine);
            int dropIndex = dropColumn == null ? -1 : header.indexOf(dropColumn);
            writer.write(joinCsv(header, dropIndex));
            writer.write("\n");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> cells = parseCsv(line);
                if (keep.test(header, cells)) {
                    writer.write(joinCsv(cells, dropIndex));
                    writer.write("\n");
                    written++;
                }
            }
        }
        writer.flush();
        out.closeEntry();
        return written;
    }

    private BufferedReader reader(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        // GTFS-Dateien beginnen oft mit einem UTF-8-BOM – überspringen
        InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        reader.mark(1);
        int first = reader.read();
        if (first != 0xFEFF) {
            reader.reset();
        }
        return reader;
    }

    private static String get(List<String> header, List<String> row, String column) {
        int i = header.indexOf(column);
        return i >= 0 && i < row.size() ? row.get(i) : "";
    }

    /** GTFS-Zeit "HH:MM:SS" (Stunden können &gt;24 sein) im Fenster [start,end]? */
    private boolean inWindow(String departure, String arrival) {
        int t = gtfsSeconds(departure);
        if (t < 0) {
            t = gtfsSeconds(arrival);
        }
        return t >= windowStartSec && t <= windowEndSec;
    }

    private static int gtfsSeconds(String time) {
        if (time == null || time.isBlank()) {
            return -1;
        }
        String[] p = time.trim().split(":");
        if (p.length != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Minimaler RFC-4180-CSV-Parser (Kommas/Anführungszeichen/eingebettete Quotes). */
    static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** Zeile als CSV serialisieren, optional eine Spalte auslassen; quotet bei Bedarf. */
    private static String joinCsv(List<String> cells, int dropIndex) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < cells.size(); i++) {
            if (i == dropIndex) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(cells.get(i)));
        }
        return sb.toString();
    }

    private static String quote(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
