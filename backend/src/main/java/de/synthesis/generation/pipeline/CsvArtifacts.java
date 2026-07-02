package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writers for zones.csv and attractivities.csv, format-aligned with the
 * mobiTopp tutorial (kit-ifv/mobiTopp-tutorial, data/zone-repository).
 */
public final class CsvArtifacts {

    private CsvArtifacts() {
    }

    /** Location literal as used by mobiTopp's PointAndRoadPositionParser: (lon,lat: edgeId, relativePosition). */
    public static String location(double lon, double lat) {
        return String.format(Locale.ROOT, "(%f,%f: 0, 0.5)", lon, lat);
    }

    public static void writeZonesCsv(Path file, List<Zone> zones) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write(quoted("matrixColumn", "id", "name", "areaType", "regionType", "classification",
                    "parkingPlaces", "centroidLocation", "isDestination", "relief"));
            w.write("\n");
            for (Zone zone : zones) {
                w.write(quoted(String.valueOf(zone.index), String.valueOf(zone.id), zone.name,
                        "3", "123", "studyArea", "0",
                        location(zone.centroidLon, zone.centroidLat), "true", "0.0"));
                w.write("\n");
            }
        }
    }

    public static void writeAttractivitiesCsv(Path file, List<Zone> zones) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("zoneId;" + AttractivityCalculator.CATEGORIES.stream()
                    .map(category -> "Attractivity:" + category)
                    .collect(Collectors.joining(";")));
            w.write("\n");
            for (Zone zone : zones) {
                StringBuilder row = new StringBuilder().append(zone.id);
                for (String category : AttractivityCalculator.CATEGORIES) {
                    row.append(';').append(Math.round(zone.attractivities.getOrDefault(category, 0.0)));
                }
                w.write(row.append('\n').toString());
            }
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
