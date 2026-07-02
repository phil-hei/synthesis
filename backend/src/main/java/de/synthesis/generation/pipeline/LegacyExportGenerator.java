package de.synthesis.generation.pipeline;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Exports the generated artifacts in the directory layout of the legacy
 * mobiTopp 0.3.x stack (the one logiTopp runs on): zone-repository,
 * zoneproperties, demand-data (legacy columns) and plain-text Visum matrices
 * with the legacy matrix-configuration.yaml. Validated against the
 * mobitopp-rastatt example project, see docs/05-validierung.md (findings L1-L6).
 */
public class LegacyExportGenerator {

    private static final String MODE_PREFERENCES =
            "BIKE=0.0,CAR=0.0,PASSENGER=0.0,PEDESTRIAN=0.0,PUBLICTRANSPORT=0.0";

    private final List<Zone> zones;
    private final Random random;
    /** Legacy demand files reference zones by OID (= matrix column), not external id. */
    private final Map<Long, Zone> zonesByExternalId = new HashMap<>();

    public LegacyExportGenerator(List<Zone> zones, Random random) {
        this.zones = zones;
        this.random = random;
        zones.forEach(zone -> zonesByExternalId.put(zone.id, zone));
    }

    private String oidOf(String externalId) {
        Zone zone = zonesByExternalId.get(Long.parseLong(externalId));
        return zone != null ? String.valueOf(zone.index) : "-1";
    }

    /** @return number of exported files */
    public int generate(Path artifactsDir) throws IOException {
        Path legacy = artifactsDir.resolve("legacy");
        int files = 0;
        files += writeZoneRepository(legacy.resolve("zone-repository"), artifactsDir);
        files += writeZoneProperties(legacy.resolve("zoneproperties"), artifactsDir);
        files += writeDemandData(legacy.resolve("demand-data"), artifactsDir);
        files += writeMatrices(legacy.resolve("matrices"), artifactsDir);
        files += writeLogistics(legacy.resolve("logistics"), artifactsDir);
        return files;
    }

    // --- logistics (DistributionCenterParser format) ----------------------------

    /**
     * Converts the scenario depots into the column layout of logiTopp's
     * DistributionCenterParser. Unlike the demand data, the parser resolves the
     * zone column via getByExternalId, so the external zone id is kept. Each
     * depot is assigned a CEP service provider from delivery-services.csv
     * (round-robin in share order); the renormalized shares of the providers
     * that actually own a depot go to cepsp-shares.csv for the market share model.
     */
    private int writeLogistics(Path dir, Path artifacts) throws IOException {
        Files.createDirectories(dir);
        Path logistics = artifacts.resolve("logistics");

        List<String[]> services = new java.util.ArrayList<>();
        Path servicesFile = logistics.resolve("delivery-services.csv");
        if (Files.exists(servicesFile)) {
            forEachRow(servicesFile, services::add);
        }
        if (services.isEmpty()) {
            services.add(new String[]{"CEP", "1.0"});
        }

        Map<String, Double> usedShares = new java.util.LinkedHashMap<>();
        try (Writer w = Files.newBufferedWriter(dir.resolve("distribution-centers.csv"))) {
            w.write("id;name;cepsp;vehicles;attempts;loc_x;loc_y;zone;vehicle_type;service_area;volume\n");
            int[] index = {0};
            forEachRow(logistics.resolve("distribution-centers.csv"), cells -> {
                // in: id;name;lat;lon;zoneId;numEmployees;share;vehicleType
                String[] service = services.get(index[0]++ % services.size());
                usedShares.merge(service[0], Double.parseDouble(service[1]), Double::sum);
                int vehicles = Math.max(1, Integer.parseInt(cells[5]));
                // service_area 0 = alle Zonen; volume je Fahrzeug analog Validierungslauf
                write(w, cells[0] + ";" + cells[1] + ";" + service[0] + ";" + vehicles + ";3;"
                        + cells[3] + ";" + cells[2] + ";" + cells[4] + ";"
                        + vehicleTypeCode(cells[7]) + ";0;150.0\n");
            });
        }
        double total = usedShares.values().stream().mapToDouble(Double::doubleValue).sum();
        try (Writer w = Files.newBufferedWriter(dir.resolve("cepsp-shares.csv"))) {
            w.write("name;share\n");
            for (Map.Entry<String, Double> entry : usedShares.entrySet()) {
                write(w, entry.getKey() + ";" + (entry.getValue() / total) + "\n");
            }
        }
        return 2;
    }

