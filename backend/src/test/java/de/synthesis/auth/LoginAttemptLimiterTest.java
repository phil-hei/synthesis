package de.synthesis.auth;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-04T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void locksAfterConfiguredFailuresPerUserAndOriginAndReleasesAfterTheWindow() {
        var properties = new SynthesisProperties();
        properties.getAuth().setMaxLoginFailures(3);
        properties.getAuth().setLockoutMinutes(15);
        var clock = new MutableClock();
        var limiter = new LoginAttemptLimiter(properties, clock);

        limiter.failed("Admin", "10.0.0.1");
        limiter.failed("admin ", "10.0.0.1");
        assertTrue(limiter.lockedFor("admin", "10.0.0.1").isEmpty(), "zwei Fehlversuche sperren noch nicht");
        limiter.failed("admin", "10.0.0.1");
        Duration remaining = limiter.lockedFor("admin", "10.0.0.1").orElseThrow();
        assertEquals(15, remaining.toMinutes());
        assertTrue(limiter.lockedFor("admin", "10.0.0.2").isEmpty(), "andere Herkunft bleibt frei");
        assertTrue(limiter.lockedFor("maria", "10.0.0.1").isEmpty(), "anderes Konto bleibt frei");

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertTrue(limiter.lockedFor("admin", "10.0.0.1").isEmpty(), "Sperre läuft ab");
        limiter.failed("admin", "10.0.0.1");
        assertFalse(limiter.lockedFor("admin", "10.0.0.1").isPresent(), "alte Fehlversuche verfallen mit der Sperre");

        limiter.succeeded("admin", "10.0.0.1");
        limiter.failed("admin", "10.0.0.1");
        limiter.failed("admin", "10.0.0.1");
        assertTrue(limiter.lockedFor("admin", "10.0.0.1").isEmpty(), "Erfolg setzt den Zähler zurück");
    }
}
