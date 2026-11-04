package de.synthesis.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/settings/tools")
public class ToolSettingsController {
    private final ObjectMapper mapper;
    private final Path file;
    private final ToolSettings active;
    public record State(ToolSettings settings, ToolSettings active, boolean restartRequired) {}

    public ToolSettingsController(ObjectMapper mapper, SynthesisProperties properties) {
        // Use the same record-only representation for startup and API persistence.
        this.mapper = mapper.copy();
        this.file = ToolSettingsConfiguration.settingsFile(properties);
        this.active = ToolSettings.from(properties);
    }
    @GetMapping
    public synchronized State get() {
        try { return state(Files.exists(file) ? ToolSettingsStore.read(file, mapper) : active); }
        catch (IOException ex) { throw failure("Gespeicherte Tool-Einstellungen konnten nicht gelesen werden", ex); }
    }
    @PutMapping
    public synchronized State save(@Valid @RequestBody ToolSettings settings) {
        try {
            ToolSettingsStore.write(file, mapper, settings);
            return state(settings);
        } catch (IOException ex) { throw failure("Tool-Einstellungen konnten nicht gespeichert werden", ex); }
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    org.springframework.http.ProblemDetail invalid(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        var detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
    private State state(ToolSettings settings) { return new State(settings, active, !settings.equals(active)); }
    private ResponseStatusException failure(String message, IOException ex) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, ex);
    }
}
