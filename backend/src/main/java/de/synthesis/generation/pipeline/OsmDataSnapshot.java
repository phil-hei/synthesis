package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Validated, scenario-owned snapshot of Overpass POI and settlement-node data. */
public final class OsmDataSnapshot {

    public static final String NAME = "osm-data.json";

    public record Content(List<OverpassClient.Poi> pois, List<OverpassClient.Place> places,
                          int elements, String sha256) {
    }

    private OsmDataSnapshot() {
    }

    public static Content read(Path file, ObjectMapper mapper) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("Ungültiges Overpass-JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.path("elements").isArray()) {
            throw new IllegalArgumentException("Overpass-JSON enthält kein elements-Array");
        }

        List<OverpassClient.Poi> pois = new ArrayList<>();
        List<OverpassClient.Place> places = new ArrayList<>();
        int elements = 0;
        for (JsonNode element : root.path("elements")) {
            elements++;
            if (!element.has("lat") || !element.has("lon")) {
                continue;
            }
            double lat = element.path("lat").asDouble(Double.NaN);
            double lon = element.path("lon").asDouble(Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException("OSM-Element enthält ungültige Koordinaten");
            }
            Map<String, String> tags = new HashMap<>();
            element.path("tags").properties()
                    .forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            if (isPoi(tags)) {
                pois.add(new OverpassClient.Poi(lat, lon, Map.copyOf(tags)));
            }
            String place = tags.getOrDefault("place", "");
            long population = parsePopulation(tags.get("population"));
            if (isSettlement(place) && population > 0) {
                places.add(new OverpassClient.Place(tags.getOrDefault("name", "?"), place,
                        lat, lon, population));
            }
        }
        if (pois.isEmpty() && places.isEmpty()) {
            throw new IllegalArgumentException(
                    "Overpass-JSON enthält weder verwertbare POIs noch Siedlungen mit Einwohnerzahl");
        }
        return new Content(List.copyOf(pois), List.copyOf(places), elements, sha256(file));
    }

    private static boolean isPoi(Map<String, String> tags) {
        return tags.containsKey("shop") || tags.containsKey("amenity")
                || tags.containsKey("office") || tags.containsKey("leisure")
                || tags.containsKey("tourism") || tags.containsKey("craft");
    }

    private static boolean isSettlement(String place) {
        return switch (place) {
            case "city", "town", "village", "suburb", "quarter", "neighbourhood" -> true;
            default -> false;
        };
    }

    private static long parsePopulation(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
