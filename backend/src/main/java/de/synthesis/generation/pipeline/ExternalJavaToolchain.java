package de.synthesis.generation.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/** Konfiguriert die separat laufenden KIT-Adapterprozesse mit ihrem gepinnten JDK. */
final class ExternalJavaToolchain {

    static final int KIT_JAVA = 25;
    private static final String ENV = "SYNTHESIS_KIT_JAVA_HOME";

    private ExternalJavaToolchain() {
    }

    static void configure(ProcessBuilder builder) {
        Path home = configuredHome();
        if (home == null) {
            return;
        }
        var environment = builder.environment();
        environment.put("JAVA_HOME", home.toString());
        String path = environment.getOrDefault("PATH", System.getenv().getOrDefault("PATH", ""));
        environment.put("PATH", home.resolve("bin") + (path.isBlank() ? "" : ":" + path));
    }

    static Path configuredHome() {
        String explicit = System.getenv(ENV);
        if (explicit != null && !explicit.isBlank()) {
            Path home = Path.of(explicit).toAbsolutePath().normalize();
            if (Files.isExecutable(home.resolve("bin/java"))) {
                return home;
            }
        }
        Path homebrew = Path.of("/opt/homebrew/opt/openjdk@" + KIT_JAVA,
                "libexec/openjdk.jdk/Contents/Home");
        return Files.isExecutable(homebrew.resolve("bin/java")) ? homebrew : null;
    }
}
