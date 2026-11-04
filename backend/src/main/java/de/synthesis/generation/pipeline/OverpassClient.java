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
import java.nio.file.Files;
import java.nio.file.Path;
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
        return fetchPlaces(region, false);
    }

    /** Adds the full diagnostic horizon only when external relations are requested. */
    public List<Place> fetchPlaces(Scenario.Region region, boolean includeExternal) throws Exception {
        JsonNode root = execute(placeQuery(region, includeExternal));
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

    /** Population always uses the original local footprint, including on snapshot replay. */
    public static List<Place> populationPlaces(Scenario.Region region, List<Place> places) {
        double[] bounds = populationPlaceBounds(region);
        return places.stream().filter(p -> p.lat() >= bounds[0] && p.lat() <= bounds[2]
                && p.lon() >= bounds[1] && p.lon() <= bounds[3]).toList();
    }

    private static double[] populationPlaceBounds(Scenario.Region region) {
        double latPad = (region.maxLat - region.minLat) * 0.5 + 0.03;
        double lonPad = (region.maxLon - region.minLon) * 0.5 + 0.03;
        return new double[]{Math.max(-90, region.minLat - latPad),
                Math.max(-180, region.minLon - lonPad), Math.min(90, region.maxLat + latPad),
                Math.min(180, region.maxLon + lonPad)};
    }

    String placeQuery(Scenario.Region region, boolean includeExternal) {
        double[] bounds = populationPlaceBounds(region);
        double centreLat = (region.minLat + region.maxLat) / 2;
        double centreLon = (region.minLon + region.maxLon) / 2;
        if (includeExternal) {
            double angle = OsmExternalDestinationCandidates.MAX_DISTANCE_KM / 6371.0088;
            double latPad = Math.toDegrees(angle);
            double lonPad = Math.abs(centreLat) + latPad >= 90 ? 180
                    : Math.toDegrees(Math.asin(Math.sin(angle) / Math.cos(Math.toRadians(centreLat))));
            bounds[0] = Math.max(-90, Math.min(bounds[0], centreLat - latPad));
            bounds[2] = Math.min(90, Math.max(bounds[2], centreLat + latPad));
            boolean wraps = centreLon - lonPad < -180 || centreLon + lonPad > 180;
            bounds[1] = wraps ? -180 : Math.min(bounds[1], centreLon - lonPad);
            bounds[3] = wraps ? 180 : Math.max(bounds[3], centreLon + lonPad);
        }
        String bbox = bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3];
        return """
                [out:json][timeout:%d];
                node["place"~"city|town|village|suburb|quarter|neighbourhood"](%s);
                out body qt;
                """.formatted(properties.getOverpass().getTimeoutSeconds(), bbox);
    }

    /**
     * Fetches the routable road network (highway ways + node geometry) within the
     * region as OSM XML, ready to feed into GraphHopper. The bbox is slightly padded
     * so routes near the border can still use adjacent roads.
     *
     * @throws Exception on any network failure; callers fall back to beeline matrices.
     */
    public String fetchRoadNetworkOsm(Scenario.Region region) throws Exception {
        return fetchRoadNetworkOsm(region, null);
    }

    /**
     * Like {@link #fetchRoadNetworkOsm(Scenario.Region)}, but a scenario-owned
     * extract takes precedence over the deployment-wide configured file.
     */
    public String fetchRoadNetworkOsm(Scenario.Region region, Path scenarioFile) throws Exception {
        Path file = selectedRoadNetworkFile(scenarioFile);
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("Lokale OSM-Netzdatei nicht gefunden: " + file);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        int serverTimeout = properties.getOverpass().getRoadNetworkTimeoutSeconds();
        // Client wartet etwas länger als das serverseitige Query-Budget, sonst bricht er ab,
        // während Overpass die schwere Extraktion noch fertigstellt.
        return post(roadNetworkQuery(region), serverTimeout + 30L);
    }

    /** Menschenlesbare Quelle für Job-Protokolle und Diagnose. */
    public String roadNetworkSource() {
        return roadNetworkSource(null);
    }

    public String roadNetworkSource(Path scenarioFile) {
        Path selected = selectedRoadNetworkFile(scenarioFile);
        if (selected != null) {
            return "Datei " + selected;
        }
        return "Overpass";
    }

    private Path selectedRoadNetworkFile(Path scenarioFile) {
        if (scenarioFile != null && Files.isRegularFile(scenarioFile)) {
            return scenarioFile.toAbsolutePath().normalize();
        }
        String configuredFile = properties.getOverpass().getRoadNetworkFile();
        if (configuredFile == null || configuredFile.isBlank()) {
            return null;
        }
        return Path.of(configuredFile).toAbsolutePath().normalize();
    }

    /** Overpass-Query für das routbare Straßennetz (eigenes {@code [timeout:…]}-Budget). */
    String roadNetworkQuery(Scenario.Region region) {
        double latPad = (region.maxLat - region.minLat) * 0.1 + 0.005;
        double lonPad = (region.maxLon - region.minLon) * 0.1 + 0.005;
        String bbox = (region.minLat - latPad) + "," + (region.minLon - lonPad) + ","
                + (region.maxLat + latPad) + "," + (region.maxLon + lonPad);
        return """
                [out:xml][timeout:%d];
                way["highway"](%s)->.roads;
                (.roads; relation(bw.roads)["type"~"^restriction"];);
                (._; >;);
                out body qt;
                """.formatted(properties.getOverpass().getRoadNetworkTimeoutSeconds(), bbox);
    }

    /** Nodes, ways and relations with stable source identities and representative centres. */
    public record EnergyResponse(JsonNode data, String endpoint) { }

    public EnergyResponse fetchEnergyStations(Scenario.Region region) throws Exception {
        String query = "[out:json][timeout:" + properties.getOverpass().getTimeoutSeconds()
                + "];nwr[\"amenity\"~\"^(charging_station|fuel)$\"](" + bbox(region) + ");out meta center;";
        var response = postResponse(query, properties.getOverpass().getTimeoutSeconds() + 15L);
        return new EnergyResponse(mapper.readTree(response.body()), response.endpoint());
    }

    private JsonNode execute(String query) throws Exception {
        return mapper.readTree(post(query, properties.getOverpass().getTimeoutSeconds() + 15L));
    }

    /**
     * Sends the query to the primary endpoint and, if it fails (HTTP error/timeout),
     * to each configured mirror in turn. Only the last failure is surfaced.
     *
     * @param clientTimeoutSeconds absolutes HTTP-Timeout je Endpunkt
     */
    private String post(String query, long clientTimeoutSeconds) throws Exception {
        return postResponse(query, clientTimeoutSeconds).body();
    }

    private record QueryResponse(String body, String endpoint) { }
    private QueryResponse postResponse(String query, long clientTimeoutSeconds) throws Exception {
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
                        .timeout(Duration.ofSeconds(clientTimeoutSeconds))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return new QueryResponse(response.body(), endpoint);
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
