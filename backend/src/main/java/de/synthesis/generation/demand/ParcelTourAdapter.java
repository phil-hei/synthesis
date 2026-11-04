package de.synthesis.generation.demand;

import de.synthesis.vehicle.VehicleOperationsConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Normalises logiTopp plans before any target export. Coordinates keep their source precision. */
public final class ParcelTourAdapter {
    public record Leg(int departSec, String vehicleClass, String fromLon, String fromLat, String toLon, String toLat,
                      String sourceKey, int serviceSeconds, String tourId, int day, boolean returning, Double plannedArrivalSec) {}
    private record TourClosure(String sourceTourId, String type, String depotLon, String depotLat,
                               String lastLon, String lastLat, int departMinute, boolean returning) {}
    public static List<Leg> readAll(Path file) throws IOException {
        if (file == null || !Files.exists(file)) return List.of();
        List<Leg> result = new ArrayList<>();
        for (int day = 0; day < 7; day++) result.addAll(read(file, day, true));
        return List.copyOf(result);
    }
    public static List<Leg> read(Path plannedTours, int day) throws IOException { return read(plannedTours, day, false); }
    private static List<Leg> read(Path plannedTours, int day, boolean strict) throws IOException {
        int dayStart = day * 1440;
        int dayEnd = (day + 1) * 1440;
        List<Leg> legs = new ArrayList<>();
        Map<String, TourClosure> closures = new java.util.LinkedHashMap<>();
        Map<String, Integer> sourceLegs = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(plannedTours, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return legs;
            }
            List<String> header = List.of(headerLine.split(";", -1));
            int tourId = header.indexOf("tourId");
            int returning = header.indexOf("isReturning");
            int vehicleType = header.indexOf("vehicleType");
            int fromX = header.indexOf("fromX");
            int fromY = header.indexOf("fromY");
            int toX = header.indexOf("toX");
            int toY = header.indexOf("toY");
            int planned = header.indexOf("plannedTimeSimMin");
            int tripDur = header.indexOf("tripDur");
            int deliveryDur = header.indexOf("deliveryDur");
            if (tourId < 0 || vehicleType < 0 || fromX < 0 || fromY < 0
                    || toX < 0 || toY < 0 || planned < 0) {
                if (strict) throw new IOException("Unbekanntes Pakettourenformat; gemeinsame Nachfrage kann nicht vollständig erstellt werden");
                return legs; // compatibility entry point
            }
            int lastRequiredColumn = java.util.stream.IntStream.of(
                    tourId, vehicleType, fromX, fromY, toX, toY, planned)
                    .max().orElse(-1);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                if (c.length <= lastRequiredColumn
                        || c[fromX].isBlank() || c[toX].isBlank()) {
                    if (strict) throw new IOException("Unvollständige Pakettourenzeile; gemeinsame Nachfrage wird nicht still gekürzt");
                    continue;
                }
                if (strict) {
                    for (int column : new int[]{fromX,fromY,toX,toY}) {
                        try {
                            double value=Double.parseDouble(c[column]);
                            if (!Double.isFinite(value) || Math.abs(value) > (column == fromY || column == toY ? 90 : 180)) throw new NumberFormatException();
                        } catch (RuntimeException e) { throw new IOException("Ungültige Koordinate in Pakettour " + c[tourId]); }
                    }
                }
                String type = switch (c[vehicleType].trim()) {
                    case "1" -> "bicycle";
                    case "3" -> "tram";
                    default -> "delivery";
                };
                if (type == null) {
                    continue;
                }
                int sourceLeg = sourceLegs.merge(c[tourId], 1, Integer::sum) - 1;
                double arrival = Double.parseDouble(c[planned]);
                double travel = tripDur >= 0 && tripDur < c.length && !c[tripDur].isBlank()
                        ? Double.parseDouble(c[tripDur]) : 0;
                if (strict && (!Double.isFinite(arrival) || !Double.isFinite(travel) || travel < 0 || arrival < 0 || arrival - travel < 0 || arrival - travel >= 7*1440))
                    throw new IOException("Ungültige Planzeit in Pakettour " + c[tourId]);
                int begin = (int) (arrival - travel);
                if (begin < dayStart || begin >= dayEnd) {
                    continue;
                }
                double serviceMinutes = deliveryDur >= 0 && deliveryDur < c.length && !c[deliveryDur].isBlank()
                        ? Double.parseDouble(c[deliveryDur]) : 0;
                if (!Double.isFinite(serviceMinutes) || serviceMinutes < 0 || serviceMinutes * 60 > Integer.MAX_VALUE)
                    throw new IOException("Ungültige Bedienzeit in Zustelltour " + c[tourId]);
                int service = (int) serviceMinutes;
                legs.add(new Leg((begin - dayStart) * 60, type, c[fromX], c[fromY], c[toX], c[toY],
                        VehicleOperationsConfiguration.parcelKey(c[tourId], Integer.toString(sourceLeg)), (int)Math.ceil(serviceMinutes * 60), c[tourId], day, returning >= 0 && returning < c.length && Boolean.parseBoolean(c[returning]), (arrival - dayStart)*60));
                TourClosure previous = closures.get(c[tourId]);
                closures.put(c[tourId], new TourClosure(
                        c[tourId], type,
                        previous == null ? c[fromX] : previous.depotLon(),
                        previous == null ? c[fromY] : previous.depotLat(),
                        c[toX], c[toY], (int) arrival + service,
                        (previous != null && previous.returning())
                                || (returning >= 0 && returning < c.length
                                && Boolean.parseBoolean(c[returning]))));
            }
        }
        for (TourClosure closure : closures.values()) {
            if (closure.returning() || closure.departMinute() < dayStart
                    || closure.departMinute() >= dayEnd
                    || sameCoordinate(closure.depotLon(), closure.depotLat(),
                    closure.lastLon(), closure.lastLat())) {
                continue;
            }
            legs.add(new Leg((closure.departMinute() - dayStart) * 60, closure.type(),
                    closure.lastLon(), closure.lastLat(), closure.depotLon(), closure.depotLat(),
                    VehicleOperationsConfiguration.parcelKey(closure.sourceTourId(), "return"), 0, closure.sourceTourId(), day, true, null));
        }
        return legs;
    }

    private static boolean sameCoordinate(String firstLon, String firstLat,
                                          String secondLon, String secondLat) {
        try {
            return Math.abs(Double.parseDouble(firstLon) - Double.parseDouble(secondLon)) < 1e-9
                    && Math.abs(Double.parseDouble(firstLat) - Double.parseDouble(secondLat)) < 1e-9;
        } catch (NumberFormatException e) {
            return firstLon.equals(secondLon) && firstLat.equals(secondLat);
        }
    }

}
