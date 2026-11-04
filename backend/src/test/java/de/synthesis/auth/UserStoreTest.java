package de.synthesis.auth;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserStoreTest {

    @TempDir
    Path dir;

    private UserStore store() {
        SynthesisProperties properties = new SynthesisProperties();
        properties.setDataDir(dir.toString());
        return new UserStore(properties, PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    @Test
    void bootstrapsAnAdminOnceAndPersistsHashedAccountsAcrossRestarts() throws Exception {
        UserStore first = store();
        assertEquals(1, first.list().size());
        String password = Files.readString(dir.resolve(UserStore.INITIAL_PASSWORD_FILE)).trim();
        assertTrue(first.verify("admin", password));
        assertFalse(first.verify("admin", password + "x"));
        String stored = Files.readString(dir.resolve(UserStore.FILE));
        assertFalse(stored.contains(password), "Passwörter dürfen nur gehasht gespeichert werden");
        assertTrue(stored.contains("{bcrypt}"));

        first.create("Maria", "Maria M.", "sehr-geheim", "user");
        UserStore restarted = store();
        assertEquals(2, restarted.list().size(), "Neustart darf keinen zweiten Bootstrap auslösen");
        assertTrue(restarted.verify("Maria", "sehr-geheim"));
        assertEquals("USER", restarted.find("Maria").orElseThrow().roles().get(0));
    }

    @Test
    void protectsTheLastAdministratorAndValidatesInput() {
        UserStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.delete("admin"));
        assertThrows(IllegalArgumentException.class, () -> store.setRole("admin", "USER"));
        assertThrows(IllegalArgumentException.class, () -> store.create("a", "", "sehr-geheim", "USER"));
        assertThrows(IllegalArgumentException.class, () -> store.create("ok.name", "", "kurz", "USER"));
        assertThrows(IllegalArgumentException.class, () -> store.create("ok.name", "", "sehr-geheim", "ROOT"));
        store.create("zweiter", "", "sehr-geheim", "ADMIN");
        store.setRole("admin", "USER");
        assertFalse(store.find("admin").orElseThrow().isAdmin());
        store.delete("admin");
        assertTrue(store.find("admin").isEmpty());
    }
}
