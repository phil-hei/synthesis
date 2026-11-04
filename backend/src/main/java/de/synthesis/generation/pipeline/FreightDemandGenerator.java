package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Explicit, already spatially prepared daily vehicle OD demand. No hidden quota or empty-trip uplift. */
public final class FreightDemandGenerator {
    public static final String CONTRACT = "synthesis-freight-demand-v1";
    public static final int MAX_TRIPS = 200_000;
    public static final String HEADER = "id;vehicleClass;movement;fromLon;fromLat;toLon;toLat;trips;beginHour;endHour;source;referenceDate";

    public static final String DIRECTED_HEADER = HEADER + ";fromEdge;toEdge";

    public record Relation(String id, String vehicleClass, String movement,
                           double fromLon, double fromLat, double toLon, double toLat,
                           int trips, double beginHour, double endHour, String source, String referenceDate,
                           String fromEdge, String toEdge) {
        public Relation {
            fromEdge = fromEdge == null || fromEdge.isBlank() ? null : fromEdge.trim();
            toEdge = toEdge == null || toEdge.isBlank() ? null : toEdge.trim();
        }
        public Relation(String id, String vehicleClass, String movement,
                        double fromLon, double fromLat, double toLon, double toLat,
                        int trips, double beginHour, double endHour, String source, String referenceDate) {
            this(id, vehicleClass, movement, fromLon, fromLat, toLon, toLat,
                    trips, beginHour, endHour, source, referenceDate, null, null);
        }
    }
    public record Trip(String id, String relationId, String vehicleClass, String movement,
                       int departSec, double fromLon, double fromLat, double toLon, double toLat,
                       String fromEdge, String toEdge) { }

