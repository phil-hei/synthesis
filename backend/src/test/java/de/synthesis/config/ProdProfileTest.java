package de.synthesis.config;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Das Produktionsprofil schaltet CORS ab und verlangt HTTPS für das Session-Cookie (Review N2). */
class ProdProfileTest {

    @Test
    void prodProfileOverridesDevOriginAndSecuresTheSessionCookie() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // Reihenfolge wie Spring Boot: Profil-Datei vor der Basisdatei.
        for (PropertySource<?> source : loader.load("prod", new ClassPathResource("application-prod.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        for (PropertySource<?> source : loader.load("base", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        Binder binder = Binder.get(environment);

        SynthesisProperties properties = binder.bind("synthesis", SynthesisProperties.class).get();
        List<String> origins = properties.getAuth().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank()).toList();
        assertThat(origins).isEmpty();
        assertThat(binder.bind("server.servlet.session.cookie.secure", Boolean.class).get()).isTrue();
        assertThat(binder.bind("server.servlet.session.cookie.same-site", String.class).get()).isEqualTo("lax");
    }
}
