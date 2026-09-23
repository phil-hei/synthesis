package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Synthesizes households, persons, cars and fixed destinations and writes them
 * in the mobiTopp CSV column layout (HouseholdColumns/PersonColumns/... of the
 * current mobiTopp main branch). Statistical distributions are rough German
 * averages; this is the "synthetic" data-quality tier described in
 * docs/02-datenanforderungen.md.
 */
public class PopulationGenerator {

    public record PersonRef(long id, long householdId, int age, String employment,
                            int homeZoneIndex, int workZoneIndex, int eduZoneIndex) {
    }

    public record Result(List<PersonRef> persons, int householdCount, int carCount,
                         int externalWorkDestinations) {
    }

    private static final double[] HOUSEHOLD_SIZE_SHARES = {0.42, 0.33, 0.12, 0.09, 0.04};
    private final int referenceYear;

    private final List<Zone> zones;
    private final Random random;
    private final long destinationSeed;
    private final Map<String, double[]> gravityCache = new HashMap<>();
    private CommuterMatrix.Mapping commuterMapping;
    private Double externalWorkShare;
    private CensusStructureSource.Resolved censusStructure;
    private final Map<String, Object> censusReport = new java.util.LinkedHashMap<>();

    public void useCensusStructure(CensusStructureSource.Resolved structure) { this.censusStructure = structure; }
    public Map<String, Object> censusStructureReport() { return censusReport; }

    public PopulationGenerator(List<Zone> zones, Random random) {
        this(zones, random, 0L);
    }

    public PopulationGenerator(List<Zone> zones, Random random, long destinationSeed) {
        this(zones, random, destinationSeed, 2026);
    }

    public PopulationGenerator(List<Zone> zones, Random random, long destinationSeed, int referenceYear) {
        this.zones = zones;
        this.random = random;
        this.destinationSeed = destinationSeed;
        this.referenceYear = referenceYear;
    }

    /** Uses observed commuter relations for WORK; uncovered home zones keep the gravity fallback. */
    public void useCommuterMapping(CommuterMatrix.Mapping mapping) {
        this.commuterMapping = mapping;
    }

    /** Applies an observed regional outbound marginal while OD relations choose the external target. */
    public void useExternalWorkShare(double share) {
        if (!Double.isFinite(share) || share < 0 || share > 1) {
            throw new IllegalArgumentException("Außenarbeitsplatzanteil muss zwischen 0 und 1 liegen");
        }
        this.externalWorkShare = share;
    }

    /**
     * Captures the untreated WORK gravity distribution before commuter anchors
     * move shared external-zone centroids. Conditional outbound mappings must
     * change destinations, not the probability of choosing an external trip.
     */
    public void freezeWorkGravity() {
        zones.stream().filter(zone -> !zone.external)
                .forEach(zone -> gravityCumulative(zone.index, "Work"));
    }

    /**
     * Distributes total population over zones. Preference order of spatial signals:
     * OSM place nodes (Gaussian footprint weights), then POI density, then uniform noise.
     */
    public void distributePopulation(int totalPersons, int[] poiCountPerZone, double[] placeWeights) {
        double[] weights = new double[zones.size()];
        double placeSum = 0;
        if (placeWeights != null) {
            for (double w : placeWeights) {
                placeSum += w;
            }
        }
        double sum = 0;
        for (Zone zone : zones) {
            double weight;
            if (placeSum > 0) {
                // small floor keeps remote zones from being completely empty
                weight = placeWeights[zone.index] + placeSum / zones.size() * 0.03;
            } else {
                weight = 1 + 0.5 * random.nextDouble();
                if (poiCountPerZone != null) {
                    weight += Math.min(poiCountPerZone[zone.index], 50) * 0.06;
                }
            }
            weights[zone.index] = weight;
            sum += weight;
        }
        for (Zone zone : zones) {
            zone.population = totalPersons * weights[zone.index] / sum;
        }
    }

