package de.synthesis.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/** Persistiertes Benutzerkonto; der Passwort-Hash verlässt den Server nie. */
public record UserAccount(
        String username,
        String displayName,
        @JsonIgnore String passwordHash,
        List<String> roles,
        Instant createdAt,
        Instant lastLoginAt) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public boolean isAdmin() {
        return roles != null && roles.contains(ROLE_ADMIN);
    }

    UserAccount withPasswordHash(String hash) {
        return new UserAccount(username, displayName, hash, roles, createdAt, lastLoginAt);
    }

    UserAccount withRoles(List<String> newRoles) {
        return new UserAccount(username, displayName, passwordHash, List.copyOf(newRoles), createdAt, lastLoginAt);
    }

    UserAccount withLastLogin(Instant at) {
        return new UserAccount(username, displayName, passwordHash, roles, createdAt, at);
    }
}
