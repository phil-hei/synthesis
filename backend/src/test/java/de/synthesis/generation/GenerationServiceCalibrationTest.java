package de.synthesis.generation;

import de.synthesis.generation.pipeline.GridZoning;
import de.synthesis.generation.pipeline.InspireGridZoning;
import de.synthesis.generation.pipeline.Zone;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationServiceCalibrationTest {

    @TempDir
    Path tempDir;

    @Test
    void activeModeDistanceCalibrationWinsOverLegacyModeChoiceFlag() throws Exception {
        Path simulationDir = Files.createDirectories(tempDir.resolve("simulation"));
        Path modeChoice = Files.createFile(
                simulationDir.resolve("mode_choice_calibrated.txt"));
        Path modeDistance = Files.createFile(
                simulationDir.resolve("mode_distance_calibrated.txt"));
        Scenario scenario = new Scenario();
        scenario.population.calibrateModeChoice = true;
        scenario.population.calibrateModeDistance = true;

        assertEquals(
                modeDistance,
                GenerationService.modeChoiceCalibrationOverride(scenario, simulationDir));

        scenario.population.calibrateModeDistance = false;
        assertEquals(
                modeChoice,
                GenerationService.modeChoiceCalibrationOverride(scenario, simulationDir));
    }

    @Test
    void disabledFlagsIgnoreStaleCalibrationArtifacts() throws Exception {
        Path simulationDir = Files.createDirectories(tempDir.resolve("simulation"));
        Files.createFile(simulationDir.resolve("mode_choice_calibrated.txt"));
        Files.createFile(simulationDir.resolve("mode_distance_calibrated.txt"));
        Files.createDirectories(simulationDir.resolve("dc-calibrated"));

        Scenario scenario = new Scenario();

        assertNull(GenerationService.modeChoiceCalibrationOverride(scenario, simulationDir));
        assertNull(GenerationService.destinationChoiceCalibrationOverride(
                scenario, simulationDir));
    }

    @Test
    void activeDistanceCalibrationIsForwardedToNetworkFeedback() throws Exception {
        Path simulationDir = Files.createDirectories(tempDir.resolve("simulation"));
        Path destinationChoice = Files.createDirectories(
                simulationDir.resolve("dc-calibrated"));
        Scenario scenario = new Scenario();
        scenario.population.calibrateDistance = true;

        assertEquals(
                destinationChoice,
                GenerationService.destinationChoiceCalibrationOverride(
                        scenario, simulationDir));
    }

    @Test
    void distanceCalibrationRequiresIntrazonalValueInsideNearBand() {
        Scenario scenario = new Scenario();
        scenario.population.calibrateDistance = true;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GenerationService.requireObservableDistanceCalibration(
                        scenario, gridZones(2300), true));
        assertTrue(error.getMessage().contains("1.62 km"));
        assertTrue(error.getMessage().contains("Zellgröße verkleinern"));

        assertDoesNotThrow(() -> GenerationService.requireObservableDistanceCalibration(
                scenario, gridZones(1000), true));

        scenario.population.calibrateDistance = false;
        assertDoesNotThrow(() -> GenerationService.requireObservableDistanceCalibration(
                scenario, gridZones(2300), true));
    }

    @Test
    void inactiveDistanceCalibrationDoesNotConstrainZoning() {
        Scenario scenario = new Scenario();
        scenario.population.calibrateDistance = true;
        scenario.targetModels = List.of("mobilecity");

        assertDoesNotThrow(() -> GenerationService.requireObservableDistanceCalibration(
                scenario, gridZones(2300), true));

        scenario.targetModels = List.of("logitopp");
        assertDoesNotThrow(() -> GenerationService.requireObservableDistanceCalibration(
                scenario, gridZones(2300), false));
    }

    @Test
    void fineLargeAreaKeepsDistanceCalibrationObservable() {
        Scenario scenario = new Scenario();
        scenario.population.calibrateDistance = true;

        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.613;
        region.minLon = 7.825;
        region.maxLat = 49.107;
        region.maxLon = 8.575;

        int resolution = InspireGridZoning.pickResolution(region, 1000, 3600);
        List<Zone> zones = InspireGridZoning.generate(region, resolution, 3600);

        assertEquals(1000, resolution,
                "55-km-Großraum muss für den Spike im beobachtbaren 1-km-Raster bleiben");
        assertEquals(3249, zones.size(),
                "präregistrierte Region muss ein stabiles 57×57-INSPIRE-Gitter liefern");
        assertDoesNotThrow(() -> GenerationService.requireObservableDistanceCalibration(
                scenario, zones, true));
    }

    private static List<Zone> gridZones(double cellSizeMeters) {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.85;
        region.minLon = 8.20;
        region.maxLat = 48.87;
        region.maxLon = 8.23;
        return GridZoning.generate(region, cellSizeMeters, 600);
    }
}
