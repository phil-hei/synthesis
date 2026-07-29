package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Writes the logistics artifacts for logiTopp: distribution centers, delivery
 * services, packstations and parcel demand parameters. Choice model parameter
 * values default to the calibrated set published in kit-ifv/logitopp
 * (choice-models/parameters, GPL-3.0).
 */
public class LogisticsGenerator {

    static final String PARCEL_DEMAND_CONTRACT = "synthesis-parcel-demand-v1";

    public record Result(int depotCount, int packstationCount) {
    }

    private final List<Zone> zones;
    private final Random random;

    public LogisticsGenerator(List<Zone> zones, Random random) {
        this.zones = zones;
        this.random = random;
    }

    public record Sources(boolean packstationsFromOsm, boolean depotsFromOsm) {
    }

    private Sources sources = new Sources(false, false);

    public Sources sources() {
        return sources;
    }

    public Result generate(Path logisticsDir, Scenario scenario, int totalPersons,
                           List<OverpassClient.Poi> pois) throws IOException {
        Files.createDirectories(logisticsDir);
        List<Scenario.Depot> depots = scenario.logistics.depots.isEmpty()
                ? autoPlaceDepots(totalPersons, pois)
                : scenario.logistics.depots;

        writeDepots(logisticsDir.resolve("distribution-centers.csv"), depots);
        writeServices(logisticsDir.resolve("delivery-services.csv"), scenario.logistics.services);
        int packstations = writePackstations(logisticsDir.resolve("packstations.csv"), totalPersons, pois);
        writeParcelDemandConfig(logisticsDir.resolve("parcel-demand-config.yaml"), scenario.logistics);
        writeChoiceModelParameters(logisticsDir);
        return new Result(depots.size(), packstations);
    }

    /**
     * Without user-placed depots: real OSM logistics locations (post depots,
     * courier/logistics offices) if present, otherwise synthetic placement.
     * One depot per ~15k persons.
     *
     * Synthetic placement scores zones by gewerbliche Eignung × Nachfragenähe:
     * work attractivity stands in for commercial/industrial land (real parcel
     * depots sit in Gewerbegebieten, not residential streets), weighted by a
     * population gravity term so the depot still ends up close to its delivery
     * demand instead of in a remote office park.
     */
    private List<Scenario.Depot> autoPlaceDepots(int totalPersons, List<OverpassClient.Poi> pois) {
        int count = Math.max(1, totalPersons / 15_000);
        int employees = Math.max(5, totalPersons / (count * 800));

        List<OverpassClient.Poi> osmDepots = pois.stream()
                .filter(p -> "post_depot".equals(p.tags().get("amenity"))
                        || "courier".equals(p.tags().get("office"))
                        || "logistics".equals(p.tags().get("office")))
                .toList();
        List<Scenario.Depot> depots = new ArrayList<>();
        for (int i = 0; i < osmDepots.size() && depots.size() < count; i++) {
            OverpassClient.Poi poi = osmDepots.get(i);
            String name = poi.tags().getOrDefault("name", "OSM-Depot " + (i + 1));
            depots.add(new Scenario.Depot(name, poi.lat(), poi.lon(), employees, 1.0 / count, "TRUCK"));
        }
        boolean fromOsm = !depots.isEmpty();

        List<Zone> candidates = new ArrayList<>(zones);
        candidates.sort(Comparator.comparingDouble(this::depotScore).reversed());
        for (int i = 0; depots.size() < count && i < candidates.size(); i++) {
            Zone zone = candidates.get(i);
            depots.add(new Scenario.Depot("Depot " + (depots.size() + 1), zone.centroidLat,
                    zone.centroidLon, employees, 1.0 / count, "TRUCK"));
        }
        sources = new Sources(sources.packstationsFromOsm(), fromOsm);
        return depots;
    }

    /** Commercial suitability (work attractivity) × reachable parcel demand (population gravity). */
    private double depotScore(Zone candidate) {
        double reachablePopulation = 0;
        for (Zone zone : zones) {
            double distance = GridZoning.distanceKm(candidate, zone);
            reachablePopulation += zone.population / (1 + distance * distance);
        }
        return Math.sqrt(candidate.attractivities.getOrDefault("Work", 0.0) + 1) * reachablePopulation;
    }

