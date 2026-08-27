package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CensusPopulationSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsVerifiesCachesAndFiltersOfficialGridDialect() throws Exception {
        byte[] archive = archive("population.csv", csvPoint(48.86, 8.20, 250)
                + csvPoint(52.52, 13.40, 500));
        SynthesisProperties.Census config = config(sha256(archive));
        AtomicInteger downloads = new AtomicInteger();
        CensusPopulationSource source = new CensusPopulationSource(config, (uri, timeout) -> {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        });
        List<String> log = new ArrayList<>();

        var first = source.resolve(rastatt(), tempDir, log::add);
        var second = source.resolve(rastatt(), tempDir, log::add);

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().grid().points()).hasSize(1);
        assertThat(first.orElseThrow().grid().totalPopulation()).isEqualTo(250);
        assertThat(first.orElseThrow().provenance().referenceDate()).isEqualTo("2022-05-15");
        assertThat(second).isPresent();
        assertThat(downloads).hasValue(1);
        assertThat(Files.size(tempDir.resolve("test-2022/Zensus2022_Bevoelkerungszahl_100m-Gitter.csv")))
                .isPositive();
    }

    @Test
    void rejectsUnexpectedArchiveHashAndLeavesFallbackAvailable() throws Exception {
        byte[] archive = archive("population.csv", csvPoint(48.86, 8.20, 250));
        CensusPopulationSource source = new CensusPopulationSource(config("0".repeat(64)),
                (uri, timeout) -> new ByteArrayInputStream(archive));
        List<String> log = new ArrayList<>();

        var result = source.resolve(rastatt(), tempDir, log::add);

        assertThat(result).isEmpty();
        assertThat(log).anyMatch(line -> line.contains("SHA-256-Abweichung"));
        assertThat(tempDir.resolve("test-2022/Zensus2022_Bevoelkerungszahl_100m-Gitter.csv"))
                .doesNotExist();
    }

    @Test
    void skipsDownloadOutsideGermany() {
        AtomicInteger downloads = new AtomicInteger();
        CensusPopulationSource source = new CensusPopulationSource(config("0".repeat(64)),
                (uri, timeout) -> {
                    downloads.incrementAndGet();
                    return InputStream.nullInputStream();
                });
        Scenario.Region paris = new Scenario.Region();
        paris.minLat = 48.80;
        paris.maxLat = 48.90;
        paris.minLon = 2.25;
        paris.maxLon = 2.45;

        assertThat(source.resolve(paris, tempDir, ignored -> { })).isEmpty();
        assertThat(downloads).hasValue(0);
    }

    private static SynthesisProperties.Census config(String sha256) {
        SynthesisProperties.Census config = new SynthesisProperties.Census();
        config.setVersion("test-2022");
        config.setSourceUrl("https://example.invalid/census.zip");
        config.setArchiveSha256(sha256);
        config.setGridEntry("population.csv");
        return config;
    }

    private static Scenario.Region rastatt() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.maxLat = 48.88;
        region.minLon = 8.17;
        region.maxLon = 8.24;
        return region;
    }

    private static String csvPoint(double lat, double lon, int population) {
        double[] laea = Epsg3035.fromLatLon(lat, lon);
        return String.format(Locale.ROOT, "cell;%.3f;%.3f;%d%n",
                laea[0], laea[1], population);
    }

    private static byte[] archive(String entry, String rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(("GITTER_ID_100m;x_mp_100m;y_mp_100m;Einwohner\n" + rows)
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
