package de.synthesis.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Repository
public class ScenarioRepository {

    private static final Pattern SCENARIO_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final ObjectMapper mapper;
    private final Path scenariosDir;

    public ScenarioRepository(ObjectMapper mapper, SynthesisProperties properties) {
        this.mapper = mapper;
        this.scenariosDir = Path.of(properties.getDataDir(), "scenarios").toAbsolutePath().normalize();
    }

    public Path scenarioDir(String id) {
        if (id == null || !SCENARIO_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Ungültige Szenario-ID");
        }
        Path dir = scenariosDir.resolve(id).normalize();
        if (!dir.startsWith(scenariosDir)) {
            throw new IllegalArgumentException("Ungültige Szenario-ID");
        }
        return dir;
    }

    public Path artifactsDir(String id) {
        return scenarioDir(id).resolve("artifacts");
    }

    public Path uploadsDir(String id) {
        return scenarioDir(id).resolve("uploads");
    }

    public Scenario save(Scenario scenario) {
        try {
            Path dir = scenarioDir(scenario.id);
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("scenario.json").toFile(), scenario);
            return scenario;
        } catch (IOException e) {
            throw new UncheckedIOException("Szenario konnte nicht gespeichert werden: " + scenario.id, e);
        }
    }

    public Optional<Scenario> findById(String id) {
        Path file = scenarioDir(id).resolve("scenario.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Scenario scenario = mapper.readValue(file.toFile(), Scenario.class);
            // Alte Szenarien hatten nur useActitopp. Nach dem Lesen auf den neuen Vertrag
            // normalisieren, damit API und nächster Save ausschließlich behaviorSource nutzen.
            scenario.population.behaviorSource = scenario.population.behaviorSourceEffective();
            scenario.normalizeTargetDependencies();
            return Optional.of(scenario);
        } catch (IOException e) {
            throw new UncheckedIOException("Szenario konnte nicht gelesen werden: " + id, e);
        }
    }

    public List<Scenario> findAll() {
        if (!Files.exists(scenariosDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(scenariosDir)) {
            List<Scenario> scenarios = new ArrayList<>();
            dirs.filter(Files::isDirectory)
                    .map(dir -> findById(dir.getFileName().toString()))
                    .forEach(s -> s.ifPresent(scenarios::add));
            scenarios.sort(Comparator.comparing((Scenario s) -> s.createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return scenarios;
        } catch (IOException e) {
            throw new UncheckedIOException("Szenarien konnten nicht aufgelistet werden", e);
        }
    }

    public void delete(String id) {
        Path dir = scenarioDir(id);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Szenario konnte nicht gelöscht werden: " + id, e);
        }
    }
}
