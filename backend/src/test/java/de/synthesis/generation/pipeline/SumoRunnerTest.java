package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifiziert den nativen (pyproj-freien) SUMO-Pfad der Achse B: netconvert →
 * fromLonLat-Trips → duarouter (--mapmatch.distance) → sumo → vehicleTripStatistics.
 *
 * <p>Der Test ist self-contained, aber von lokalen Voraussetzungen abhängig
 * (installierte SUMO-Binaries, vorhandenes Demand-CSV, erreichbares OSM) und
 * überspringt sich via {@code assumeTrue}, falls etwas fehlt.
 */
class SumoRunnerTest {

    /** Rastatt-Ausschnitt, passend zum hinterlegten Demand-CSV. */
    private static final Path DEMAND_CSV = Path.of(
            "../validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv");
    private static final Path CACHED_OSM = Path.of("/tmp/rastatt.osm");

    @Test
    void runsTrafficSimulationFromDemand() throws Exception {
        SynthesisProperties.Sumo config = new SynthesisProperties.Sumo();
        config.setMaxTrips(2000); // genug für aussagekräftige Statistik, schnell genug für CI
        SumoRunner runner = new SumoRunner(config);

        assumeTrue(runner.isAvailable(), "SUMO-Binaries/ SUMO_HOME nicht verfügbar – Test übersprungen");
        assumeTrue(Files.exists(DEMAND_CSV), "Demand-CSV nicht vorhanden – Test übersprungen");

        String osm = loadOsm();
        assumeTrue(osm != null && osm.contains("<way"), "Kein OSM-Netz verfügbar – Test übersprungen");

        Path artifacts = Files.createTempDirectory("sumo-test");
        try {
            SumoRunner.Result result = runner.run(artifacts, osm, DEMAND_CSV, msg -> { });

            assertTrue(result.vehicles() > 0, "Es sollten Fahrzeuge simuliert werden");
            assertTrue(result.meanRouteLengthM() > 0, "Routenlänge > 0 erwartet");
            assertTrue(result.meanSpeedMs() > 0, "Geschwindigkeit > 0 erwartet");
            assertTrue(result.meanDurationSec() > 0, "Reisedauer > 0 erwartet");
        } finally {
            deleteRecursively(artifacts);
        }
    }

    /** Gecachtes OSM bevorzugen; sonst live über Overpass (inkl. Mirror-Fallback) holen. */
    private String loadOsm() {
        if (Files.exists(CACHED_OSM)) {
            try {
                return Files.readString(CACHED_OSM);
            } catch (Exception ignored) {
                // fällt auf Overpass zurück
            }
        }
        try {
            SynthesisProperties properties = new SynthesisProperties();
            OverpassClient overpass = new OverpassClient(properties, new ObjectMapper());
            Scenario.Region region = new Scenario.Region();
            region.minLat = 48.825;
            region.minLon = 8.155;
            region.maxLat = 48.885;
            region.maxLon = 8.245;
            return overpass.fetchRoadNetworkOsm(region);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
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
