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
        var origins = properties.getAuth().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
