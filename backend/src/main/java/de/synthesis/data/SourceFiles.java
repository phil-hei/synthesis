package de.synthesis.data;

import de.synthesis.scenario.ScenarioRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

/** Scenario-local immutable source configuration; no generated artifacts are inputs. */
final class SourceFiles {
    private SourceFiles() { }
    static String hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Ungültige Quellenversions-ID");
        return value;
    }
    static Path root(ScenarioRepository repo,String scenario) {
        repo.findById(scenario).orElseThrow(() -> new IllegalArgumentException("Szenario nicht gefunden"));
        return repo.uploadsDir(scenario).resolve("data-sources");
    }
    static void immutable(Path file,byte[] bytes)throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            if (!Arrays.equals(Files.readAllBytes(file),bytes)) throw new IOException("Unveränderliche Quellenversion enthält andere Daten");
            return;
        }
        Path temporary=Files.createTempFile(file.getParent(),".source-",".part");
        try { Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temporary); }
    }
}
