package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs the exported package through the real MobileCity ConfigTool in an isolated copy. */
public final class MobileCityValidationRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> NATIVE_LOGS = List.of(
            "build.log", "import.log", "network-validation.log", "simulation.log");

    private final SynthesisProperties.MobileCityValidation config;

    public MobileCityValidationRunner(SynthesisProperties.MobileCityValidation config) {
        this.config = config;
    }

    public record Result(List<Integer> years, List<String> errors, int nonFiniteCells,
                         Path report) {
        public Result {
            years = List.copyOf(years);
            errors = List.copyOf(errors);
        }
    }

    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    /** Null means available; otherwise a stable, user-facing reason for a SKIPPED stage. */
    public String unavailableReason() {
        if (!config.isEnabled()) return "betriebsweit deaktiviert";
        if (config.getTimeoutMinutes() < 1) return "Timeout muss mindestens eine Minute betragen";
        if (config.getYears() < 3) return "Prüfhorizont muss mindestens drei Jahre umfassen";
        Path script = path(config.getScript());
        if (script == null || !Files.isRegularFile(script)) return "Prüfskript fehlt: " + config.getScript();
        Path source = path(config.getToolSource());
        if (source == null || !Files.isDirectory(source)
                || !Files.isRegularFile(source.resolve("MobileCityConfigurationTool.csproj"))) {
            return "ConfigTool-Quellcode fehlt: " + config.getToolSource();
        }
        if (!commandAvailable(config.getPython())) return "Python nicht verfügbar: " + config.getPython();
        if (!commandAvailable(config.getDotnet())) return ".NET-Laufzeit nicht verfügbar: " + config.getDotnet();
        return null;
    }

    public Result run(Path artifacts, Consumer<String> log) throws IOException, InterruptedException {
        String unavailable = unavailableReason();
        if (unavailable != null) throw new IOException(unavailable);
        Path imports = artifacts.resolve("mobilecity").toAbsolutePath().normalize();
        if (!Files.isRegularFile(imports.resolve("summary.json"))) {
            throw new IOException("Vollständiges MobileCity-Paket fehlt: " + imports);
        }

        Files.createDirectories(artifacts);
        Path output = Files.createTempDirectory(artifacts, ".mobilecity-validation-");
        Path scratch = Files.createTempDirectory("synthesis-mobilecity-validation-");
        Path work = scratch.resolve("run"); // validate-mobilecity.py requires a new directory
        Path runLog = output.resolve("run.log");
        int exit = -1;
        boolean timedOut = false;
        try {
            List<String> command = new ArrayList<>();
            command.add(command(config.getPython()));
            command.add(path(config.getScript()).toString());
            command.add("--dotnet");
            command.add(executable(config.getDotnet()));
            command.add("--tool-source");
            command.add(path(config.getToolSource()).toString());
            command.add("--import-dir");
            command.add(imports.toString());
            command.add("--work-dir");
            command.add(work.toString());
            command.add("--timeout");
            command.add(Integer.toString(config.getTimeoutMinutes() * 60));
            command.add("--years");
            command.add(Integer.toString(config.getYears()));
            Path nuget = path(config.getNugetCache());
            if (nuget != null && Files.isDirectory(nuget)) {
                command.add("--nuget-cache");
                command.add(nuget.toString());
            }

            log.accept("Starte native MobileCity-Prüfung für " + config.getYears() + " Jahre…");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(runLog.toFile());
            Process process = builder.start();
            if (!process.waitFor(config.getTimeoutMinutes() + 1L, TimeUnit.MINUTES)) {
                timedOut = true;
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            } else {
                exit = process.exitValue();
            }

            for (String name : NATIVE_LOGS) {
                Path source = work.resolve(name);
                if (Files.isRegularFile(source)) {
                    Files.copy(source, output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path nativeReport = work.resolve("validation-report.json");
            Path report = output.resolve("validation-report.json");
            if (Files.isRegularFile(nativeReport)) {
                Files.copy(nativeReport, report, StandardCopyOption.REPLACE_EXISTING);
            } else {
                ObjectNode failed = JSON.createObjectNode();
                failed.put("status", "failed");
                failed.putArray("errors").add(timedOut
                        ? "Native MobileCity-Prüfung nach " + config.getTimeoutMinutes() + " Minuten abgebrochen"
                        : "Prüfprozess lieferte keinen Bericht (Exit " + exit + ")");
                failed.putArray("nonFiniteCells");
                JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), failed);
            }

            JsonNode parsed = JSON.readTree(report.toFile());
            List<Integer> years = new ArrayList<>();
            parsed.path("years").forEach(year -> years.add(year.asInt()));
            List<String> errors = new ArrayList<>();
            parsed.path("errors").forEach(error -> errors.add(error.asText()));
            int nonFinite = parsed.path("nonFiniteCells").size();
            String status = parsed.path("status").asText("failed");
            boolean passed = !timedOut && exit == 0 && "passed".equals(status);
            String effectiveStatus = passed ? "passed" : "failed";
            String reason = passed ? null : errors.isEmpty()
                    ? timedOut ? "Native Prüfung nach Timeout abgebrochen"
                    : "Prüfprozess endete mit Exit " + exit
                    : errors.getFirst();
            writeStatus(output, effectiveStatus, reason,
                    years, errors, nonFinite);
            publish(output, artifacts.resolve("mobilecity-validation"));
            if (timedOut) {
                throw new IOException("Native MobileCity-Prüfung nach "
                        + config.getTimeoutMinutes() + " Minuten abgebrochen");
            }
            if (!passed) {
                throw new IOException("Native MobileCity-Prüfung fehlgeschlagen"
                        + (errors.isEmpty() ? " (Exit " + exit + ")" : ": " + errors.getFirst()));
            }
            return new Result(years, errors, nonFinite,
                    artifacts.resolve("mobilecity-validation/validation-report.json"));
        } catch (IOException | InterruptedException e) {
            if (Files.exists(output)) {
                try {
                    Path report = output.resolve("validation-report.json");
                    ObjectNode failed = JSON.createObjectNode();
                    failed.put("status", "failed");
                    failed.putArray("errors").add(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                    failed.putArray("nonFiniteCells");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), failed);
                    writeStatus(output, "failed", e.getMessage(), List.of(),
                            List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 0);
                    publish(output, artifacts.resolve("mobilecity-validation"));
                } catch (IOException publishingFailure) {
                    e.addSuppressed(publishingFailure);
                    deleteTree(output);
                }
            }
            throw e;
        } finally {
            try {
                deleteTree(scratch);
            } catch (IOException ignored) {
                // Scratch cleanup must not change the native validation result.
            }
        }
    }

    /** Replaces a report from an older run with an explicit skipped state. */
    public static void writeSkipped(Path artifacts, String reason) throws IOException {
        Files.createDirectories(artifacts);
        Path output = Files.createTempDirectory(artifacts, ".mobilecity-validation-");
        writeStatus(output, "skipped", reason, List.of(), List.of(), 0);
        publish(output, artifacts.resolve("mobilecity-validation"));
    }

    private static void writeStatus(Path output, String status, String reason,
                                    List<Integer> years, List<String> errors,
                                    int nonFiniteCells) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", status);
        if (reason != null && !reason.isBlank()) node.put("reason", reason);
        node.set("years", JSON.valueToTree(years));
        node.set("errors", JSON.valueToTree(errors));
        node.put("nonFiniteCells", nonFiniteCells);
        if (!"skipped".equals(status)) node.put("report", "validation-report.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("status.json").toFile(), node);
    }

    private static void publish(Path source, Path target) throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".previous");
        deleteTree(backup);
        if (Files.exists(target)) move(target, backup);
        try {
            move(source, target);
            deleteTree(backup);
        } catch (IOException e) {
            if (Files.exists(backup) && !Files.exists(target)) move(backup, target);
            throw e;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Path path(String configured) {
        if (configured == null || configured.isBlank()) return null;
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String command(String configured) {
        if (configured.contains("/") || configured.contains("\\")) return path(configured).toString();
        return configured;
    }

    private static String executable(String configured) {
        if (configured.contains("/") || configured.contains("\\")) return path(configured).toString();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(entry, configured);
                if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return configured;
    }

    private static boolean commandAvailable(String configured) {
        if (configured == null || configured.isBlank()) return false;
        if (configured.contains("/") || configured.contains("\\")) {
            Path executable = path(configured);
            return executable != null && Files.isRegularFile(executable) && Files.isExecutable(executable);
        }
        try {
            Process process = new ProcessBuilder(configured, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
