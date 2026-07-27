package de.synthesis.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
