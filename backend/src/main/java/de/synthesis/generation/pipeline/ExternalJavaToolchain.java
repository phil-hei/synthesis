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
        for (Path candidate : CANDIDATES) {
            if (Files.isExecutable(candidate.resolve("bin/java"))) {
                return candidate;
            }
        }
        return null;
    }

    /** Übliche Installationsorte ohne Versionsprüfung: der Pfad trägt die Hauptversion bereits im Namen. */
    private static final java.util.List<Path> CANDIDATES = java.util.List.of(
            Path.of("/opt/homebrew/opt/openjdk@" + KIT_JAVA, "libexec/openjdk.jdk/Contents/Home"),
            Path.of("/usr/lib/jvm/java-" + KIT_JAVA + "-openjdk-amd64"),
            Path.of("/usr/lib/jvm/java-" + KIT_JAVA + "-openjdk-arm64"),
            Path.of("/usr/lib/jvm/java-" + KIT_JAVA + "-openjdk"),
            Path.of("/usr/lib/jvm/temurin-" + KIT_JAVA + "-jdk-amd64"),
            Path.of("/usr/lib/jvm/temurin-" + KIT_JAVA + "-jdk-arm64"),
            Path.of("/opt/java/openjdk-" + KIT_JAVA));
}