    public Result generate(Path populationDir, Scenario scenario) throws IOException {
        Files.createDirectories(populationDir);
        censusReport.clear();
        Files.deleteIfExists(populationDir.resolve("census-structure.json"));
        if (scenario.population.useCensusStructure && !scenario.population.useKitPopulation)
            throw new IllegalArgumentException("Zensus-Struktur benötigt das KIT-Demografie-Sample");
        List<Map<String, Object>> structureZones = new ArrayList<>();
        List<PersonRef> persons = new ArrayList<>();
        long householdId = 1;
        long personId = 1;
        long carId = 1;
        int carCount = 0;
        int externalWorkDestinations = 0;

        try (Writer households = Files.newBufferedWriter(populationDir.resolve("household.csv"));
             Writer personsCsv = Files.newBufferedWriter(populationDir.resolve("person.csv"));
             Writer cars = Files.newBufferedWriter(populationDir.resolve("car.csv"));
             Writer fixed = Files.newBufferedWriter(populationDir.resolve("fixedDestination.csv"));
             Writer fixedLogitopp = Files.newBufferedWriter(populationDir.resolve("fixedDestination-logitopp.csv"))) {

            households.write("householdNumber;householdId;year;homeZone;homeLocation;domCode;type;income;economicalStatus\n");
            personsCsv.write("personId;personNumber;householdId;age;employment;gender;graduation;income;"
                    + "hasBike;hasCommuterTicket;hasLicense;eMobilityAcceptance;chargingInfluencesDestinationChoice;"
                    + "mobilityProviderCustomership\n");
            // Legacy-Spaltenlayout des mobiTopp-Long-Term-Outputs: positional segment(7)/seats(8)
            cars.write("ownerId;mainUserId;personalUserId;carType;car attributes\n");
            fixed.write("personOid;activityType;location;zoneId\n");
            fixedLogitopp.write("personOid;activityType;location;zoneId\n");

            // KIT-Demografie-Sample (echte Haushaltskompositionen) optional zuschalten
            PopulationSample kitSample = scenario.population.useKitPopulation
                    ? PopulationSample.fromResource("/kit-population-sample.csv") : null;
            if (scenario.population.useCensusStructure) {
                censusReport.put("seedPool", "kit-population-sample.csv");
                censusReport.put("seedPoolCompositionSha256", kitSample.fingerprint());
                censusReport.put("seedPoolHouseholds", kitSample.householdCount());
            }
            int[] sampledPersonsByZone = sampledPersonsByZone(scenario.population.samplingFraction);

            for (int zonePosition = 0; zonePosition < zones.size(); zonePosition++) {
                Zone zone = zones.get(zonePosition);
                int remaining = sampledPersonsByZone[zonePosition];
                CensusHouseholdSampler regional = null;
                if (scenario.population.useCensusStructure && censusStructure != null
                        && censusStructure.zones().containsKey(zone.index) && remaining > 0 && !zone.external)
                    regional = new CensusHouseholdSampler(kitSample, censusStructure.zones().get(zone.index));
                while (remaining > 0) {
                    List<PopulationSample.Member> composition = regional != null
                            ? regional.draw(random, remaining) : drawHousehold(kitSample, remaining);
                    int size = composition.size();
                    int economicalStatus = 1 + random.nextInt(5);
                    int income = 900 + economicalStatus * 800 + random.nextInt(600);
                    double lat = zone.minLat + random.nextDouble() * (zone.maxLat - zone.minLat);
                    double lon = zone.minLon + random.nextDouble() * (zone.maxLon - zone.minLon);
                    String homeLocation = CsvArtifacts.location(lon, lat);

                    households.write(householdId + ";" + householdId + ";" + referenceYear + ";" + zone.id + ";"
                            + homeLocation + ";0;" + size + ";" + income + ";" + economicalStatus + "\n");

                    List<PersonRef> members = new ArrayList<>(size);
                    for (int member = 0; member < size; member++) {
                        PopulationSample.Member m = composition.get(member);
                        String employment = drawEmployment(m.age());
                        // Jede Person bekommt ein festes Arbeits- und Bildungsziel: actiTopp
                        // entscheidet selbst, wer arbeitet/lernt; fehlt das Fixziel, bricht die
                        // Legacy-Zielwahl mit "No destination available for WORK/EDUCATION" ab.
                        // Wird nur konsultiert, wenn die jeweilige Aktivität tatsächlich auftritt.
                        int workZone = sampleWorkZone(zone.index, personId);
                        if (zones.get(workZone).external) {
                            externalWorkDestinations++;
                        }
                        int eduZone = sampleEducationZone(zone.index, m.age(), personId);
                        PersonRef person = new PersonRef(personId, householdId, m.age(), employment,
                                zone.index, workZone, eduZone);
                        members.add(person);
                        persons.add(person);
                        writePerson(personsCsv, person, member, income / size, m.gender());
                        writeFixedDestinations(fixed, fixedLogitopp, person, zone);
                        personId++;
                    }

                    int householdCars = drawCarCount(scenario.population.carsPerHousehold, members);
                    for (int c = 0; c < householdCars; c++) {
                        long mainUser = members.stream()
                                .filter(p -> p.age() >= 18)
                                .findFirst().map(PersonRef::id).orElse(members.get(0).id());
                        String carType = random.nextDouble() < 0.08 ? "bev" : "conventional";
                        cars.write(householdId + ";" + mainUser + ";" + mainUser + ";" + carType
                                + ";CarId(value=" + carId + ");0;"
                                + zone.centroidLon + "," + zone.centroidLat + ",ZoneId(value=" + zone.id + ")"
                                + ";MIDSIZE;5;0.0;1.0;1000.0\n");
                        carId++;
                        carCount++;
                    }

                    zone.households++;
                    remaining -= size;
                    householdId++;
                }
                if (regional != null) {
                    Map<String, Object> report = regional.report(); report.put("zoneId", zone.id);
                    structureZones.add(report);
                }
            }
        }
        if (scenario.population.useCensusStructure) {
            long weighted = structureZones.stream().filter(r -> "weighted".equals(r.get("status"))).count();
            censusReport.put("schemaVersion", 1);
            censusReport.put("source", "destatis-zensus-2022");
            censusReport.put("referenceDate", "2022-05-15");
            censusReport.put("scenarioYear", scenario.referenceYear);
            censusReport.put("seed", scenario.population.seed);
            censusReport.put("samplingFraction", scenario.population.samplingFraction);
            censusReport.put("method", "whole-household-exponential-weighting-v1");
            censusReport.put("temporalPolicy", "2022 shares applied to scenario totals; no demographic projection");
            censusReport.put("status", weighted == 0 ? "fallback" : "weighted-with-diagnostics");
            censusReport.put("weightedZones", weighted);
            censusReport.put("fitFallbackZones", structureZones.size() - weighted);
            censusReport.put("maxFitShareError", CensusHouseholdSampler.MAX_FIT_ERROR);
            censusReport.put("license", CensusPopulationSource.LICENSE);
            censusReport.put("attribution", CensusPopulationSource.ATTRIBUTION);
            censusReport.put("unavailableReason", censusStructure == null ? "Strukturdaten nicht aufgelöst" : censusStructure.unavailableReason());
            censusReport.put("datasets", censusStructure == null ? List.of() : censusStructure.datasets());
            censusReport.put("ageGrid", censusStructure == null ? null : censusStructure.ages());
            censusReport.put("householdGrid", censusStructure == null ? null : censusStructure.households());
            censusReport.put("zones", structureZones);
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(populationDir.resolve("census-structure.json").toFile(), censusReport);
        }
        return new Result(persons, (int) (householdId - 1), carCount,
                externalWorkDestinations);
    }

