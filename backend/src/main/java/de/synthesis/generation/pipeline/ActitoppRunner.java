package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the pipeline to KIT actiTopp (actitoppNG): writes the synthesized
 * persons as an input CSV, invokes a separate SyntheSis adapter with unchanged
 * actiTopp core sources and returns the generated weekly schedule CSV
 * (personId;activityType;startMinute;durationMinute). Calibrated activity
 * scheduling replaces the simple template generator when enabled.
 */
public class ActitoppRunner {

    static final String CONTRACT = "synthesis-actitopp-households-v2";
    private static final Path LAUNCHER = Path.of("bin/synthesis-actitopp-adapter");
    private static final Path CONTRACT_FILE = Path.of("synthesis-contract.properties");

    public record Result(Path scheduleCsv, long persons, long households, long cars) {
    }

    private final Path projectDir;
    private final int timeoutMinutes;

    public ActitoppRunner(SynthesisProperties.Actitopp config) {
        this.projectDir = Path.of(config.getProjectDir()).toAbsolutePath().normalize();
        this.timeoutMinutes = config.getTimeoutMinutes();
    }

    public boolean isAvailable() {
        if (!Files.isRegularFile(projectDir.resolve(LAUNCHER))) return false;
        try {
            verifyContract();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Path projectDir() {
        return projectDir;
    }

    /**
     * Runs actiTopp for the persons in {@code personCsv} (the pipeline's person.csv)
     * and returns the schedule CSV under {@code workDir}.
     */
    public Result run(Path personCsv, Path householdCsv, Path carCsv, String areaType, Path workDir) throws IOException, InterruptedException {
        verifyContract();
        Files.createDirectories(workDir);
        Path input = workDir.resolve("actitopp-input.csv");
        Path output = workDir.resolve("actitopp-schedule.csv");
        ActitoppInput.Counts counts = ActitoppInput.write(personCsv, householdCsv, carCsv, areaType, input);

        Files.deleteIfExists(output);
        Files.deleteIfExists(workDir.resolve("actitopp-households.csv"));
        Path logFile = workDir.resolve("actitopp.log");
        ProcessBuilder builder = new ProcessBuilder("sh", projectDir.resolve(LAUNCHER).toString(),
                input.toAbsolutePath().toString(), output.toAbsolutePath().toString());
        builder.directory(projectDir.toFile());
        ExternalJavaToolchain.configure(builder);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("actiTopp nach " + timeoutMinutes + " min abgebrochen (Timeout)");
        }
        if (process.exitValue() != 0 || !Files.exists(output)) {
            throw new IOException("actiTopp fehlgeschlagen (Exit " + process.exitValue() + "): "
                    + lastLines(logFile));
        }
        return new Result(output, counts.persons(), counts.households(), counts.cars());
    }

    void verifyContract() throws IOException {
        Path marker = projectDir.resolve(CONTRACT_FILE);
        if (!Files.isRegularFile(marker)) {
            throw new IOException("actiTopp-Vertrag fehlt: " + marker
                    + " (scripts/build-actitopp-adapter.sh ausführen)");
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(marker)) {
            properties.load(in);
        }
        String actual = properties.getProperty("contractVersion", "");
        if (!CONTRACT.equals(actual)) {
            throw new IOException("Inkompatibler actiTopp-Vertrag: erwartet " + CONTRACT
                    + ", gefunden " + (actual.isBlank() ? "(leer)" : actual));
        }
        if (!Files.isRegularFile(projectDir.resolve(LAUNCHER))) {
            throw new IOException("Separater actiTopp-Adapter fehlt: " + projectDir.resolve(LAUNCHER));
        }
    }

    private static String lastLines(Path logFile) {
        try {
            var lines = Files.readAllLines(logFile);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
        } catch (IOException e) {
            return "(Log nicht lesbar)";
        }
    }
}
