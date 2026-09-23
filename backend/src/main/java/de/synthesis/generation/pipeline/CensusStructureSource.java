package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/** Pinned census margins. Counts remain observations for 2022; shares are modelling inputs. */
public final class CensusStructureSource {
    public record Dataset(String id, String url, String sha256, String entry, List<String> columns) {}
    public static final Dataset AGES = new Dataset("age5",
            "https://www.destatis.de/static/DE/zensus/gitterdaten/Alter_in_5_Altersklassen.zip",
            "88965e23852b901c2eeb27502688777934ef2d1a4ed20f0e6544b773b35005c5",
            "Alter_in_5_Altersklassen/Zensus2022_Alter_in_5_Altersklassen_100m-Gitter.csv",
            List.of("Insgesamt_Bevoelkerung", "Unter18", "a18bis29", "a30bis49", "a50bis64", "a65undaelter"));
    public static final Dataset HOUSEHOLDS = new Dataset("household-size",
            "https://www.destatis.de/static/DE/zensus/gitterdaten/Zensus2022_Groesse_des_privaten_Haushalts_in_Gitterzellen.zip",
            "fdb709378848ad67f20f71e2db33dc1a23c09f23034e507ebc549990f4a8dc58",
            "Zensus2022_Groesse_des_privaten_Haushalts_100m-Gitter.csv",
            List.of("Insgesamt_Haushalte", "1_Person", "2_Personen", "3_Personen", "4_Personen", "5_Personen", "6_Personen_und_mehr"));

    public record Margins(double[] ages, double[] households, String ageLevel, String householdLevel) {}
    public record Grid(double[][] counts, long cells, long inconsistentCells, long zeroStructureCells) {}
    public record Resolved(Map<Integer, Margins> zones, Grid ages, Grid households,
                           List<Dataset> datasets, String unavailableReason) {
        static Resolved unavailable(String reason) {
            return new Resolved(Map.of(), null, null, List.of(), reason);
        }
    }
    /** Raw three-group age observations aggregated into arbitrary WGS84 areas. */
    public record AreaAges(double[][] counts, long cells, Dataset dataset,
                           String unavailableReason) {
        static AreaAges unavailable(String reason) {
            return new AreaAges(new double[0][0], 0, AGES, reason);
        }
        public boolean available() { return unavailableReason == null; }
    }
    @FunctionalInterface interface Downloader { InputStream open(String url) throws IOException, InterruptedException; }
    private final SynthesisProperties.Census config;
    private final Downloader downloader;
    private final List<Dataset> datasets;

