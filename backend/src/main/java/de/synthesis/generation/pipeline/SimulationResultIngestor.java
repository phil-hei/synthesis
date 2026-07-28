package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.reference.Mid2017;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Liest die logiTopp-Ergebnis-CSVs (resultFolder) und verdichtet sie zu
 * Frontend-Artefakten unter artifacts/simulation/: kpis.json (Nachfrage-KPIs,
 * Modal Split inkl. MiD-2017-Referenz, Paket-KPIs), depot-stats.json und
 * parcel-density.geojson (Zonenpolygone mit Paketzahl) + tours.geojson.
 */
public class SimulationResultIngestor {

    /** MiD 2017 (bundesweit): Wege/Person/Tag, mittlere Wegelänge, Modal Split (Wege). */
    private static final double MID_TRIPS_PER_PERSON_DAY = 3.1;
    private static final double MID_AVG_TRIP_KM = 12.5;
    private static final Map<String, Double> MID_MODAL_SPLIT = Map.of(
            "pedestrian", 0.22, "bike", 0.11, "car", 0.43,
            "passenger", 0.14, "publicTransport", 0.10);
    private final ObjectMapper mapper;

    public SimulationResultIngestor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Summary(long trips, long persons, long parcels, long delivered, int tours) {
    }

    /**
     * Ungeglättete Messwerte für die Zwei-Hebel-Modus-Distanz-Kalibrierung.
     * {@code longPublicTransportShare} ist bedingt auf Wege ab 20 km; der Joint-RMSE
     * gewichtet dagegen jede Modus×Distanz-Zelle mit ihrem Anteil an allen Wegen.
     */
    public record ModeDistanceMetrics(
            long trips,
            long longTrips,
            double overallPublicTransportShare,
            double longPublicTransportShare,
            double jointRmseToMid2017) {
    }

    public Summary ingest(Path resultsDir, Path artifacts, long durationSeconds) throws IOException {
        Path simulationDir = artifacts.resolve("simulation");

        Demand demand = readDemand(resultsDir.resolve("demandsimulationResult.csv"));
        Map<String, DepotStats> depots = readDepots(resultsDir);
        applyCepsp(depots, artifacts.resolve("legacy/logistics/distribution-centers.csv"));
        Map<String, Long> parcelsPerZone = new HashMap<>();
        long totalParcels = readParcels(resultsDir.resolve("parcel-orders-private.csv"),
                parcelsPerZone, depots);
        long delivered = readDelivered(resultsDir.resolve("parcel-states.csv"));

        writeKpis(simulationDir.resolve("kpis.json"), demand, totalParcels, delivered, durationSeconds);
        writeDepotStats(simulationDir.resolve("depot-stats.json"), depots);
        writeParcelDensity(simulationDir.resolve("parcel-density.geojson"),
                artifacts.resolve("zone-repository").resolve("zones.geojson"), parcelsPerZone);
        int tours = writeTours(simulationDir.resolve("tours.geojson"),
                resultsDir.resolve("planned-tours.csv"));

        return new Summary(demand.trips, demand.persons.size(), totalParcels, delivered, tours);
    }

    // --- Nachfrage (demandsimulationResult.csv) --------------------------------

    private static final class Demand {
        long trips;
        double totalKm;
        final Set<String> persons = new HashSet<>();
        final Map<String, Long> modeCounts = new LinkedHashMap<>();
        final long[] distanceBandCounts = new long[Mid2017.DISTANCE_BANDS.size()];
        final Map<String, long[]> modeDistanceCounts = new LinkedHashMap<>();
    }

