package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    /**
     * Schreibt die geparsten POIs und Siedlungen wieder als Overpass-{@code elements}-Array und
     * liefert den SHA-256 der Datei. Damit bekommt auch ein Live-Abruf einen nachprüfbaren
     * Fingerabdruck: Zwei Läufe teilen genau dann dieselbe OSM-Eingabe, wenn dieser Hash gleich
     * ist. Die Datei ist zugleich als {@link #NAME} in {@code uploads/} eines anderen Szenarios
     * wiederverwendbar und friert die Eingabe damit für einen A/B-Vergleich ein.
     *
     * <p>Elemente werden je Koordinate zusammengeführt, damit ein Knoten, der sowohl POI als auch
     * Siedlung ist, beim Zurücklesen nicht doppelt gezählt wird. Zwei verschiedene Knoten auf
     * exakt derselben Koordinate verschmelzen dabei zu einem — für die koordinatenbasierte
     * Attraktivitätsrechnung ohne Belang.
     */
    public static String write(Path file, List<OverpassClient.Poi> pois,
                               List<OverpassClient.Place> places, ObjectMapper mapper)
            throws IOException {
        Map<String, Map<String, String>> byCoordinate = new LinkedHashMap<>();
        for (OverpassClient.Poi poi : pois) {
            byCoordinate.computeIfAbsent(key(poi.lat(), poi.lon()), k -> new LinkedHashMap<>())
                    .putAll(poi.tags());
        }
        for (OverpassClient.Place place : places) {
            Map<String, String> tags = byCoordinate
                    .computeIfAbsent(key(place.lat(), place.lon()), k -> new LinkedHashMap<>());
            tags.put("place", place.type());
            tags.put("name", place.name());
            tags.put("population", Long.toString(place.population()));
        }

        ObjectNode root = mapper.createObjectNode();
        ArrayNode elements = root.putArray("elements");
        byCoordinate.forEach((coordinate, tags) -> {
            int separator = coordinate.indexOf('/');
            ObjectNode element = elements.addObject();
            element.put("type", "node");
            element.put("lat", Double.parseDouble(coordinate.substring(0, separator)));
            element.put("lon", Double.parseDouble(coordinate.substring(separator + 1)));
            ObjectNode tagNode = element.putObject("tags");
            tags.forEach(tagNode::put);
        });

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return sha256(file);
    }

    private static String key(double lat, double lon) {
        return lat + "/" + lon;
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