    /** logiTopp VehicleType codes: OTHER=0, BIKE=1, TRUCK=2, TRAM=3. */
    private static int vehicleTypeCode(String vehicleType) {
        String type = vehicleType.toUpperCase(Locale.ROOT);
        if (type.contains("BIKE")) {
            return 1;
        }
        if (type.contains("TRUCK")) {
            return 2;
        }
        if (type.contains("TRAM")) {
            return 3;
        }
        return 0;
    }

    // --- zone-repository -----------------------------------------------------

    private int writeZoneRepository(Path dir, Path artifacts) throws IOException {
        Files.createDirectories(dir);
        Files.copy(artifacts.resolve("zone-repository/zones.csv"), dir.resolve("zones.csv"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // companion files of the legacy serializer, header-only (no charging/sharing data)
        Map<String, String> headers = Map.of(
                "chargingdata.csv", "\"zoneId\";\"id\";\"stationId\";\"location\";\"type\";\"power\"",
                "stations.csv", "\"company\";\"zone\";\"id\";\"name\";\"parkingSpace\";\"location\";\"numberOfCars\"",
                "station-based-cars.csv", "\"owner\";\"stationId\";\"carId\"",
                "station-based-organizations.csv", "\"name\"",
                "free-floating-cars.csv", "\"zoneOid\";\"owner\";\"carId\"",
                "free-floating-organizations.csv", "\"name\"",
                "car-sharing-cars.csv",
                "\"carId\";\"position\";\"segment\";\"capacity\";\"currentMileage\";\"currentFuelLevel\";\"maxRange\"");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            Files.writeString(dir.resolve(entry.getKey()), entry.getValue() + "\n");
        }
        return 1 + headers.size();
    }

    // --- zoneproperties -------------------------------------------------------

    private int writeZoneProperties(Path dir, Path artifacts) throws IOException {
        Files.createDirectories(dir);
        Files.copy(artifacts.resolve("zone-repository/attractivities.csv"),
                dir.resolve("Attractivities.csv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        try (Writer w = Files.newBufferedWriter(dir.resolve("zoneproperties.csv"))) {
            w.write("zoneId;isDestination;zoneClassification;AreaType;RegionType;generatePopulation;relief;cs_density\n");
            for (Zone zone : zones) {
                w.write(zone.id + ";1;0;3;123;1;0;0\n");
            }
        }
        try (Writer w = Files.newBufferedWriter(dir.resolve("parkingFacilities.csv"))) {
            w.write("zoneId;numberofparkingplaces\n");
        }
        try (Writer w = Files.newBufferedWriter(dir.resolve("bikesharing-properties.csv"))) {
            w.write("zoneId;operating_area:BIKESHARING;number_of_bikes:BIKESHARING\n");
        }
        Files.writeString(dir.resolve("carsharing-stations.csv"),
                "ID;zoneId;system;name;num_vehicles;x_coordinate;y_coordinate\n");
        Files.writeString(dir.resolve("carsharing-properties.csv"), "zoneId;system;density\n");
        Files.writeString(dir.resolve("carsharing-free-floating.csv"), "zoneId;system;num_vehicles\n");
        return 7;
    }

    // --- demand-data (legacy columns) ------------------------------------------

    private int writeDemandData(Path dir, Path artifacts) throws IOException {
        Files.createDirectories(dir);
        Path population = artifacts.resolve("population");

        Map<Long, long[]> householdStats = countMinorsAndCars(population);
        Map<Long, Long> personToHousehold = new HashMap<>();

        convertHouseholds(population.resolve("household.csv"), dir.resolve("household.csv"), householdStats);
        convertPersons(population.resolve("person.csv"), dir.resolve("person.csv"), personToHousehold);
        convertActivities(population.resolve("activity.csv"), dir.resolve("activity.csv"));
        convertFixedDestinations(population.resolve("fixedDestination-logitopp.csv"),
                dir.resolve("fixedDestination.csv"), personToHousehold);
        convertCars(population, dir.resolve("car.csv"));
        writeOpportunities(dir.resolve("opportunity.csv"));
        return 6;
    }

    /**
     * Legacy car attributes (ConventionalCarFormat): carId; zoneOid (matrix column!);
     * location; segment; capacity; mileage; fuelLevel; maxRange — unlike the new
     * stack, carId is a bare int and the zone is referenced by its matrix column.
     */
    private void convertCars(Path population, Path out) throws IOException {
        Map<Long, Zone> householdToZone = new HashMap<>();
        forEachRow(population.resolve("household.csv"), cells ->
                householdToZone.put(Long.parseLong(cells[1]),
                        zonesByExternalId.get(Long.parseLong(cells[3]))));

        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("ownerId", "mainUserId", "personalUserId", "carType", "car attributes") + "\n");
            long[] carId = {1};
            forEachRow(population.resolve("car.csv"), cells -> {
                // in: ownerId;mainUserId;personalUserId;carType;CarId(value=N);0;pos;SEGMENT;seats;...
                Zone zone = householdToZone.get(Long.parseLong(cells[0]));
                if (zone == null) {
                    return;
                }
                // immer "conventional": BEV bräuchte im Legacy-Format ein zusätzliches
                // batteryLevel-Attribut (BevCarFormat); für die Logistik irrelevant
                write(w, quoted(cells[0], cells[1], cells[2], "conventional",
                        String.valueOf(carId[0]++), String.valueOf(zone.index),
                        CsvArtifacts.location(zone.centroidLon, zone.centroidLat),
                        cells[7], cells[8], "0.0", "1.0", "1000.0") + "\n");
            });
        }
    }

