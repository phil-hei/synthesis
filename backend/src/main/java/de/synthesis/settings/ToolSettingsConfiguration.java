package de.synthesis.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ToolSettingsConfiguration {
    /** Runs after Spring property binding, before runners or services receive the properties. */
    @Bean
    static BeanPostProcessor persistedToolSettings() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof SynthesisProperties properties) {
                    Path file = settingsFile(properties);
                    if (Files.exists(file)) {
                        try { ToolSettingsStore.read(file, new ObjectMapper()).applyTo(properties); }
                        catch (IOException ex) {
                            throw new IllegalStateException("Tool-Einstellungen konnten nicht geladen werden: " + file, ex);
                        }
                    }
                }
                return bean;
            }
        };
    }
    static Path settingsFile(SynthesisProperties properties) {
        return Path.of(properties.getDataDir()).toAbsolutePath().normalize().resolve("tool-settings.json");
    }
}
