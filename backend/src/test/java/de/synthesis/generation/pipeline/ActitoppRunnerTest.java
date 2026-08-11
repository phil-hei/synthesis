package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActitoppRunnerTest {

    @TempDir
    Path tempDir;

    private ActitoppRunner runner(Path project) {
        SynthesisProperties.Actitopp config = new SynthesisProperties.Actitopp();
        config.setProjectDir(project.toString());
        return new ActitoppRunner(config);
    }

    @Test
    void availabilityRequiresRunnerSourceAndVersionedContract() throws Exception {
        Path project = tempDir.resolve("actitoppNG");
        Files.createDirectories(project);
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh\n");
        Files.writeString(project.resolve("build.gradle.kts"), "// test\n");
        ActitoppRunner runner = runner(project);

        assertFalse(runner.isAvailable(), "Ein bloßer Gradle-Clone ist noch kein SyntheSis-Runner");
        assertThrows(java.io.IOException.class, runner::verifyContract);

        Path source = project.resolve("src/main/kotlin/SynthesisRunner.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package test\n");
        Files.writeString(project.resolve("synthesis-contract.properties"),
                "contractVersion=old\n");
        assertFalse(runner.isAvailable(), "Ein unbekannter Vertrag darf nicht ausgeführt werden");
        assertThrows(java.io.IOException.class, runner::verifyContract);

        Files.writeString(project.resolve("synthesis-contract.properties"),
                "contractVersion=" + ActitoppRunner.CONTRACT + "\n");
        assertFalse(runner.isAvailable(), "Der Vertragsmarker ersetzt nicht den Gradle-Task");

        Files.writeString(project.resolve("build.gradle.kts"),
                "tasks.register<JavaExec>(\"runSynthesis\")\n");
        assertTrue(runner.isAvailable());
        assertDoesNotThrow(runner::verifyContract);
    }
}