    private static Demand readDemand(Path file) throws IOException {
        Demand demand = new Demand();
        Map<String, Double> tripDistances = new HashMap<>();
        Map<String, String> tripModes = new HashMap<>();
        Set<String> startedTrips = new HashSet<>();
        forEachRow(file, (header, cells) -> {
            String personOid = cells[header.get("personOid")];
            demand.persons.add(personOid);
            String tripKey = personOid + '\0' + cells[header.get("tripId")];
            tripDistances.merge(tripKey,
                    parseDouble(cells[header.get("distanceInKm")]), Double::sum);
            // eine Zeile je Weg-Etappe; legId 0 = Wegbeginn
            if ("0".equals(cells[header.get("legId")]) && startedTrips.add(tripKey)) {
                demand.trips++;
                String mode = modeName(cells[header.get("tripMode")]);
                tripModes.put(tripKey, mode);
                demand.modeCounts.merge(mode, 1L, Long::sum);
            }
        });
        for (String tripKey : startedTrips) {
            double distanceKm = tripDistances.getOrDefault(tripKey, 0.0);
            demand.totalKm += distanceKm;
            int bandIndex = distanceBandIndex(distanceKm);
            demand.distanceBandCounts[bandIndex]++;
            demand.modeDistanceCounts
                    .computeIfAbsent(tripModes.get(tripKey),
                            ignored -> new long[Mid2017.DISTANCE_BANDS.size()])
                    [bandIndex]++;
        }
        return demand;
    }

    /** Liest die für die Modus-Distanz-Kalibrierung benötigten Rohwerte ohne KPI-Rundung. */
    public static ModeDistanceMetrics readModeDistanceMetrics(Path demandCsv) throws IOException {
        Demand demand = readDemand(demandCsv);
        int longBand = Mid2017.DISTANCE_BANDS.size() - 1;
        long longTrips = demand.distanceBandCounts[longBand];
        long[] ptByDistance = demand.modeDistanceCounts.get("publicTransport");
        long ptTrips = demand.modeCounts.getOrDefault("publicTransport", 0L);
        long longPtTrips = ptByDistance == null ? 0 : ptByDistance[longBand];
        double overallPtShare = demand.trips == 0 ? Double.NaN : ptTrips / (double) demand.trips;
        double longPtShare = longTrips == 0 ? Double.NaN : longPtTrips / (double) longTrips;
        double jointRmse = demand.trips == 0
                ? Double.NaN : modeDistanceJointRmse(demand.modeDistanceCounts, demand.trips);
        return new ModeDistanceMetrics(
                demand.trips, longTrips, overallPtShare, longPtShare, jointRmse);
    }

    /** Legacy StandardMode-Codes → MiD-Hauptverkehrsmittel-Gruppen. */
    private static String modeName(String code) {
        return switch (code) {
            case "0" -> "bike";
            case "1" -> "car";
            case "2" -> "passenger";
            case "3" -> "pedestrian";
            case "4" -> "publicTransport";
            default -> "other";
        };
    }

    // --- Depots (fleet.csv + vehicle_events.csv) ---------------------------------

    private static final class DepotStats {
        String name;
        String cepsp = "";
        String vehicleType = "";
        int vehicles;
        long parcelsAssigned;
        long delivered;
        final Set<String> tourIds = new HashSet<>();
        double distanceKm;
    }

    private Map<String, DepotStats> readDepots(Path resultsDir) throws IOException {
        Map<String, DepotStats> depots = new LinkedHashMap<>();
        forEachRow(resultsDir.resolve("fleet.csv"), (header, cells) -> {
            DepotStats stats = depots.computeIfAbsent(cells[header.get("owner")], k -> new DepotStats());
            stats.name = cells[header.get("owner")];
            stats.vehicleType = cells[header.get("vehicleType")];
            stats.vehicles++;
        });
        Path events = resultsDir.resolve("vehicle_events.csv");
        if (Files.exists(events)) {
            forEachRow(events, (header, cells) -> {
                DepotStats stats = depots.get(cells[header.get("dc")]);
                if (stats == null) {
                    return;
                }
                stats.cepsp = cells[header.get("cepsp")];
                stats.tourIds.add(cells[header.get("tour_id")]);
                stats.delivered += (long) parseDouble(cells[header.get("success_delivery")]);
                stats.distanceKm += parseDouble(cells[header.get("distance")]) / 1000.0;
            });
        }
        return depots;
    }

