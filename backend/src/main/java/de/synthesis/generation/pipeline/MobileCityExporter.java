package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports the scenario as MobileCity (Takomat) study-area config tables. MobileCity
 * is a district-level, aggregate strategic model — it does not import individual
 * trips/tours, but per-district population (by age group), attractivity (per trip
 * purpose) and district geography. This exporter maps our zones onto that model:
 * each zone becomes a district, internal zones are study-area districts
 * (districtType 1) and external zones the surrounding (0).
 *
 * <p>Output under {@code artifacts/mobilecity/}: REGdistricts.csv, REGattractivity.csv,
 * POPinhabitants.csv — in the column structure of the MobileCity Config Tool import
 * tables (see Network Pipeline / Includes/StudyAreas). District ids are synthetic
 * ({@code SYN-NNN}); for a real study area they are replaced by NUTS3-coded ids.
 */
public class MobileCityExporter {

    private static final int BASE_YEAR = 2025;

    private final List<Zone> zones;

    public MobileCityExporter(List<Zone> zones) {
        this.zones = zones;
    }

    private static String districtId(Zone zone) {
        return String.format("SYN-%03d", zone.index + 1);
    }

    /** @return number of exported files */
    public int export(Path artifactsDir) throws IOException {
        Path dir = artifactsDir.resolve("mobilecity");
        Files.createDirectories(dir);
        long[][] ageByZone = countAgeGroups(artifactsDir.resolve("population"));
        double[][] rates = countTripRates(artifactsDir.resolve("population"));

        writeStudyAreaConfig(dir.resolve("StudyAreaConfig.csv"));
        writeDistricts(dir.resolve("REGdistricts.csv"));
        writeAttractivity(dir.resolve("REGattractivity.csv"));
        writeInhabitants(dir.resolve("POPinhabitants.csv"), ageByZone);
        writeTrips(dir.resolve("POPtrips.csv"), rates);
        // District-Geometrien als Shapefile (WGS84, Attribut districtID) – laut Takomat
        // (2026-07-03) das erwartete Importformat; IDs müssen zu REGdistricts passen.
        ShapefileWriter.writeRectangles(dir.resolve("districts"), zones, "districtID",
                MobileCityExporter::districtId);
        writeSummary(dir.resolve("summary.json"), ageByZone, rates);
        return 9; // 5 Tabellen + districts.shp/.shx/.dbf/.prj
    }

