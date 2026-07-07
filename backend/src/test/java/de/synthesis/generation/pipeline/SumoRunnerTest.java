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
    private static final Path PLANNED_TOURS = Path.of(
            "../validation/mobitopp-rastatt/output/synthesis/results/planned-tours.csv");
    /** Persistenter Test-Cache (validation/ ist gitignored; /tmp wird gereaped). */
    private static final Path CACHED_OSM = Path.of("../validation/rastatt-test.osm");

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
            Path tours = Files.exists(PLANNED_TOURS) ? PLANNED_TOURS : null;
            SumoRunner.Result result = runner.run(artifacts, osm, DEMAND_CSV, tours, msg -> { });

            assertTrue(result.vehicles() > 0, "Es sollten Fahrzeuge simuliert werden");
            assertTrue(result.meanRouteLengthM() > 0, "Routenlänge > 0 erwartet");
            assertTrue(result.meanSpeedMs() > 0, "Geschwindigkeit > 0 erwartet");
            assertTrue(result.meanDurationSec() > 0, "Reisedauer > 0 erwartet");
            assertTrue(result.carTrips() > 0, "PKW-Trips eingespeist");
            assertTrue(result.bikeTrips() > 0, "Rad-Trips (multimodal) eingespeist");
            assertTrue(result.walkTrips() > 0, "Fuß-Trips (Fußgänger als SUMO-Personen) eingespeist");
            assertTrue(result.co2Kg() > 0, "CO₂-Emissionen (HBEFA) sollten ausgewertet werden");
            assertTrue(result.co2KgByType().getOrDefault("pkw", 0.0) > 0, "PKW-CO₂ > 0");
            Path congestion = artifacts.resolve("sumo").resolve("congestion.geojson");
            assertTrue(Files.exists(congestion), "Netzlast-GeoJSON fehlt");
            String geo = Files.readString(congestion);
            assertTrue(geo.contains("LineString") && geo.contains("relSpeed"),
                    "Staukarte braucht Kanten mit relSpeed-Attribut");

            // Netz-Feedback: OD-Stau-Faktoren auf dem gebauten Netz (3 Zentroiden in Rastatt)
            java.util.List<Zone> odZones = new java.util.ArrayList<>();
            double[][] centroids = {{48.850, 8.200}, {48.858, 8.210}, {48.865, 8.195}};
            for (int i = 0; i < centroids.length; i++) {
                Zone z = new Zone();
                z.index = i;
                z.centroidLat = centroids[i][0];
                z.centroidLon = centroids[i][1];
                odZones.add(z);
            }
            SumoRunner.OdFactors od = runner.computeOdFactors(
                    artifacts.resolve("sumo"), odZones, msg -> { });
            assertTrue(od.routedPairs() > 0, "OD-Relationen müssen routbar sein");
            for (double[] row : od.factors()) {
                for (double factor : row) {
                    assertTrue(factor >= 1 && factor <= 3, "Faktor in [1,3]: " + factor);
                }
            }
            if (tours != null) {
                assertTrue(result.deliveryTrips() > 0,
                        "Zustelltouren-Legs (Lkw/Lastenrad) sollten eingespeist werden");
                assertTrue(result.co2KgByType().getOrDefault("lkw", 0.0) > 0, "Lkw-CO₂ > 0");
            }
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
            String osm = overpass.fetchRoadNetworkOsm(region);
            try {
                Files.writeString(CACHED_OSM, osm); // Cache selbstheilend auffrischen
            } catch (Exception ignored) {
                // Cache ist optional
            }
            return osm;
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
