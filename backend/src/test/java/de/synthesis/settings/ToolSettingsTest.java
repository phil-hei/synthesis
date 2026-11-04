package de.synthesis.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ToolSettingsTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SynthesisProperties.class)
    static class BoundProperties {}
    private SynthesisProperties properties() {
        var p = new SynthesisProperties(); p.setDataDir(dir.toString()); return p;
    }
    private ToolSettings edited() {
        var p = properties();
        p.getLlm().setModel("installed-test-model");
        p.getSumo().setBinDir("/custom/sumo/bin");
        p.getTransit().setVenvPython("/custom/venv/bin/python");
        return ToolSettings.from(p);
    }
    @Test void persistsWithoutChangingRunningConfigurationAndAppliesOnRestart() throws Exception {
        var p = properties(); var active = ToolSettings.from(p);
        var controller = new ToolSettingsController(mapper, p);
        assertFalse(controller.get().restartRequired());
        var saved = controller.save(edited());
        assertTrue(saved.restartRequired());
        assertEquals(active, saved.active());
        assertEquals(active, ToolSettings.from(p));
        assertEquals(edited(), controller.get().settings());
        assertEquals(edited(), ToolSettingsStore.read(dir.resolve("tool-settings.json"), mapper));
        // Exercise real Spring binding and postprocessor ordering, not just applyTo().
        new ApplicationContextRunner().withUserConfiguration(BoundProperties.class, ToolSettingsConfiguration.class)
                .withPropertyValues("synthesis.data-dir=" + dir, "synthesis.llm.model=yaml-model",
                        "synthesis.sumo.max-trips=77")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var restarted = context.getBean(SynthesisProperties.class);
                    assertEquals(edited(), ToolSettings.from(restarted));
                    assertEquals(77, restarted.getSumo().getMaxTrips(), "Scenario/runtime values outside the allow-list survive");
                    assertFalse(new ToolSettingsController(mapper, restarted).get().restartRequired());
                });
    }
    @Test void revertingToActiveValuesClearsRestartRequirement() {
        var controller = new ToolSettingsController(mapper, properties());
        var active = controller.get().active(); controller.save(edited());
        assertFalse(controller.save(active).restartRequired());
    }
    @Test void apiRejectsInvalidOrIncompleteSettingsWithoutOverwritingFile() throws Exception {
        var controller = new ToolSettingsController(mapper, properties());
        controller.save(edited());
        byte[] before = Files.readAllBytes(dir.resolve("tool-settings.json"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String body : new String[]{"{}", "null", mapper.writeValueAsString(edited()).replace("\"temperature\":0.7", "\"temperature\":3"),
                mapper.writeValueAsString(edited()).replace("http://localhost:11434/v1", "file:///tmp/llm"),
                mapper.writeValueAsString(edited()).replace("installed-test-model", " ")}) {
            mvc.perform(put("/api/settings/tools").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("tool-settings.json")));
        }
        mvc.perform(get("/api/settings/tools").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.llm.model").value("installed-test-model"))
                .andExpect(jsonPath("$.restartRequired").value(true));
    }
    @Test void failedSaveDoesNotClaimSuccessOrModifyActiveProperties() throws Exception {
        var p = properties(); var controller = new ToolSettingsController(mapper, p);
        Files.createDirectory(dir.resolve("tool-settings.json"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.save(edited()));
        assertNotEquals(edited(), ToolSettings.from(p));
        try (var files = Files.list(dir)) { assertEquals(1, files.count(), "Temporary files are cleaned up"); }
    }
    @Test void corruptSavedSettingsFailStartupVisiblyInsteadOfSilentlyChangingConfiguration() throws Exception {
        Files.writeString(dir.resolve("tool-settings.json"), "{}");
        new ApplicationContextRunner().withUserConfiguration(BoundProperties.class, ToolSettingsConfiguration.class)
                .withPropertyValues("synthesis.data-dir=" + dir)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
