package de.synthesis.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonControllerTest {

    @TempDir
    Path dataDir;

    @Test
    void returnsExistingScenariosInRequestOrderWithParsedKpis() throws Exception {
        TestContext context = context();
        saveScenario(context.repository(), "first", "Erstes Szenario");
        saveScenario(context.repository(), "second", "Zweites Szenario");
        writeKpis(context.repository(), "first", "{\"demand\":{\"trips\":42}}");

        ArrayNode result = context.controller().compare(List.of("second", "missing", "first"));

        assertEquals(2, result.size());
        assertEquals("second", result.get(0).path("id").asText());
        assertFalse(result.get(0).path("simulated").asBoolean());
        assertEquals("first", result.get(1).path("id").asText());
        assertTrue(result.get(1).path("simulated").asBoolean());
        assertEquals(42, result.get(1).path("kpis").path("demand").path("trips").asInt());
    }

    @Test
    void doesNotClaimSimulationForMalformedKpis() throws Exception {
        TestContext context = context();
        saveScenario(context.repository(), "broken", "Beschädigtes Ergebnis");
        writeKpis(context.repository(), "broken", "kein-json");

        ArrayNode result = context.controller().compare(List.of("broken"));

        assertEquals(1, result.size());
        assertTrue(result.get(0).path("kpis").isNull());
        assertFalse(result.get(0).path("simulated").asBoolean());
    }

    private TestContext context() {
        SynthesisProperties properties = new SynthesisProperties();
        properties.setDataDir(dataDir.toString());
        ObjectMapper mapper = new ObjectMapper();
        ScenarioRepository repository = new ScenarioRepository(mapper, properties);
        return new TestContext(repository, new ComparisonController(repository, mapper));
    }

    private static void saveScenario(ScenarioRepository repository, String id, String name) {
        Scenario scenario = new Scenario();
        scenario.id = id;
        scenario.name = name;
        repository.save(scenario);
    }

    private static void writeKpis(ScenarioRepository repository, String id, String content)
            throws Exception {
        Path simulation = repository.artifactsDir(id).resolve("simulation");
        Files.createDirectories(simulation);
        Files.writeString(simulation.resolve("kpis.json"), content);
    }

    private record TestContext(ScenarioRepository repository, ComparisonController controller) {
    }
}
