package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.reference.Mid2017;
import de.synthesis.scenario.Scenario;

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

    static final String LOGISTICS_KPI_CONTRACT = "synthesis-logistics-kpis-v1";

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

    public record Summary(
            long trips,
            long persons,
            long parcels,
            long delivered,
            long deliveries,
            long deliveredDeliveries,
            long customerReturns,
            long collectedReturns,
            int tours) {
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

    public Summary ingest(Path resultsDir, Path artifacts, long durationSeconds,
                          Scenario.ReturnDemand returnDemand) throws IOException {
        Path simulationDir = artifacts.resolve("simulation");

        Demand demand = readDemand(resultsDir.resolve("demandsimulationResult.csv"));
        DepotData depotData = readDepots(resultsDir);
        applyCepsp(depotData.depots(),
                artifacts.resolve("legacy/logistics/distribution-centers.csv"));
        ParcelStates states = readParcelStates(resultsDir.resolve("parcel-states.csv"));
        Map<String, ZoneParcelCounts> parcelsPerZone = new HashMap<>();
        ParcelMetrics parcels = readParcels(resultsDir.resolve("parcel-orders-private.csv"),
                states, parcelsPerZone, depotData.depots());
        validateReturnPostconditions(returnDemand, parcels, depotData.events());

        writeKpis(simulationDir.resolve("kpis.json"), demand, parcels, depotData.events(),
                durationSeconds);
        writeDepotStats(simulationDir.resolve("depot-stats.json"), depotData.depots());
        writeParcelDensity(simulationDir.resolve("parcel-density.geojson"),
                artifacts.resolve("zone-repository").resolve("zones.geojson"), parcelsPerZone);
        int tours = writeTours(simulationDir.resolve("tours.geojson"),
                resultsDir.resolve("planned-tours.csv"),
                depotData.events().failedDeliveriesByTour());

        return new Summary(
                demand.trips,
                demand.persons.size(),
                parcels.total(),
                parcels.deliveredTotal(),
                parcels.deliveriesGenerated(),
                parcels.deliveriesDelivered(),
                parcels.customerReturnsGenerated(),
                depotData.events().collectedPickups(),
                tours);
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
        long deliveriesAssigned;
        long customerReturnsAssigned;
        long delivered;
        long pickupsPlanned;
        long pickupsCollected;
        long failedReturning;
        final Set<String> tourIds = new HashSet<>();
        double distanceKm;
    }

    private record TourKey(String vehicle, String tourId) {
    }

    private record VehicleEvents(
            long plannedPickups,
            long successfulPickups,
            long collectedPickups,
            Map<TourKey, Long> failedDeliveriesByTour) {
    }

    private record DepotData(Map<String, DepotStats> depots, VehicleEvents events) {
    }

    private DepotData readDepots(Path resultsDir) throws IOException {
        Map<String, DepotStats> depots = new LinkedHashMap<>();
        forEachRow(resultsDir.resolve("fleet.csv"), (header, cells) -> {
            DepotStats stats = depots.computeIfAbsent(cells[header.get("owner")], k -> new DepotStats());
            stats.name = cells[header.get("owner")];
            stats.vehicleType = cells[header.get("vehicleType")];
            stats.vehicles++;
        });
        long[] plannedPickups = {0};
        long[] successfulPickups = {0};
        long[] collectedPickups = {0};
        Map<TourKey, Long> failedDeliveriesByTour = new HashMap<>();
        Path events = resultsDir.resolve("vehicle_events.csv");
        if (Files.exists(events)) {
            forEachRow(events, (header, cells) -> {
                String event = cells[header.get("event")];
                long toDeliver = parseLong(cells[header.get("to_deliver")]);
                long successDelivery = parseLong(cells[header.get("success_delivery")]);
                long toPickup = parseLong(cells[header.get("to_pickup")]);
                long successPickup = parseLong(cells[header.get("success_pickup")]);
                long returning = parseLong(cells[header.get("returning")]);
                long collected = parseLong(cells[header.get("collected")]);
                if ("stop".equals(event)) {
                    plannedPickups[0] += toPickup;
                    successfulPickups[0] += successPickup;
                    long failed = Math.max(0, toDeliver - successDelivery);
                    if (failed > 0) {
                        TourKey key = new TourKey(
                                cells[header.get("veh_name")],
                                cells[header.get("tour_id")]);
                        failedDeliveriesByTour.merge(key, failed, Long::sum);
                    }
                } else if ("unload".equals(event)) {
                    // An Stopps ist collected kumulativ; nur der Unload-Wert zählt jedes
                    // eingesammelte Paket genau einmal.
                    collectedPickups[0] += collected;
                }

                DepotStats stats = depots.get(cells[header.get("dc")]);
                if (stats == null) {
                    return;
                }
                stats.cepsp = cells[header.get("cepsp")];
                stats.tourIds.add(cells[header.get("tour_id")]);
                stats.delivered += successDelivery;
                if ("stop".equals(event)) {
                    stats.pickupsPlanned += toPickup;
                } else if ("unload".equals(event)) {
                    stats.pickupsCollected += collected;
                    stats.failedReturning += returning;
                }
                stats.distanceKm += parseDouble(cells[header.get("distance")]) / 1000.0;
            });
        }
        return new DepotData(
                depots,
                new VehicleEvents(
                        plannedPickups[0],
                        successfulPickups[0],
                        collectedPickups[0],
                        Map.copyOf(failedDeliveriesByTour)));
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

    private static final class ParcelStates {
        final Map<String, Boolean> pickupById = new LinkedHashMap<>();
        final Set<String> delivered = new HashSet<>();
        final Set<String> failedReturning = new HashSet<>();
    }

    private static final class RecipientParcelCounts {
        long deliveries;
        long pickups;
    }

    private static final class ZoneParcelCounts {
        long total;
        long deliveries;
        long customerReturns;
    }

    private record ParcelMetrics(
            long total,
            long deliveredTotal,
            long deliveriesGenerated,
            long deliveriesDelivered,
            long failedReturning,
            long customerReturnsGenerated,
            long customerReturnsDelivered,
            Map<String, RecipientParcelCounts> byRecipient) {
    }

    private static ParcelStates readParcelStates(Path file) throws IOException {
        ParcelStates states = new ParcelStates();
        forEachRow(file, (header, cells) -> {
            String parcelId = cells[header.get("ParcelID")];
            String pickupValue = cells[header.get("IsPickup")].trim();
            boolean pickup;
            if ("true".equalsIgnoreCase(pickupValue)) {
                pickup = true;
            } else if ("false".equalsIgnoreCase(pickupValue)) {
                pickup = false;
            } else {
                throw new IOException("Ungültiger IsPickup-Wert für Paket " + parcelId
                        + ": " + pickupValue);
            }
            Boolean previous = states.pickupById.putIfAbsent(parcelId, pickup);
            if (previous != null && previous != pickup) {
                throw new IOException("Widersprüchliche IsPickup-Richtung für Paket " + parcelId);
            }
            String state = cells[header.get("State")];
            if ("DELIVERED".equals(state)) {
                states.delivered.add(parcelId);
            } else if (!pickup && "RETURNING".equals(state)) {
                states.failedReturning.add(parcelId);
            }
        });
        return states;
    }

    private ParcelMetrics readParcels(Path file, ParcelStates states,
                                      Map<String, ZoneParcelCounts> parcelsPerZone,
                                      Map<String, DepotStats> depots) throws IOException {
        Set<String> orderIds = new HashSet<>();
        Map<String, RecipientParcelCounts> byRecipient = new HashMap<>();
        long[] deliveriesGenerated = {0};
        long[] customerReturnsGenerated = {0};
        long[] deliveriesDelivered = {0};
        long[] customerReturnsDelivered = {0};
        forEachRow(file, (header, cells) -> {
            String parcelId = cells[header.get("ParcelID")];
            if (!orderIds.add(parcelId)) {
                throw new IOException("Doppelte Paket-ID in parcel-orders-private.csv: " + parcelId);
            }
            Boolean pickup = states.pickupById.get(parcelId);
            if (pickup == null) {
                throw new IOException("Paket " + parcelId
                        + " fehlt in parcel-states.csv oder besitzt keine Richtung");
            }
            ZoneParcelCounts zone = parcelsPerZone.computeIfAbsent(
                    cells[header.get("DestinationZone")], ignored -> new ZoneParcelCounts());
            zone.total++;
            RecipientParcelCounts recipient = byRecipient.computeIfAbsent(
                    cells[header.get("RecipientID")], ignored -> new RecipientParcelCounts());
            DepotStats stats = depots.get(cells[header.get("DistributionCenter")]);
            if (pickup) {
                customerReturnsGenerated[0]++;
                recipient.pickups++;
                zone.customerReturns++;
                if (states.delivered.contains(parcelId)) {
                    customerReturnsDelivered[0]++;
                }
                if (stats != null) {
                    stats.customerReturnsAssigned++;
                }
            } else {
                deliveriesGenerated[0]++;
                recipient.deliveries++;
                zone.deliveries++;
                if (states.delivered.contains(parcelId)) {
                    deliveriesDelivered[0]++;
                }
                if (stats != null) {
                    stats.deliveriesAssigned++;
                }
            }
            if (stats != null) {
                stats.parcelsAssigned++;
            }
        });
        long deliveredTotal = deliveriesDelivered[0] + customerReturnsDelivered[0];
        return new ParcelMetrics(
                orderIds.size(),
                deliveredTotal,
                deliveriesGenerated[0],
                deliveriesDelivered[0],
                states.failedReturning.size(),
                customerReturnsGenerated[0],
                customerReturnsDelivered[0],
                Map.copyOf(byRecipient));
    }

    private static void validateReturnPostconditions(
            Scenario.ReturnDemand returnDemand,
            ParcelMetrics parcels,
            VehicleEvents events) throws IOException {
        if (returnDemand == null) {
            throw new IOException("Retouren-Postcondition ohne Szenariokonfiguration");
        }
        if (!returnDemand.enabled) {
            if (parcels.customerReturnsGenerated() != 0
                    || events.plannedPickups() != 0
                    || events.successfulPickups() != 0
                    || events.collectedPickups() != 0) {
                throw new IOException("Retouren sind deaktiviert, Ergebnis enthält aber "
                        + parcels.customerReturnsGenerated() + " Pickup-Pakete");
            }
            return;
        }

        double share = returnDemand.shareOfPrivateOrders;
        for (Map.Entry<String, RecipientParcelCounts> entry : parcels.byRecipient().entrySet()) {
            RecipientParcelCounts counts = entry.getValue();
            double expected = counts.deliveries * share;
            long minimum = (long) Math.floor(expected);
            long maximum = (long) Math.ceil(expected);
            if (counts.pickups < minimum || counts.pickups > maximum) {
                throw new IOException("Retourenmenge verletzt die Ziehregel für Empfänger "
                        + entry.getKey() + ": " + counts.pickups + " statt "
                        + minimum + ".." + maximum);
            }
        }
        if (events.successfulPickups() > events.plannedPickups()) {
            throw new IOException("Mehr erfolgreiche als geplante Retouren-Pickups: "
                    + events.successfulPickups() + " > " + events.plannedPickups());
        }
        if (events.collectedPickups() != parcels.customerReturnsDelivered()) {
            throw new IOException("Retouren-Postcondition verletzt: Depot-Unloads "
                    + events.collectedPickups() + ", eindeutige collected-Paket-IDs "
                    + parcels.customerReturnsDelivered());
        }
        if (events.successfulPickups() != events.collectedPickups()) {
            throw new IOException("Retouren-Postcondition verletzt: erfolgreiche Pickups "
                    + events.successfulPickups() + ", Depot-Unloads "
                    + events.collectedPickups());
        }
    }

    // --- Ausgaben -----------------------------------------------------------------

    private void writeKpis(Path out, Demand demand, ParcelMetrics parcels,
                           VehicleEvents events, long durationSeconds) throws IOException {
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
        parcelsNode.put("total", parcels.total());
        parcelsNode.put("delivered", parcels.deliveredTotal());
        parcelsNode.put("deliveryRate", parcels.total() == 0
                ? 0 : round2(parcels.deliveredTotal() / (double) parcels.total()));

        ObjectNode logistics = kpis.putObject("logistics");
        logistics.put("contract", LOGISTICS_KPI_CONTRACT);
        ObjectNode deliveries = logistics.putObject("deliveries");
        deliveries.put("generated", parcels.deliveriesGenerated());
        deliveries.put("delivered", parcels.deliveriesDelivered());
        deliveries.put("failedReturning", parcels.failedReturning());
        deliveries.put("deliveryRate", parcels.deliveriesGenerated() == 0
                ? 0 : round4(parcels.deliveriesDelivered()
                / (double) parcels.deliveriesGenerated()));
        ObjectNode customerReturns = logistics.putObject("customerReturns");
        customerReturns.put("generated", parcels.customerReturnsGenerated());
        customerReturns.put("plannedPickups", events.plannedPickups());
        customerReturns.put("collected", events.collectedPickups());
        customerReturns.put("collectionRate", parcels.customerReturnsGenerated() == 0
                ? 0 : round4(events.collectedPickups()
                / (double) parcels.customerReturnsGenerated()));

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
            node.put("deliveriesAssigned", stats.deliveriesAssigned);
            node.put("customerReturnsAssigned", stats.customerReturnsAssigned);
            node.put("delivered", stats.delivered);
            node.put("pickupsPlanned", stats.pickupsPlanned);
            node.put("pickupsCollected", stats.pickupsCollected);
            node.put("failedReturning", stats.failedReturning);
            node.put("tours", stats.tourIds.size());
            node.put("distanceKm", round2(stats.distanceKm));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), array);
    }

    /** Zonenpolygone aus zones.geojson, angereichert um die Paketzahl je Zone. */
    private void writeParcelDensity(Path out, Path zonesGeoJson,
                                    Map<String, ZoneParcelCounts> parcelsPerZone) throws IOException {
        JsonNode collection = mapper.readTree(zonesGeoJson.toFile());
        for (JsonNode feature : collection.path("features")) {
            ObjectNode properties = (ObjectNode) feature.path("properties");
            String zoneId = properties.path("zoneId").asText();
            ZoneParcelCounts counts = parcelsPerZone.get(zoneId);
            properties.put("parcels", counts == null ? 0 : counts.total);
            properties.put("deliveries", counts == null ? 0 : counts.deliveries);
            properties.put("customerReturns", counts == null ? 0 : counts.customerReturns);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), collection);
    }

    /**
     * Geplante Touren als LineStrings (Depot → Stopps → ggf. Rückfahrt). Die Zeilen in
     * planned-tours.csv sind je Tour gruppiert; eine neue Tour beginnt, wenn stopNo
     * nicht mehr wächst oder das Fahrzeug wechselt.
     */
    private int writeTours(Path out, Path plannedTours,
                           Map<TourKey, Long> failedDeliveriesByTour) throws IOException {
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
            long deliveries;
            long pickups;
            long failedDeliveries;

            void flush() {
                if (properties != null) {
                    properties.put("distanceKm", round2(distance / 1000.0));
                    properties.put("stops", stops);
                    properties.put("deliveries", deliveries);
                    properties.put("pickups", pickups);
                    properties.put("failedDeliveries", failedDeliveries);
                }
                coordinates = null;
                properties = null;
                distance = 0;
                stops = 0;
                deliveries = 0;
                pickups = 0;
                failedDeliveries = 0;
            }
        }
        TourBuilder tour = new TourBuilder();

        forEachRow(plannedTours, (header, cells) -> {
            int stopNo = (int) parseDouble(cells[header.get("stopNo")]);
            String vehicle = cells[header.get("vehicleTag")];
            if (stopNo <= tour.lastStopNo || !vehicle.equals(tour.lastVehicle)) {
                tour.flush();
                String tourId = cells[header.get("tourId")];
                ObjectNode feature = features.addObject();
                feature.put("type", "Feature");
                tour.properties = feature.putObject("properties");
                tour.properties.put("depot", cells[header.get("fromDepot")]);
                tour.properties.put("vehicleType", cells[header.get("vehicleType")]);
                tour.properties.put("tourId", tourId);
                tour.failedDeliveries = failedDeliveriesByTour.getOrDefault(
                        new TourKey(vehicle, tourId), 0L);
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
            // logiTopp schreibt hier kommaseparierte Paket-IDs je Stopp, keine
            // numerischen Mengen. "22,23" bedeutet daher zwei Pakete, nicht 0 oder 22.
            tour.deliveries += countCsvIds(cells[header.get("deliveries")]);
            tour.pickups += countCsvIds(cells[header.get("pickups")]);
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
        void accept(Map<String, Integer> header, String[] cells) throws IOException;
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

    private static long parseLong(String value) {
        return Math.round(parseDouble(value));
    }

    private static long countCsvIds(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .count();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