    /** Compact summary for the frontend MobileCity panel (counts + KPIs). */
    private void writeSummary(Path file, long[][] ageByZone, double[][] rates) throws IOException {
        long studyArea = zones.stream().filter(z -> !z.external).count();
        long[] inhabitants = new long[3];
        for (Zone zone : zones) {
            if (!zone.external) {
                for (int g = 0; g < 3; g++) {
                    inhabitants[g] += ageByZone[zone.index][g];
                }
            }
        }
        long total = inhabitants[0] + inhabitants[1] + inhabitants[2];
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"tables\": 5,\n");
        json.append("  \"districts\": {\"total\": ").append(zones.size())
                .append(", \"studyArea\": ").append(studyArea)
                .append(", \"surrounding\": ").append(zones.size() - studyArea).append("},\n");
        json.append("  \"inhabitants\": {\"children\": ").append(inhabitants[0])
                .append(", \"adults\": ").append(inhabitants[1])
                .append(", \"seniors\": ").append(inhabitants[2])
                .append(", \"total\": ").append(total).append("},\n");
        json.append("  \"tripsPerDay\": {\"children\": ").append(round2(groupSum(rates, 0)))
                .append(", \"adults\": ").append(round2(groupSum(rates, 1)))
                .append(", \"seniors\": ").append(round2(groupSum(rates, 2))).append("}\n");
        json.append("}\n");
        Files.writeString(file, json.toString());
    }

    private static double groupSum(double[][] rates, int group) {
        double sum = 0;
        for (double rate : rates[group]) {
            sum += rate;
        }
        return sum;
    }

    // --- StudyAreaConfig -------------------------------------------------------

    private static final int END_YEAR = 2050;

    private void writeStudyAreaConfig(Path file) throws IOException {
        long internal = zones.stream().filter(z -> !z.external).count();
        long surrounding = zones.size() - internal;
        double centerLat = zones.stream().filter(z -> !z.external)
                .mapToDouble(z -> z.centroidLat).average().orElse(0);
        double centerLon = zones.stream().filter(z -> !z.external)
                .mapToDouble(z -> z.centroidLon).average().orElse(0);

        List<String> rows = new ArrayList<>();
        rows.add("referenceYear," + BASE_YEAR);
        rows.add("earliestDataYear," + BASE_YEAR);
        rows.add("endYear," + END_YEAR);
        rows.add("studyAreaDistrictCount," + internal);
        rows.add("surroundingDistrictCount," + surrounding);
        rows.add("centerLatitude," + centerLat);
        rows.add("centerLongitude," + centerLon);
        rows.add("centerZoom,12");
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "StudyAreaConfig");
            table(w, "studyAreaParametersList", "parameterName,Value", rows);
        }
    }

    private static final int FIELDS = 8;                        // Spaltenzahl der Titelzeile
    private static final String BLANK = ",".repeat(FIELDS - 1);  // emptyLine = Tabellentrenner
    private static final String[] PURPOSES = {"Education", "Work", "Shopping", "Leisure"};
    private static final String[] AGE_GROUPS = {"Children", "Adults", "Seniors"};

    // --- REGdistricts ----------------------------------------------------------

    private void writeDistricts(Path file) throws IOException {
        List<String> rows = new ArrayList<>();
        for (Zone zone : zones) {
            rows.add(districtId(zone) + "," + csv(zone.name) + "," + (zone.external ? 0 : 1));
        }
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "REGdistricts");
            table(w, "districts", "districtID,name,districtType", rows);
        }
    }

    // --- REGattractivity -------------------------------------------------------

    private void writeAttractivity(Path file) throws IOException {
        // je Purpose auf 0..100 normieren (attraktivster District = 100)
        double[] max = new double[4];
        for (Zone zone : zones) {
            double[] raw = attractivity(zone);
            for (int i = 0; i < 4; i++) {
                max[i] = Math.max(max[i], raw[i]);
            }
        }
        List<String> rows = new ArrayList<>();
        for (Zone zone : zones) {
            double[] raw = attractivity(zone);
            StringBuilder row = new StringBuilder(districtId(zone));
            for (int i = 0; i < 4; i++) {
                int score = max[i] > 0 ? (int) Math.round(100 * raw[i] / max[i]) : 0;
                row.append(',').append(score);
            }
            rows.add(row.toString());
        }
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "REGattractivity");
            table(w, "attractivity",
                    "districtID,TripPurpose.Education,TripPurpose.Work,TripPurpose.Shopping,TripPurpose.Leisure",
                    rows);
        }
    }

    private static double[] attractivity(Zone zone) {
        return new double[]{
                sum(zone, "Education_Primary", "Education_Secondary", "Education_Tertiary"),
                sum(zone, "Work"),
                sum(zone, "Shopping_Daily", "Shopping_Other"),
                sum(zone, "Leisure_Indoor", "Leisure_Outdoor"),
        };
    }

    // --- POPinhabitants --------------------------------------------------------

    private void writeInhabitants(Path file, long[][] ageByZone) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "POPinhabitants");
            for (int g = 0; g < AGE_GROUPS.length; g++) {
                List<String> rows = new ArrayList<>();
                for (Zone zone : zones) {
                    rows.add(districtId(zone) + "," + ageByZone[zone.index][g]);
                }
                table(w, "population[AgeGroups." + AGE_GROUPS[g] + "]", "districtID," + BASE_YEAR, rows);
            }
        }
    }

    // --- POPtrips --------------------------------------------------------------

    private void writeTrips(Path file, double[][] rates) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "POPtrips");
            for (int g = 0; g < AGE_GROUPS.length; g++) {
                List<String> rows = new ArrayList<>();
                for (int p = 0; p < PURPOSES.length; p++) {
                    rows.add(PURPOSES[p] + "," + round2(rates[g][p]));
                }
                table(w, "averageDailyTrips[AgeGroups." + AGE_GROUPS[g] + "]", "tripPurpose," + BASE_YEAR, rows);
            }
        }
    }

    // --- MobileCity-Tabellenformat ---------------------------------------------

    /**
     * First line of the file: the table title padded to {@link #FIELDS} columns.
     * The Config Tool's CSV reader (EpigeneCSVTableBuilder) derives the delimiter
     * and the table-separator "empty line" — exactly (FIELDS-1) commas — from this
     * first line, so every separator we emit must have that width (truly empty lines
     * are ignored, not treated as separators).
     */
    private void fileTitle(Writer w, String name) throws IOException {
        w.write(name + ",".repeat(FIELDS - 1) + "\n");
    }

    /**
     * One named sub-table: separator line (closes the previous table), the
     * table-name line, the column-header line and the data rows — matching the
     * reader's columnHeaderIndex=1 (name row, then header row, then data).
     */
    private void table(Writer w, String name, String header, List<String> rows) throws IOException {
        w.write(BLANK + "\n");
        w.write(name + "\n");
        w.write(header + "\n");
        for (String row : rows) {
            w.write(row + "\n");
        }
    }

    /**
     * Average daily trips per person by age group and purpose. Each activity
     * transition is one trip: a trip to an out-of-home activity takes that
     * activity's purpose, a home-end trip keeps the purpose of the activity it
     * returns from (standard trip-purpose convention) — so the per-purpose totals
     * add up to the full daily trip count, not just the out-of-home activities.
     */
    private double[][] countTripRates(Path populationDir) throws IOException {
        Map<Long, Integer> groupOf = new HashMap<>();
        long[] groupSize = new long[3];
        forEachRow(populationDir.resolve("person.csv"), cells -> {
            int age = Integer.parseInt(cells[3]);
            int group = age < 18 ? 0 : age < 65 ? 1 : 2;
            groupOf.put(Long.parseLong(cells[0]), group);
            groupSize[group]++;
        });

        long[][] trips = new long[3][PURPOSES.length];
        long[] currentPerson = {-1};
        int[] previousPurpose = {-1}; // purpose of the person's previous activity (-1 = home/none)
        forEachRow(populationDir.resolve("activity.csv"), cells -> {
            long person = Long.parseLong(cells[0]);
            boolean home = "7".equals(cells[1]);
            int destination = purposeOf(cells[1]);
            if (person != currentPerson[0]) {
                currentPerson[0] = person; // first activity of this person: no trip yet
            } else {
                Integer group = groupOf.get(person);
                int tripPurpose = home ? previousPurpose[0] : destination;
                if (group != null && tripPurpose >= 0) {
                    trips[group][tripPurpose]++;
                }
            }
            previousPurpose[0] = home ? -1 : destination;
        });

        double[][] rates = new double[3][PURPOSES.length];
        for (int g = 0; g < 3; g++) {
            for (int p = 0; p < PURPOSES.length; p++) {
                rates[g][p] = groupSize[g] > 0 ? trips[g][p] / (double) groupSize[g] / 7.0 : 0;
            }
        }
        return rates;
    }

    /** Maps a LegacyActivityType code to a MobileCity trip purpose index, or -1 (home/none). */
    private static int purposeOf(String activityCode) {
        return switch (activityCode) {
            case "31", "32", "33" -> 0; // Education
            case "1" -> 1;              // Work
            case "41", "42" -> 2;       // Shopping
            case "51", "52", "6", "12" -> 3; // Leisure (incl. service, private visit)
            default -> -1;              // 7=home, parcel codes etc.
        };
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Counts inhabitants per zone and age group (0=children<18, 1=adults 18-64, 2=seniors 65+). */
    private long[][] countAgeGroups(Path populationDir) throws IOException {
        long[][] counts = new long[zones.size()][3];
        Map<Long, Integer> householdToZone = new HashMap<>();
        forEachRow(populationDir.resolve("household.csv"), cells -> {
            // householdNumber;householdId;year;homeZone;...  homeZone = zone.id
            Zone zone = zoneById(Long.parseLong(cells[3]));
            if (zone != null) {
                householdToZone.put(Long.parseLong(cells[1]), zone.index);
            }
        });
        forEachRow(populationDir.resolve("person.csv"), cells -> {
            // personId;personNumber;householdId;age;...
            Integer zoneIndex = householdToZone.get(Long.parseLong(cells[2]));
            if (zoneIndex == null) {
                return;
            }
            int age = Integer.parseInt(cells[3]);
            int group = age < 18 ? 0 : age < 65 ? 1 : 2;
            counts[zoneIndex][group]++;
        });
        return counts;
    }

    // --- helpers ---------------------------------------------------------------

    private Zone zoneById(long id) {
        for (Zone zone : zones) {
            if (zone.id == id) {
                return zone;
            }
        }
        return null;
    }

    private static double sum(Zone zone, String... categories) {
        double total = 0;
        for (String category : categories) {
            total += zone.attractivities.getOrDefault(category, 0.0);
        }
        return total;
    }

    private static String csv(String value) {
        return value != null && value.contains(",") ? "\"" + value + "\"" : value;
    }

    private interface RowConsumer {
        void accept(String[] cells);
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
}