    /** logiTopp protokolliert in der cepsp-Spalte den Depotnamen; Dienstleister aus dem Export. */
    private void applyCepsp(Map<String, DepotStats> depots, Path distributionCenters) throws IOException {
        if (!Files.exists(distributionCenters)) {
            return;
        }
        forEachRow(distributionCenters, (header, cells) -> {
            DepotStats stats = depots.get(cells[header.get("name")]);
            if (stats != null) {
                stats.cepsp = cells[header.get("cepsp")];
            }
        });
    }

    private long readParcels(Path file, Map<String, Long> parcelsPerZone,
                             Map<String, DepotStats> depots) throws IOException {
        long[] total = {0};
        forEachRow(file, (header, cells) -> {
            total[0]++;
            parcelsPerZone.merge(cells[header.get("DestinationZone")], 1L, Long::sum);
            DepotStats stats = depots.get(cells[header.get("DistributionCenter")]);
            if (stats != null) {
                stats.parcelsAssigned++;
            }
        });
        return total[0];
    }

    private long readDelivered(Path file) throws IOException {
        Set<String> deliveredParcels = new HashSet<>();
        forEachRow(file, (header, cells) -> {
            if ("DELIVERED".equals(cells[header.get("State")])) {
                deliveredParcels.add(cells[header.get("ParcelID")]);
            }
        });
        return deliveredParcels.size();
    }

    // --- Ausgaben -----------------------------------------------------------------

