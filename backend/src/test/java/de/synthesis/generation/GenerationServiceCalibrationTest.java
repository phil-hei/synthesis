package de.synthesis.generation;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
