package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * POPinhabitants.csv, POPtrips.csv and the evidenced part of TECCarFleet.csv — in the
 * column structure of the MobileCity Config Tool import tables (see Network Pipeline /
 * Includes/StudyAreas). District ids are synthetic ({@code SYN-NNN}) in contribution mode; template mode
 * aggregates onto the reference district polygons and retains their identifiers.
 */
public class MobileCityExporter {

    private final int referenceYear;
    private final de.synthesis.scenario.Scenario scenario;
    private final Path templateRoot;
    private final Map<String, Object> runSources;
    private final MobileCityGtfsAdapter.Feed transitFeed;
    private final MobileCitySurroundingSource.Result surrounding;

    private final List<Zone> zones;
    private MobileCityFleetSource.Result fleet;

    public MobileCityExporter(List<Zone> zones) {
        this(zones, new de.synthesis.scenario.Scenario(), null);
    }

    public MobileCityExporter(List<Zone> zones, de.synthesis.scenario.Scenario scenario, Path templateRoot) {
        this(zones, scenario, templateRoot, Map.of(), null);
    }

    public MobileCityExporter(List<Zone> zones, de.synthesis.scenario.Scenario scenario,
                              Path templateRoot, Map<String, Object> runSources) {
        this(zones, scenario, templateRoot, runSources, null);
    }

    public MobileCityExporter(List<Zone> zones, de.synthesis.scenario.Scenario scenario,
                              Path templateRoot, Map<String, Object> runSources,
                              MobileCityGtfsAdapter.Feed transitFeed) {
        this(zones, scenario, templateRoot, runSources, transitFeed, null);
    }

    public MobileCityExporter(List<Zone> zones, de.synthesis.scenario.Scenario scenario,
                              Path templateRoot, Map<String, Object> runSources,
                              MobileCityGtfsAdapter.Feed transitFeed,
                              MobileCitySurroundingSource.Result surrounding) {
        this.zones = zones;
        this.scenario = scenario;
        this.referenceYear = scenario.referenceYear;
        this.templateRoot = templateRoot;
        this.runSources = Collections.unmodifiableMap(new LinkedHashMap<>(runSources));
        this.transitFeed = transitFeed;
        this.surrounding = surrounding;
    }

    private static String districtId(Zone zone) {
        return String.format("SYN-%03d", zone.index + 1);
    }

    /** @return number of exported files */
    public int export(Path artifactsDir) throws IOException {
        Files.createDirectories(artifactsDir);
        Path dir = Files.createTempDirectory(artifactsDir, ".mobilecity-export-");
        try {
            int count = exportTo(artifactsDir, dir);
            Path output = artifactsDir.resolve("mobilecity");
            deleteGeneratedDirectory(output);
            Files.move(dir, output);
            return count;
        } finally {
            deleteGeneratedDirectory(dir);
        }
    }

    private static void deleteGeneratedDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private int exportTo(Path artifactsDir, Path dir) throws IOException {
        fleet = null;
        MobileCityPopulation population = new MobileCityPopulation(zones, artifactsDir.resolve("population"),
                scenario.population.samplingFraction, referenceYear);
        if (scenario.mobileCity.useRegionalFleet || scenario.mobileCity.electricShare != null) {
            Path cache = templateRoot == null ? artifactsDir.resolve(".mobilecity-source-cache") : templateRoot.resolve("fleet-cache");
            fleet = new MobileCityFleetSource().resolve(scenario, zones, cache, ignored -> {});
            MobileCityFleetSource.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("fleet-provenance.json").toFile(),fleet.provenance());
            if (fleet.boundarySnapshot() != null) {
                Files.write(dir.resolve("fleet-boundaries.geojson"), fleet.boundarySnapshot());
            }
        }
        if (scenario.mobileCity.regionalExport) {
            return new MobileCityRegionalExporter(zones,scenario,templateRoot,runSources,transitFeed,surrounding)
                    .export(artifactsDir,dir,population,fleet);
        }
        if (scenario.mobileCity.useTemplate) {
            return new MobileCityTemplateExporter(zones, scenario, templateRoot).export(dir, population, fleet);
        }
        long[][] ageByZone = population.ages;
        double[] fractions = new double[zones.size()]; java.util.Arrays.fill(fractions, 1);
        double[][] rates = population.tripRates(fractions);
        long[] carsByZone = population.cars;

