package de.synthesis.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Anmeldefreier Lebenszeichen-Endpunkt für Container-Healthchecks und Reverse-Proxys. */
@RestController
public class HealthController {

    private final Instant startedAt = Instant.now();

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "startedAt", startedAt.toString(), "time", Instant.now().toString());
    }
}
