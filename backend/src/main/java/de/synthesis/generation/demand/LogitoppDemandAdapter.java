package de.synthesis.generation.demand;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Einziger Legacy-Adapter von {@code demandsimulationResult.csv} auf den nativen
 * SyntheSis-Nachfragevertrag.
 */
public final class LogitoppDemandAdapter {

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "tripId", "legId", "personOid", "legMode", "mainMode", "tripMode", "distanceInKm",
            "sourceZone", "targetZone", "fromX", "fromY", "toX", "toY",
            "tripBegin", "tripEnd");

    private record TripKey(String personOid, String tripId) implements Comparable<TripKey> {
        @Override
        public int compareTo(TripKey other) {
            int person = personOid.compareTo(other.personOid);
            return person != 0 ? person : tripId.compareTo(other.tripId);
        }

        String display() {
            return personOid + ":" + tripId;
        }
    }

    private record RawLeg(
            TravelDemand.Leg leg,
            String tripModeCode,
            String mainModeCode,
            double tripBeginMinute,
            double tripEndMinute,
            double activityFromLon,
            double activityFromLat,
            double activityToLon,
            double activityToLat,
            int sourceLine) {
    }

    public TravelDemand read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Demand-Datei fehlt: " + file);
        }
        Map<TripKey, TreeMap<Integer, RawLeg>> grouped = new TreeMap<>();
        int sourceRows = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw failure(file, 1, "header", null, "Datei ist leer");
            }
            String[] headerCells = headerLine.split(";", -1);
            Map<String, Integer> header = parseHeader(file, headerCells);
            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(";", -1);
                if (cells.length != headerCells.length) {
                    throw failure(file, lineNumber, "row", null,
                            "erwartet " + headerCells.length + " Spalten, gefunden "
                                    + cells.length);
                }
                String personOid = requiredText(
                        file, lineNumber, "personOid", cells[header.get("personOid")], null);
                String tripId = requiredText(
                        file, lineNumber, "tripId", cells[header.get("tripId")], null);
                TripKey key = new TripKey(personOid, tripId);
                int legId = parseInteger(
                        file, lineNumber, "legId", cells[header.get("legId")], key);
                if (legId < 0) {
                    throw failure(file, lineNumber, "legId", key, "darf nicht negativ sein");
                }
                String legModeCode = requiredText(
                        file, lineNumber, "legMode", cells[header.get("legMode")], key);
                String tripModeCode = requiredText(
                        file, lineNumber, "tripMode", cells[header.get("tripMode")], key);
                String mainModeCode = requiredText(
                        file, lineNumber, "mainMode", cells[header.get("mainMode")], key);
                double distanceKm = parseFinite(
                        file, lineNumber, "distanceInKm",
                        cells[header.get("distanceInKm")], key);
                if (distanceKm < 0) {
                    throw failure(file, lineNumber, "distanceInKm", key,
                            "darf nicht negativ sein");
                }
                double plannedDepartureMinute = parseFinite(
                        file, lineNumber, "tripBegin", cells[header.get("tripBegin")], key);
                double plannedArrivalMinute = parseFinite(
                        file, lineNumber, "tripEnd", cells[header.get("tripEnd")], key);
                if (plannedDepartureMinute < 0) {
                    throw failure(file, lineNumber, "tripBegin", key,
                            "darf nicht negativ sein");
                }
                if (plannedArrivalMinute < plannedDepartureMinute) {
                    throw failure(file, lineNumber, "tripEnd", key,
                            "liegt vor tripBegin");
                }
                if (plannedArrivalMinute > TravelDemand.MAX_PLANNED_ARRIVAL_MINUTES) {
                    throw failure(file, lineNumber, "tripEnd", key,
                            "liegt außerhalb des unterstützten Wochenhorizonts mit "
                                    + "höchstens einem Tag Überlauf");
                }
                String originZone = requiredText(
                        file, lineNumber, "sourceZone", cells[header.get("sourceZone")], key);
                String destinationZone = requiredText(
                        file, lineNumber, "targetZone", cells[header.get("targetZone")], key);
                double fromLon = longitude(file, lineNumber, "fromX",
                        cells[header.get("fromX")], key);
                double fromLat = latitude(file, lineNumber, "fromY",
                        cells[header.get("fromY")], key);
                double toLon = longitude(file, lineNumber, "toX",
                        cells[header.get("toX")], key);
                double toLat = latitude(file, lineNumber, "toY",
                        cells[header.get("toY")], key);

                TravelDemand.Leg leg = new TravelDemand.Leg(
                        legId,
                        TravelDemand.Mode.fromLegacyCode(legModeCode),
                        legModeCode,
                        distanceKm,
                        originZone,
                        destinationZone);
                TreeMap<Integer, RawLeg> legs = grouped.computeIfAbsent(
                        key, ignored -> new TreeMap<>());
                RawLeg previous = legs.putIfAbsent(
                        legId, new RawLeg(
                                leg,
                                tripModeCode,
                                mainModeCode,
                                plannedDepartureMinute,
                                plannedArrivalMinute,
                                fromLon,
                                fromLat,
                                toLon,
                                toLat,
                                lineNumber));
                if (previous != null) {
                    throw failure(file, lineNumber, "legId", key,
                            "doppeltes Leg " + legId + " (erste Zeile "
                                    + previous.sourceLine() + ")");
                }
                sourceRows++;
            }
        }

        List<TravelDemand.PersonTrip> trips = new ArrayList<>(grouped.size());
        for (Map.Entry<TripKey, TreeMap<Integer, RawLeg>> entry : grouped.entrySet()) {
            TripKey key = entry.getKey();
            TreeMap<Integer, RawLeg> legsById = entry.getValue();
            int firstSourceLine = legsById.firstEntry().getValue().sourceLine();
            if (!legsById.containsKey(0)) {
                throw failure(file, firstSourceLine, "legId", key, "legId=0 fehlt");
            }
            for (int expected = 0; expected < legsById.size(); expected++) {
                if (!legsById.containsKey(expected)) {
                    throw failure(file, firstSourceLine, "legId", key,
                            "Leg-Folge ist nicht lückenlos; " + expected + " fehlt");
                }
            }
            RawLeg firstRaw = legsById.firstEntry().getValue();
            RawLeg lastRaw = legsById.lastEntry().getValue();
            String tripModeCode = firstRaw.tripModeCode();
            String mainModeCode = firstRaw.mainModeCode();
            double previousEnd = firstRaw.tripEndMinute();
            double distanceKm = 0;
            for (RawLeg raw : legsById.values()) {
                if (!tripModeCode.equals(raw.tripModeCode())) {
                    throw failure(file, raw.sourceLine(), "tripMode", key,
                            "widersprüchliche Wegmodus-Sequenz " + raw.tripModeCode()
                                    + " statt " + tripModeCode);
                }
                if (!mainModeCode.equals(raw.mainModeCode())) {
                    throw failure(file, raw.sourceLine(), "mainMode", key,
                            "widersprüchlicher Hauptmodus " + raw.mainModeCode()
                                    + " statt " + mainModeCode);
                }
                if (Double.compare(raw.tripBeginMinute(), firstRaw.tripBeginMinute()) != 0) {
                    throw failure(file, raw.sourceLine(), "tripBegin", key,
                            "Leg-Zeile weicht vom gemeinsamen Aktivitätsbeginn ab");
                }
                if (!sameActivityEnvelope(raw, firstRaw)) {
                    throw failure(file, raw.sourceLine(), "fromX/fromY/toX/toY", key,
                            "Leg-Zeile weicht von der gemeinsamen Aktivitätsgeometrie ab");
                }
                if (raw.tripEndMinute() < previousEnd) {
                    throw failure(file, raw.sourceLine(), "tripEnd", key,
                            "Leg-Endzeiten sind nach legId nicht monoton");
                }
                distanceKm += raw.leg().distanceKm();
                if (!Double.isFinite(distanceKm)) {
                    throw failure(file, raw.sourceLine(), "distanceInKm", key,
                            "Leg-Distanzsumme ist nicht endlich");
                }
                previousEnd = raw.tripEndMinute();
            }
            TravelDemand.Leg first = firstRaw.leg();
            TravelDemand.Leg last = lastRaw.leg();
            if (firstRaw.tripBeginMinute() >= TravelDemand.HORIZON_MINUTES) {
                throw failure(file, firstRaw.sourceLine(), "tripBegin", key,
                        "Weg beginnt außerhalb des Wochenhorizonts [0,"
                                + TravelDemand.HORIZON_MINUTES + ")");
            }
            List<TravelDemand.Leg> legs = legsById.values().stream()
                    .map(RawLeg::leg)
                    .toList();
            trips.add(new TravelDemand.PersonTrip(
                    key.personOid(),
                    key.tripId(),
                    TravelDemand.Mode.fromLegacyCode(mainModeCode),
                    mainModeCode,
                    tripModeCode,
                    firstRaw.tripBeginMinute(),
                    lastRaw.tripEndMinute(),
                    distanceKm,
                    first.originZoneId(),
                    last.destinationZoneId(),
                    firstRaw.activityFromLon(),
                    firstRaw.activityFromLat(),
                    lastRaw.activityToLon(),
                    lastRaw.activityToLat(),
                    legs));
        }
        return new TravelDemand(trips, sourceRows);
    }

    private static boolean sameActivityEnvelope(RawLeg left, RawLeg right) {
        return Double.compare(left.activityFromLon(), right.activityFromLon()) == 0
                && Double.compare(left.activityFromLat(), right.activityFromLat()) == 0
                && Double.compare(left.activityToLon(), right.activityToLon()) == 0
                && Double.compare(left.activityToLat(), right.activityToLat()) == 0;
    }

    private static Map<String, Integer> parseHeader(Path file, String[] cells)
            throws IOException {
        Map<String, Integer> header = new HashMap<>();
        int logicalEnd = cells.length;
        if (logicalEnd > 0 && cells[logicalEnd - 1].isBlank()) {
            logicalEnd--;
        }
        for (int i = 0; i < logicalEnd; i++) {
            String name = cells[i].trim();
            if (name.isEmpty()) {
                throw failure(file, 1, "header", null,
                        "leerer Spaltenname an Position " + (i + 1));
            }
            Integer previous = header.putIfAbsent(name, i);
            if (previous != null) {
                throw failure(file, 1, name, null,
                        "doppelter Spaltenname an Position " + (i + 1));
            }
        }
        for (String required : REQUIRED_COLUMNS) {
            if (!header.containsKey(required)) {
                throw failure(file, 1, required, null, "Pflichtspalte fehlt");
            }
        }
        return header;
    }

    private static String requiredText(
            Path file, int line, String field, String value, TripKey key) throws IOException {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw failure(file, line, field, key, "Wert fehlt");
        }
        return normalized;
    }

    private static int parseInteger(
            Path file, int line, String field, String value, TripKey key) throws IOException {
        try {
            return Integer.parseInt(requiredText(file, line, field, value, key));
        } catch (NumberFormatException e) {
            throw failure(file, line, field, key, "keine ganze Zahl: " + value);
        }
    }

    private static double parseFinite(
            Path file, int line, String field, String value, TripKey key) throws IOException {
        String normalized = requiredText(file, line, field, value, key);
        try {
            double parsed = Double.parseDouble(normalized);
            if (!Double.isFinite(parsed)) {
                throw failure(file, line, field, key, "Wert ist nicht endlich: " + normalized);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw failure(file, line, field, key, "keine Zahl: " + normalized);
        }
    }

    private static double longitude(
            Path file, int line, String field, String value, TripKey key) throws IOException {
        double parsed = parseFinite(file, line, field, value, key);
        if (parsed < -180 || parsed > 180) {
            throw failure(file, line, field, key, "Längengrad außerhalb [-180,180]");
        }
        return parsed;
    }

    private static double latitude(
            Path file, int line, String field, String value, TripKey key) throws IOException {
        double parsed = parseFinite(file, line, field, value, key);
        if (parsed < -90 || parsed > 90) {
            throw failure(file, line, field, key, "Breitengrad außerhalb [-90,90]");
        }
        return parsed;
    }

    private static IOException failure(
            Path file, int line, String field, TripKey key, String detail) {
        String location = line > 0 ? "Zeile " + line : "Trip";
        String trip = key == null ? "" : ", Trip " + key.display();
        return new IOException("Ungültige Verkehrsnachfrage in " + file + ": "
                + location + ", Feld " + field + trip + " – " + detail);
    }
}
