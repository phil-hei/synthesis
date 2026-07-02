package de.synthesis.storage;

import de.synthesis.scenario.ScenarioRepository;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Service
public class ArtifactService {

    public record ArtifactInfo(String path, long sizeBytes) {
    }

    private final ScenarioRepository scenarios;

    public ArtifactService(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    public List<ArtifactInfo> list(String scenarioId) {
        Path dir = scenarios.artifactsDir(scenarioId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> {
                        try {
                            return new ArtifactInfo(
                                    dir.relativize(file).toString().replace('\\', '/'),
                                    Files.size(file));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted((a, b) -> a.path().compareTo(b.path()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Artefakte konnten nicht aufgelistet werden", e);
        }
    }

    public Resource load(String scenarioId, String relativePath) {
        Path dir = scenarios.artifactsDir(scenarioId).toAbsolutePath().normalize();
        Path file = dir.resolve(relativePath.startsWith("/") ? relativePath.substring(1) : relativePath)
                .toAbsolutePath().normalize();
        if (!file.startsWith(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Artefakt-Pfad");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artefakt nicht gefunden: " + relativePath);
        }
        return new PathResource(file);
    }
}
