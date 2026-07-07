package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prüft den GTFS-Bbox-Zuschnitt an einem echten Verbund-Feed (KVV, ~120 MB) auf die
 * Rastatt-Region: kräftige Größenreduktion, verworfene shapes.txt und – am wichtigsten –
 * referenzielle Integrität (jede trip_id in stop_times steht auch in trips.txt).
 *
 * <p>Der Feed liegt im persistenten, gitignorierten {@code validation/}-Cache; fehlt er,
 * überspringt sich der Test via {@code assumeTrue}.
 */
class GtfsCropperTest {

    /** Rastatt-Ausschnitt, deckungsgleich mit SumoRunnerTest. */
    private static final Path KVV_GTFS = Path.of("../validation/kvv-gtfs.zip");

    @Test
    void cropsFeedToRegionKeepingReferentialIntegrity() throws Exception {
        assumeTrue(Files.exists(KVV_GTFS), "KVV-GTFS-Cache nicht vorhanden – Test übersprungen");

        Path out = Files.createTempFile("rastatt-gtfs", ".zip");
        try {
            GtfsCropper cropper = new GtfsCropper(48.825, 8.155, 48.885, 8.245);
            GtfsCropper.Result result = cropper.crop(KVV_GTFS, out);

            assertTrue(result.stopsInBbox() > 0, "Halte in der Bbox erwartet");
            assertTrue(result.trips() > 0, "Fahrten durch die Region erwartet");
            assertTrue(result.routes() > 0, "Linien erwartet");
            // Kräftige Reduktion: der zugeschnittene Feed ist um Größenordnungen kleiner
            assertTrue(Files.size(out) < Files.size(KVV_GTFS) / 20,
                    "Zugeschnittener Feed sollte deutlich kleiner sein");

            try (ZipFile zip = new ZipFile(out.toFile())) {
                assertNull(zip.getEntry("shapes.txt"), "shapes.txt muss verworfen sein");
                assertTrue(hasRows(zip, "stops.txt"), "stops.txt darf nicht leer sein");
                assertTrue(hasRows(zip, "trips.txt"), "trips.txt darf nicht leer sein");

                Set<String> tripIds = columnValues(zip, "trips.txt", "trip_id");
                Set<String> stopIds = columnValues(zip, "stops.txt", "stop_id");
                // Jede in stop_times referenzierte Fahrt/Haltestelle muss auch existieren
                Set<String> stopTimeTrips = columnValues(zip, "stop_times.txt", "trip_id");
                Set<String> stopTimeStops = columnValues(zip, "stop_times.txt", "stop_id");
                assertTrue(tripIds.containsAll(stopTimeTrips),
                        "Alle stop_times-Fahrten müssen in trips.txt stehen");
                assertTrue(stopIds.containsAll(stopTimeStops),
                        "Alle stop_times-Halte müssen in stops.txt stehen");
                assertEquals(result.trips(), tripIds.size(), "trips()-Zähler == trips.txt-Zeilen");
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void timeWindowReducesTripCount() throws Exception {
        assumeTrue(Files.exists(KVV_GTFS), "KVV-GTFS-Cache nicht vorhanden – Test übersprungen");

        Path full = Files.createTempFile("gtfs-full", ".zip");
        Path win = Files.createTempFile("gtfs-win", ".zip");
        try {
            GtfsCropper.Result all = new GtfsCropper(48.825, 8.155, 48.885, 8.245).crop(KVV_GTFS, full);
            // Morgenspitze 6–10 Uhr
            GtfsCropper.Result peak = new GtfsCropper(48.825, 8.155, 48.885, 8.245,
                    6 * 3600, 10 * 3600).crop(KVV_GTFS, win);

            assertTrue(peak.trips() > 0, "Auch im Fenster sollten Fahrten bleiben");
            assertTrue(peak.trips() < all.trips(),
                    "Zeitfenster muss die Fahrten-Zahl reduzieren (" + peak.trips()
                            + " < " + all.trips() + ")");
        } finally {
            Files.deleteIfExists(full);
            Files.deleteIfExists(win);
        }
    }

    private static boolean hasRows(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            return reader.readLine() != null;
        }
    }

    private static Set<String> columnValues(ZipFile zip, String name, String column) throws Exception {
        Set<String> values = new HashSet<>();
        ZipEntry entry = zip.getEntry(name);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            List<String> header = GtfsCropper.parseCsv(reader.readLine());
            int idx = header.indexOf(column);
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    List<String> cells = GtfsCropper.parseCsv(line);
                    if (idx >= 0 && idx < cells.size()) {
                        values.add(cells.get(idx));
                    }
                }
            }
        }
        return values;
    }
}