        writeStudyAreaConfig(dir.resolve("StudyAreaConfig.csv"));
        writeDistricts(dir.resolve("REGdistricts.csv"));
        writeAttractivity(dir.resolve("REGattractivity.csv"));
        writeInhabitants(dir.resolve("POPinhabitants.csv"), ageByZone);
        writeTrips(dir.resolve("POPtrips.csv"), rates);
        writeCarFleet(dir.resolve("TECCarFleet.csv"), carsByZone);
        // District-Geometrien als Shapefile (WGS84, Attribut districtID) – laut Takomat
        // (2026-07-03) das erwartete Importformat; IDs müssen zu REGdistricts passen.
        ShapefileWriter.writeRectangles(dir.resolve("districts"), zones, "districtID",
                MobileCityExporter::districtId);
        writeSummary(dir.resolve("summary.json"), ageByZone, rates, carsByZone);
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                dir.resolve("provenance.json").toFile(), java.util.Map.of(
                        "mode", "contribution", "referenceYear", referenceYear,
                        "samplingFraction", scenario.population.samplingFraction,
                        "stockBasis", "Zone population; sampled age shares and car ownership expanded per zone",
                        "ageAndCarImputationZoneIds", population.imputedZones,
                        "attractivityPolicy", "Purpose scores normalized to 0..100 across exported zones; differs from TRAMIGO calibration",
                        "validation", "Partial contribution; networks and model parameters required for full import"));
        return 12 + (fleet == null ? 0 : 1); // 6 tables, 4 Shapefile components, summary, provenance
    }

    /** Compact summary for the frontend MobileCity panel (counts + KPIs). */
    private void writeSummary(Path file, long[][] ageByZone, double[][] rates,
                              long[] carsByZone) throws IOException {
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
        long cars = 0;
        for (Zone zone : zones) {
            if (!zone.external) {
                cars += carsByZone[zone.index];
            }
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"tables\": 6,\n");
        json.append("  \"mode\": \"contribution\", \"referenceYear\": ").append(referenceYear).append(",\n");
        json.append("  \"districts\": {\"total\": ").append(zones.size())
                .append(", \"studyArea\": ").append(studyArea)
                .append(", \"surrounding\": ").append(zones.size() - studyArea).append("},\n");
        json.append("  \"inhabitants\": {\"children\": ").append(inhabitants[0])
                .append(", \"adults\": ").append(inhabitants[1])
                .append(", \"seniors\": ").append(inhabitants[2])
                .append(", \"total\": ").append(total).append("},\n");
        json.append("  \"privateCars\": ").append(cars).append(",\n");
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
        rows.add("referenceYear," + referenceYear);
        rows.add("earliestDataYear," + referenceYear);
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
                table(w, "population[AgeGroups." + AGE_GROUPS[g] + "]", "districtID," + referenceYear, rows);
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
                table(w, "averageDailyTrips[AgeGroups." + AGE_GROUPS[g] + "]", "tripPurpose," + referenceYear, rows);
            }
        }
    }

    // --- TECCarFleet -----------------------------------------------------------

    /**
     * Private passenger-car stock by district for the scenario reference year.
     * Registration events remain absent. Optional observed/private BEV shares or
     * explicit assumptions are accompanied by fleet-provenance.json.
     */
    private void writeCarFleet(Path file, long[] carsByZone) throws IOException {
        List<String> rows = new ArrayList<>();
        for (Zone zone : zones) {
            rows.add(districtId(zone) + "," + carsByZone[zone.index]);
        }
        try (Writer w = Files.newBufferedWriter(file)) {
            fileTitle(w, "TECCarFleet");
            table(w, "stock.total", "districtID," + referenceYear, rows);
            if (fleet != null) {
                List<String> shares = new ArrayList<>();
                for (Zone zone : zones) {
                    var share = fleet.shares().get(zone.index);
                    if (share != null) shares.add(districtId(zone) + "," + MobileCityCsv.number(share.bev()));
                }
                if (!shares.isEmpty()) table(w, "stock.shareElectric", "districtID," + referenceYear, shares);
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

    private static double sum(Zone zone, String... categories) {
        double total = 0;
        for (String category : categories) {
            total += zone.attractivities.getOrDefault(category, 0.0);
        }
        return total;
    }

    private static String csv(String value) { return MobileCityCsv.quote(value); }
    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }
}