    public static List<Relation> parseCsv(byte[] bytes) {
        if (bytes.length > 5_000_000) throw new IllegalArgumentException("Güterverkehrs-CSV darf höchstens 5 MB groß sein");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").split("\\R");
        boolean directed = lines.length > 0 && DIRECTED_HEADER.equals(lines[0].trim());
        if (lines.length == 0 || (!directed && !HEADER.equals(lines[0].trim())))
            throw new IllegalArgumentException("CSV-Kopf erwartet: " + HEADER);
        List<Relation> relations = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] c = lines[i].split(";", -1);
            if (c.length != (directed ? 14 : 12)) throw new IllegalArgumentException("CSV-Zeile " + (i + 1) + ": " + (directed ? 14 : 12) + " Spalten erforderlich");
            for (int j = 0; j < c.length; j++) c[j] = c[j].trim();
            try {
                relations.add(new Relation(c[0], c[1], c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                        Double.parseDouble(c[5]), Double.parseDouble(c[6]), Integer.parseInt(c[7]),
                        Double.parseDouble(c[8]), Double.parseDouble(c[9]), c[10], c[11],
                        directed ? c[12] : null, directed ? c[13] : null));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CSV-Zeile " + (i + 1) + ": ungültige Zahl");
            }
        }
        validateRelations(relations, null);
        return relations.stream().sorted(Comparator.comparing(Relation::id)).toList();
    }

    public static void validateDemand(Scenario scenario) {
        if (scenario.freight == null) throw new IllegalArgumentException("Güterverkehrsparameter fehlen");
        validateRelations(scenario.freight.relations, scenario.freight.enabled ? scenario.region : null);
        if (!scenario.freight.enabled) return;
        if (scenario.population.samplingFraction != 1.0)
            throw new IllegalArgumentException("Schwerer Güterverkehr benötigt 100 % Personennachfrage (keine gemischten Stichproben)");
        if (scenario.freight.day < 0 || scenario.freight.day > 6)
            throw new IllegalArgumentException("Güterverkehrstag muss 0 (Montag) bis 6 (Sonntag) sein");
    }
    /** Target-specific restrictions are checked only by the SUMO adapter. */
    public static void validate(Scenario scenario) {
        validateDemand(scenario);
        SevasFreightPermissions.validate(scenario.freight.sevasSnapshot);
        if (scenario.freight.sevasEnabled && (!scenario.freight.enabled || !scenario.targets().contains("sumo")))
            throw new IllegalArgumentException("SEVAS benötigt aktiven schweren Güterverkehr und SUMO");
        if (scenario.freight.profileChecksEnabled) {
            if (!scenario.freight.enabled || !scenario.targets().contains("sumo"))
                throw new IllegalArgumentException("LKW-Profilprüfung benötigt aktiven Güterverkehr und SUMO");
            if (scenario.vehicleConfiguration != null && scenario.vehicleConfiguration.appliesToSumo()
                    && scenario.vehicleConfiguration.trafficAssignments().stream().anyMatch(a -> Set.of("heavyTruck", "articulatedTruck").contains(a.role())))
                throw new IllegalArgumentException("LKW-Profilprüfung verwendet Standardprofile; eigene schwere SUMO-Typzuordnungen entfernen oder Profilprüfung deaktivieren");
        }
    }

    public static void validateRelations(List<Relation> relations, Scenario.Region region) {
        if (relations == null || relations.size() > 10_000) throw new IllegalArgumentException("Höchstens 10.000 Güterverkehrsbeziehungen zulässig");
        Set<String> ids = new HashSet<>();
        long count = 0;
        for (Relation r : relations) {
            if (r == null || r.id() == null || !r.id().matches("[A-Za-z0-9_-]{1,80}") || !ids.add(r.id()))
                throw new IllegalArgumentException("Güterverkehr: eindeutige Relations-ID erforderlich");
            if (!Set.of("truck", "trailer").contains(Objects.toString(r.vehicleClass(), "")))
                throw new IllegalArgumentException(r.id() + ": vehicleClass muss truck oder trailer sein");
            if (!Set.of("internal", "inbound", "outbound", "through").contains(Objects.toString(r.movement(), "")))
                throw new IllegalArgumentException(r.id() + ": unbekannte Außenbeziehung");
            if (!coordinate(r.fromLon(), r.fromLat()) || !coordinate(r.toLon(), r.toLat()))
                throw new IllegalArgumentException(r.id() + ": ungültige Koordinaten");
            if (region != null && (!inside(region, r.fromLon(), r.fromLat()) || !inside(region, r.toLon(), r.toLat())))
                throw new IllegalArgumentException(r.id() + ": Start/Ziel muss im Szenarionetz liegen; Außenverkehr an Netzrandzugänge anbinden");
            if ((r.fromEdge() == null) != (r.toEdge() == null))
                throw new IllegalArgumentException(r.id() + ": fromEdge und toEdge gemeinsam angeben");
            for (String edge : List.of(Objects.toString(r.fromEdge(), ""), Objects.toString(r.toEdge(), "")))
                if (!edge.isEmpty() && !edge.matches("[A-Za-z0-9_#.:+-]{1,160}"))
                    throw new IllegalArgumentException(r.id() + ": ungültige SUMO-Kanten-ID");
            if (r.fromEdge() != null && r.fromEdge().equals(r.toEdge()))
                throw new IllegalArgumentException(r.id() + ": gerichtete Start-/Zielkanten müssen verschieden sein");
            if (r.fromLon() == r.toLon() && r.fromLat() == r.toLat())
                throw new IllegalArgumentException(r.id() + ": Start und Ziel müssen verschieden sein");
            if (r.trips() <= 0 || !Double.isFinite(r.beginHour()) || !Double.isFinite(r.endHour())
                    || r.beginHour() < 0 || r.endHour() > 24 || r.beginHour() >= r.endHour())
                throw new IllegalArgumentException(r.id() + ": positive Fahrten und gültiges Stundenfenster [0,24] erforderlich");
            if (r.source() == null || r.source().isBlank() || r.source().length() > 500
                    || r.source().contains(";") || r.source().contains("\n") || r.source().contains("\r"))
                throw new IllegalArgumentException(r.id() + ": Quellenangabe erforderlich");
            try { java.time.LocalDate.parse(r.referenceDate()); }
            catch (Exception e) { throw new IllegalArgumentException(r.id() + ": referenceDate muss JJJJ-MM-TT sein"); }
            count += r.trips();
        }
        if (count > MAX_TRIPS) throw new IllegalArgumentException("Höchstens " + MAX_TRIPS + " schwere Fahrten pro Tag zulässig");
    }

    private static boolean coordinate(double lon, double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90;
    }
    private static boolean inside(Scenario.Region r, double lon, double lat) {
        return lon >= r.minLon && lon <= r.maxLon && lat >= r.minLat && lat <= r.maxLat;
    }

    public static List<Trip> generate(Scenario scenario) {
        validateDemand(scenario);
        if (!scenario.freight.enabled) return List.of();
        if (scenario.freight.relations.isEmpty()) throw new IllegalArgumentException("Bitte Güterverkehrsbeziehungen hochladen");
        List<Trip> trips = new ArrayList<>();
        for (Relation r : scenario.freight.relations.stream().sorted(Comparator.comparing(Relation::id)).toList()) {
            // Independent per-relation stream: preserves all pre-existing person/population draws.
            Random random = new Random(scenario.population.seed ^ 0x66726569676874L ^ r.id().hashCode());
            for (int n = 0; n < r.trips(); n++) {
                double h = r.beginHour() + (n + random.nextDouble()) / r.trips() * (r.endHour() - r.beginHour());
                trips.add(new Trip("freight_" + r.id() + "_" + n, r.id(), r.vehicleClass(), r.movement(),
                        (int) Math.floor(h * 3600), r.fromLon(), r.fromLat(), r.toLon(), r.toLat(), r.fromEdge(), r.toEdge()));
            }
        }
        return trips.stream().sorted(Comparator.comparingInt(Trip::departSec).thenComparing(Trip::id)).toList();
    }

    public static void write(Path artifacts, Scenario scenario, ObjectMapper mapper) throws IOException {
        write(artifacts, scenario, mapper, generate(scenario));
    }
    public static void write(Path artifacts, Scenario scenario, ObjectMapper mapper, List<Trip> trips) throws IOException {
        Path dir = artifacts.resolve("freight");
        Files.createDirectories(dir);
        String quality=sourceQuality(scenario);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("sources.json").toFile(), Map.of(
                "contract", CONTRACT, "enabled", scenario.freight.enabled, "day", scenario.freight.day,
                "quality", quality, "relations", scenario.freight.relations,
                "inputSha256", hash(scenario.freight.relations, mapper),
                "departureProfile", "stratified-uniform-within-input-window",
                "countsIncludeEmptyTrips", true));
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(), Map.of(
                "contract", CONTRACT, "enabled", scenario.freight.enabled, "trips", trips.size(),
                "byVehicleClass", counts(trips, true), "byMovement", counts(trips, false),
                "sourceQuality", quality, "fleetStateSimulated", false));
        try (var w = Files.newBufferedWriter(dir.resolve("trips.csv"))) {
            w.write("id;relationId;trafficCategory;vehicleClass;movement;departSec;fromLon;fromLat;toLon;toLat;fromEdge;toEdge\n");
            for (Trip t : trips) w.write(t.id() + ";" + t.relationId() + ";logistics;" + t.vehicleClass() + ";"
                    + t.movement() + ";" + t.departSec() + ";" + t.fromLon() + ";" + t.fromLat() + ";" + t.toLon() + ";" + t.toLat() + ";" + Objects.toString(t.fromEdge(), "") + ";" + Objects.toString(t.toEdge(), "") + "\n");
        }
    }
    /** Explicit demonstration parameters are assumptions, never unverified observations. */
    public static String sourceQuality(Scenario scenario) {
        return scenario.dataPlan!=null&&scenario.dataPlan.enabled&&scenario.dataPlan.policy("freight.vehicleOd").source.equals("model")
                ?"assumed":"user-provided-unverified";
    }
    private static Map<String, Integer> counts(List<Trip> trips, boolean vehicleClass) {
        Map<String, Integer> result = new TreeMap<>();
        for (Trip t : trips) result.merge(vehicleClass ? t.vehicleClass() : t.movement(), 1, Integer::sum);
        return result;
    }
    private static String hash(List<Relation> relations, ObjectMapper mapper) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                mapper.writeValueAsBytes(relations.stream().sorted(Comparator.comparing(Relation::id)).toList()))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
