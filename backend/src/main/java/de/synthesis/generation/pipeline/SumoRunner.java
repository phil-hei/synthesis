package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zielmodell SUMO (Achse B): baut aus dem OSM-Straßennetz ein SUMO-Netz
 * (netconvert) und speist die Auto-Trips der mobiTopp/logiTopp-Nachfrage
 * (demandsimulationResult.csv) in eine mikroskopische Verkehrssimulation.
 *
 * <p>Der Weg ist bewusst rein nativ (kein Python/sumolib/pyproj): die Trips
 * werden als Geo-Koordinaten (fromLonLat/toLonLat) geschrieben, das Snapping
 * auf Netzkanten übernimmt {@code duarouter --mapmatch.distance}. Erzeugt unter
 * artifacts/sumo/ ein lauffähiges SUMO-Szenario (net/routes/sumocfg) und eine
 * Ergebnis-Zusammenfassung.
 */
public class SumoRunner {

    public record Result(int vehicles, double meanDurationSec, double meanRouteLengthM,
                         double meanSpeedMs, long durationSeconds) {
    }

    /** Max. Snapping-Distanz (m) von Geo-Koordinate zu Netzkante in duarouter. */
    private static final int MAPMATCH_DISTANCE_M = 300;

    private final Path binDir;
    private final String sumoHome;
    private final int day;
    private final int maxTrips;
    private final int timeoutMinutes;

    public SumoRunner(SynthesisProperties.Sumo config) {
        this.binDir = Path.of(config.getBinDir());
        this.sumoHome = config.getSumoHome();
        this.day = config.getDay();
        this.maxTrips = config.getMaxTrips();
        this.timeoutMinutes = config.getTimeoutMinutes();
    }

    public boolean isAvailable() {
        return Files.exists(binDir.resolve("sumo")) && Files.exists(binDir.resolve("netconvert"))
                && Files.exists(binDir.resolve("duarouter")) && Files.isDirectory(Path.of(sumoHome));
    }

    /**
     * @param osmXml       OSM-Straßennetz (Overpass) als XML
     * @param demandResult mobiTopp-Trip-Ergebnis (demandsimulationResult.csv)
     */
    public Result run(Path artifacts, String osmXml, Path demandResult, Consumer<String> log)
            throws IOException, InterruptedException {
        Path dir = artifacts.resolve("sumo");
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("network.osm"), osmXml, StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        // 1. OSM → SUMO-Netz (mit Geo-Projektion für das spätere LonLat-Mapping)
        exec(dir, log, binDir.resolve("netconvert").toString(),
                "--osm-files", "network.osm", "-o", "net.net.xml",
                "--geometry.remove", "--ramps.guess", "--junctions.join",
                "--tls.guess-signals", "--tls.discard-simple",
                "--keep-edges.by-vclass", "passenger", "--remove-edges.isolated", "--no-turnarounds");

        // 2. Auto-Trips der Nachfrage als Geo-Trips schreiben (fromLonLat/toLonLat)
        int trips = writeTripsXml(demandResult, dir.resolve("trips.xml"));
        log.accept(trips + " Auto-Trips (Tag " + day + ", max " + maxTrips + ") aus der Nachfrage übernommen");

        // 3. Geo-Trips auf Netzkanten abbilden + routen (duarouter snappt LonLat nativ)
        exec(dir, log, binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", "trips.xml", "-o", "routes.rou.xml",
                "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log");

        // 4. SUMO-Lauf
        Files.writeString(dir.resolve("scenario.sumocfg"), """
                <configuration>
                  <input><net-file value="net.net.xml"/><route-files value="routes.rou.xml"/></input>
                  <output><tripinfo-output value="tripinfo.xml"/><statistic-output value="statistics.xml"/></output>
                  <time><begin value="0"/><end value="86400"/></time>
                  <processing><time-to-teleport value="300"/><ignore-route-errors value="true"/></processing>
                  <report><no-step-log value="true"/><duration-log.statistics value="true"/></report>
                </configuration>
                """);
        exec(dir, log, binDir.resolve("sumo").toString(), "-c", "scenario.sumocfg");

        long duration = (System.currentTimeMillis() - start) / 1000;
        return parseStatistics(dir.resolve("statistics.xml"), duration);
    }

    private record Trip(int departSec, String fromLon, String fromLat, String toLon, String toLat) {
    }

    /**
     * demandsimulationResult.csv → trips.xml (Auto-Trips des Tages als Geo-Trips,
     * nach Abfahrtszeit sortiert – duarouter erwartet aufsteigende depart-Zeiten).
     */
    private int writeTripsXml(Path demandResult, Path out) throws IOException {
        int dayStart = day * 1440;
        int dayEnd = (day + 1) * 1440;
        List<Trip> trips = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(demandResult, StandardCharsets.UTF_8)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null && trips.size() < maxTrips) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                // legId(1)==0, tripMode(12)==1 (StandardMode.CAR), tripBegin(42) in Minuten
                if (c.length < 43 || !"0".equals(c[1]) || !"1".equals(c[12])) {
                    continue;
                }
                int begin = (int) Double.parseDouble(c[42]);
                if (begin < dayStart || begin >= dayEnd) {
                    continue;
                }
                trips.add(new Trip((begin - dayStart) * 60, c[38], c[39], c[40], c[41]));
            }
        }
        trips.sort(Comparator.comparingInt(Trip::departSec));
        try (Writer writer = Files.newBufferedWriter(out)) {
            writer.write("<routes>\n");
            int id = 0;
            for (Trip t : trips) {
                writer.write(String.format(
                        "  <trip id=\"v%d\" depart=\"%d\" fromLonLat=\"%s,%s\" toLonLat=\"%s,%s\"/>%n",
                        id++, t.departSec(), t.fromLon(), t.fromLat(), t.toLon(), t.toLat()));
            }
            writer.write("</routes>\n");
        }
        return trips.size();
    }

    private static final Pattern STAT_TAG = Pattern.compile("<vehicleTripStatistics\\b[^>]*/>");

    private Result parseStatistics(Path statistics, long durationSeconds) throws IOException {
        String xml = Files.exists(statistics) ? Files.readString(statistics) : "";
        Matcher tag = STAT_TAG.matcher(xml);
        if (tag.find()) {
            String el = tag.group();
            return new Result((int) attr(el, "count"), attr(el, "duration"),
                    attr(el, "routeLength"), attr(el, "speed"), durationSeconds);
        }
        return new Result(0, 0, 0, 0, durationSeconds);
    }

    /** Liest ein numerisches Attribut aus einem XML-Element (Reihenfolge-unabhängig). */
    private static double attr(String element, String name) {
        Matcher m = Pattern.compile(name + "=\"([\\d.eE+-]+)\"").matcher(element);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // --- Prozess-Helfer --------------------------------------------------------

    private void exec(Path workDir, Consumer<String> log, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.environment().put("SUMO_HOME", sumoHome);
        builder.redirectErrorStream(true);
        Path logFile = workDir.resolve("sumo.log");
        builder.redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(command[0] + " nach " + timeoutMinutes + " min abgebrochen (Timeout)");
        }
        if (process.exitValue() != 0) {
            throw new IOException(Path.of(command[0]).getFileName() + " fehlgeschlagen (Exit "
                    + process.exitValue() + "): " + lastLines(logFile));
        }
    }

    private static String lastLines(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 4), lines.size()));
        } catch (IOException e) {
            return "(Log nicht lesbar)";
        }
    }
}
