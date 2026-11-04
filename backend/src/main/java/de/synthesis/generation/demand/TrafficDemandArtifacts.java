package de.synthesis.generation.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Shared JSON, CSV and explicitly unrouted OD geometry. Existing person-only contracts stay intact. */
public final class TrafficDemandArtifacts {
    public static final String INPUT = "demand/traffic-demand.json";
    public static final String SUMMARY = "demand/traffic-summary.json";
    public static void write(Path artifacts, TrafficDemand demand, ObjectMapper json) throws IOException {
        Path dir = artifacts.resolve("demand"); Files.createDirectories(dir);
        var movements = demand.movements(); var tours = demand.tours();
        Map<String, Long> sources = new TreeMap<>(), categories = new TreeMap<>();
        for (var t : movements) { sources.merge(t.demandSource(), 1L, Long::sum); categories.merge(t.trafficCategory(), 1L, Long::sum); }
        atomic(dir.resolve("traffic-demand.json"), file -> json.writeValue(file.toFile(), demand));
        atomic(dir.resolve("tours.json"), file -> json.writeValue(file.toFile(), Map.of("contract", TrafficDemand.CONTRACT, "tours", tours)));
        atomic(dir.resolve("traffic-summary.json"), file -> json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of(
                "contract", TrafficDemand.CONTRACT, "movements", movements.size(), "tours", tours.size(),
                "byDemandSource", sources, "byTrafficCategory", categories, "personDemandStatus", demand.personDemandStatus(),
                "composition", "additive-without-overlap-correction", "timeReference", "seconds-since-Monday-00:00",
                "geometry", "OD-connections-not-routed", "parcelDemandStatus", demand.parcelDemandStatus())));
        atomic(dir.resolve("movements.csv"), file -> {
            try (var w = Files.newBufferedWriter(file)) {
                w.write("sourceTripKey;trafficCategory;demandSource;mode;vehicleClass;tourId;vehicleId;departureWeekSeconds;plannedArrivalWeekSeconds;serviceSeconds;fromLon;fromLat;toLon;toLat;originZoneId;destinationZoneId;returning\n");
                for (var t : movements) {
                    Object[] values = {t.sourceTripKey(),t.trafficCategory(),t.demandSource(),t.mode(),t.vehicleClass(),t.tourId(),t.vehicleId(),
                            t.departureWeekSeconds(),t.plannedArrivalWeekSeconds(),t.serviceSeconds(),t.fromLon(),t.fromLat(),t.toLon(),t.toLat(),t.originZoneId(),t.destinationZoneId(),t.returning()};
                    for (int i=0;i<values.length;i++) { if (i>0) w.write(';'); w.write(csv(values[i])); } w.write('\n');
                }
            }
        });
        atomic(dir.resolve("planned-connections.geojson"), file -> {
            try (var g = json.getFactory().createGenerator(file.toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                g.writeStartObject(); g.writeStringField("type", "FeatureCollection"); g.writeArrayFieldStart("features");
                for (var t : movements) {
                    g.writeObject(Map.of("type", "Feature", "geometry", Map.of("type", "LineString", "coordinates", List.of(
                            List.of(t.fromLon(),t.fromLat()),List.of(t.toLon(),t.toLat()))), "properties", Map.of(
                            "sourceTripKey", t.sourceTripKey(), "trafficCategory", t.trafficCategory(), "demandSource", t.demandSource(),
                            "geometryKind", "OD-connection", "routed", false)));
                }
                g.writeEndArray(); g.writeEndObject();
            }
        });
    }
    public static TrafficDemand read(Path artifacts, ObjectMapper json) throws IOException {
        return json.readValue(artifacts.resolve(INPUT).toFile(), TrafficDemand.class);
    }
    private static String csv(Object v) { return v == null ? "" : "\"" + v.toString().replace("\"", "\"\"") + "\""; }
    @FunctionalInterface private interface Write { void accept(Path file) throws IOException; }
    private static void atomic(Path target, Write write) throws IOException {
        Path tmp=Files.createTempFile(target.getParent(), ".traffic-", ".tmp");
        try { write.accept(tmp); Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
    }
}
