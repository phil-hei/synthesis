package de.synthesis.generation.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Schreibt den nativen Nachfragevertrag und seine dünn besetzten 15-Minuten-Profile. */
public final class DemandArtifacts {

    public static final int TIME_BIN_MINUTES = 15;

    private DemandArtifacts() {
    }

    private record ZoneKey(
            int binStart, String zoneId, String mode, String modeCode) {
    }

    private static final class ZoneFlow {
        long departures;
        long arrivals;
    }

    private record OdKey(
            int binStart, String originZoneId, String destinationZoneId,
            String mode, String modeCode) {
    }

    @FunctionalInterface
    private interface WriterAction {
        void write(Writer writer) throws IOException;
    }

    public static void write(
            Path demandDir, TravelDemand demand, Path sourceCsv, ObjectMapper mapper)
            throws IOException {
        Files.createDirectories(demandDir);
        writeTrips(demandDir.resolve("person-trips.csv"), demand);
        writeLegs(demandDir.resolve("legs.csv"), demand);
        Aggregates aggregates = aggregate(demand);
        writeZoneFlows(demandDir.resolve("zone-flows.csv"), aggregates.zoneFlows());
        writeOdFlows(demandDir.resolve("od-flows.csv"), aggregates.odFlows());
        writeMetadata(demandDir.resolve("metadata.json"), demand, sourceCsv, mapper);
    }

    private record Aggregates(
            Map<ZoneKey, ZoneFlow> zoneFlows,
            Map<OdKey, Long> odFlows) {
    }

    private static Aggregates aggregate(TravelDemand demand) {
        Map<ZoneKey, ZoneFlow> zoneFlows = new HashMap<>();
        Map<OdKey, Long> odFlows = new HashMap<>();
        for (TravelDemand.PersonTrip trip : demand.trips()) {
            int departureBin = timeBin(trip.plannedDepartureMinute());
            int arrivalBin = timeBin(trip.plannedArrivalMinute());
            String mode = trip.mode().artifactName();
            ZoneKey origin =
                    new ZoneKey(departureBin, trip.originZoneId(), mode, trip.modeCode());
            ZoneKey destination =
                    new ZoneKey(arrivalBin, trip.destinationZoneId(), mode, trip.modeCode());
            zoneFlows.computeIfAbsent(origin, ignored -> new ZoneFlow()).departures++;
            zoneFlows.computeIfAbsent(destination, ignored -> new ZoneFlow()).arrivals++;
            odFlows.merge(new OdKey(
                    departureBin,
                    trip.originZoneId(),
                    trip.destinationZoneId(),
                    mode,
                    trip.modeCode()), 1L, Long::sum);
        }
        long odTrips = odFlows.values().stream().mapToLong(Long::longValue).sum();
        long departures = zoneFlows.values().stream().mapToLong(flow -> flow.departures).sum();
        long arrivals = zoneFlows.values().stream().mapToLong(flow -> flow.arrivals).sum();
        if (odTrips != demand.tripCount()
                || departures != demand.tripCount()
                || arrivals != demand.tripCount()) {
            throw new IllegalStateException("Nachfrage-Aggregation verletzt die Mengenbilanz");
        }
        return new Aggregates(zoneFlows, odFlows);
    }

    private static int timeBin(double minute) {
        return (int) (Math.floor(minute / TIME_BIN_MINUTES) * TIME_BIN_MINUTES);
    }

    private static void writeTrips(Path file, TravelDemand demand) throws IOException {
        atomicWrite(file, writer -> {
            writer.write("personOid;tripId;plannedDepartureMinute;plannedArrivalMinute;"
                    + "mode;modeCode;tripModeCode;originZoneId;destinationZoneId;"
                    + "fromLon;fromLat;toLon;toLat;distanceKm;legCount;trafficCategory\n");
            for (TravelDemand.PersonTrip trip : demand.trips()) {
                row(writer,
                        trip.personOid(),
                        trip.tripId(),
                        number(trip.plannedDepartureMinute()),
                        number(trip.plannedArrivalMinute()),
                        trip.mode().artifactName(),
                        trip.modeCode(),
                        trip.rawTripModeCode(),
                        trip.originZoneId(),
                        trip.destinationZoneId(),
                        number(trip.fromLon()),
                        number(trip.fromLat()),
                        number(trip.toLon()),
                        number(trip.toLat()),
                        number(trip.distanceKm()),
                        Integer.toString(trip.legs().size()),
                        "private");
            }
        });
    }

    private static void writeLegs(Path file, TravelDemand demand) throws IOException {
        atomicWrite(file, writer -> {
            writer.write("personOid;tripId;legId;legMode;legModeCode;originZoneId;"
                    + "destinationZoneId;distanceKm\n");
            for (TravelDemand.PersonTrip trip : demand.trips()) {
                for (TravelDemand.Leg leg : trip.legs()) {
                    row(writer,
                            trip.personOid(),
                            trip.tripId(),
                            Integer.toString(leg.legId()),
                            leg.mode().artifactName(),
                            leg.modeCode(),
                            leg.originZoneId(),
                            leg.destinationZoneId(),
                            number(leg.distanceKm()));
                }
            }
        });
    }

