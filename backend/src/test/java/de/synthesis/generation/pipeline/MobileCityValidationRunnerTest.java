package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobileCityValidationRunnerTest {

    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesPassedReportAndReplacesOlderStatus() throws Exception {
        Path artifacts = artifacts();
        Files.createDirectories(artifacts.resolve("mobilecity-validation"));
        Files.writeString(artifacts.resolve("mobilecity-validation/status.json"), "{\"status\":\"failed\"}");

        MobileCityValidationRunner runner = new MobileCityValidationRunner(config(fakePython(true)));
        MobileCityValidationRunner.Result result = runner.run(artifacts, ignored -> { });

        assertThat(result.years()).containsExactly(2026, 2027, 2028);
        assertThat(result.errors()).isEmpty();
        assertThat(result.nonFiniteCells()).isZero();
        assertThat(Files.readString(artifacts.resolve("mobilecity-validation/import.log")))
                .contains("native import");
        var status = json.readTree(artifacts.resolve("mobilecity-validation/status.json").toFile());
        assertThat(status.path("status").asText()).isEqualTo("passed");
        assertThat(status.path("report").asText()).isEqualTo("validation-report.json");
    }

    @Test
    void preservesFailedEvidenceAndSkipCannotExposeOldGreenReport() throws Exception {
        Path artifacts = artifacts();
        MobileCityValidationRunner runner = new MobileCityValidationRunner(config(fakePython(false)));

        assertThatThrownBy(() -> runner.run(artifacts, ignored -> { }))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("native failure");
        assertThat(json.readTree(artifacts.resolve("mobilecity-validation/status.json").toFile())
                .path("status").asText()).isEqualTo("failed");
        assertThat(Files.isRegularFile(artifacts.resolve("mobilecity-validation/validation-report.json"))).isTrue();

        MobileCityValidationRunner.writeSkipped(artifacts, "nicht angefordert");
        var skipped = json.readTree(artifacts.resolve("mobilecity-validation/status.json").toFile());
        assertThat(skipped.path("status").asText()).isEqualTo("skipped");
        assertThat(skipped.path("reason").asText()).isEqualTo("nicht angefordert");
        assertThat(Files.exists(artifacts.resolve("mobilecity-validation/validation-report.json"))).isFalse();
    }

    private Path artifacts() throws Exception {
        Path artifacts = temp.resolve("artifacts-" + System.nanoTime());
        Files.createDirectories(artifacts.resolve("mobilecity"));
        Files.writeString(artifacts.resolve("mobilecity/summary.json"), "{\"mode\":\"regional\"}\n");
        return artifacts;
    }

    private SynthesisProperties.MobileCityValidation config(Path python) throws Exception {
        Path script = temp.resolve("validate-mobilecity.py");
        Files.writeString(script, "# fixture\n");
        Path dotnet = executable(temp.resolve("dotnet"), "#!/bin/sh\nexit 0\n");
        Path tool = temp.resolve("ConfigTool");
        Files.createDirectories(tool);
        Files.writeString(tool.resolve("MobileCityConfigurationTool.csproj"), "<Project/>\n");
        Path nuget = temp.resolve("nuget");
        Files.createDirectories(nuget);
        SynthesisProperties.MobileCityValidation config = new SynthesisProperties.MobileCityValidation();
        config.setPython(python.toString());
        config.setScript(script.toString());
        config.setDotnet(dotnet.toString());
        config.setToolSource(tool.toString());
        config.setNugetCache(nuget.toString());
        config.setTimeoutMinutes(1);
        config.setYears(3);
        return config;
    }

    private Path fakePython(boolean passed) throws Exception {
        String status = passed ? "passed" : "failed";
        String errors = passed ? "[]" : "[\"native failure\"]";
        int exit = passed ? 0 : 1;
        return executable(temp.resolve("python-" + status), """
                #!/bin/sh
                if [ "$1" = "--version" ]; then exit 0; fi
                work=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--work-dir" ]; then shift; work="$1"; fi
                  shift
                done
                mkdir -p "$work"
                printf 'native import\n' > "$work/import.log"
                cat > "$work/validation-report.json" <<'JSON'
                {"status":"%s","years":[2026,2027,2028],"errors":%s,"nonFiniteCells":[]}
                JSON
                exit %d
                """.formatted(status, errors, exit));
    }

    private Path executable(Path path, String content) throws Exception {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }
}
