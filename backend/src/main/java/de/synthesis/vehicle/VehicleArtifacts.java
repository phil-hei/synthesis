package de.synthesis.vehicle;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Reproducible input snapshot, separate from the eventual SUMO execution result. */
public final class VehicleArtifacts {
    private VehicleArtifacts() { }
    public static void snapshot(Path artifacts, VehicleConfiguration input, VehicleCatalog.Report report) throws IOException {
        Path dir = artifacts.resolve("vehicles");
        write(dir.resolve("input.json"), input);
        write(dir.resolve("resolved.json"), report.resolved());
        write(dir.resolve("compatibility.json"), Map.of("schemaVersion", VehicleConfiguration.CONTRACT,
                "sha256", report.sha256(), "capabilities", report.capabilities(), "executionStatus", "not-run"));
        // Remove previous-run usage so skipped SUMO never appears as an applied configuration.
        Files.deleteIfExists(dir.resolve("sumo-types.json"));
        Files.deleteIfExists(dir.resolve("sumo-execution.json"));
    }
    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".vehicle-", ".json");
        try {
            VehicleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
