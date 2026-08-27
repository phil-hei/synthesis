package de.synthesis;

import de.synthesis.reference.Mid2017;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisPropertiesTest {

    @Test
    void distanceCalibrationUsesOfficialNearTripShareByDefault() {
        SynthesisProperties properties = new SynthesisProperties();

        assertThat(properties.getSimulation().getDistanceCalibrationTargetNearShare())
                .isEqualTo(Mid2017.NEAR_TRIP_SHARE)
                .isEqualTo(0.21);
    }

    @Test
    void liveOverpassDataIsEnabledByDefault() {
        assertThat(new SynthesisProperties().getOverpass().isLiveDataEnabled()).isTrue();
    }

    @Test
    void census2022ArchiveIsPinnedByVersionAndSha256() {
        SynthesisProperties.Census census = new SynthesisProperties().getCensus();

        assertThat(census.isEnabled()).isTrue();
        assertThat(census.getReferenceDate()).isEqualTo("2022-05-15");
        assertThat(census.getSourceUrl()).contains("Zensus2022_Bevoelkerungszahl.zip");
        assertThat(census.getArchiveSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void sumoRootFallbackCarriesNoHardcodedVersion() {
        // Ohne $SUMO_HOME und ohne sumo im PATH bleibt nur das macOS-Framework. Der Pfad
        // muss über die Installer-Symlinks laufen, sonst bricht jedes SUMO-Update den Default.
        String fallback = SynthesisProperties.Sumo.rootOf(null);

        assertThat(fallback).doesNotMatch(".*\\d+\\.\\d+.*");
        assertThat(fallback).isEqualTo("/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO");
    }

    @Test
    void sumoRootIsDerivedFromBinaryLocation(@TempDir Path tmp) throws IOException {
        // Sowohl das macOS-Framework (<root>/bin/sumo, <root>/share/sumo) als auch eine
        // Linux-Paketinstallation (/usr/bin/sumo, /usr/share/sumo) haben dieses Layout.
        Path root = tmp.resolve("opt/sumo");
        Path binary = root.resolve("bin/sumo");
        Files.createDirectories(binary.getParent());
        Files.createFile(binary);

        assertThat(SynthesisProperties.Sumo.rootOf(binary)).isEqualTo(root.toString());
    }

    @Test
    void binaryOnPathFindsExecutableAndResolvesSymlinks(@TempDir Path tmp) throws IOException {
        Path realBin = tmp.resolve("versions/1.27.1/bin");
        Path real = realBin.resolve("sumo");
        Files.createDirectories(realBin);
        Files.createFile(real);
        assertThat(real.toFile().setExecutable(true)).isTrue();

        Path linkDir = tmp.resolve("shim");
        Files.createDirectories(linkDir);
        Files.createSymbolicLink(linkDir.resolve("sumo"), real);

        String path = tmp.resolve("leer") + java.io.File.pathSeparator + linkDir;

        assertThat(SynthesisProperties.Sumo.binaryOnPath(path, "sumo"))
                .isEqualTo(real.toRealPath());
    }

    @Test
    void binaryOnPathReturnsNullWhenAbsent(@TempDir Path tmp) {
        assertThat(SynthesisProperties.Sumo.binaryOnPath(null, "sumo")).isNull();
        assertThat(SynthesisProperties.Sumo.binaryOnPath("  ", "sumo")).isNull();
        assertThat(SynthesisProperties.Sumo.binaryOnPath(tmp.toString(), "sumo")).isNull();
    }
}
