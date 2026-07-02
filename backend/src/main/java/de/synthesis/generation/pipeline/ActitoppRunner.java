package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the pipeline to KIT actiTopp (actitoppNG): writes the synthesized
 * persons as an input CSV, invokes the {@code runSynthesis} Gradle task in the
 * actitoppNG project and returns the generated weekly schedule CSV
 * (personId;activityType;startMinute;durationMinute). Calibrated activity
 * scheduling replaces the simple template generator when enabled.
 */
public class ActitoppRunner {

    public record Result(Path scheduleCsv, long persons) {
    }

    private final Path projectDir;
    private final int timeoutMinutes;

    public ActitoppRunner(SynthesisProperties.Actitopp config) {
        this.projectDir = Path.of(config.getProjectDir()).toAbsolutePath().normalize();
        this.timeoutMinutes = config.getTimeoutMinutes();
    }

    public boolean isAvailable() {
        return Files.exists(projectDir.resolve("gradlew"))
                && Files.exists(projectDir.resolve("build.gradle.kts"));
    }

    public Path projectDir() {
        return projectDir;
    }

    /**
     * Runs actiTopp for the persons in {@code personCsv} (the pipeline's person.csv)
     * and returns the schedule CSV under {@code workDir}.
     */
    public Result run(Path personCsv, Path workDir) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path input = workDir.resolve("actitopp-input.csv");
        Path output = workDir.resolve("actitopp-schedule.csv");
        long persons = writeInput(personCsv, input);

        Path logFile = workDir.resolve("actitopp.log");
        ProcessBuilder builder = new ProcessBuilder("sh", "gradlew", "--quiet", "runSynthesis",
                "-Pinput=" + input.toAbsolutePath(),
                "-Poutput=" + output.toAbsolutePath());
        builder.directory(projectDir.toFile());
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
        return new Result(output, persons);
    }

    /** person.csv → actiTopp input (personId;age;employment;gender). */
    private long writeInput(Path personCsv, Path input) throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(personCsv, StandardCharsets.UTF_8);
             Writer writer = Files.newBufferedWriter(input)) {
            writer.write("personId;age;employment;gender\n");
            reader.readLine(); // header: personId;personNumber;householdId;age;employment;gender;...
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", -1);
                writer.write(c[0] + ";" + c[3] + ";" + c[4] + ";" + c[5] + "\n");
                count++;
            }
        }
        return count;
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
