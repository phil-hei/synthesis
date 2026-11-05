package de.synthesis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Das Backend liefert den Angular-Build aus: Deep-Links → index.html, Hash-Dateien lange cachebar, API nie SPA. */
@SpringBootTest
@AutoConfigureMockMvc
class SpaFallbackTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path frontend = Files.createDirectories(root.resolve("frontend"));
        Files.writeString(frontend.resolve("index.html"), "<!doctype html><title>SyntheSis</title><app-root></app-root>");
        Files.writeString(frontend.resolve("main-ABC123.js"), "console.log('synthesis');");
        Files.createDirectories(frontend.resolve("media"));
        Files.writeString(frontend.resolve("media/marker.svg"), "<svg/>");
        registry.add("synthesis.frontend.dir", () -> frontend.toString());
        registry.add("synthesis.data-dir", () -> root.resolve("data").toString());
        registry.add("synthesis.auth.enabled", () -> "false");
        registry.add("synthesis.auth.allowed-origins", () -> "");
        registry.add("synthesis.census.enabled", () -> "false");
        registry.add("synthesis.overpass.live-data-enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void servesIndexForRootAndRouterPaths() throws Exception {
        // MockMvc folgt dem Forward nicht; der Servlet-Container liefert dann /index.html aus.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<app-root>")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
        mvc.perform(get("/vergleich")).andExpect(status().isOk()).andExpect(content().string(containsString("<app-root>")));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("<app-root>")));
        mvc.perform(get("/benutzer/")).andExpect(status().isOk()).andExpect(content().string(containsString("<app-root>")));
    }

    @Test
    void servesHashedAssetsImmutableAndKeepsApiAndFilesOutOfTheFallback() throws Exception {
        mvc.perform(get("/main-ABC123.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("synthesis")))
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
        mvc.perform(get("/media/marker.svg")).andExpect(status().isOk());
        mvc.perform(get("/missing-file.png")).andExpect(status().isNotFound());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("local"));
    }
}