    private static void writeZoneFlows(Path file, Map<ZoneKey, ZoneFlow> flows)
            throws IOException {
        List<Map.Entry<ZoneKey, ZoneFlow>> rows = new ArrayList<>(flows.entrySet());
        rows.sort(Map.Entry.comparingByKey(
                Comparator.comparingInt(ZoneKey::binStart)
                        .thenComparing(ZoneKey::zoneId)
                        .thenComparing(ZoneKey::mode)
                        .thenComparing(ZoneKey::modeCode)));
        atomicWrite(file, writer -> {
            writer.write("timeBinStartMinute;day;minuteOfDay;zoneId;mode;modeCode;"
                    + "departures;arrivals\n");
            for (Map.Entry<ZoneKey, ZoneFlow> row : rows) {
                ZoneKey key = row.getKey();
                ZoneFlow flow = row.getValue();
                row(writer,
                        Integer.toString(key.binStart()),
                        Integer.toString(Math.floorDiv(key.binStart(), 1440)),
                        Integer.toString(Math.floorMod(key.binStart(), 1440)),
                        key.zoneId(),
                        key.mode(),
                        key.modeCode(),
                        Long.toString(flow.departures),
                        Long.toString(flow.arrivals));
            }
        });
    }

    private static void writeOdFlows(Path file, Map<OdKey, Long> flows) throws IOException {
        List<Map.Entry<OdKey, Long>> rows = new ArrayList<>(flows.entrySet());
        rows.sort(Map.Entry.comparingByKey(
                Comparator.comparingInt(OdKey::binStart)
                        .thenComparing(OdKey::originZoneId)
                        .thenComparing(OdKey::destinationZoneId)
                        .thenComparing(OdKey::mode)
                        .thenComparing(OdKey::modeCode)));
        atomicWrite(file, writer -> {
            writer.write("timeBinStartMinute;day;minuteOfDay;originZoneId;"
                    + "destinationZoneId;mode;modeCode;trips\n");
            for (Map.Entry<OdKey, Long> row : rows) {
                OdKey key = row.getKey();
                row(writer,
                        Integer.toString(key.binStart()),
                        Integer.toString(Math.floorDiv(key.binStart(), 1440)),
                        Integer.toString(Math.floorMod(key.binStart(), 1440)),
                        key.originZoneId(),
                        key.destinationZoneId(),
                        key.mode(),
                        key.modeCode(),
                        Long.toString(row.getValue()));
            }
        });
    }

    private static void writeMetadata(
            Path file, TravelDemand demand, Path sourceCsv, ObjectMapper mapper)
            throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("contract", TravelDemand.CONTRACT);
        root.put("demandType", "simulatedPersonTrips");
        root.put("source", "logiTopp demandsimulationResult.csv");
        root.put("sourceSha256", sha256(sourceCsv));
        root.put("timeBase", "minutes-since-monday-00:00");
        root.put("timeSemantics", "planned");
        root.put("horizonMinutes", TravelDemand.HORIZON_MINUTES);
        root.put("aggregationIntervalMinutes", TIME_BIN_MINUTES);
        root.put("zoneIdSpace", "SyntheSis Zone.id");
        root.put("sourceRows", demand.sourceRows());
        root.put("legs", demand.legCount());
        root.put("trips", demand.tripCount());
        root.put("persons", demand.personCount());
        root.put("distanceKm", demand.totalDistanceKm());
        ObjectNode modes = root.putObject("modes");
        Map<String, Long> modeCounts = new TreeMap<>();
        for (TravelDemand.PersonTrip trip : demand.trips()) {
            modeCounts.merge(trip.mode().artifactName(), 1L, Long::sum);
        }
        modeCounts.forEach(modes::put);
        ObjectNode artifacts = root.putObject("artifacts");
        artifacts.put("personTrips", "person-trips.csv");
        artifacts.put("legs", "legs.csv");
        artifacts.put("zoneFlows", "zone-flows.csv");
        artifacts.put("odFlows", "od-flows.csv");

        Path temporary = temporarySibling(file);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            replace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist in der JVM nicht verfügbar", e);
        }
    }

    private static void row(Writer writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(';');
            }
            writer.write(csv(values[i]));
        }
        writer.write('\n');
    }

    private static String csv(String value) {
        if (value.indexOf(';') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    private static void atomicWrite(Path file, WriterAction action) throws IOException {
        Path temporary = temporarySibling(file);
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8)) {
                action.write(writer);
            }
            replace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path temporarySibling(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    }

    private static void replace(Path temporary, Path file) throws IOException {
        try {
            Files.move(
                    temporary, file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
