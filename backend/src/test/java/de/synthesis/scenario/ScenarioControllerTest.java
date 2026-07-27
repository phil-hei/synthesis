package de.synthesis.scenario;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioControllerTest {

    private final ScenarioController controller = new ScenarioController(null);

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
}
