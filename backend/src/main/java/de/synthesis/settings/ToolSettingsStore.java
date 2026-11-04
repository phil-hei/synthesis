package de.synthesis.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.*;

/** Atomic persistence shared by startup and the settings API. */
final class ToolSettingsStore {
    private ToolSettingsStore() {}
    static ToolSettings read(Path file, ObjectMapper mapper) throws IOException {
        ToolSettings value = mapper.readValue(file.toFile(), ToolSettings.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            if (value == null || !factory.getValidator().validate(value).isEmpty())
                throw new IOException("Ungültige Tool-Einstellungen in " + file);
        }
        return value;
    }
    static void write(Path file, ObjectMapper mapper, ToolSettings settings) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".tool-settings-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
