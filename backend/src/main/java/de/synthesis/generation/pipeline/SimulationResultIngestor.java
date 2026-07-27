package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private static final String[] DISTANCE_BAND_KEYS = {
            "under1Km", "km1ToUnder2", "km2ToUnder5",
            "km5ToUnder10", "km10ToUnder20", "km20AndMore"
    };
    /**
     * MiD 2017, Tabelle A W12 „Wegelänge“, Gesamt, alle Wege inklusive
     * imputierter Werte. Die neun amtlichen Klassen
     * [10, 11, 14, 23, 16, 13, 9, 2, 2] % werden nur an vorhandenen
     * Klassengrenzen zu sechs Diagnosebändern aggregiert.
     */
    private static final double[] MID_DISTANCE_BAND_SHARES = {
            0.21, 0.14, 0.23, 0.16, 0.13, 0.13
    };
    private static final String MID_DISTANCE_BANDS_SOURCE =
            "MiD 2017, Tabelle A W12, Gesamt; alle Wege inklusive imputierter Werte";

    private final ObjectMapper mapper;

    public SimulationResultIngestor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Summary(long trips, long persons, long parcels, long delivered, int tours) {
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
        final long[] distanceBandCounts = new long[DISTANCE_BAND_KEYS.length];
    }

    private Demand readDemand(Path file) throws IOException {
        Demand demand = new Demand();
        Map<String, Double> tripDistances = new HashMap<>();
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
                demand.modeCounts.merge(modeName(cells[header.get("tripMode")]), 1L, Long::sum);
            }
        });
        for (String tripKey : startedTrips) {
            double distanceKm = tripDistances.getOrDefault(tripKey, 0.0);
            demand.totalKm += distanceKm;
            demand.distanceBandCounts[distanceBandIndex(distanceKm)]++;
        }
        return demand;
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
        for (int i = 0; i < DISTANCE_BAND_KEYS.length; i++) {
            midDistanceBands.put(DISTANCE_BAND_KEYS[i], MID_DISTANCE_BAND_SHARES[i]);
        }
        mid.put("distanceBandsSource", MID_DISTANCE_BANDS_SOURCE);

        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), kpis);
    }

    private static void putDistanceBands(ObjectNode node, long[] counts, long trips) {
        for (int i = 0; i < DISTANCE_BAND_KEYS.length; i++) {
            double share = trips == 0 ? 0 : counts[i] / (double) trips;
            node.put(DISTANCE_BAND_KEYS[i], round4(share));
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
        if (counts.length != MID_DISTANCE_BAND_SHARES.length || trips <= 0) {
            throw new IllegalArgumentException("Sechs Distanzbänder und mindestens ein Weg erwartet");
        }
        double squaredError = 0;
        for (int i = 0; i < counts.length; i++) {
            double difference = counts[i] / (double) trips - MID_DISTANCE_BAND_SHARES[i];
            squaredError += difference * difference;
        }
        return Math.sqrt(squaredError / counts.length);
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
