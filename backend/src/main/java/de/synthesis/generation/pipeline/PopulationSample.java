package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A pool of realistic households drawn from a KIT-synthesized demographic sample
 * (real joint household-composition / age / sex structure from KIT
 * populationsynthesis, IPU). The pipeline samples whole households from this pool
 * per zone, giving the synthetic population realistic household compositions
 * instead of independent ad-hoc draws.
 *
 * <p>CSV format (header, ';'-separated): {@code householdId;age;sex} — one row per
 * member, sex code 1=male/2=female. This is exactly the flat output of the KIT
 * SynthesisSample runner; a Zensus-fitted output for a real region (same format)
 * can replace the bundled sample unchanged.
 */
public final class PopulationSample {

    public record Member(int age, String gender) {
    }

    private final List<List<Member>> households;

    private PopulationSample(List<List<Member>> households) {
        this.households = households;
    }

    /** Loads the bundled sample from the classpath; throws if the resource is missing. */
    public static PopulationSample fromResource(String resource) {
        try (InputStream in = PopulationSample.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Resource nicht gefunden: " + resource);
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("KIT-Populations-Sample nicht ladbar: " + resource, e);
        }
    }

    private static PopulationSample parse(InputStream in) throws IOException {
        Map<String, List<Member>> byHousehold = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                String gender = "2".equals(c[2].trim()) ? "FEMALE" : "MALE";
                byHousehold.computeIfAbsent(c[0], k -> new ArrayList<>())
                        .add(new Member(Integer.parseInt(c[1].trim()), gender));
            }
        }
        return new PopulationSample(new ArrayList<>(byHousehold.values()));
    }

    public int householdCount() {
        return households.size();
    }

    /** A random household composition from the pool. */
    public List<Member> randomHousehold(Random random) {
        return households.get(random.nextInt(households.size()));
    }
}
