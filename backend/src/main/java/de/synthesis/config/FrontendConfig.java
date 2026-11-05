package de.synthesis.config;

import de.synthesis.SynthesisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Liefert den Angular-Build aus dem Backend aus, damit die Plattform aus einem Prozess besteht:
 * ein Port, eine Herkunft (kein CORS), ein Session-Cookie. Unbekannte Pfade ohne Dateiendung
 * bekommen {@code index.html}, damit der Angular-Router Deep-Links wie {@code /vergleich} oder
 * {@code /login} selbst auflöst; {@code /api/**} fällt nie auf das SPA zurück.
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(FrontendConfig.class);
    static final String INDEX = "index.html";

    private final SynthesisProperties properties;

    public FrontendConfig(SynthesisProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = location();
        if (location == null) {
            LOG.info("Kein Frontend-Build gefunden (synthesis.frontend.dir leer, classpath:/static ohne index.html); nur API");
            return;
        }
        LOG.info("Frontend wird ausgeliefert aus {}", location);
        // Angular hängt einen Inhalts-Hash an Skript-/Stylenamen: unbegrenzt cachebar.
        CacheControl immutable = CacheControl.maxAge(Duration.ofDays(365)).immutable().cachePublic();
        registry.addResourceHandler("/*.js", "/*.css", "/*.map")
                .addResourceLocations(location)
                .setCacheControl(immutable);
        // Der Handler sieht nur den Rest hinter dem Muster, daher zeigt der Ort auf media/ selbst.
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location + "media/")
                .setCacheControl(immutable);
        // index.html und alles Übrige: immer neu validieren, sonst sehen Nutzer nach einem Deploy alte Hashes.
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    /** Der Ressourcen-Handler bekommt für {@code /} einen leeren Pfad und findet nichts; daher explizit. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (location() != null) {
            registry.addViewController("/").setViewName("forward:/" + INDEX);
        }
    }

    private String location() {
        String dir = properties.getFrontend().getDir();
        if (dir != null && !dir.isBlank()) {
            Path path = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path.resolve(INDEX))) {
                LOG.warn("synthesis.frontend.dir={} enthält keine {}", path, INDEX);
            }
            return path.toUri().toString(); // endet mit '/', da Verzeichnis
        }
        return new ClassPathResource("static/" + INDEX).exists() ? "classpath:/static/" : null;
    }

    /** Vorhandene Dateien unverändert; API-Pfade und Dateinamen 404; Router-Pfade → index.html. */
    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            if (resourcePath.startsWith("api/") || resourcePath.equals("api")
                    || resourcePath.startsWith("v3/api-docs") || resourcePath.contains(".")) {
                return null;
            }
            return super.getResource(INDEX, location);
        }
    }
}