    private void writeKpis(Path out, Demand demand, long parcels, long delivered,
                           long durationSeconds) throws IOException {
        ObjectNode kpis = mapper.createObjectNode();
        kpis.put("simulationSeconds", durationSeconds);

        ObjectNode demandNode = kpis.putObject("demand");
        demandNode.put("trips", demand.trips);
        demandNode.put("persons", demand.persons.size());
        double tripsPerPersonDay = demand.persons.isEmpty() ? 0
                : demand.trips / (double) demand.persons.size() / 7.0;
        demandNode.put("tripsPerPersonDay", round2(tripsPerPersonDay));
        double avgTripKm = demand.trips == 0 ? 0 : demand.totalKm / demand.trips;
        demandNode.put("avgTripKm", round2(avgTripKm));
        putDistanceBands(demandNode.putObject("distanceBands"),
                demand.distanceBandCounts, demand.trips);
        if (demand.trips == 0) {
            demandNode.putNull("distanceBandRmseToMid2017");
        } else {
            demandNode.put("distanceBandRmseToMid2017",
                    round4(distanceBandRmse(demand.distanceBandCounts, demand.trips)));
        }
        ObjectNode split = demandNode.putObject("modalSplit");
        demand.modeCounts.forEach((mode, count) ->
                split.put(mode, round2(count / (double) Math.max(1, demand.trips))));
        putModalSplitByDistanceBand(demandNode.putObject("modalSplitByDistanceBand"), demand);
        if (demand.trips == 0) {
            demandNode.putNull("modeDistanceJointRmseToMid2017");
        } else {
            demandNode.put("modeDistanceJointRmseToMid2017",
                    round4(modeDistanceJointRmse(demand.modeDistanceCounts, demand.trips)));
        }

        ObjectNode parcelsNode = kpis.putObject("parcels");
        parcelsNode.put("total", parcels);
        parcelsNode.put("delivered", delivered);
        parcelsNode.put("deliveryRate", parcels == 0 ? 0 : round2(delivered / (double) parcels));

        // Plausibilitäts-Referenz: MiD 2017 (bundesweite Mittelwerte)
        ObjectNode mid = kpis.putObject("mid2017");
        mid.put("tripsPerPersonDay", MID_TRIPS_PER_PERSON_DAY);
        mid.put("avgTripKm", MID_AVG_TRIP_KM);
        ObjectNode midSplit = mid.putObject("modalSplit");
        MID_MODAL_SPLIT.forEach(midSplit::put);
        ObjectNode midDistanceBands = mid.putObject("distanceBands");
        for (Mid2017.DistanceBand band : Mid2017.DISTANCE_BANDS) {
            midDistanceBands.put(band.key(), band.share());
        }
        mid.put("distanceBandsSource", Mid2017.DISTANCE_BANDS_SOURCE);
        mid.put("distanceBandsSourceUrl", Mid2017.DISTANCE_BANDS_SOURCE_URL);
        ObjectNode midModeByDistance = mid.putObject("modalSplitByDistanceBand");
        for (Mid2017.ModeDistanceBand band : Mid2017.MODAL_SPLIT_BY_DISTANCE_BAND) {
            ObjectNode bandNode = midModeByDistance.putObject(band.key());
            for (String mode : Mid2017.MODES) {
                bandNode.put(mode, band.modalSplit().get(mode));
            }
        }
        mid.put("modeDistanceSource", Mid2017.MODE_DISTANCE_SOURCE);
        mid.put("modeDistanceSourceUrl", Mid2017.DISTANCE_BANDS_SOURCE_URL);

        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), kpis);
    }

    private static void putDistanceBands(ObjectNode node, long[] counts, long trips) {
        for (int i = 0; i < Mid2017.DISTANCE_BANDS.size(); i++) {
            double share = trips == 0 ? 0 : counts[i] / (double) trips;
            node.put(Mid2017.DISTANCE_BANDS.get(i).key(), round4(share));
        }
    }

    private static void putModalSplitByDistanceBand(ObjectNode node, Demand demand) {
        for (int bandIndex = 0; bandIndex < Mid2017.DISTANCE_BANDS.size(); bandIndex++) {
            ObjectNode bandNode =
                    node.putObject(Mid2017.DISTANCE_BANDS.get(bandIndex).key());
            long bandTrips = demand.distanceBandCounts[bandIndex];
            for (String mode : Mid2017.MODES) {
                long[] counts = demand.modeDistanceCounts.get(mode);
                long count = counts == null ? 0 : counts[bandIndex];
                bandNode.put(mode, bandTrips == 0 ? 0 : round4(count / (double) bandTrips));
            }
        }
    }

    static int distanceBandIndex(double distanceKm) {
        if (distanceKm < 1.0) {
            return 0;
        }
        if (distanceKm < 2.0) {
            return 1;
        }
        if (distanceKm < 5.0) {
            return 2;
        }
        if (distanceKm < 10.0) {
            return 3;
        }
        if (distanceKm < 20.0) {
            return 4;
        }
        return 5;
    }

    static double distanceBandRmse(long[] counts, long trips) {
        if (counts.length != Mid2017.DISTANCE_BANDS.size() || trips <= 0) {
            throw new IllegalArgumentException("Sechs Distanzbänder und mindestens ein Weg erwartet");
        }
        double squaredError = 0;
        for (int i = 0; i < counts.length; i++) {
            double difference = counts[i] / (double) trips
                    - Mid2017.DISTANCE_BANDS.get(i).share();
            squaredError += difference * difference;
        }
        return Math.sqrt(squaredError / counts.length);
    }

    static double modeDistanceJointRmse(Map<String, long[]> counts, long trips) {
        if (trips <= 0) {
            throw new IllegalArgumentException("Mindestens ein Weg erwartet");
        }
        double squaredError = 0;
        int cells = 0;
        for (int bandIndex = 0; bandIndex < Mid2017.DISTANCE_BANDS.size(); bandIndex++) {
            double targetBandShare = Mid2017.DISTANCE_BANDS.get(bandIndex).share();
            Mid2017.ModeDistanceBand targetBand =
                    Mid2017.MODAL_SPLIT_BY_DISTANCE_BAND.get(bandIndex);
            for (String mode : Mid2017.MODES) {
                long[] modeCounts = counts.get(mode);
                double observed = modeCounts == null ? 0 : modeCounts[bandIndex] / (double) trips;
                double target = targetBandShare * targetBand.modalSplit().get(mode);
                double difference = observed - target;
                squaredError += difference * difference;
                cells++;
            }
        }
        return Math.sqrt(squaredError / cells);
    }

    private void writeDepotStats(Path out, Map<String, DepotStats> depots) throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (DepotStats stats : depots.values()) {
            ObjectNode node = array.addObject();
            node.put("name", stats.name);
            node.put("cepsp", stats.cepsp);
            node.put("vehicleType", stats.vehicleType);
            node.put("vehicles", stats.vehicles);
            node.put("parcelsAssigned", stats.parcelsAssigned);
            node.put("delivered", stats.delivered);
            node.put("tours", stats.tourIds.size());
            node.put("distanceKm", round2(stats.distanceKm));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), array);
    }

    /** Zonenpolygone aus zones.geojson, angereichert um die Paketzahl je Zone. */
    private void writeParcelDensity(Path out, Path zonesGeoJson,
                                    Map<String, Long> parcelsPerZone) throws IOException {
        JsonNode collection = mapper.readTree(zonesGeoJson.toFile());
        for (JsonNode feature : collection.path("features")) {
            ObjectNode properties = (ObjectNode) feature.path("properties");
            String zoneId = properties.path("zoneId").asText();
            properties.put("parcels", parcelsPerZone.getOrDefault(zoneId, 0L));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), collection);
    }

    /**
     * Geplante Touren als LineStrings (Depot → Stopps → ggf. Rückfahrt). Die Zeilen in
     * planned-tours.csv sind je Tour gruppiert; eine neue Tour beginnt, wenn stopNo
     * nicht mehr wächst oder das Fahrzeug wechselt.
     */
    private int writeTours(Path out, Path plannedTours) throws IOException {
        ObjectNode collection = mapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");

        final class TourBuilder {
            ArrayNode coordinates;
            ObjectNode properties;
            int lastStopNo = Integer.MAX_VALUE;
            String lastVehicle = "";
            double distance;
            int stops;

            void flush() {
                if (properties != null) {
                    properties.put("distanceKm", round2(distance / 1000.0));
                    properties.put("stops", stops);
                }
                coordinates = null;
                properties = null;
                distance = 0;
                stops = 0;
            }
        }
        TourBuilder tour = new TourBuilder();

        forEachRow(plannedTours, (header, cells) -> {
            int stopNo = (int) parseDouble(cells[header.get("stopNo")]);
            String vehicle = cells[header.get("vehicleTag")];
            if (stopNo <= tour.lastStopNo || !vehicle.equals(tour.lastVehicle)) {
                tour.flush();
                ObjectNode feature = features.addObject();
                feature.put("type", "Feature");
                tour.properties = feature.putObject("properties");
                tour.properties.put("depot", cells[header.get("fromDepot")]);
                tour.properties.put("vehicleType", cells[header.get("vehicleType")]);
                ObjectNode geometry = feature.putObject("geometry");
                geometry.put("type", "LineString");
                tour.coordinates = geometry.putArray("coordinates");
                addPoint(tour.coordinates,
                        parseDouble(cells[header.get("fromX")]), parseDouble(cells[header.get("fromY")]));
            }
            addPoint(tour.coordinates,
                    parseDouble(cells[header.get("toX")]), parseDouble(cells[header.get("toY")]));
            tour.distance += parseDouble(cells[header.get("distance")]);
            tour.stops++;
            tour.lastStopNo = stopNo;
            tour.lastVehicle = vehicle;
        });
        tour.flush();

        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), collection);
        return features.size();
    }

    private static void addPoint(ArrayNode coordinates, double lon, double lat) {
        ArrayNode point = coordinates.addArray();
        point.add(lon);
        point.add(lat);
    }

    // --- CSV-Helfer -----------------------------------------------------------------

    private interface RowConsumer {
        void accept(Map<String, Integer> header, String[] cells);
    }

    private static void forEachRow(Path file, RowConsumer consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            Map<String, Integer> header = new HashMap<>();
            String[] names = headerLine.split(";", -1);
            for (int i = 0; i < names.length; i++) {
                header.put(names[i].trim(), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    consumer.accept(header, line.split(";", -1));
                }
            }
        }
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
