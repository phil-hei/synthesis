package de.synthesis.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioRepositoryTest {

    @Test
    void rejectsPathTraversalIds() throws Exception {
        Path dataDir = Files.createTempDirectory("scenario-repository");
        try {
            SynthesisProperties properties = new SynthesisProperties();
            properties.setDataDir(dataDir.toString());
            ScenarioRepository repository = new ScenarioRepository(new ObjectMapper(), properties);

            assertThrows(IllegalArgumentException.class, () -> repository.scenarioDir("../outside"));
            assertThrows(IllegalArgumentException.class, () -> repository.findById(".."));
            assertThrows(IllegalArgumentException.class, () -> repository.delete("../outside"));
            assertEquals(dataDir.toAbsolutePath().normalize().resolve("scenarios").resolve("safe-id"),
                    repository.scenarioDir("safe-id"));
        } finally {
            try (var paths = Files.walk(dataDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best effort in test cleanup.
                    }
                });
            }
        }
    }

    @Test
    void migratesLegacyActivityFlagToBehaviorSource() throws Exception {
        Path dataDir = Files.createTempDirectory("scenario-legacy");
        try {
            SynthesisProperties properties = new SynthesisProperties();
            properties.setDataDir(dataDir.toString());
            ObjectMapper mapper = new ObjectMapper();
            ScenarioRepository repository = new ScenarioRepository(mapper, properties);
            Path scenarioDir = repository.scenarioDir("legacy-template");
            Files.createDirectories(scenarioDir);
            Files.writeString(scenarioDir.resolve("scenario.json"), """
                    {
                      "id": "legacy-template",
                      "population": { "useActitopp": false }
                    }
                    """);

            Scenario migrated = repository.findById("legacy-template").orElseThrow();

            assertEquals("template", migrated.population.behaviorSource);
            assertEquals("template", migrated.population.behaviorSourceEffective());
            assertFalse(mapper.writeValueAsString(migrated).contains("useActitopp"));
        } finally {
            deleteRecursively(dataDir);
        }
    }

    @Test
    void explicitBehaviorSourceWinsOverLegacyFlag() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Scenario scenario = mapper.readValue("""
                {
                  "population": {
                    "behaviorSource": "gta",
                    "useActitopp": false
                  }
                }
                """, Scenario.class);

        assertEquals("gta", scenario.population.behaviorSourceEffective());
    }

    @Test
    void missingReturnConfigUsesBackwardCompatibleDisabledDefault() throws Exception {
        Scenario scenario = new ObjectMapper().readValue("""
                {
                  "logistics": {
                    "parcelsPerPersonWeek": 0.9
                  }
                }
                """, Scenario.class);

        assertFalse(scenario.logistics.returns.enabled);
        assertEquals("exogenous-share", scenario.logistics.returns.model);
        assertEquals(0.0, scenario.logistics.returns.shareOfPrivateOrders);
        assertEquals(1.0, scenario.logistics.returns.pickupShares.home);
        assertEquals(0.0, scenario.logistics.returns.pickupShares.work);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best effort in test cleanup.
                }
            });
        }
    }
}
