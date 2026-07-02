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
                         double meanSpeedMs, long durationSeconds, int carTrips, int deliveryTrips) {
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

    /** Rückwärtskompatibler Einstieg ohne Lieferverkehr. */
    public Result run(Path artifacts, String osmXml, Path demandResult, Consumer<String> log)
            throws IOException, InterruptedException {
        return run(artifacts, osmXml, demandResult, null, log);
    }

    /**
     * @param osmXml       OSM-Straßennetz (Overpass) als XML
     * @param demandResult mobiTopp-Trip-Ergebnis (demandsimulationResult.csv)
     * @param plannedTours logiTopp-Zustelltouren (planned-tours.csv); null = ohne Lieferverkehr
     */
    public Result run(Path artifacts, String osmXml, Path demandResult, Path plannedTours,
                      Consumer<String> log) throws IOException, InterruptedException {
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

        // 2. PKW-Trips der Nachfrage + Zustelltouren-Legs als Geo-Trips schreiben
        List<Trip> trips = new ArrayList<>(readCarTrips(demandResult));
        int carTrips = trips.size();
        int deliveryTrips = 0;
        if (plannedTours != null && Files.exists(plannedTours)) {
            List<Trip> delivery = readDeliveryLegs(plannedTours);
            deliveryTrips = delivery.size();
            trips.addAll(delivery);
        }
        writeTripsXml(trips, dir.resolve("trips.xml"));
        log.accept(carTrips + " PKW-Trips (Tag " + day + ", max " + maxTrips + ") + "
                + deliveryTrips + " Lieferverkehr-Legs (Lkw/Lastenrad) übernommen");

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
        return parseStatistics(dir.resolve("statistics.xml"), duration, carTrips, deliveryTrips);
    }

    private record Trip(int departSec, String type, String fromLon, String fromLat,
                        String toLon, String toLat) {
    }

    /** demandsimulationResult.csv → PKW-Trips des Tages (Typ "pkw"). */
    private List<Trip> readCarTrips(Path demandResult) throws IOException {
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
                trips.add(new Trip((begin - dayStart) * 60, "pkw", c[38], c[39], c[40], c[41]));
            }
        }
        return trips;
    }

    /**
     * planned-tours.csv → Zustelltouren-Legs des Tages: jede Zeile ist ein Stopp
     * (fromX/Y → toX/Y) mit Fahrzeugtyp (logiTopp-Codes: 1=BIKE→Lastenrad,
     * sonst Lkw; TRAM wird verworfen). Abfahrt ≈ Ankunft (plannedTimeSimMin)
     * minus Fahrzeit (tripDur), beides in Sim-Minuten der Modellwoche.
     */
    private List<Trip> readDeliveryLegs(Path plannedTours) throws IOException {
        int dayStart = day * 1440;
        int dayEnd = (day + 1) * 1440;
        List<Trip> legs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(plannedTours, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return legs;
            }
            List<String> header = List.of(headerLine.split(";", -1));
            int vehicleType = header.indexOf("vehicleType");
            int fromX = header.indexOf("fromX");
            int fromY = header.indexOf("fromY");
            int toX = header.indexOf("toX");
            int toY = header.indexOf("toY");
            int planned = header.indexOf("plannedTimeSimMin");
            int tripDur = header.indexOf("tripDur");
            if (vehicleType < 0 || fromX < 0 || toX < 0 || planned < 0) {
                return legs; // unbekanntes Format – Lieferverkehr überspringen
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                if (c.length <= Math.max(planned, Math.max(toY, tripDur))
                        || c[fromX].isBlank() || c[toX].isBlank()) {
                    continue;
                }
                String type = switch (c[vehicleType].trim()) {
                    case "1" -> "lastenrad";
                    case "3" -> null; // TRAM: nicht auf dem Straßennetz
                    default -> "lkw";
                };
                if (type == null) {
                    continue;
                }
                double arrival = Double.parseDouble(c[planned]);
                double travel = tripDur >= 0 && !c[tripDur].isBlank() ? Double.parseDouble(c[tripDur]) : 0;
                int begin = (int) Math.max(dayStart, arrival - travel);
                if (begin < dayStart || begin >= dayEnd) {
                    continue;
                }
                legs.add(new Trip((begin - dayStart) * 60, type, c[fromX], c[fromY], c[toX], c[toY]));
            }
        }
        return legs;
    }

    /** Alle Trips (nach Abfahrt sortiert) mit Fahrzeugtypen als trips.xml schreiben. */
    private void writeTripsXml(List<Trip> trips, Path out) throws IOException {
        trips.sort(Comparator.comparingInt(Trip::departSec));
        try (Writer writer = Files.newBufferedWriter(out)) {
            writer.write("<routes>\n");
            writer.write("  <vType id=\"pkw\" vClass=\"passenger\"/>\n");
            writer.write("  <vType id=\"lkw\" vClass=\"delivery\"/>\n");
            writer.write("  <vType id=\"lastenrad\" vClass=\"bicycle\"/>\n");
            int id = 0;
            for (Trip t : trips) {
                writer.write(String.format(
                        "  <trip id=\"v%d\" type=\"%s\" depart=\"%d\" fromLonLat=\"%s,%s\" toLonLat=\"%s,%s\"/>%n",
                        id++, t.type(), t.departSec(), t.fromLon(), t.fromLat(), t.toLon(), t.toLat()));
            }
            writer.write("</routes>\n");
        }
    }

    private static final Pattern STAT_TAG = Pattern.compile("<vehicleTripStatistics\\b[^>]*/>");

    private Result parseStatistics(Path statistics, long durationSeconds, int carTrips,
                                   int deliveryTrips) throws IOException {
        String xml = Files.exists(statistics) ? Files.readString(statistics) : "";
        Matcher tag = STAT_TAG.matcher(xml);
        if (tag.find()) {
            String el = tag.group();
            return new Result((int) attr(el, "count"), attr(el, "duration"),
                    attr(el, "routeLength"), attr(el, "speed"), durationSeconds, carTrips, deliveryTrips);
        }
        return new Result(0, 0, 0, 0, durationSeconds, carTrips, deliveryTrips);
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
