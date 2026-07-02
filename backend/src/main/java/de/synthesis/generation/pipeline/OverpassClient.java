package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fetches POI nodes from Overpass for attractivity estimation. */
@Component
public class OverpassClient {

    public record Poi(double lat, double lon, Map<String, String> tags) {
    }

    /** Settlement node (place=city/town/...) with its population tag, if mapped. */
    public record Place(String name, String type, double lat, double lon, long population) {
    }

    private final SynthesisProperties properties;
    private final ObjectMapper mapper;

    public OverpassClient(SynthesisProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * @throws Exception on any network/parsing failure; callers fall back to synthetic data.
     */
    public List<Poi> fetchPois(Scenario.Region region) throws Exception {
        String bbox = bbox(region);
        String query = """
                [out:json][timeout:%d];
                (
                  node["shop"](%s);
                  node["amenity"](%s);
                  node["office"](%s);
                  node["leisure"](%s);
                  node["tourism"](%s);
                  node["craft"](%s);
                );
                out body qt;
                """.formatted(properties.getOverpass().getTimeoutSeconds(), bbox, bbox, bbox, bbox, bbox, bbox);
        JsonNode root = execute(query);
        List<Poi> pois = new ArrayList<>();
        for (JsonNode element : root.path("elements")) {
            if (!element.has("lat") || !element.has("lon")) {
                continue;
            }
            Map<String, String> tags = new HashMap<>();
            element.path("tags").properties()
                    .forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            pois.add(new Poi(element.get("lat").asDouble(), element.get("lon").asDouble(), tags));
        }
        return pois;
    }

    /**
     * Fetches settlement nodes (place=*) with population tags around the region.
     * The bbox is expanded so that nearby settlement centers (e.g. a city node just
     * outside a small selection) still contribute to the population model.
     *
     * @throws Exception on any network/parsing failure; callers fall back to a density estimate.
     */
    public List<Place> fetchPlaces(Scenario.Region region) throws Exception {
        double latPad = (region.maxLat - region.minLat) * 0.5 + 0.03;
        double lonPad = (region.maxLon - region.minLon) * 0.5 + 0.03;
        String bbox = (region.minLat - latPad) + "," + (region.minLon - lonPad) + ","
                + (region.maxLat + latPad) + "," + (region.maxLon + lonPad);
        String query = """
                [out:json][timeout:%d];
                node["place"~"city|town|village|suburb|quarter|neighbourhood"](%s);
                out body qt;
                """.formatted(properties.getOverpass().getTimeoutSeconds(), bbox);
        JsonNode root = execute(query);
        List<Place> places = new ArrayList<>();
        for (JsonNode element : root.path("elements")) {
            if (!element.has("lat") || !element.has("lon")) {
                continue;
            }
            JsonNode tags = element.path("tags");
            long population = parsePopulation(tags.path("population").asText(""));
            if (population <= 0) {
                continue;
            }
            places.add(new Place(tags.path("name").asText("?"), tags.path("place").asText(""),
                    element.get("lat").asDouble(), element.get("lon").asDouble(), population));
        }
        return places;
    }

    /**
     * Fetches the routable road network (highway ways + node geometry) within the
     * region as OSM XML, ready to feed into GraphHopper. The bbox is slightly padded
     * so routes near the border can still use adjacent roads.
     *
     * @throws Exception on any network failure; callers fall back to beeline matrices.
     */
    public String fetchRoadNetworkOsm(Scenario.Region region) throws Exception {
        double latPad = (region.maxLat - region.minLat) * 0.1 + 0.005;
        double lonPad = (region.maxLon - region.minLon) * 0.1 + 0.005;
        String bbox = (region.minLat - latPad) + "," + (region.minLon - lonPad) + ","
                + (region.maxLat + latPad) + "," + (region.maxLon + lonPad);
        String query = """
                [out:xml][timeout:%d];
                (
                  way["highway"](%s);
                  >;
                );
                out body qt;
                """.formatted(properties.getOverpass().getTimeoutSeconds(), bbox);
        return executeRaw(query);
    }

    private String executeRaw(String query) throws Exception {
        return post(query, 45L);
    }

    private JsonNode execute(String query) throws Exception {
        return mapper.readTree(post(query, 15L));
    }

    /**
     * Sends the query to the primary endpoint and, if it fails (HTTP error/timeout),
     * to each configured mirror in turn. Only the last failure is surfaced.
     */
    private String post(String query, long extraTimeoutSeconds) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        List<String> endpoints = properties.getOverpass().allEndpoints();
        Exception last = null;
        for (int i = 0; i < endpoints.size(); i++) {
            String endpoint = endpoints.get(i);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(properties.getOverpass().getTimeoutSeconds() + extraTimeoutSeconds))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IllegalStateException("Overpass-Antwort: HTTP " + response.statusCode()
                        + " (" + endpoint + ")");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("Overpass: kein Endpunkt erreichbar");
    }

    private static String bbox(Scenario.Region region) {
        return region.minLat + "," + region.minLon + "," + region.maxLat + "," + region.maxLon;
    }

    private static long parsePopulation(String value) {
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
