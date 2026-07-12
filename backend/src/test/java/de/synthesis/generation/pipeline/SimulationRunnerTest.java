package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifiziert die Parameter-Overrides in der logiTopp-Run-Config OHNE echten Gradle-Lauf
 * (writeRunConfig ist package-private): der Zielwahl-Override (Distanz-Kampagne) und der
 * bestehende Mode-Choice-Override (Modal-Split-Kalibrierung) landen an den richtigen
 * Stellen, und {@code null} erhält das Standardverhalten (Projekt-Standarddateien).
 */
class SimulationRunnerTest {

    private Path[] prepare() throws Exception {
        Path tmp = Files.createTempDirectory("simrun-test");
        Path artifacts = tmp.resolve("artifacts");
        Path simDir = artifacts.resolve("simulation");
        Path resultsDir = simDir.resolve("results");
        Files.createDirectories(resultsDir);
        return new Path[]{tmp, artifacts, simDir, resultsDir};
    }

    private Scenario scenario() {
        Scenario s = new Scenario();
        s.population.seed = 42;
        s.population.samplingFraction = 0.05;
        return s;
    }

    @Test
    void defaultsUseSharedProjectFiles() throws Exception {
        Path[] p = prepare();
        SimulationRunner runner = new SimulationRunner(new SynthesisProperties.Simulation());
        Path config = runner.writeRunConfig(scenario(), p[1], p[2], p[3], null, null);
        String yaml = Files.readString(config);

        assertTrue(yaml.contains(
                "base: config/shared/parameters/destination_choice_utility_calculation_parameters.txt"),
                "Ohne Override zeigt die Zielwahl-Basis auf die Projekt-Standarddatei");
        assertTrue(yaml.contains(
                "shopping: config/shared/parameters/destination_choice_parameters_SHOPPING.txt"),
                "Ohne Override zeigt shopping auf die Projekt-Standarddatei");
        assertTrue(yaml.contains(
                "MNL: config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt"),
                "Ohne Override zeigt die MNL-Moduswahl auf die Projekt-Standarddatei");
    }

    @Test
    void destinationChoiceOverrideRewritesAllFivePaths() throws Exception {
        Path[] p = prepare();
        Path dcDir = p[0].resolve("dc-patched");
        SimulationRunner runner = new SimulationRunner(new SynthesisProperties.Simulation());
        Path config = runner.writeRunConfig(scenario(), p[1], p[2], p[3], null, dcDir);
        String yaml = Files.readString(config);

        String abs = dcDir.toAbsolutePath().toString();
        assertTrue(yaml.contains("base: " + abs + "/destination_choice_utility_calculation_parameters.txt"),
                "Override lenkt die Zielwahl-Basis in das gepatchte Verzeichnis (absolut)");
        assertTrue(yaml.contains("shopping: " + abs + "/destination_choice_parameters_SHOPPING.txt"),
                "Override lenkt shopping in das gepatchte Verzeichnis");
        assertTrue(yaml.contains("leisure: " + abs + "/destination_choice_parameters_LEISURE.txt"),
                "Override lenkt leisure in das gepatchte Verzeichnis");
        // Der Mode-Choice-Pfad bleibt Standard, wenn nur die Zielwahl überschrieben wird
        assertTrue(yaml.contains(
                "MNL: config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt"),
                "Zielwahl-Override lässt die Moduswahl unberührt");
    }

    @Test
    void modeChoiceOverrideStillHonored() throws Exception {
        Path[] p = prepare();
        Path mnl = p[0].resolve("mode_choice_calibrated.txt");
        SimulationRunner runner = new SimulationRunner(new SynthesisProperties.Simulation());
        Path config = runner.writeRunConfig(scenario(), p[1], p[2], p[3], mnl, null);
        String yaml = Files.readString(config);

        assertTrue(yaml.contains("MNL: " + mnl.toAbsolutePath()),
                "Der bestehende Mode-Choice-Override bleibt funktionsfähig");
        assertTrue(yaml.contains(
                "base: config/shared/parameters/destination_choice_utility_calculation_parameters.txt"),
                "Moduswahl-Override lässt die Zielwahl-Standarddateien unberührt");
    }
}
