package de.synthesis.config;

import de.synthesis.SynthesisProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS nur für die konfigurierten Dev-Herkünfte; im Server-Betrieb liefert das Backend das SPA selbst. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SynthesisProperties properties;

    public WebConfig(SynthesisProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var configured = properties.getAuth().getAllowedOrigins();
        var origins = configured == null ? java.util.List.<String>of()
                : configured.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
        if (origins.isEmpty()) {
            return; // same-origin (Backend liefert das SPA): kein CORS nötig
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
