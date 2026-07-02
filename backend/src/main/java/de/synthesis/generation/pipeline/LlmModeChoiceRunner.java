package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GTA 3c (experimentell): LLM-gestützte Moduswahl auf den simulierten Trips, geerdet in
 * Reisezeiten je Verkehrsmittel. Statt einer Anfrage pro Trip (zu teuer) werden die Trips
 * nach Distanzband gebündelt; je Band fragt das LLM – mit realistischen Reisezeiten für
 * Fuß/Rad/Pkw/ÖV bei dieser Länge – nach dem Modal-Split. Die Bandanteile werden mit den
 * Trip-Häufigkeiten gewichtet zu einem Gesamt-Modal-Split aggregiert, der neben dem
 * logiTopp-MNL-Modell und MiD verglichen werden kann. Ergänzt die logiTopp-Simulation,
 * ersetzt sie nicht (deren Moduswahl bleibt unangetastet).
 */
public class LlmModeChoiceRunner {

    public record BandResult(String band, int trips, double meanKm,
                             Map<String, Double> times, Map<String, Double> split) {
    }

    public record Result(Map<String, Double> modalSplit, List<BandResult> bands, int trips) {
    }

    public static final String[] MODES = {"pedestrian", "bike", "car", "passenger", "publicTransport"};
    private static final double[] BAND_MAX = {1, 2, 5, 10, 20, Double.MAX_VALUE};
    private static final String[] BAND_LABEL = {"<1 km", "1-2 km", "2-5 km", "5-10 km", "10-20 km", ">20 km"};

    private final SynthesisProperties.Llm config;
    private final long baseSeed;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public LlmModeChoiceRunner(SynthesisProperties.Llm config, long baseSeed) {
        this.config = config;
        this.baseSeed = baseSeed;
    }

    public boolean isAvailable() {
        if (!config.isEnabled()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getEndpoint() + "/models"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param demandResult mobiTopp-Trip-Ergebnis (demandsimulationResult.csv mit distanceInKm)
     */
    public Result run(Path demandResult, Consumer<String> log) throws Exception {
        int[] counts = new int[BAND_MAX.length];
        double[] sumKm = new double[BAND_MAX.length];
        int total = 0;
        try (BufferedReader reader = Files.newBufferedReader(demandResult, StandardCharsets.UTF_8)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                // legId(1)==0 zählt jeden Trip einmal; distanceInKm(16)
                if (c.length < 17 || !"0".equals(c[1])) {
                    continue;
                }
                double km;
                try {
                    km = Double.parseDouble(c[16]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (km <= 0) {
                    continue;
                }
                int b = band(km);
                counts[b]++;
                sumKm[b] += km;
                total++;
            }
        }
        if (total == 0) {
            throw new IllegalStateException("Keine Trips mit distanceInKm in der Nachfrage");
        }

        java.util.List<BandResult> bands = new java.util.ArrayList<>();
        Map<String, Double> overall = new LinkedHashMap<>();
        for (String m : MODES) {
            overall.put(m, 0.0);
        }
        for (int b = 0; b < BAND_MAX.length; b++) {
            if (counts[b] == 0) {
                continue;
            }
            double meanKm = sumKm[b] / counts[b];
            Map<String, Double> times = travelTimes(meanKm);
            Map<String, Double> split = requestSplit(meanKm, times, b);
            bands.add(new BandResult(BAND_LABEL[b], counts[b], round(meanKm, 2), times, split));
            for (String m : MODES) {
                overall.merge(m, split.getOrDefault(m, 0.0) * counts[b], Double::sum);
            }
            log.accept(String.format("Band %s: %d Trips, Ø %.1f km → Fuß %.0f%% Rad %.0f%% Pkw %.0f%% ÖV %.0f%%",
                    BAND_LABEL[b], counts[b], meanKm, split.get("pedestrian") * 100,
                    split.get("bike") * 100, split.get("car") * 100, split.get("publicTransport") * 100));
        }
        for (String m : MODES) {
            overall.put(m, round(overall.get(m) / total, 4));
        }
        return new Result(overall, bands, total);
    }

    // --- Reisezeit-Modell (geerdet) -------------------------------------------

    /** Reisezeiten je Modus (Minuten) für eine Weglänge – einfache, transparente Annahmen. */
    private static Map<String, Double> travelTimes(double km) {
        Map<String, Double> t = new LinkedHashMap<>();
        t.put("pedestrian", round(km / 4.5 * 60, 1));     // 4,5 km/h
        t.put("bike", round(km / 15.0 * 60, 1));          // 15 km/h
        double car = round(km / 25.0 * 60 + 4, 1);        // 25 km/h innerorts + Parken/Zugang
        t.put("car", car);
        t.put("passenger", car);
        t.put("publicTransport", round(km / 18.0 * 60 + 12, 1)); // 18 km/h + Zugang/Warten
        return t;
    }

    private static int band(double km) {
        for (int i = 0; i < BAND_MAX.length; i++) {
            if (km < BAND_MAX[i]) {
                return i;
            }
        }
        return BAND_MAX.length - 1;
    }

    // --- LLM ------------------------------------------------------------------

    private static final String SYSTEM = """
            Du bist ein Verkehrsmittelwahl-Modell fuer Deutschland (Stil MiD 2017). Fuer Wege einer
            gegebenen Laenge mit gegebenen Reisezeiten je Verkehrsmittel gibst du den realistischen
            Modal-Split. Antworte AUSSCHLIESSLICH als JSON-Objekt mit Anteilen (0..1), die sich zu 1
            summieren: {"pedestrian":..,"bike":..,"car":..,"passenger":..,"publicTransport":..}.
            car = Selbstfahrer Pkw, passenger = Mitfahrer Pkw. Beruecksichtige: kurze Wege eher Fuss/Rad,
            mittlere/lange eher Pkw/OEV; OEV-Anteil staedtisch hoeher.""";

    private Map<String, Double> requestSplit(double km, Map<String, Double> times, int bandIndex) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        body.put("seed", baseSeed * 131 + bandIndex); // reproduzierbar je Szenario-Seed
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM);
        messages.addObject().put("role", "user").put("content", String.format(
                "Weglaenge ~%.1f km. Reisezeiten: zu Fuss %.0f min, Rad %.0f min, Pkw %.0f min, "
                        + "OEV %.0f min. Modal-Split?",
                km, times.get("pedestrian"), times.get("bike"), times.get("car"),
                times.get("publicTransport")));
        body.putObject("response_format").put("type", "json_object");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getEndpoint() + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        String content = mapper.readTree(response.body())
                .path("choices").path(0).path("message").path("content").asText("");
        JsonNode json = mapper.readTree(stripFences(content));
        return normalize(json);
    }

    /** Liest Anteile, ersetzt Fehlendes durch 0, normalisiert auf Summe 1. */
    private static Map<String, Double> normalize(JsonNode json) {
        Map<String, Double> split = new LinkedHashMap<>();
        double sum = 0;
        for (String m : MODES) {
            double v = Math.max(0, json.path(m).asDouble(0));
            split.put(m, v);
            sum += v;
        }
        if (sum <= 0) {
            throw new IllegalStateException("LLM-Modal-Split summiert zu 0");
        }
        for (String m : MODES) {
            split.put(m, round(split.get(m) / sum, 4));
        }
        return split;
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t;
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
