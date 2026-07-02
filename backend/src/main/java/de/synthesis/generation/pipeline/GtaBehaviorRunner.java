package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.ActivityGenerator.GtaActivity;
import de.synthesis.generation.pipeline.ActivityGenerator.GtaWeek;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Generative behaviour source (Achse A, GTA-Stil): turns each person into a persona and
 * asks a local LLM (OpenAI-compatible endpoint, e.g. Ollama) for a typical weekday and
 * weekend as out-of-home tours. The mechanical layout (HOME weaving, timing, fitting) is
 * left to {@link ActivityGenerator#generateFromGta}.
 *
 * <p>Because throughput is ~30–40 s per call on a base M1, persons are grouped by persona
 * signature (employment × age band × gender) and the LLM is called once per distinct
 * signature (capped by {@code maxPersonas}); all persons sharing a signature reuse the plan
 * (per-person timing still varies via the scheduler's jitter). This keeps GTA usable on real
 * populations with only a few dozen calls instead of thousands.
 */
public class GtaBehaviorRunner {

    public record Result(Map<Long, GtaWeek> plans, int personas, int llmCalls,
                         int personsCovered, int personsTotal) {
    }

    private final SynthesisProperties.Llm config;
    private final long baseSeed;
    private final Path cacheDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public GtaBehaviorRunner(SynthesisProperties.Llm config, long baseSeed) {
        this(config, baseSeed, null);
    }

    /**
     * @param cacheDir Verzeichnis für den Plan-Cache (null = kein Cache). Da Pläne bei
     *                 gleichem Seed deterministisch sind, können Wiederholungsläufe die
     *                 ~8 s/Persona-LLM-Aufrufe komplett einsparen; der Schlüssel enthält
     *                 Modell, Seed, Prompt-Version und Persona-Signatur.
     */
    public GtaBehaviorRunner(SynthesisProperties.Llm config, long baseSeed, Path cacheDir) {
        this.config = config;
        this.baseSeed = baseSeed;
        this.cacheDir = cacheDir;
    }

    /** Quick reachability probe of the OpenAI-compatible endpoint ({endpoint}/models). */
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

    private record Persona(String employment, String ageBand, String gender) {
    }

    /**
     * Reads person.csv (personId;personNumber;householdId;age;employment;gender;…),
     * builds plans for up to {@code maxPersonas} distinct personas and maps every covered
     * person to its persona's plan. Infants (age &lt; 6) are left uncovered (escorted-trip
     * fallback downstream), as are persons beyond the persona budget.
     */
    public Result run(Path personCsv, Consumer<String> log) throws Exception {
        // Persona → die Personen-IDs mit dieser Signatur (Einfügereihenfolge = Häufigkeitsnähe)
        Map<Persona, List<Long>> byPersona = new LinkedHashMap<>();
        int personsTotal = 0;
        try (BufferedReader reader = Files.newBufferedReader(personCsv, StandardCharsets.UTF_8)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                long id = Long.parseLong(c[0].trim());
                int age = Integer.parseInt(c[3].trim());
                personsTotal++;
                if (age < 6) {
                    continue; // keine LLM-Persona für Kleinkinder
                }
                Persona persona = new Persona(c[4].trim(), ageBand(age), gender(c[5]));
                byPersona.computeIfAbsent(persona, k -> new ArrayList<>()).add(id);
            }
        }

        // Häufigste Personas zuerst, damit das Persona-Budget die meisten Personen abdeckt
        List<Map.Entry<Persona, List<Long>>> personas = new ArrayList<>(byPersona.entrySet());
        personas.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        Map<Long, GtaWeek> plans = new HashMap<>();
        int llmCalls = 0;
        int cacheHits = 0;
        int covered = 0;
        int budget = Math.min(personas.size(), config.getMaxPersonas());
        log.accept(personas.size() + " distinkte Personas, " + budget + " im Budget (Modell "
                + config.getModel() + ")");
        for (int i = 0; i < budget; i++) {
            Persona persona = personas.get(i).getKey();
            List<Long> ids = personas.get(i).getValue();
            GtaWeek week = fromCache(persona);
            if (week != null) {
                cacheHits++;
            } else {
                try {
                    week = requestPlan(persona);
                } catch (Exception e) {
                    log.accept("Persona " + persona + " übersprungen (" + e.getClass().getSimpleName() + ")");
                    continue;
                }
                llmCalls++;
                toCache(persona, week);
            }
            for (long id : ids) {
                plans.put(id, week);
            }
            covered += ids.size();
        }
        log.accept(llmCalls + " LLM-Pläne erzeugt, " + cacheHits + " aus Cache, " + covered + "/"
                + personsTotal + " Personen abgedeckt");
        return new Result(plans, budget, llmCalls, covered, personsTotal);
    }

    // --- Plan-Cache -------------------------------------------------------------

    record CacheEntry(String key, GtaWeek week) {
    }

    /** Schlüssel: Modell × Seed × Prompt-Version × Persona – Prompt-Änderungen invalidieren automatisch. */
    private String cacheKey(Persona persona) {
        return config.getModel() + "|" + baseSeed + "|" + SYSTEM.hashCode() + "|"
                + persona.employment() + "|" + persona.ageBand() + "|" + persona.gender();
    }

    private Path cacheFile(String key) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder("plan-");
        for (int i = 0; i < 8; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return cacheDir.resolve(hex.append(".json").toString());
    }

    private GtaWeek fromCache(Persona persona) {
        if (cacheDir == null) {
            return null;
        }
        try {
            String key = cacheKey(persona);
            Path file = cacheFile(key);
            if (!Files.exists(file)) {
                return null;
            }
            CacheEntry entry = mapper.readValue(file.toFile(), CacheEntry.class);
            return key.equals(entry.key()) ? entry.week() : null;
        } catch (Exception e) {
            return null; // Cache ist optional – im Zweifel frisch anfragen
        }
    }

    private void toCache(Persona persona, GtaWeek week) {
        if (cacheDir == null) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            String key = cacheKey(persona);
            mapper.writeValue(cacheFile(key).toFile(), new CacheEntry(key, week));
        } catch (Exception e) {
            // Cache ist optional – Fehler beim Schreiben ignorieren
        }
    }

    // --- LLM ------------------------------------------------------------------

    private static final String SYSTEM = """
            Du bist ein Mobilitaetsverhaltens-Modell fuer Deutschland (Stil der MiD-2017-Erhebung).
            Fuer die beschriebene Person erzeugst du typische AUSSERHAEUSIGE Aktivitaeten eines normalen
            Werktags und eines Wochenendtags. Antworte AUSSCHLIESSLICH als JSON-Objekt der Form:
            {"weekday":[[{"type":"WORK","duration_min":480}]],"weekend":[[{"type":"LEISURE","duration_min":120}]]}
            weekday und weekend sind Listen von TOUREN. Eine Tour ist eine Liste aufeinanderfolgender
            Aktivitaeten zwischen zwei Aufenthalten zu Hause. Erlaubte type-Werte: WORK, EDUCATION,
            SHOPPING, LEISURE, TRANSPORT. Liste KEINE HOME-Aktivitaet (Zuhause ist implizit). duration_min
            in Minuten, realistisch.
            WICHTIG fuer realistische Haeufigkeit (MiD 2017: im Schnitt nur ~3 Wege pro Person und Tag):
            Ein typischer Werktag hat GENAU EINE Haupttour (z.B. Arbeit oder Schule, oft mit einem kurzen
            Einkauf oder Erledigung auf dem Heimweg in derselben Tour); nur gelegentlich eine kurze zweite
            Tour am Abend. Ein typischer Wochenendtag hat oft GAR KEINE Tour (leere Liste []) oder nur eine
            einzige. Fuelle NICHT jeden Tag mit mehreren Touren. Maximal 2 Touren pro Tag, maximal 3
            Aktivitaeten pro Tour.""";

    private GtaWeek requestPlan(Persona persona) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        // Reproduzierbarkeit: gleicher Szenario-Seed → identische Pläne; Persona-Hash variiert je Signatur
        body.put("seed", baseSeed * 31 + persona.hashCode());
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "Person: " + persona.ageBand() + ", " + employmentLabel(persona.employment())
                + ", " + persona.gender() + ". Erzeuge weekday- und weekend-Touren.");
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
        JsonNode plan = mapper.readTree(stripFences(content));
        return new GtaWeek(parseTours(plan.path("weekday")), parseTours(plan.path("weekend")));
    }

    /** {weekday|weekend} → list of tours; defensive against malformed model output. */
    private List<List<GtaActivity>> parseTours(JsonNode toursNode) {
        List<List<GtaActivity>> tours = new ArrayList<>();
        if (!toursNode.isArray()) {
            return tours;
        }
        for (JsonNode tourNode : toursNode) {
            if (!tourNode.isArray() || tours.size() >= 2) {
                continue;
            }
            List<GtaActivity> tour = new ArrayList<>();
            for (JsonNode act : tourNode) {
                if (tour.size() >= 3) {
                    break;
                }
                String type = act.path("type").asText("").trim().toUpperCase();
                int dur = act.path("duration_min").asInt(0);
                if (!type.isEmpty() && dur > 0) {
                    tour.add(new GtaActivity(type, dur));
                }
            }
            if (!tour.isEmpty()) {
                tours.add(tour);
            }
        }
        return tours;
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t;
    }

    // --- Persona-Ableitung ----------------------------------------------------

    private static String ageBand(int age) {
        if (age < 11) return "Grundschulkind (6-10 Jahre)";
        if (age < 18) return "Jugendliche Person (11-17 Jahre)";
        if (age < 26) return "junge erwachsene Person (18-25 Jahre)";
        if (age < 65) return "erwachsene Person (26-64 Jahre)";
        return "Person im Ruhestandsalter (65+ Jahre)";
    }

    private static String gender(String raw) {
        return "FEMALE".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? "weiblich" : "maennlich";
    }

    private static String employmentLabel(String employment) {
        return switch (employment == null ? "" : employment.trim().toUpperCase()) {
            case "FULLTIME" -> "vollzeitbeschaeftigt";
            case "PARTTIME" -> "teilzeitbeschaeftigt";
            case "EDUCATION" -> "in schulischer Ausbildung";
            case "STUDENT" -> "studierend";
            case "HOMEKEEPER" -> "im Haushalt taetig";
            case "RETIRED" -> "im Ruhestand";
            default -> "nicht erwerbstaetig";
        };
    }
}
