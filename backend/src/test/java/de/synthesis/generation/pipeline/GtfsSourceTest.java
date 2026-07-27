package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prüft die GTFS-Quellenauflösung (Auto-Fetch nach Region + Zuschnitt) ohne echten Download:
 * der Verbund-Feed liegt bereits im Raw-Cache, den {@link GtfsSource} bevorzugt. Damit wird
 * die Feed-Registry-Auswahl (Bbox-Abdeckung) und der Zuschnitt end-to-end getestet, ohne den
 * langsamen gtfs2pt-Schritt.
 */
class GtfsSourceTest {

    private static final Path KVV_GTFS = Path.of("../validation/kvv-gtfs.zip");

    @Test
    void autoFetchCropsRegionFeedFromCache() throws Exception {
        assumeTrue(Files.exists(KVV_GTFS), "KVV-GTFS-Cache nicht vorhanden – Test übersprungen");

        // Raw-Cache mit dem vorhandenen KVV-Feed vorbelegen (Name muss zum Feed passen)
        Path rawCache = Files.createTempDirectory("gtfs-raw");
        Files.copy(KVV_GTFS, rawCache.resolve("kvv.zip"));
        Path cropped = Files.createTempFile("cropped", ".zip");

        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.825;
        region.minLon = 8.155;
        region.maxLat = 48.885;
        region.maxLon = 8.245;

        SynthesisProperties.Transit config = new SynthesisProperties.Transit();
        GtfsSource source = new GtfsSource(config);
        try {
            GtfsSource.Resolved resolved = source.resolve(region, null, rawCache, cropped, msg -> { });

            assertNotNull(resolved, "KVV deckt Rastatt ab – Feed sollte aufgelöst werden");
            assertEquals("fetch", resolved.source(), "Auto-Fetch (aus Raw-Cache) erwartet");
            assertEquals("kvv", resolved.feedName());
            assertTrue(resolved.crop().trips() > 0, "Zugeschnittener Feed muss Fahrten enthalten");
            assertTrue(Files.exists(cropped) && Files.size(cropped) > 0, "Cropped-Feed geschrieben");
            // Zeitfenster-Default (6–10 Uhr) reduziert gegenüber dem ganzen Tag
            assertTrue(Files.size(cropped) < Files.size(KVV_GTFS) / 20, "deutlich kleiner als Rohfeed");
        } finally {
            Files.deleteIfExists(cropped);
            try (var paths = Files.walk(rawCache)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    @Test
    void noFeedForUncoveredRegionWithoutUpload() throws Exception {
        Path rawCache = Files.createTempDirectory("gtfs-raw-empty");
        Path cropped = Files.createTempFile("cropped-none", ".zip");
        // Ohne Registry-Feed und ohne Upload muss die ÖV-Stufe sauber übersprungen werden.
        Scenario.Region region = new Scenario.Region();
        region.minLat = 54.0;
        region.minLon = 8.0;
        region.maxLat = 54.1;
        region.maxLon = 8.1;
        try {
            SynthesisProperties.Transit config = new SynthesisProperties.Transit();
            config.setFeeds(List.of());
            GtfsSource.Resolved resolved = new GtfsSource(config)
                    .resolve(region, null, rawCache, cropped, msg -> { });
            org.junit.jupiter.api.Assertions.assertNull(resolved,
                    "Ohne abdeckenden Feed und ohne Upload → null (ÖV übersprungen)");
        } finally {
            Files.deleteIfExists(cropped);
            deleteRecursively(rawCache);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Best effort in test cleanup.
                }
            });
        }
    }
}
