package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifiziert die Parameter-Overrides in der logiTopp-Run-Config OHNE echten Gradle-Lauf
 * (writeRunConfig ist package-private): der Zielwahl-Override (Distanz-Kampagne) und der
 * bestehende Mode-Choice-Override (Modal-Split-Kalibrierung) landen an den richtigen
 * Stellen, und {@code null} erhält das Standardverhalten (Projekt-Standarddateien).
 */
class SimulationRunnerTest {

    @TempDir
    Path tempDir;

    private Path[] prepare() throws Exception {
        Path tmp = tempDir;
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
        assertTrue(yaml.contains("fractionOfPopulation: 1.0"),
                "Die bereits gesampelte Export-Population darf in logiTopp nicht erneut reduziert werden");
        assertTrue(yaml.contains("harnessContract: " + SimulationRunner.HARNESS_CONTRACT),
                "Die Run-Config fordert den versionierten Harness-Vertrag explizit an");
        assertTrue(yaml.contains("parcelDemandConfig: "
                        + p[1].toAbsolutePath() + "/logistics/parcel-demand-config.yaml"),
                "Die wirksame Paketkonfiguration wird an den Legacy-Harness übergeben");
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

    @Test
    void modeChoiceAndDestinationChoiceOverridesCanBeComposed() throws Exception {
        Path[] p = prepare();
        Path mnl = p[0].resolve("mode_distance_calibrated.txt");
        Path dcDir = p[0].resolve("dc-calibrated");
        SimulationRunner runner = new SimulationRunner(new SynthesisProperties.Simulation());
        Path config = runner.writeRunConfig(scenario(), p[1], p[2], p[3], mnl, dcDir);
        String yaml = Files.readString(config);

        String absDc = dcDir.toAbsolutePath().toString();
        assertTrue(yaml.contains("MNL: " + mnl.toAbsolutePath()),
                "Der kombinierte Lauf verwendet die kalibrierte MNL-Datei");
        assertTrue(yaml.contains(
                        "base: " + absDc + "/destination_choice_utility_calculation_parameters.txt"),
                "Der kombinierte Lauf verwendet zugleich die kalibrierte Zielwahl-Basis");
        assertTrue(yaml.contains(
                        "shopping: " + absDc + "/destination_choice_parameters_SHOPPING.txt"),
                "Der kombinierte Lauf lenkt auch die zweckspezifischen Zielwahlparameter um");
    }

    @Test
    void harnessContractFailsFastOnMissingOrWrongVersion() throws Exception {
        Path project = tempDir.resolve("legacy-project");
        Files.createDirectories(project.resolve("config/synthesis"));
        SynthesisProperties.Simulation config = new SynthesisProperties.Simulation();
        config.setProjectDir(project.toString());
        SimulationRunner runner = new SimulationRunner(config);

        assertThrows(java.io.IOException.class, runner::verifyHarnessContract,
                "Ein fehlender Marker darf nicht als kompatibler Harness gelten");

        Path marker = project.resolve("synthesis-contract.properties");
        Files.writeString(marker, "contractVersion=old-contract\n");
        assertThrows(java.io.IOException.class, runner::verifyHarnessContract,
                "Eine unbekannte Vertragsversion muss sichtbar fehlschlagen");

        Files.writeString(marker, "contractVersion=" + SimulationRunner.HARNESS_CONTRACT + "\n");
        assertDoesNotThrow(runner::verifyHarnessContract);
    }
    @Test
    void startsPrebuiltLauncherWithConfigAndRequiresContract() throws Exception {
        Path[] p = prepare();
        Path distribution = tempDir.resolve("adapter distribution");
        Files.createDirectories(distribution.resolve("bin"));
        SynthesisProperties.Simulation settings = new SynthesisProperties.Simulation();
        settings.setProjectDir(distribution.toString());
        SimulationRunner runner = new SimulationRunner(settings);
        assertFalse(runner.isAvailable());
        Files.writeString(distribution.resolve("synthesis-contract.properties"),
                "contractVersion=" + SimulationRunner.HARNESS_CONTRACT + "\n");
        Files.writeString(distribution.resolve("bin/synthesis-logitopp-adapter"), """
                #!/bin/sh
                test "$#" -eq 1 || exit 2
                test -f "$1" || exit 3
                test -f synthesis-contract.properties || exit 4
                cat "$1"
                """);
        assertTrue(runner.isAvailable());
        var result = runner.run(scenario(), p[1], message -> {});
        assertEquals(p[1].resolve("simulation/results"), result.resultsDir());
        assertTrue(Files.readString(p[1].resolve("simulation/run.log"))
                .contains("harnessContract: " + SimulationRunner.HARNESS_CONTRACT));
    }

}
