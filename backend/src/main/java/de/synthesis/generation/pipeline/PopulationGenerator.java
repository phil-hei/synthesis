package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    public record Result(List<PersonRef> persons, int householdCount, int carCount) {
    }

    private static final double[] HOUSEHOLD_SIZE_SHARES = {0.42, 0.33, 0.12, 0.09, 0.04};
    private static final int YEAR = 2026;

    private final List<Zone> zones;
    private final Random random;
    private final Map<String, double[]> gravityCache = new HashMap<>();

    public PopulationGenerator(List<Zone> zones, Random random) {
        this.zones = zones;
        this.random = random;
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
        List<PersonRef> persons = new ArrayList<>();
        long householdId = 1;
        long personId = 1;
        long carId = 1;
        int carCount = 0;

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

            for (Zone zone : zones) {
                int remaining = (int) Math.round(zone.population * scenario.population.samplingFraction);
                while (remaining > 0) {
                    List<PopulationSample.Member> composition = drawHousehold(kitSample, remaining);
                    int size = composition.size();
                    int economicalStatus = 1 + random.nextInt(5);
                    int income = 900 + economicalStatus * 800 + random.nextInt(600);
                    double lat = zone.minLat + random.nextDouble() * (zone.maxLat - zone.minLat);
                    double lon = zone.minLon + random.nextDouble() * (zone.maxLon - zone.minLon);
                    String homeLocation = CsvArtifacts.location(lon, lat);

                    households.write(householdId + ";" + householdId + ";" + YEAR + ";" + zone.id + ";"
                            + homeLocation + ";0;" + size + ";" + income + ";" + economicalStatus + "\n");

                    List<PersonRef> members = new ArrayList<>(size);
                    for (int member = 0; member < size; member++) {
                        PopulationSample.Member m = composition.get(member);
                        String employment = drawEmployment(m.age());
                        // Jede Person bekommt ein festes Arbeits- und Bildungsziel: actiTopp
                        // entscheidet selbst, wer arbeitet/lernt; fehlt das Fixziel, bricht die
                        // Legacy-Zielwahl mit "No destination available for WORK/EDUCATION" ab.
                        // Wird nur konsultiert, wenn die jeweilige Aktivität tatsächlich auftritt.
                        int workZone = sampleGravityZone(zone.index, "Work");
                        int eduZone = sampleEducationZone(zone.index, m.age());
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
            }
        }
        return new Result(persons, (int) (householdId - 1), carCount);
    }

    /**
     * One household composition: a KIT-sampled household when a pool is given
     * (realistic joint age/sex/size), otherwise a template draw (independent ages,
     * random sex) capped at the zone's remaining persons.
     */
    private List<PopulationSample.Member> drawHousehold(PopulationSample kitSample, int remaining) {
        if (kitSample != null) {
            return kitSample.randomHousehold(random);
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
    private int sampleGravityZone(int homeIndex, String category) {
        double[] cumulative = gravityCache.computeIfAbsent(homeIndex + ":" + category, key -> {
            Zone home = zones.get(homeIndex);
            double[] weights = new double[zones.size()];
            double sum = 0;
            for (Zone candidate : zones) {
                double distance = GridZoning.distanceKm(home, candidate);
                double attractivity = candidate.attractivities.getOrDefault(category, 0.0) + 1;
                sum += attractivity / (1 + distance * distance);
                weights[candidate.index] = sum;
            }
            return weights;
        });
        return sampleCumulative(cumulative);
    }

    private int sampleEducationZone(int homeIndex, int age) {
        String category = age < 11 ? "Education_Primary"
                : age < 19 ? "Education_Secondary" : "Education_Tertiary";
        return sampleGravityZone(homeIndex, category);
    }

    private int sampleCumulative(double[] cumulative) {
        double roll = random.nextDouble() * cumulative[cumulative.length - 1];
        for (int i = 0; i < cumulative.length; i++) {
            if (roll <= cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }
}
