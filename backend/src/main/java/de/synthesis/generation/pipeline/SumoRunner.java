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
import java.util.Map;
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
                         double meanSpeedMs, long durationSeconds, int carTrips, int deliveryTrips,
                         double co2Kg, Map<String, Double> co2KgByType) {
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
        // Kantenscharfe Messung (Netzlast): über den ganzen Tag aggregierte edgeData
        Files.writeString(dir.resolve("additional.xml"), """
                <additional>
                  <edgeData id="netzlast" file="edgedata.xml"/>
                </additional>
                """);
        Files.writeString(dir.resolve("scenario.sumocfg"), """
                <configuration>
                  <input><net-file value="net.net.xml"/><route-files value="routes.rou.xml"/>
                    <additional-files value="additional.xml"/></input>
                  <output><tripinfo-output value="tripinfo.xml"/><statistic-output value="statistics.xml"/></output>
                  <time><begin value="0"/><end value="86400"/></time>
                  <processing><time-to-teleport value="300"/><ignore-route-errors value="true"/>
                    <device.emissions.probability value="1"/></processing>
                  <report><no-step-log value="true"/><duration-log.statistics value="true"/></report>
                </configuration>
                """);
        exec(dir, log, binDir.resolve("sumo").toString(), "-c", "scenario.sumocfg");

        // Kantengeometrien in WGS84 exportieren und mit den Messwerten zur Staukarte verbinden
        exec(dir, log, binDir.resolve("netconvert").toString(),
                "-s", "net.net.xml", "--plain-output-prefix", "plain", "--proj.plain-geo", "true");
        int congestionEdges = writeCongestionGeoJson(dir);
        log.accept(congestionEdges + " befahrene Kanten in sumo/congestion.geojson (Netzlast-Layer)");

        long duration = (System.currentTimeMillis() - start) / 1000;
        Map<String, Double> co2 = parseEmissions(dir.resolve("tripinfo.xml"));
        return parseStatistics(dir.resolve("statistics.xml"), duration, carTrips, deliveryTrips, co2);
    }

    // --- Netz-Feedback: OD-Stau-Faktoren -------------------------------------------

    /** Stau-Faktoren je Zonenrelation (staugewichtete / freie Routenzeit, geklemmt auf 1–3). */
    public record OdFactors(double[][] factors, int routedPairs, double meanFactor, double maxFactor) {
    }

    /**
     * Routet alle Zonen-Zentroid-Paare zweimal auf dem SUMO-Netz – einmal frei, einmal
     * gewichtet mit den gemessenen Kantenzeiten (edgedata.xml) – und liefert je Relation
     * das Zeitverhältnis. Externe Zonen liegen außerhalb des Netzes und fallen beim
     * Mapmatch heraus (Faktor bleibt 1). Voraussetzung: {@link #run} lief zuvor im selben
     * Verzeichnis (net.net.xml + edgedata.xml vorhanden).
     */
    public OdFactors computeOdFactors(Path dir, List<Zone> zones, Consumer<String> log)
            throws IOException, InterruptedException {
        int n = zones.size();
        Path odTrips = dir.resolve("od-trips.xml");
        try (Writer writer = Files.newBufferedWriter(odTrips)) {
            writer.write("<routes>\n");
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    writer.write(String.format(
                            "  <trip id=\"od_%d_%d\" depart=\"0\" fromLonLat=\"%s,%s\" toLonLat=\"%s,%s\"/>%n",
                            i, j, zones.get(i).centroidLon, zones.get(i).centroidLat,
                            zones.get(j).centroidLon, zones.get(j).centroidLat));
                }
            }
            writer.write("</routes>\n");
        }
        exec(dir, log, binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", "od-trips.xml", "-o", "od-free.rou.xml",
                "--write-costs", "true", "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log");
        exec(dir, log, binDir.resolve("duarouter").toString(),
                "-n", "net.net.xml", "--route-files", "od-trips.xml", "-o", "od-congested.rou.xml",
                "--weight-files", "edgedata.xml", "--weight-attribute", "traveltime",
                "--write-costs", "true", "--mapmatch.distance", String.valueOf(MAPMATCH_DISTANCE_M),
                "--ignore-errors", "--no-step-log");

        double[][] free = parseOdCosts(dir.resolve("od-free.rou.xml"), n);
        double[][] congested = parseOdCosts(dir.resolve("od-congested.rou.xml"), n);
        double[][] factors = new double[n][n];
        int routed = 0;
        double sum = 0;
        double max = 1;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                factors[i][j] = 1;
                if (free[i][j] > 0.001 && congested[i][j] > 0) {
                    double f = Math.max(1, Math.min(3, congested[i][j] / free[i][j]));
                    factors[i][j] = f;
                    routed++;
                    sum += f;
                    max = Math.max(max, f);
                }
            }
        }
        return new OdFactors(factors, routed, routed > 0 ? sum / routed : 1, max);
    }

    /** rou.xml (--write-costs) → Routenzeit (s) je od_i_j; 0 = nicht routbar. */
    private double[][] parseOdCosts(Path rouFile, int n) throws IOException {
        double[][] costs = new double[n][n];
        int[] current = {-1, -1};
        readLines(rouFile, line -> {
            if (line.contains("<vehicle ")) {
                current[0] = -1;
                String id = attrStr(line, "id");
                if (id != null && id.startsWith("od_")) {
                    String[] parts = id.split("_");
                    if (parts.length == 3) {
                        current[0] = Integer.parseInt(parts[1]);
                        current[1] = Integer.parseInt(parts[2]);
                    }
                }
            } else if (current[0] >= 0 && line.contains("<route ")) {
                double cost = attrD(line, "cost");
                if (cost > 0) {
                    costs[current[0]][current[1]] = cost;
                }
                current[0] = -1;
            }
        });
        return costs;
    }

    // --- Netzlast (edgeData → GeoJSON) -------------------------------------------

    private record EdgeMetric(double speedMs, double timeLossS, double sampledS) {
    }

    /**
     * Verbindet die tages-aggregierten Kantenmesswerte (edgedata.xml) mit den
     * WGS84-Kantengeometrien des plain-geo-Exports (plain.nod/.edg.xml) zu
     * {@code congestion.geojson}: eine LineString-Feature je befahrener Kante mit
     * gemessener Geschwindigkeit, Tempolimit und relSpeed (Stau-Indikator).
     */
    private int writeCongestionGeoJson(Path dir) throws IOException {
        Map<String, EdgeMetric> metrics = new java.util.HashMap<>();
        readLines(dir.resolve("edgedata.xml"), line -> {
            if (line.contains("<edge ") && attrStr(line, "id") != null) {
                double sampled = attrD(line, "sampledSeconds");
                if (sampled >= 1) {
                    metrics.put(attrStr(line, "id"),
                            new EdgeMetric(attrD(line, "speed"), attrD(line, "timeLoss"), sampled));
                }
            }
        });
        Map<String, double[]> nodes = new java.util.HashMap<>();
        readLines(dir.resolve("plain.nod.xml"), line -> {
            if (line.contains("<node ") && attrStr(line, "id") != null) {
                nodes.put(attrStr(line, "id"), new double[]{attrD(line, "x"), attrD(line, "y")});
            }
        });

        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode collection = json.createObjectNode();
        collection.put("type", "FeatureCollection");
        com.fasterxml.jackson.databind.node.ArrayNode features = collection.putArray("features");
        readLines(dir.resolve("plain.edg.xml"), line -> {
            if (!line.contains("<edge ")) {
                return;
            }
            String id = attrStr(line, "id");
            EdgeMetric metric = id == null ? null : metrics.get(id);
            if (metric == null) {
                return; // nur befahrene Kanten in die Staukarte
            }
            com.fasterxml.jackson.databind.node.ArrayNode coords = json.createArrayNode();
            double[] from = nodes.get(attrStr(line, "from"));
            double[] to = nodes.get(attrStr(line, "to"));
            if (from != null) {
                coords.add(json.createArrayNode().add(from[0]).add(from[1]));
            }
            String shape = attrStr(line, "shape");
            if (shape != null) {
                for (String point : shape.split(" ")) {
                    String[] xy = point.split(",");
                    if (xy.length >= 2) {
                        coords.add(json.createArrayNode()
                                .add(Double.parseDouble(xy[0])).add(Double.parseDouble(xy[1])));
                    }
                }
            }
            if (to != null) {
                coords.add(json.createArrayNode().add(to[0]).add(to[1]));
            }
            if (coords.size() < 2) {
                return;
            }
            double limit = line.contains("speed=\"") ? attrD(line, "speed") : 13.89;
            com.fasterxml.jackson.databind.node.ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            com.fasterxml.jackson.databind.node.ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "LineString");
            geometry.set("coordinates", coords);
            com.fasterxml.jackson.databind.node.ObjectNode props = feature.putObject("properties");
            props.put("edge", id);
            props.put("speedKmh", Math.round(metric.speedMs() * 3.6 * 10) / 10.0);
            props.put("limitKmh", Math.round(limit * 3.6 * 10) / 10.0);
            props.put("relSpeed", limit > 0
                    ? Math.round(Math.min(1, metric.speedMs() / limit) * 100) / 100.0 : 1);
            props.put("timeLossS", Math.round(metric.timeLossS()));
            props.put("sampledS", Math.round(metric.sampledS()));
        });
        json.writeValue(dir.resolve("congestion.geojson").toFile(), collection);
        return features.size();
    }

    private static void readLines(Path file, java.util.function.Consumer<String> consumer)
            throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        }
    }

    private static String attrStr(String line, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static double attrD(String line, String name) {
        String value = attrStr(line, name);
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * tripinfo.xml → CO₂ (kg) je Fahrzeugtyp. Das Emissions-Device schreibt je Fahrzeug
     * einen &lt;emissions CO2_abs="…"&gt;-Kindknoten (mg); zeilenweiser Scan merkt sich
     * den vType der umschließenden tripinfo-Zeile.
     */
    private Map<String, Double> parseEmissions(Path tripinfo) throws IOException {
        Map<String, Double> co2 = new java.util.LinkedHashMap<>();
        if (!Files.exists(tripinfo)) {
            return co2;
        }
        Pattern typePattern = Pattern.compile("vType=\"(\\w+)\"");
        Pattern co2Pattern = Pattern.compile("CO2_abs=\"([\\d.eE+-]+)\"");
        String currentType = null;
        try (BufferedReader reader = Files.newBufferedReader(tripinfo, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("<tripinfo ")) {
                    Matcher m = typePattern.matcher(line);
                    currentType = m.find() ? m.group(1) : null;
                } else if (currentType != null && line.contains("<emissions ")) {
                    Matcher m = co2Pattern.matcher(line);
                    if (m.find()) {
                        co2.merge(currentType, Double.parseDouble(m.group(1)) / 1_000_000, Double::sum);
                    }
                }
            }
        }
        co2.replaceAll((k, v) -> Math.round(v * 100) / 100.0);
        return co2;
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
            // HBEFA3-Emissionsklassen: Benzin-Pkw EU4, Diesel-Lieferwagen EU4, Lastenrad emissionsfrei
            writer.write("  <vType id=\"pkw\" vClass=\"passenger\" emissionClass=\"HBEFA3/PC_G_EU4\"/>\n");
            writer.write("  <vType id=\"lkw\" vClass=\"delivery\" emissionClass=\"HBEFA3/LDV_D_EU4\"/>\n");
            writer.write("  <vType id=\"lastenrad\" vClass=\"bicycle\" emissionClass=\"Zero\"/>\n");
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
                                   int deliveryTrips, Map<String, Double> co2ByType) throws IOException {
        double co2Total = Math.round(co2ByType.values().stream().mapToDouble(Double::doubleValue).sum()
                * 100) / 100.0;
        String xml = Files.exists(statistics) ? Files.readString(statistics) : "";
        Matcher tag = STAT_TAG.matcher(xml);
        if (tag.find()) {
            String el = tag.group();
            return new Result((int) attr(el, "count"), attr(el, "duration"),
                    attr(el, "routeLength"), attr(el, "speed"), durationSeconds, carTrips, deliveryTrips,
                    co2Total, co2ByType);
        }
        return new Result(0, 0, 0, 0, durationSeconds, carTrips, deliveryTrips, co2Total, co2ByType);
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
