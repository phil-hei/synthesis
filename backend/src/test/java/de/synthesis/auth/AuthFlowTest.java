package de.synthesis.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fährt den vollständigen Anmeldefluss durch die echte Security-Konfiguration: Bootstrap-Admin,
 * 401 ohne Sitzung, JSON-Login mit CSRF-Cookie, Kontenverwaltung nur für ADMIN, Besitzerschutz
 * beim Löschen von Szenarien, Logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("synthesis.data-dir", () -> dataDir.toString());
        registry.add("synthesis.census.enabled", () -> "false");
        registry.add("synthesis.overpass.live-data-enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    UserStore users;

    @Test
    void platformRequiresLoginAndSeparatesAdminFromUser() throws Exception {
        Path passwordFile = dataDir.resolve(UserStore.INITIAL_PASSWORD_FILE);
        assertTrue(Files.isRegularFile(passwordFile), "Startpasswort muss beim ersten Start abgelegt werden");
        String adminPassword = Files.readString(passwordFile).trim();
        assertTrue(Files.isRegularFile(dataDir.resolve(UserStore.FILE)));

        mvc.perform(get("/api/scenarios")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        // CSRF-Cookie kommt mit der ersten Antwort; der Login selbst braucht den Token.
        MvcResult probe = mvc.perform(get("/api/auth/me")).andReturn();
        Cookie xsrf = probe.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(xsrf, "CSRF-Token muss als lesbares Cookie gesetzt werden");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"falsch\"}").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + adminPassword + "\"}"))
                .andExpect(status().isForbidden()); // ohne CSRF-Token

        MockHttpSession admin = login("admin", adminPassword);
        mvc.perform(get("/api/auth/me").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        mvc.perform(get("/api/scenarios").session(admin)).andExpect(status().isOk());

        mvc.perform(post("/api/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"maria\",\"displayName\":\"Maria\",\"password\":\"geheim-123\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kurz\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest());

        MockHttpSession maria = login("maria", "geheim-123");
        mvc.perform(get("/api/users").session(maria)).andExpect(status().isForbidden());

        // Szenario-Besitz: Maria legt an, Admin darf löschen, ein weiterer Nutzer nicht.
        String scenario = mvc.perform(get("/api/scenarios/defaults").session(maria)).andReturn().getResponse().getContentAsString();
        JsonNode draft = json.readTree(scenario);
        ((com.fasterxml.jackson.databind.node.ObjectNode) draft).putObject("region")
                .put("minLat", 48.85).put("minLon", 8.19).put("maxLat", 48.86).put("maxLon", 8.2);
        MvcResult created = mvc.perform(post("/api/scenarios").session(maria).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(draft.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("maria"))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

        users.create("tom", "Tom", "geheim-456", "USER");
        MockHttpSession tom = login("tom", "geheim-456");
        mvc.perform(delete("/api/scenarios/" + id).session(tom).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/scenarios/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(put("/api/users/maria/role").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        mvc.perform(delete("/api/users/admin").session(admin).with(csrf())).andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/logout").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "Login muss eine Sitzung anlegen");
        assertEquals(username, json.readTree(result.getResponse().getContentAsString()).path("username").asText());
        return session;
    }
}
