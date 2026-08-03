package de.synthesis.generation.pipeline;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the official BKG VG250 municipality-point layer in one compact CSV request. */
@Component
public class BkgMunicipalityCentroids implements MunicipalityCentroidSource {

    public static final URI SOURCE = URI.create("https://sgx.geodatenzentrum.de/wfs_vg250"
            + "?service=WFS&version=2.0.0&request=GetFeature"
            + "&typeNames=vg250%3Avg250_pk&outputFormat=csv"
            + "&propertyName=ags%2Clon_dez%2Clat_dez");

    private final HttpClient http;
    private final URI source;

    public BkgMunicipalityCentroids() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), SOURCE);
    }

    BkgMunicipalityCentroids(HttpClient http, URI source) {
        this.http = http;
        this.source = source;
    }

    @Override
    public Map<String, Point> fetch() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "text/csv")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("BKG-WFS antwortet mit HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("BKG-WFS-Abfrage unterbrochen", e);
        }
    }

    static Map<String, Point> parse(String csv) throws IOException {
        Map<String, Point> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String header = reader.readLine();
            if (header == null || !header.replace("\r", "").equals("FID,ags,lon_dez,lat_dez")) {
                throw new IllegalArgumentException("BKG-Gemeindepunkte haben ein unbekanntes Schema");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.replace("\r", "").split(",", -1);
                if (values.length != 4 || !values[1].matches("\\d{8}")) {
                    throw new IllegalArgumentException(
                            "Ungültiger BKG-Gemeindepunkt in Zeile " + lineNumber);
                }
                double lon = Double.parseDouble(values[2]);
                double lat = Double.parseDouble(values[3]);
                if (!Double.isFinite(lat) || lat < -90 || lat > 90
                        || !Double.isFinite(lon) || lon < -180 || lon > 180) {
                    throw new IllegalArgumentException(
                            "Ungültige BKG-Koordinate in Zeile " + lineNumber);
                }
                result.put(values[1], new Point(values[1], lat, lon));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("BKG-WFS enthält keine Gemeindepunkte");
        }
        return Map.copyOf(result);
    }
}
