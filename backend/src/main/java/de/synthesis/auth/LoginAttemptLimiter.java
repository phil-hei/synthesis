package de.synthesis.auth;

import de.synthesis.SynthesisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bremst Passwort-Raten: nach {@code synthesis.auth.max-login-failures} Fehlversuchen je Benutzername
 * und Herkunft ist der Login für {@code lockout-minutes} gesperrt. Der Schlüssel enthält die Herkunft,
 * damit ein Angreifer ein fremdes Konto nicht gezielt aussperren kann; bcrypt allein bremst nicht genug.
 * Zustand im Speicher: bei Neustart leer, für einen Server-Prozess ausreichend.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) { }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final SynthesisProperties.Auth config;
    private final Clock clock;

    @Autowired
    public LoginAttemptLimiter(SynthesisProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttemptLimiter(SynthesisProperties properties, Clock clock) {
        this.config = properties.getAuth();
        this.clock = clock;
    }

    /** Restdauer der Sperre, falls gesperrt. */
    public Optional<Duration> lockedFor(String username, String origin) {
        Attempts current = attempts.get(key(username, origin));
        Instant now = clock.instant();
        if (current == null || current.lockedUntil() == null || !current.lockedUntil().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, current.lockedUntil()));
    }

    public void failed(String username, String origin) {
        Instant now = clock.instant();
        Duration lockout = Duration.ofMinutes(Math.max(1, config.getLockoutMinutes()));
        int limit = Math.max(1, config.getMaxLoginFailures());
        Attempts updated = attempts.compute(key(username, origin), (k, current) -> {
            // Fehlversuche verfallen nach der Sperrdauer, sonst sperrt sich ein Konto über Wochen hinweg selbst.
            int failures = current == null || current.lastFailure().plus(lockout).isBefore(now) ? 1 : current.failures() + 1;
            Instant lockedUntil = failures >= limit ? now.plus(lockout) : null;
            return new Attempts(failures, now, lockedUntil);
        });
        if (updated.lockedUntil() != null) {
            LOG.warn("Login für '{}' von {} nach {} Fehlversuchen bis {} gesperrt", username, origin, updated.failures(), updated.lockedUntil());
        } else {
            LOG.info("Fehlgeschlagener Login für '{}' von {} ({}/{})", username, origin, updated.failures(), limit);
        }
        evictStale(now, lockout);
    }

    public void succeeded(String username, String origin) {
        attempts.remove(key(username, origin));
    }

    private void evictStale(Instant now, Duration lockout) {
        if (attempts.size() < 1000) {
            return;
        }
        attempts.entrySet().removeIf(e -> e.getValue().lastFailure().plus(lockout).isBefore(now));
    }

    private static String key(String username, String origin) {
        return (username == null ? "" : username.trim().toLowerCase(Locale.ROOT)) + "|" + (origin == null ? "" : origin);
    }
}
