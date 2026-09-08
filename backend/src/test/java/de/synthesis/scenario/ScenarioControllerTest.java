package de.synthesis.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioControllerTest {

    private final SynthesisProperties properties = new SynthesisProperties();
    private final ScenarioController controller = new ScenarioController(null, properties);

    @Test
    void rejectsMissingScenarioSectionsAsBadRequest() {
        Scenario scenario = validScenario();
        scenario.zoning = null;

        assertBadRequest(scenario, "Zonierungsparameter fehlen");

        scenario = validScenario();
        scenario.population = null;

        assertBadRequest(scenario, "Bevölkerungsparameter fehlen");

        scenario = validScenario();
        scenario.logistics = null;

        assertBadRequest(scenario, "Logistikparameter fehlen");
    }

    @Test
    void rejectsUnknownBehaviorSource() {
        Scenario scenario = validScenario();
        scenario.population.behaviorSource = "unknown";

        assertBadRequest(scenario, "Unbekannte Verhaltensquelle");
    }

    @Test
    void rejectsUnknownTargetModel() {
        Scenario scenario = validScenario();
        scenario.targetModels = List.of("logitopp", "unknown");

        assertBadRequest(scenario, "Unbekanntes Zielmodell");

        scenario = validScenario();
        scenario.zoning.mode = "unknown";

        assertBadRequest(scenario, "Unbekannter Zonierungsmodus");
    }

    @Test
    void rejectsNonFiniteCoordinatesAndInvalidSamplingFraction() {
        Scenario scenario = validScenario();
        scenario.region.minLat = Double.NaN;

        assertBadRequest(scenario, "Region enthält ungültige Koordinaten");

        scenario = validScenario();
        scenario.population.samplingFraction = 1.1;

        assertBadRequest(scenario, "Stichprobenanteil muss größer 0 und höchstens 1 sein");
    }

    @Test
    void defaultsExposeDisabledReturnDemand() {
        Scenario defaults = controller.defaults();

        assertEquals("grid", defaults.zoning.mode);
        assertEquals(100.0, defaults.zoning.cellSizeMeters);
        assertEquals(600, defaults.zoning.maxZones);
        assertFalse(defaults.population.correctDistanceDecay,
                "Experimenteller Distanz-Abkling-Hebel muss standardmäßig aus sein");
        assertNotNull(defaults.logistics.returns);
        assertFalse(defaults.logistics.returns.enabled);
        assertEquals("exogenous-share", defaults.logistics.returns.model);
        assertEquals(0.0, defaults.logistics.returns.shareOfPrivateOrders);
        assertEquals(1.0, defaults.logistics.returns.pickupShares.home);
        assertEquals(0.0, defaults.logistics.returns.pickupShares.work);
    }

    @Test
    void rejectsMissingOrUnknownReturnContract() {
        Scenario scenario = validScenario();
        scenario.logistics.returns = null;

        assertBadRequest(scenario, "Retourenparameter fehlen");

        scenario = validScenario();
        scenario.logistics.returns.model = "linked-lifecycle";

        assertBadRequest(scenario, "Unbekanntes Retourenmodell");
    }

    @Test
    void acceptsAlignedGridAndRejectsUnsafeZoneLimit(@TempDir Path tempDir) {
        properties.setDataDir(tempDir.toString());
        ScenarioRepository repository = new ScenarioRepository(
                new ObjectMapper().findAndRegisterModules(), properties);
        ScenarioController realController = new ScenarioController(repository, properties);
        Scenario scenario = validScenario();
        scenario.zoning.mode = "epsg3035";
        scenario.zoning.cellSizeMeters = 700;
        scenario.zoning.maxZones = 1200;

        assertEquals(HttpStatus.CREATED, realController.create(scenario).getStatusCode());

        scenario = validScenario();
        scenario.zoning.maxZones = 1201;
        assertBadRequest(scenario, "Maximale Zonenanzahl muss zwischen 100 und 1200 liegen");
    }

    @Test
    void rejectsInvalidReturnShare() {
        Scenario scenario = validScenario();
        scenario.logistics.returns.shareOfPrivateOrders = Double.NaN;

        assertBadRequest(scenario, "Retourenquote muss zwischen 0 und 1 liegen");

        scenario = enabledReturns();
        scenario.logistics.returns.shareOfPrivateOrders = 0;

        assertBadRequest(scenario, "Aktivierte Retouren benötigen eine Quote größer 0");

        scenario = validScenario();
        scenario.logistics.returns.shareOfPrivateOrders = 1.1;

        assertBadRequest(scenario, "Retourenquote muss zwischen 0 und 1 liegen");
    }

    @Test
    void rejectsInvalidReturnPickupShares() {
        Scenario scenario = validScenario();
        scenario.logistics.returns.pickupShares = null;

        assertBadRequest(scenario, "Retouren-Abholortanteile fehlen");

        scenario = validScenario();
        scenario.logistics.returns.pickupShares.home = -0.1;
        scenario.logistics.returns.pickupShares.work = 1.1;

        assertBadRequest(scenario,
                "Retouren-Abholortanteile müssen endlich und nicht negativ sein");

        scenario = validScenario();
        scenario.logistics.returns.pickupShares.work = Double.POSITIVE_INFINITY;

        assertBadRequest(scenario,
                "Retouren-Abholortanteile müssen endlich und nicht negativ sein");

        scenario = validScenario();
        scenario.logistics.returns.pickupShares.home = 0.8;
        scenario.logistics.returns.pickupShares.work = 0.3;

        assertBadRequest(scenario, "Retouren-Abholortanteile müssen zusammen 1 ergeben");
    }

    @Test
    void enabledReturnsRequireLogitopp() {
        Scenario scenario = enabledReturns();
        scenario.targetModels = List.of("mobilecity");

        assertBadRequest(scenario, "Aktivierte Retouren benötigen das Zielmodell logiTopp");
    }

    @Test
    void acceptsAndPersistsValidEnabledReturns(@TempDir Path tempDir) {
        SynthesisProperties properties = new SynthesisProperties();
        properties.setDataDir(tempDir.toString());
        ScenarioRepository repository = new ScenarioRepository(
                new ObjectMapper().findAndRegisterModules(), properties);
        ScenarioController realController = new ScenarioController(repository, properties);
        Scenario scenario = enabledReturns();
        scenario.logistics.returns.shareOfPrivateOrders = 0.35;
        scenario.logistics.returns.pickupShares.home = 0.75;
        scenario.logistics.returns.pickupShares.work = 0.25;

        var response = realController.create(scenario);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Scenario created = response.getBody();
        assertNotNull(created);
        Scenario persisted = repository.findById(created.id).orElseThrow();
        assertTrue(persisted.logistics.returns.enabled);
        assertEquals(0.35, persisted.logistics.returns.shareOfPrivateOrders);
        assertEquals(0.75, persisted.logistics.returns.pickupShares.home);
        assertEquals(0.25, persisted.logistics.returns.pickupShares.work);
    }

    @Test
    void sumoAutomaticallyAddsAndPersistsLogitoppDependency(@TempDir Path tempDir) {
        SynthesisProperties localProperties = new SynthesisProperties();
        localProperties.setDataDir(tempDir.toString());
        ScenarioRepository repository = new ScenarioRepository(
                new ObjectMapper().findAndRegisterModules(), localProperties);
        ScenarioController realController = new ScenarioController(repository, localProperties);
        Scenario scenario = validScenario();
        scenario.targetModels = List.of("sumo");

        Scenario created = realController.create(scenario).getBody();

        assertNotNull(created);
        assertEquals(List.of("logitopp", "sumo"), created.targetModels);
        assertEquals(List.of("logitopp", "sumo"),
                repository.findById(created.id).orElseThrow().targetModels);
    }

    private void assertBadRequest(Scenario scenario, String reason) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.create(scenario));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(reason, exception.getReason());
    }

    private static Scenario validScenario() {
        Scenario scenario = new Scenario();
        scenario.region = new Scenario.Region();
        scenario.region.minLat = 48.8;
        scenario.region.minLon = 8.1;
        scenario.region.maxLat = 48.9;
        scenario.region.maxLon = 8.2;
        return scenario;
    }

    private static Scenario enabledReturns() {
        Scenario scenario = validScenario();
        scenario.logistics.returns.enabled = true;
        scenario.logistics.returns.shareOfPrivateOrders = 0.2;
        return scenario;
    }
}
