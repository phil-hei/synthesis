package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Voll-Integrationstest der ÖV-Kette (Phase C): GTFS-Auto-Fetch/Zuschnitt → gtfs2pt.py
 * (Fahrplan-Infrastruktur) → SumoRunner mit intermodaler Personen-Routung. Endet mit realen
 * ÖV-Fahrzeugen und mitfahrenden Nachfrage-Personen.
 *
 * <p>Bewusst NICHT in jedem Lauf aktiv: gtfs2pt.py (tracemapper) dauert mehrere Minuten. Der
 * Test läuft nur, wenn {@code SYNTHESIS_TRANSIT_VENV} auf einen Python-Interpreter mit
 * pandas/rtree/pyproj zeigt – sonst überspringt er sich (wie SumoRunnerTest ohne SUMO).
 */
class PtIntegrationTest {

    private static final Path DEMAND_CSV = Path.of(
            "../validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv");
    private static final Path CACHED_OSM = Path.of("../validation/rastatt-test.osm");
    private static final Path KVV_GTFS = Path.of("../validation/kvv-gtfs.zip");

    @Test
    void fullTransitChainRidesBuses() throws Exception {
        String venv = System.getenv("SYNTHESIS_TRANSIT_VENV");
        assumeTrue(venv != null && !venv.isBlank() && Files.exists(Path.of(venv)),
                "SYNTHESIS_TRANSIT_VENV nicht gesetzt – ÖV-Integrationstest übersprungen");
        assumeTrue(Files.exists(DEMAND_CSV) && Files.exists(CACHED_OSM) && Files.exists(KVV_GTFS),
                "Rastatt-Fixtures (Demand/OSM/KVV) fehlen – Test übersprungen");

        SynthesisProperties.Sumo sumoConfig = new SynthesisProperties.Sumo();
        sumoConfig.setMaxTrips(1500);
        SumoRunner sumo = new SumoRunner(sumoConfig);
        assumeTrue(sumo.isAvailable(), "SUMO nicht verfügbar – Test übersprungen");

        SynthesisProperties.Transit config = new SynthesisProperties.Transit();
        config.setVenvPython(venv);
        config.setWindowStartHour(6);
        config.setWindowEndHour(9);
        TransitRunner transit = new TransitRunner(config, sumoConfig.getSumoHome());
        assumeTrue(transit.isAvailable(), "gtfs2pt/venv nicht verfügbar – Test übersprungen");

        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.825;
        region.minLon = 8.155;
        region.maxLat = 48.885;
        region.maxLon = 8.245;

        Path artifacts = Files.createTempDirectory("pt-test");
        Path rawCache = Files.createDirectory(artifacts.resolve("gtfs-raw"));
        Files.copy(KVV_GTFS, rawCache.resolve("kvv.zip")); // Auto-Fetch aus Cache (kein Download)
        Path transitDir = artifacts.resolve("sumo").resolve("transit");
        Path cropped = transitDir.resolve("feed-cropped.zip");
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        GtfsSource gtfs = new GtfsSource(config);
        SumoRunner.PtProvider provider = net -> {
            Files.createDirectories(transitDir);
            GtfsSource.Resolved resolved = gtfs.resolve(region, null, rawCache, cropped, msg -> { });
            assertNotNull(resolved, "GTFS-Feed sollte per Auto-Fetch aufgelöst werden");
            return transit.build(net, resolved.croppedFeed(), date, transitDir, msg -> { });
        };

        try {
            String osm = Files.readString(CACHED_OSM);
            SumoRunner.Result result = sumo.run(artifacts, osm, DEMAND_CSV, null, provider, msg -> { });

            assertTrue(result.ptVehicles() > 0, "gtfs2pt sollte ÖV-Fahrzeuge erzeugen");
            assertTrue(result.ptRiders() > 0, "ÖV-Nachfrage-Personen sollten eingespeist werden");
            assertTrue(result.vehicles() > 0, "PKW/Rad sollten weiterhin simuliert werden");
            // Die intermodalen Personen müssen in geroutete <ride>-Etappen münden
            String routed = Files.readString(artifacts.resolve("sumo").resolve("routes.rou.xml"));
            assertTrue(routed.contains("<ride "), "Mindestens eine ÖV-Ride-Etappe erwartet");
        } finally {
            try (var paths = Files.walk(artifacts)) {
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
}