    private void writeDepots(Path file, List<Scenario.Depot> depots) throws IOException {
        double totalShare = depots.stream().mapToDouble(d -> d.share).sum();
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("id;name;lat;lon;zoneId;numEmployees;share;vehicleType\n");
            int id = 1;
            for (Scenario.Depot depot : depots) {
                int zoneIndex = GridZoning.zoneIndexOf(zones, depot.lat, depot.lon);
                long zoneId = zoneIndex >= 0 ? zones.get(zoneIndex).id : zones.get(0).id;
                double normalizedShare = totalShare > 0 ? depot.share / totalShare : 1.0 / depots.size();
                w.write(id + ";" + depot.name + ";" + depot.lat + ";" + depot.lon + ";" + zoneId + ";"
                        + depot.employees + ";" + format(normalizedShare) + ";" + depot.vehicleType + "\n");
                id++;
            }
        }
    }

    private void writeServices(Path file, List<Scenario.DeliveryService> services) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("name;share\n");
            for (Scenario.DeliveryService service : services) {
                w.write(service.name + ";" + format(service.share) + "\n");
            }
        }
    }

    /**
     * Real packstations from OSM (amenity=parcel_locker, well-mapped in Germany);
     * fallback: one synthetic packstation per ~4000 persons in shopping-heavy zones.
     */
    private int writePackstations(Path file, int totalPersons, List<OverpassClient.Poi> pois) throws IOException {
        List<OverpassClient.Poi> lockers = pois.stream()
                .filter(p -> "parcel_locker".equals(p.tags().get("amenity")))
                .toList();
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("id;lat;lon;zoneId;capacity;name\n");
            if (!lockers.isEmpty()) {
                int id = 1;
                for (OverpassClient.Poi locker : lockers) {
                    int zoneIndex = GridZoning.zoneIndexOf(zones, locker.lat(), locker.lon());
                    if (zoneIndex < 0) {
                        continue;
                    }
                    String name = locker.tags().getOrDefault("brand",
                            locker.tags().getOrDefault("operator", "Packstation"));
                    w.write(id + ";" + locker.lat() + ";" + locker.lon() + ";"
                            + zones.get(zoneIndex).id + ";150;" + name + "\n");
                    id++;
                }
                sources = new Sources(id > 1, sources.depotsFromOsm());
                if (id > 1) {
                    return id - 1;
                }
            }
            int count = Math.max(1, totalPersons / 4_000);
            List<Zone> candidates = new ArrayList<>(zones);
            candidates.sort(Comparator.comparingDouble(
                    (Zone z) -> z.attractivities.getOrDefault("Shopping_Daily", 0.0)).reversed());
            for (int i = 0; i < count && i < candidates.size(); i++) {
                Zone zone = candidates.get(i);
                double lat = zone.minLat + random.nextDouble() * (zone.maxLat - zone.minLat);
                double lon = zone.minLon + random.nextDouble() * (zone.maxLon - zone.minLon);
                w.write((i + 1) + ";" + lat + ";" + lon + ";" + zone.id + ";150;Packstation (synthetisch)\n");
            }
            return count;
        }
    }

    static void writeParcelDemandConfig(Path file, Scenario.Logistics logistics) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("""
                    # Paketnachfrage-Parameter für logiTopp (NumberOfParcelsSelector / ParcelDestinationSelector)
                    contractVersion: %s
                    parcelOrders:
                      perPersonPerWeek:
                        distribution: normal
                        mean: %s
                        stdDev: %s
                        min: 0
                        max: %d
                    destinationShares:
                      HOME: %s
                      WORK: %s
                      PACKSTATION: %s
                    deliveryDates:
                      from: MONDAY
                      to: SATURDAY
                      precision: day
                    returns:
                      enabled: false
                      shareOfPrivateOrders: 0.0
                      pickupShares:
                        HOME: 1.0
                        WORK: 0.0
                    """.formatted(
                    PARCEL_DEMAND_CONTRACT,
                    format(logistics.parcelsPerPersonWeek), format(logistics.parcelStdDev),
                    logistics.maxParcelsPerPerson,
                    format(logistics.shareHome), format(logistics.shareWork),
                    format(logistics.sharePackstation)));
        }
    }

    /** Calibrated parameters from kit-ifv/logitopp (choice-models/parameters). */
    private void writeChoiceModelParameters(Path dir) throws IOException {
        Files.writeString(dir.resolve("number_of_parcels_model_parameters.txt"), """
                asc_pkg1 = -1.8366
                asc_pkg2 = -3.3311
                asc_pkg3 = -4.0946
                asc_pkg4 = -6.3392
                asc_pkg5 = -5.5133
                asc_pkg6 = -5.4478
                asc_pkg7 = -6.3787
                b_male = 0.3511
                b_income2 = 0.4031
                b_income3 = 0.7382
                b_job1 = 0.2112
                b_age2 = 0.3591
                b_age3 = -0.3774
                b_age4 = -0.9983
                lambda_root = 1.0
                """);
        Files.writeString(dir.resolve("parcel_destination_choice_parameters.txt"), """
                asc_work = -1.6726
                asc_locker = -0.8484
                b_male = 0.0852
                b_income2 = -0.1129
                b_income3 = -0.2273
                b_job1 = 0.2845
                b_age2 = -0.2272
                b_age3 = -1.0022
                b_age4 = -1.3015
                lambda_root = 1.0
                """);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