    /** minors (age<18) and car count per household, derived from the generated CSVs. */
    private Map<Long, long[]> countMinorsAndCars(Path population) throws IOException {
        Map<Long, long[]> stats = new HashMap<>();
        forEachRow(population.resolve("person.csv"), cells -> {
            long household = Long.parseLong(cells[2]);
            stats.computeIfAbsent(household, k -> new long[2]);
            if (Integer.parseInt(cells[3]) < 18) {
                stats.get(household)[0]++;
            }
        });
        forEachRow(population.resolve("car.csv"), cells ->
                stats.computeIfAbsent(Long.parseLong(cells[0]), k -> new long[2])[1]++);
        return stats;
    }

    private void convertHouseholds(Path in, Path out, Map<Long, long[]> stats) throws IOException {
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("householdId", "year", "householdNumber", "nominalSize", "domCode", "type",
                    "homeZone", "homeLocation", "homeX", "homeY", "numberOfMinors",
                    "numberOfNotSimulatedChildren", "totalNumberOfCars", "income", "incomeClass",
                    "economicalStatus", "canChargePrivately") + "\n");
            forEachRow(in, cells -> {
                // in: householdNumber;householdId;year;homeZone;homeLocation;domCode;type;income;economicalStatus
                long id = Long.parseLong(cells[1]);
                long[] stat = stats.getOrDefault(id, new long[2]);
                double[] lonLat = parseLocation(cells[4]);
                write(w, quoted(cells[1], cells[2], cells[0], cells[6], cells[5], cells[6],
                        oidOf(cells[3]), cells[4], String.valueOf(lonLat[0]), String.valueOf(lonLat[1]),
                        String.valueOf(stat[0]), "0", String.valueOf(stat[1]), cells[7], cells[8],
                        cells[8], "false") + "\n");
            });
        }
    }

    private final Map<Long, String> personNumbers = new HashMap<>();

    private void convertPersons(Path in, Path out, Map<Long, Long> personToHousehold) throws IOException {
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("personId", "personNumber", "householdId", "age", "employment", "gender",
                    "graduation", "income", "hasBike", "hasAccessToCar", "hasPersonalCar",
                    "hasCommuterTicket", "hasLicense", "preferencesSurvey", "preferencesSimulation",
                    "eMobilityAcceptance", "chargingInfluencesDestinationChoice",
                    "mobilityProviderCustomership") + "\n");
            forEachRow(in, cells -> {
                // in: personId;personNumber;householdId;age;employment;gender;graduation;income;
                //     hasBike;hasCommuterTicket;hasLicense;eMobilityAcceptance;charging...;membership
                personToHousehold.put(Long.parseLong(cells[0]), Long.parseLong(cells[2]));
                personNumbers.put(Long.parseLong(cells[0]), cells[1]);
                write(w, quoted(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6],
                        cells[7], cells[8], "true", "false", cells[9], cells[10],
                        MODE_PREFERENCES, MODE_PREFERENCES, "0.5", "NEVER", "{}") + "\n");
            });
        }
    }

    /**
     * The legacy core builds tour-based activity patterns: tournr increments with
     * every block change (home period vs. out-of-home tour), every tour carries
     * exactly one main activity, home activities have no observed trip duration,
     * and a home period must be a single row (consecutive HOME rows - e.g. across
     * midnight - are merged, matching the original long-term output).
     */
    private void convertActivities(Path in, Path out) throws IOException {
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("personId", "activityType", "observedTripDuration", "startTime", "duration",
                    "tournr", "isMainActivity", "isSupertour") + "\n");
            long[] currentPerson = {-1};
            int[] tournr = {0};
            boolean[] lastWasHome = {true};
            boolean[] mainPending = {false};
            // gepufferte HOME-Zeile: personId, startTime, kumulierte Dauer
            String[] pendingHome = {null, null, null};

            RowConsumer flushHome = cells -> {
                if (pendingHome[0] != null) {
                    write(w, quoted(pendingHome[0], "7", "0", pendingHome[1], pendingHome[2],
                            String.valueOf(tournr[0]), "false", "false") + "\n");
                    pendingHome[0] = null;
                }
            };

            forEachRow(in, cells -> {
                long person = Long.parseLong(cells[0]);
                boolean isHome = "7".equals(cells[1]);
                boolean personChanged = person != currentPerson[0];
                if (personChanged) {
                    flushHome.accept(cells);
                    currentPerson[0] = person;
                    tournr[0] = 0;
                    lastWasHome[0] = isHome;
                    mainPending[0] = !isHome;
                } else if (isHome != lastWasHome[0]) {
                    flushHome.accept(cells);
                    tournr[0]++;
                    mainPending[0] = !isHome;
                    lastWasHome[0] = isHome;
                }
                if (isHome) {
                    if (pendingHome[0] == null) {
                        pendingHome[0] = cells[0];
                        pendingHome[1] = cells[3];
                        pendingHome[2] = cells[4];
                    } else {
                        pendingHome[2] = String.valueOf(
                                Integer.parseInt(pendingHome[2]) + Integer.parseInt(cells[4]));
                    }
                    return;
                }
                boolean isMain = mainPending[0];
                mainPending[0] = false;
                write(w, quoted(cells[0], cells[1], cells[2], cells[3], cells[4],
                        String.valueOf(tournr[0]), String.valueOf(isMain), "false") + "\n");
            });
            flushHome.accept(null);
        }
    }

    private void convertFixedDestinations(Path in, Path out, Map<Long, Long> personToHousehold)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("personOid", "personNumber", "householdOid", "householdYear",
                    "householdNumber", "activityType", "zoneId", "location", "locationX", "locationY") + "\n");
            forEachRow(in, cells -> {
                // in: personOid;activityType;location;zoneId
                long person = Long.parseLong(cells[0]);
                long household = personToHousehold.getOrDefault(person, 0L);
                double[] lonLat = parseLocation(cells[2]);
                // householdNumber == householdId in unserem Modell; Parser verlangt Werte >= 0
                write(w, quoted(cells[0], personNumbers.getOrDefault(person, "1"),
                        String.valueOf(household), "2026", String.valueOf(household), cells[1],
                        oidOf(cells[3]), cells[2], String.valueOf(lonLat[0]), String.valueOf(lonLat[1])) + "\n");
            });
        }
    }

    /**
     * Opportunities per zone and activity category: destination choice candidates with
     * the zonal attractivity as weight. The legacy file references zones by their
     * matrix column (oid), not the external id.
     */
    private void writeOpportunities(Path out) throws IOException {
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(quoted("zoneId", "activityType", "location", "attractivity", "locationX", "locationY") + "\n");
            for (Zone zone : zones) {
                for (Map.Entry<String, Double> category : zone.attractivities.entrySet()) {
                    long attractivity = Math.round(category.getValue());
                    if (attractivity <= 0) {
                        continue;
                    }
                    String type = toLegacyActivityType(category.getKey());
                    double lat = zone.minLat + random.nextDouble() * (zone.maxLat - zone.minLat);
                    double lon = zone.minLon + random.nextDouble() * (zone.maxLon - zone.minLon);
                    write(w, quoted(String.valueOf(zone.index), type,
                            CsvArtifacts.location(lon, lat), String.valueOf(attractivity),
                            String.valueOf(lon), String.valueOf(lat)) + "\n");
                }
            }
        }
    }

    private static String toLegacyActivityType(String category) {
        // attractivity categories match LegacyActivityType names except for case
        return category.toUpperCase(Locale.ROOT);
    }

    // --- matrices ----------------------------------------------------------------

    /**
     * Decompresses the generated .mtx.bz2 matrices into the plain-text layout the
     * legacy matrix-configuration.yaml references; cost matrices for sharing/taxi
     * reuse car/pt costs, dummy and VM_BLOCK are zero matrices.
     */
    private int writeMatrices(Path dir, Path artifacts) throws IOException {
        Files.createDirectories(dir.resolve("travel_times"));
        Files.createDirectories(dir.resolve("travelcosts"));
        Files.createDirectories(dir.resolve("other"));
        Path source = artifacts.resolve("matrix");

        Map<String, String> mapping = new HashMap<>();
        mapping.put("travel_times/CAR_TTC.mtx", "travel_times/CAR_TTC.mtx.bz2");
        mapping.put("travel_times/BIKE_TTC.mtx", "travel_times/BIKE_TTC.mtx.bz2");
        mapping.put("travel_times/PED_TTC.mtx", "travel_times/PED_TTC.mtx.bz2");
        mapping.put("travel_times/PUT_TTC.mtx", "travel_times/PUT_TTC.mtx.bz2");
        mapping.put("travelcosts/CAR_COST.mtx", "travelcosts/CAR_COST.mtx.bz2");
        mapping.put("travelcosts/PUT_COST.mtx", "travelcosts/PUT_COST.mtx.bz2");
        mapping.put("travelcosts/BS_COST.mtx", "travelcosts/PUT_COST.mtx.bz2");
        mapping.put("travelcosts/CSFF_COST.mtx", "travelcosts/CAR_COST.mtx.bz2");
        mapping.put("travelcosts/CSSB_COST.mtx", "travelcosts/CAR_COST.mtx.bz2");
        mapping.put("travelcosts/TAXI_COST.mtx", "travelcosts/CAR_COST.mtx.bz2");
        mapping.put("other/CAR_DIS.mtx", "other/CAR_DIS.mtx.bz2");
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            decompress(source.resolve(entry.getValue()), dir.resolve(entry.getKey()));
        }

        float[][] zero = new float[zones.size()][zones.size()];
        VisumMatrixWriter.write(dir.resolve("dummy.mtx"), zones, zero);
        VisumMatrixWriter.write(dir.resolve("travel_times/VM_BLOCK.mtx"), zones, zero);

        try (InputStream config = getClass().getResourceAsStream("/legacy-matrix-configuration.yaml")) {
            Files.copy(config, dir.resolve("matrix-configuration.yaml"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return mapping.size() + 3;
    }

    private static void decompress(Path bz2, Path target) throws IOException {
        try (InputStream in = new BZip2CompressorInputStream(Files.newInputStream(bz2))) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- helpers --------------------------------------------------------------------

    private interface RowConsumer {
        void accept(String[] cells) throws IOException;
    }

    private static void forEachRow(Path file, RowConsumer consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    consumer.accept(line.split(";", -1));
                }
            }
        }
    }

    /** Extracts lon/lat from our location literal "(lon,lat: edge, pos)". */
    private static double[] parseLocation(String location) {
        String inner = location.substring(1, location.indexOf(':'));
        String[] parts = inner.split(",");
        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    }

    private static void write(Writer w, String s) {
        try {
            w.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quoted(String... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(';');
            }
            line.append('"').append(values[i]).append('"');
        }
        return line.toString();
    }
}
