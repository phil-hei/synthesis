package de.synthesis.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.synthesis.SynthesisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Dateibasierter Kontenspeicher ({@code <data-dir>/users.json}), passend zum übrigen
 * Artefakt-Layout: inspizierbar, ohne Datenbankserver, atomar geschrieben. Beim ersten Start
 * ohne Konten entsteht ein Administrator mit zufälligem Startpasswort, das einmalig im Log und
 * in {@code initial-admin-password.txt} steht.
 */
@Service
public class UserStore {

    private static final Logger LOG = LoggerFactory.getLogger(UserStore.class);
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{2,64}");
    static final String FILE = "users.json";
    static final String INITIAL_PASSWORD_FILE = "initial-admin-password.txt";

    /** Hash bleibt beim Speichern erhalten; {@link UserAccount} selbst ignoriert ihn in der API. */
    private record StoredAccount(String username, String displayName, String passwordHash, List<String> roles,
                                 Instant createdAt, Instant lastLoginAt) {
        static StoredAccount of(UserAccount a) {
            return new StoredAccount(a.username(), a.displayName(), a.passwordHash(), a.roles(), a.createdAt(), a.lastLoginAt());
        }

        UserAccount account() {
            return new UserAccount(username, displayName, passwordHash, roles == null ? List.of() : roles, createdAt, lastLoginAt);
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final Path file;
    private final PasswordEncoder encoder;
    private final SynthesisProperties.Auth config;
    private final Map<String, UserAccount> accounts = new LinkedHashMap<>();

    public UserStore(SynthesisProperties properties, PasswordEncoder encoder) {
        this.file = Path.of(properties.getDataDir()).toAbsolutePath().normalize().resolve(FILE);
        this.encoder = encoder;
        this.config = properties.getAuth();
        load();
        if (config.isEnabled() && accounts.isEmpty()) {
            bootstrapAdmin();
        }
    }

    public synchronized Optional<UserAccount> find(String username) {
        return Optional.ofNullable(accounts.get(normalize(username)));
    }

    public synchronized List<UserAccount> list() {
        return new ArrayList<>(accounts.values());
    }

    public synchronized UserAccount create(String username, String displayName, String password, String role) {
        String key = normalize(username);
        if (!USERNAME.matcher(key).matches()) {
            throw new IllegalArgumentException("Benutzername: 2–64 Zeichen aus Buchstaben, Ziffern, . _ @ -");
        }
        if (accounts.containsKey(key)) {
            throw new IllegalArgumentException("Benutzername ist bereits vergeben");
        }
        validatePassword(password);
        String resolvedRole = role == null || role.isBlank() ? UserAccount.ROLE_USER : role.trim().toUpperCase(Locale.ROOT);
        if (!UserAccount.ROLE_USER.equals(resolvedRole) && !UserAccount.ROLE_ADMIN.equals(resolvedRole)) {
            throw new IllegalArgumentException("Unbekannte Rolle: " + role);
        }
        UserAccount account = new UserAccount(key, displayName == null || displayName.isBlank() ? key : displayName.trim(),
                encoder.encode(password), List.of(resolvedRole), Instant.now(), null);
        accounts.put(key, account);
        save();
        return account;
    }

    public synchronized UserAccount setRole(String username, String role) {
        UserAccount account = require(username);
        String resolved = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!UserAccount.ROLE_USER.equals(resolved) && !UserAccount.ROLE_ADMIN.equals(resolved)) {
            throw new IllegalArgumentException("Unbekannte Rolle: " + role);
        }
        if (UserAccount.ROLE_USER.equals(resolved) && account.isAdmin() && countAdmins() == 1) {
            throw new IllegalArgumentException("Der letzte Administrator kann nicht herabgestuft werden");
        }
        UserAccount updated = account.withRoles(List.of(resolved));
        accounts.put(updated.username(), updated);
        save();
        return updated;
    }

    public synchronized void setPassword(String username, String password) {
        UserAccount account = require(username);
        validatePassword(password);
        accounts.put(account.username(), account.withPasswordHash(encoder.encode(password)));
        save();
    }

    public synchronized boolean verify(String username, String password) {
        return find(username).map(a -> password != null && encoder.matches(password, a.passwordHash())).orElse(false);
    }

    public synchronized void delete(String username) {
        UserAccount account = require(username);
        if (account.isAdmin() && countAdmins() == 1) {
            throw new IllegalArgumentException("Der letzte Administrator kann nicht gelöscht werden");
        }
        accounts.remove(account.username());
        save();
    }

    public synchronized void recordLogin(String username) {
        find(username).ifPresent(a -> {
            accounts.put(a.username(), a.withLastLogin(Instant.now()));
            save();
        });
    }

    public Path file() {
        return file;
    }

    void validatePassword(String password) {
        if (password == null || password.length() < config.getMinPasswordLength()) {
            throw new IllegalArgumentException("Passwort muss mindestens " + config.getMinPasswordLength() + " Zeichen haben");
        }
    }

    private UserAccount require(String username) {
        return find(username).orElseThrow(() -> new IllegalArgumentException("Unbekannter Benutzer: " + username));
    }

    private long countAdmins() {
        return accounts.values().stream().filter(UserAccount::isAdmin).count();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim();
    }

    private void bootstrapAdmin() {
        String username = config.getBootstrapAdmin() == null || config.getBootstrapAdmin().isBlank()
                ? "admin" : config.getBootstrapAdmin().trim();
        String password = randomPassword();
        accounts.put(username, new UserAccount(username, "Administrator", encoder.encode(password),
                List.of(UserAccount.ROLE_ADMIN), Instant.now(), null));
        save();
        Path passwordFile = file.resolveSibling(INITIAL_PASSWORD_FILE);
        try {
            Files.writeString(passwordFile, password + System.lineSeparator());
            try {
                Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows-Dateisystem: keine POSIX-Rechte
            }
        } catch (IOException e) {
            LOG.warn("Startpasswort konnte nicht nach {} geschrieben werden: {}", passwordFile, e.getMessage());
        }
        LOG.warn("Kein Benutzerkonto vorhanden – Administrator '{}' angelegt. Startpasswort: {} (auch in {})",
                username, password, passwordFile);
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<StoredAccount> stored = JSON.readValue(file.toFile(), new TypeReference<>() { });
            for (StoredAccount account : stored) {
                accounts.put(account.username(), account.account());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Benutzerkonten konnten nicht gelesen werden: " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".users-", ".json");
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                        accounts.values().stream().map(StoredAccount::of).toList());
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows-Dateisystem: keine POSIX-Rechte
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Benutzerkonten konnten nicht gespeichert werden: " + file, e);
        }
    }
}