    /**
     * Converts fractional zone populations into one exact global sample using the
     * largest-remainder method. Rounding independently per zone would make the
     * sample grow with the zone count (especially for fine grids).
     */
    private int[] sampledPersonsByZone(double samplingFraction) {
        int[] sampled = new int[zones.size()];
        double[] remainders = new double[zones.size()];
        double expectedTotal = 0;
        int floorTotal = 0;

        for (int i = 0; i < zones.size(); i++) {
            double expected = Math.max(0, zones.get(i).population * samplingFraction);
            sampled[i] = (int) Math.floor(expected);
            remainders[i] = expected - sampled[i];
            expectedTotal += expected;
            floorTotal += sampled[i];
        }

        int targetTotal = (int) Math.round(expectedTotal);
        List<Integer> byRemainder = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            byRemainder.add(i);
        }
        byRemainder.sort(Comparator
                .comparingDouble((Integer i) -> remainders[i]).reversed()
                .thenComparingInt(i -> i));
        for (int i = 0; i < targetTotal - floorTotal; i++) {
            sampled[byRemainder.get(i)]++;
        }
        return sampled;
    }

    /**
     * One household composition: a KIT-sampled household when a pool is given
     * (realistic joint age/sex/size), otherwise a template draw (independent ages,
     * random sex) capped at the zone's remaining persons.
     */
    private List<PopulationSample.Member> drawHousehold(PopulationSample kitSample, int remaining) {
        if (kitSample != null) {
            List<PopulationSample.Member> sampled = kitSample.randomHousehold(random);
            return sampled.size() <= remaining
                    ? sampled
                    : new ArrayList<>(sampled.subList(0, remaining));
        }
        int size = Math.min(drawHouseholdSize(), remaining);
        List<PopulationSample.Member> members = new ArrayList<>(size);
        for (int member = 0; member < size; member++) {
            String gender = random.nextBoolean() ? "MALE" : "FEMALE";
            members.add(new PopulationSample.Member(drawAge(member, size), gender));
        }
        return members;
    }

    private void writePerson(Writer w, PersonRef person, int memberIndex, int personalIncome,
                             String gender) throws IOException {
        boolean hasLicense = person.age() >= 18 && random.nextDouble() < 0.85;
        boolean hasBike = random.nextDouble() < 0.75;
        boolean isMobile = needsWorkZone(person.employment()) || needsEduZone(person.employment());
        boolean ticket = isMobile ? random.nextDouble() < 0.25 : random.nextDouble() < 0.10;
        // chargingInfluencesDestinationChoice ist ein Enum (NEVER), membership erwartet "{}"
        // (validiert gegen mobiTopp-Tutorial, siehe docs/05-validierung.md)
        w.write(person.id() + ";" + (memberIndex + 1) + ";" + person.householdId() + ";" + person.age() + ";"
                + person.employment() + ";" + gender + ";0;" + personalIncome + ";"
                + hasBike + ";" + ticket + ";" + hasLicense + ";1.0;NEVER;{}\n");
    }

    /**
     * Writes fixed destinations twice: the standard file uses only activity types the
     * mobiTopp-tutorial project defines; the -logitopp variant additionally carries
     * PICK_UP_PARCEL rows, which only the legacy logiTopp activity set knows.
     */
    private void writeFixedDestinations(Writer w, Writer logitoppVariant, PersonRef person, Zone home)
            throws IOException {
        if (person.workZoneIndex() >= 0) {
            Zone work = zones.get(person.workZoneIndex());
            String row = person.id() + ";WORK;" + CsvArtifacts.location(work.centroidLon, work.centroidLat)
                    + ";" + work.id + "\n";
            w.write(row);
            logitoppVariant.write(row);
        }
        if (person.eduZoneIndex() >= 0) {
            Zone edu = zones.get(person.eduZoneIndex());
            String row = person.id() + ";" + educationType(person.age()) + ";"
                    + CsvArtifacts.location(edu.centroidLon, edu.centroidLat) + ";" + edu.id + "\n";
            w.write(row);
            logitoppVariant.write(row);
        }
        // logiTopp (legacy) resolves PICK_UP_PARCEL via a fixed destination, by default the home zone
        logitoppVariant.write(person.id() + ";PICK_UP_PARCEL;"
                + CsvArtifacts.location(home.centroidLon, home.centroidLat) + ";" + home.id + "\n");
    }

    static String educationType(int age) {
        return age < 11 ? "EDUCATION_PRIMARY" : age < 19 ? "EDUCATION_SECONDARY" : "EDUCATION_TERTIARY";
    }

    private int drawHouseholdSize() {
        double roll = random.nextDouble();
        double cumulative = 0;
        for (int size = 0; size < HOUSEHOLD_SIZE_SHARES.length; size++) {
            cumulative += HOUSEHOLD_SIZE_SHARES[size];
            if (roll < cumulative) {
                return size + 1;
            }
        }
        return HOUSEHOLD_SIZE_SHARES.length;
    }

    private int drawAge(int memberIndex, int householdSize) {
        if (householdSize >= 3 && memberIndex >= 2) {
            return random.nextInt(18); // children in family households
        }
        double roll = random.nextDouble();
        if (roll < 0.14) return 18 + random.nextInt(12);
        if (roll < 0.40) return 30 + random.nextInt(20);
        if (roll < 0.61) return 50 + random.nextInt(15);
        if (roll < 0.83) return 65 + random.nextInt(25);
        return 18 + random.nextInt(60);
    }

    private String drawEmployment(int age) {
        if (age < 6) return "INFANT";
        if (age < 18) return "EDUCATION";
        if (age < 26) {
            return random.nextDouble() < 0.5 ? "STUDENT" : "FULLTIME";
        }
        if (age < 65) {
            double roll = random.nextDouble();
            if (roll < 0.58) return "FULLTIME";
            if (roll < 0.75) return "PARTTIME";
            if (roll < 0.80) return "UNEMPLOYED";
            return "HOMEKEEPER";
        }
        return "RETIRED";
    }

    private boolean needsWorkZone(String employment) {
        return employment.equals("FULLTIME") || employment.equals("PARTTIME");
    }

    private boolean needsEduZone(String employment) {
        return employment.equals("EDUCATION") || employment.equals("STUDENT");
    }

    private int drawCarCount(double carsPerHousehold, List<PersonRef> members) {
        boolean hasAdult = members.stream().anyMatch(p -> p.age() >= 18);
        if (!hasAdult) {
            return 0;
        }
        int base = (int) Math.floor(carsPerHousehold);
        int count = base + (random.nextDouble() < carsPerHousehold - base ? 1 : 0);
        return Math.min(count, 3);
    }

    /** Gravity-model destination choice: attractivity / (1 + distance_km^2). */
    private int sampleWorkZone(int homeIndex, long personId) {
        if (externalWorkShare != null && commuterMapping != null
                && commuterMapping.hasExternalDistribution(homeIndex)) {
            Random decisionRandom = destinationRandom(personId, 0x574f524b5f4d4152L);
            if (decisionRandom.nextDouble() < externalWorkShare) {
                int observed = commuterMapping.sampleExternalWorkZone(homeIndex,
                        destinationRandom(personId, 0x574f524b5f4f4454L));
                if (observed >= 0) {
                    return observed;
                }
            } else {
                return sampleGravityZone(homeIndex, "Work",
                        destinationRandom(personId, 0x574f524b5f494e54L), false);
            }
        }
        Random destinationRandom = destinationRandom(personId, 0x574f524bL);
        if (commuterMapping != null && commuterMapping.hasFullDistribution(homeIndex)) {
            int observed = commuterMapping.sampleWorkZone(homeIndex, destinationRandom);
            if (observed >= 0) {
                return observed;
            }
        }
        int gravity = sampleGravityZone(homeIndex, "Work", destinationRandom);
        if (commuterMapping != null && zones.get(gravity).external
                && commuterMapping.hasExternalDistribution(homeIndex)) {
            int observed = commuterMapping.sampleExternalWorkZone(homeIndex, destinationRandom);
            if (observed >= 0) {
                return observed;
            }
        }
        return gravity;
    }

    private int sampleGravityZone(int homeIndex, String category, Random destinationRandom) {
        return sampleGravityZone(homeIndex, category, destinationRandom, null);
    }

    private int sampleGravityZone(int homeIndex, String category, Random destinationRandom,
                                  Boolean externalOnly) {
        return sampleCumulative(gravityCumulative(homeIndex, category, externalOnly),
                destinationRandom);
    }

    private double[] gravityCumulative(int homeIndex, String category) {
        return gravityCumulative(homeIndex, category, null);
    }

    private double[] gravityCumulative(int homeIndex, String category, Boolean externalOnly) {
        String scope = externalOnly == null ? "all" : externalOnly ? "external" : "internal";
        return gravityCache.computeIfAbsent(homeIndex + ":" + category + ":" + scope, key -> {
            Zone home = zones.get(homeIndex);
            double[] weights = new double[zones.size()];
            double sum = 0;
            for (Zone candidate : zones) {
                if (externalOnly != null && candidate.external != externalOnly) {
                    weights[candidate.index] = sum;
                    continue;
                }
                double distance = GridZoning.distanceKm(home, candidate);
                double attractivity = candidate.attractivities.getOrDefault(category, 0.0) + 1;
                sum += attractivity / (1 + distance * distance);
                weights[candidate.index] = sum;
            }
            return weights;
        });
    }

    private int sampleEducationZone(int homeIndex, int age, long personId) {
        String category = age < 11 ? "Education_Primary"
                : age < 19 ? "Education_Secondary" : "Education_Tertiary";
        return sampleGravityZone(homeIndex, category,
                destinationRandom(personId, 0x454455L));
    }

    private int sampleCumulative(double[] cumulative, Random destinationRandom) {
        double roll = destinationRandom.nextDouble() * cumulative[cumulative.length - 1];
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }

    /**
     * Stable per-person substream: destination alternatives may consume more
     * draws without perturbing household composition, later persons or activity
     * generation. This is required for controlled A/B destination experiments.
     */
    private Random destinationRandom(long personId, long purposeSalt) {
        long value = destinationSeed ^ purposeSalt ^ (personId * 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return new Random(value ^ (value >>> 31));
    }
}