    public CensusStructureSource(SynthesisProperties.Census config) {
        this(config, url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds())).GET().build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Zensus-Struktur: HTTP " + response.statusCode());
            }
            return response.body();
        }, List.of(AGES, HOUSEHOLDS));
    }

    CensusStructureSource(SynthesisProperties.Census config, Downloader downloader, List<Dataset> datasets) {
        this.config = config; this.downloader = downloader; this.datasets = List.copyOf(datasets);
    }

    public Resolved resolve(Scenario.Region region, List<Zone> zones, Path cache, Consumer<String> log) {
        if (zones.isEmpty()) return Resolved.unavailable("Keine Zonen vorhanden");
        if (!config.isEnabled()) return Resolved.unavailable("Zensus-Datenzugriff serverseitig deaktiviert");
        if (region == null || region.maxLat < 47 || region.minLat > 55.2
                || region.maxLon < 5.5 || region.minLon > 15.5)
            return Resolved.unavailable("Region außerhalb der deutschen Zensus-Abdeckung");
        try {
            Grid age = read(cached(datasets.get(0), cache, log), datasets.get(0), region, zones);
            Grid hh = read(cached(datasets.get(1), cache, log), datasets.get(1), region, zones);
            double[] regionalAge = sum(age.counts()), regionalHouseholds = sum(hh.counts());
            if (categories(regionalAge) == 0 || categories(regionalHouseholds) == 0)
                return Resolved.unavailable("Keine verwertbaren Alters- oder Haushaltsgruppen in der Region");
            Map<Integer, Margins> mapped = new LinkedHashMap<>();
            for (int i = 0; i < zones.size(); i++) {
                if (zones.get(i).external) continue;
                // Very small/suppressed cells cannot support nine independent margins.
                boolean localAge = categories(age.counts()[i]) >= 50;
                boolean localHh = categories(hh.counts()[i]) >= 20;
                double[] a = localAge ? age.counts()[i] : regionalAge;
                double[] h = localHh ? hh.counts()[i] : regionalHouseholds;
                mapped.put(zones.get(i).index, new Margins(
                        shares(new double[]{a[1], a[2] + a[3] + a[4], a[5]}),
                        shares(Arrays.copyOfRange(h, 1, h.length)),
                        localAge ? "zone" : "region-fallback", localHh ? "zone" : "region-fallback"));
            }
            log.accept("Zensus-Struktur 2022: " + age.cells() + " Alterszellen, " + hh.cells() + " Haushaltszellen");
            return new Resolved(mapped, age, hh, datasets, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resolved.unavailable("Zensus-Strukturabruf unterbrochen");
        } catch (IOException | IllegalArgumentException e) {
            log.accept("Zensus-Struktur nicht verfügbar: " + e.getMessage());
            return Resolved.unavailable(e.getMessage());
        }
    }

    /**
     * Aggregates the official five age classes into Children/Adults/Seniors for polygonal
     * areas. The areas must be non-overlapping WGS84 geometries; boundary points are assigned
     * to the first matching area to keep totals deterministic.
     */
    public AreaAges resolveAgeAreas(Scenario.Region region, List<Geometry> areas,
                                    Path cache, Consumer<String> log) {
        if (areas.isEmpty()) return AreaAges.unavailable("Keine Umlandflächen vorhanden");
        if (!config.isEnabled()) return AreaAges.unavailable("Zensus-Datenzugriff serverseitig deaktiviert");
        if (region == null || region.maxLat < 47 || region.minLat > 55.2
                || region.maxLon < 5.5 || region.minLon > 15.5)
            return AreaAges.unavailable("Region außerhalb der deutschen Zensus-Abdeckung");
        try {
            Grid raw = readAreas(cached(datasets.get(0), cache, log), datasets.get(0), region, areas);
            double[][] grouped = new double[areas.size()][3];
            for (int i = 0; i < grouped.length; i++) {
                grouped[i][0] = raw.counts()[i][1];
                grouped[i][1] = raw.counts()[i][2] + raw.counts()[i][3] + raw.counts()[i][4];
                grouped[i][2] = raw.counts()[i][5];
            }
            if (Arrays.stream(grouped).flatMapToDouble(Arrays::stream).sum() <= 0)
                return AreaAges.unavailable("Keine verwertbaren Altersgruppen im Umlandpuffer");
            log.accept("Zensus-Altersstruktur 2022: " + raw.cells()
                    + " Zellen im Umlandpuffer");
            return new AreaAges(grouped, raw.cells(), datasets.get(0), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AreaAges.unavailable("Zensus-Altersstrukturabruf unterbrochen");
        } catch (IOException | IllegalArgumentException e) {
            log.accept("Zensus-Altersstruktur im Umland nicht verfügbar: " + e.getMessage());
            return AreaAges.unavailable(e.getMessage());
        }
    }

    private synchronized Path cached(Dataset dataset, Path cache, Consumer<String> log) throws IOException, InterruptedException {
        Files.createDirectories(cache);
        Path target = cache.resolve(dataset.id() + "-" + dataset.sha256() + ".zip");
        if (Files.isRegularFile(target) && sha256(target).equals(dataset.sha256())) return target;
        log.accept("Lade Zensus-Struktur " + dataset.id() + " (geprüfter Datenstand 2022) ...");
        Path temp = Files.createTempFile(cache, "structure-", ".part");
        try {
            try (InputStream in = downloader.open(dataset.url()); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536]; long total = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > config.getMaxArchiveMb() * 1024L * 1024L) throw new IOException("Zensus-Strukturarchiv überschreitet Größenlimit");
                    out.write(buffer, 0, count);
                }
            }
            if (!sha256(temp).equals(dataset.sha256())) throw new IOException("SHA-256-Abweichung bei " + dataset.id());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally { Files.deleteIfExists(temp); }
    }

    static Grid read(Path archive, Dataset dataset, Scenario.Region region, List<Zone> zones) throws IOException {
        double[][] counts = new double[zones.size()][dataset.columns().size()];
        long cells = 0, inconsistent = 0, zero = 0;
        double[] bounds = PopulationGridParser.laeaBounds(region);
        // Destatis household ZIP has legacy CP437 entry names; UTF-8-flagged entries remain supported.
        try (ZipFile zip = new ZipFile(archive.toFile(), java.nio.charset.Charset.forName("IBM437"))) {
            var entry = zip.getEntry(dataset.entry());
            if (entry == null || entry.getSize() > 2_000_000_000L) throw new IOException("Ungültiger Zensus-CSV-Eintrag: " + dataset.id());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                String first = reader.readLine();
                if (first == null) throw new IOException("Leere Zensus-Strukturdatei");
                List<String> header = Arrays.asList(first.replace("\ufeff", "").split(";", -1));
                int x = header.indexOf("x_mp_100m"), y = header.indexOf("y_mp_100m");
                int[] columns = dataset.columns().stream().mapToInt(header::indexOf).toArray();
                if (x < 0 || y < 0 || Arrays.stream(columns).anyMatch(c -> c < 0)) throw new IOException("Zensus-Strukturschema geändert: " + dataset.id());
                String line; long row = 1;
                while ((line = reader.readLine()) != null) {
                    row++;
                    if (line.isBlank()) continue;
                    String[] c = line.split(";", -1);
                    try {
                        double e = number(c[x]), n = number(c[y]);
                        if (!PopulationGridParser.withinLaeaBounds(e, n, bounds)) continue;
                        double[] ll = Epsg3035.toLatLon(e, n);
                        if (ll[0] < region.minLat || ll[0] >= region.maxLat || ll[1] < region.minLon || ll[1] >= region.maxLon) continue;
                        int found = -1;
                        for (int i = 0; i < zones.size(); i++) {
                            Zone z = zones.get(i);
                            if (z.external) continue;
                            boolean inside = z.laeaRes > 0 ? e >= z.laeaE && e < z.laeaE + z.laeaRes
                                    && n >= z.laeaN && n < z.laeaN + z.laeaRes : z.contains(ll[0], ll[1]);
                            if (inside) { found = i; break; }
                        }
                        if (found < 0) continue;
                        double[] values = new double[columns.length];
                        for (int i = 0; i < columns.length; i++) values[i] = number(c[columns[i]]);
                        cells++;
                        double grouped = categories(values);
                        if (grouped != values[0]) inconsistent++;
                        if (grouped == 0 && values[0] > 0) zero++;
                        for (int i = 0; i < values.length; i++) counts[found][i] += values[i];
                    } catch (RuntimeException ex) {
                        throw new IOException("Ungültiger Zensus-Strukturwert in " + dataset.id() + ", Zeile " + row, ex);
                    }
                }
            }
        }
        return new Grid(counts, cells, inconsistent, zero);
    }

    private static Grid readAreas(Path archive, Dataset dataset, Scenario.Region region,
                                  List<Geometry> areas) throws IOException {
        double[][] counts = new double[areas.size()][dataset.columns().size()];
        PreparedGeometry[] prepared = areas.stream().map(PreparedGeometryFactory::prepare)
                .toArray(PreparedGeometry[]::new);
        GeometryFactory geometryFactory = new GeometryFactory();
        long cells = 0, inconsistent = 0, zero = 0;
        double[] bounds = PopulationGridParser.laeaBounds(region);
        try (ZipFile zip = new ZipFile(archive.toFile(), java.nio.charset.Charset.forName("IBM437"))) {
            var entry = zip.getEntry(dataset.entry());
            if (entry == null || entry.getSize() > 2_000_000_000L)
                throw new IOException("Ungültiger Zensus-CSV-Eintrag: " + dataset.id());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                String first = reader.readLine();
                if (first == null) throw new IOException("Leere Zensus-Strukturdatei");
                List<String> header = Arrays.asList(first.replace("\ufeff", "").split(";", -1));
                int x = header.indexOf("x_mp_100m"), y = header.indexOf("y_mp_100m");
                int[] columns = dataset.columns().stream().mapToInt(header::indexOf).toArray();
                if (x < 0 || y < 0 || Arrays.stream(columns).anyMatch(c -> c < 0))
                    throw new IOException("Zensus-Strukturschema geändert: " + dataset.id());
                String line; long row = 1;
                while ((line = reader.readLine()) != null) {
                    row++;
                    if (line.isBlank()) continue;
                    String[] c = line.split(";", -1);
                    try {
                        double e = number(c[x]), n = number(c[y]);
                        if (!PopulationGridParser.withinLaeaBounds(e, n, bounds)) continue;
                        double[] ll = Epsg3035.toLatLon(e, n);
                        if (ll[0] < region.minLat || ll[0] >= region.maxLat
                                || ll[1] < region.minLon || ll[1] >= region.maxLon) continue;
                        var point = geometryFactory.createPoint(new Coordinate(ll[1], ll[0]));
                        int found = -1;
                        for (int i = 0; i < prepared.length; i++) {
                            if (prepared[i].covers(point)) { found = i; break; }
                        }
                        if (found < 0) continue;
                        double[] values = new double[columns.length];
                        for (int i = 0; i < columns.length; i++) values[i] = number(c[columns[i]]);
                        cells++;
                        double grouped = categories(values);
                        if (grouped != values[0]) inconsistent++;
                        if (grouped == 0 && values[0] > 0) zero++;
                        for (int i = 0; i < values.length; i++) counts[found][i] += values[i];
                    } catch (RuntimeException ex) {
                        throw new IOException("Ungültiger Zensus-Strukturwert in "
                                + dataset.id() + ", Zeile " + row, ex);
                    }
                }
            }
        }
        return new Grid(counts, cells, inconsistent, zero);
    }

    private static double number(String value) {
        if (value.equals("–") || value.equals("-")) return 0;
        double n = Double.parseDouble(value.replace(',', '.'));
        if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Ungültige Fallzahl");
        return n;
    }
    static double[] shares(double[] values) {
        double total = Arrays.stream(values).sum();
        return Arrays.stream(values).map(v -> total > 0 ? v / total : 0).toArray();
    }
    private static double categories(double[] row) { return Arrays.stream(row).skip(1).sum(); }
    private static double[] sum(double[][] rows) {
        double[] total = new double[rows[0].length];
        for (double[] row : rows) for (int i = 0; i < row.length; i++) total[i] += row[i];
        return total;
    }
    static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
