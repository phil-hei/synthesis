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
    void rejectsUnknownActivityAreaType() {
        Scenario scenario = validScenario();
        scenario.population.areaType = "unknown";
        assertBadRequest(scenario, "Ungültiger Raumtyp für die Aktivitätenplanung");
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

        assertEquals("epsg3035", defaults.zoning.mode);
        assertEquals(100.0, defaults.zoning.cellSizeMeters);
        assertEquals(600, defaults.zoning.maxZones);
        assertFalse(defaults.population.correctDistanceDecay,
                "Experimenteller Distanz-Abkling-Hebel muss standardmäßig aus sein");
        assertFalse(defaults.mobileCity.validateNative,
                "Native MobileCity-Prüfung muss ausdrücklich angefordert werden");
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

        scenario = validScenario();
        scenario.targetModels = List.of("mobilecity");
        scenario.mobileCity.regionalExport = true;
        scenario.mobileCity.electricShare = 0.0;
        scenario.mobileCity.allowSyntheticTransitFallback = false;
        scenario.mobileCity.transitVehiclesPer1000 = 0;
        assertEquals(HttpStatus.CREATED, realController.create(scenario).getStatusCode());
    }

    @Test
    void validatesSyntheticTransitFleetOnlyWhenFallbackIsEnabled() {
        Scenario scenario = validScenario();
        scenario.mobileCity.regionalExport = true;
        scenario.mobileCity.electricShare = 0.0;
        scenario.mobileCity.transitVehiclesPer1000 = 0;

        assertBadRequest(scenario,
                "Modellierte ÖV-Flotte muss größer als 0 und höchstens 100 Fahrzeuge je 1000 Einwohner sein");
    }

    @Test
    void rejectsInvalidManualChargingCapacity() {
        Scenario scenario = validScenario();
        scenario.mobileCity.chargingPointsPer1000 = Double.NaN;

        assertBadRequest(scenario,
                "MobileCity-Ladepunkte müssen zwischen 0 und 100 je 1000 Einwohner liegen");
    }

    @Test
    void completeMobileCityPackageRequiresAnExplicitBevSource(@TempDir Path tempDir) {
        Scenario scenario = validScenario();
        scenario.mobileCity.regionalExport = true;

        assertBadRequest(scenario,
                "MobileCity-Regionalpaket benötigt regionale KBA-Daten oder eine explizite BEV-Annahme; für einen rudimentären Export ist 0 zulässig");

        scenario.mobileCity.electricShare = 0.0;
        scenario.mobileCity.allowSyntheticTransitFallback = true;
        SynthesisProperties localProperties = new SynthesisProperties();
        localProperties.setDataDir(tempDir.toString());
        ScenarioRepository repository = new ScenarioRepository(
                new ObjectMapper().findAndRegisterModules(), localProperties);
        assertEquals(HttpStatus.CREATED,
                new ScenarioController(repository, localProperties).create(scenario).getStatusCode());
    }

    @Test
    void rejectsInvalidManualCarsharingAssumptions() {
        Scenario scenario = validScenario();
        scenario.mobileCity.carsharingVehiclesPer1000 = -0.1;

        assertBadRequest(scenario,
                "MobileCity-Carsharing-Flotte muss zwischen 0 und 100 Fahrzeugen je 1000 Einwohner liegen");

        scenario = validScenario();
        scenario.mobileCity.carsharingMemberShare = 0.4001;

        assertBadRequest(scenario,
                "MobileCity-Carsharing-Mitgliederanteil muss zwischen 0 und 0,4 liegen");
    }

    @Test
    void validatesMobileCitySurroundingAssumptionsAsACompleteSet() {
        Scenario scenario = validScenario();
        scenario.mobileCity.surroundingPopulation = 10_000;

        assertBadRequest(scenario,
                "MobileCity-Umlandanteil für Bildungswege muss zwischen 0 und 1 liegen");

        scenario.mobileCity.surroundingEducationTripShare = 0.2;
        scenario.mobileCity.surroundingWorkTripShare = 1.1;
        scenario.mobileCity.surroundingShoppingTripShare = 0.1;
        scenario.mobileCity.surroundingLeisureTripShare = 0.05;
        assertBadRequest(scenario,
                "MobileCity-Umlandanteil für Arbeitswege muss zwischen 0 und 1 liegen");

        scenario.mobileCity.surroundingWorkTripShare = 0.4;
        scenario.population.externalTrips = false;
        assertBadRequest(scenario,
                "MobileCity-Umlandbevölkerung benötigt aktivierte Außenbeziehungen");

        scenario = validScenario();
        scenario.mobileCity.surroundingWorkTripShare = 0.2;
        assertBadRequest(scenario,
                "MobileCity-Umlandanteile benötigen eine positive Umlandbevölkerung");
    }

    @Test
    void validatesAutomaticMobileCitySurroundingBufferAndShares() {
        Scenario scenario = validScenario();
        scenario.mobileCity.autoSurrounding = true;
        scenario.mobileCity.surroundingBufferKm = 0.5;
        assertBadRequest(scenario, "MobileCity-Umlandpuffer muss zwischen 1 und 100 km liegen");

        scenario.mobileCity.surroundingBufferKm = 10;
        assertBadRequest(scenario,
                "MobileCity-Umlandanteil für Bildungswege muss zwischen 0 und 1 liegen");

        scenario.mobileCity.surroundingEducationTripShare = 0.1;
        scenario.mobileCity.surroundingWorkTripShare = 0.4;
        scenario.mobileCity.surroundingShoppingTripShare = 0.15;
        scenario.mobileCity.surroundingLeisureTripShare = 0.08;
        scenario.population.externalTrips = false;
        assertBadRequest(scenario,
                "MobileCity-Umlandbevölkerung benötigt aktivierte Außenbeziehungen");
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
    void nativeMobileCityValidationRequiresCompleteSelectedPackage() {
        Scenario scenario = validScenario();
        scenario.mobileCity.validateNative = true;
        assertBadRequest(scenario,
                "Native MobileCity-Prüfung benötigt MobileCity und ein vollständiges Regional- oder Vorlagenpaket");

        scenario = validScenario();
        scenario.mobileCity.regionalExport = true;
        scenario.mobileCity.electricShare = 0.0;
        scenario.mobileCity.validateNative = true;
        scenario.targetModels = List.of("logitopp");
        assertBadRequest(scenario,
                "Native MobileCity-Prüfung benötigt MobileCity und ein vollständiges Regional- oder Vorlagenpaket");
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
