package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifiziert (ohne Netz), dass der Straßennetz-Abruf sein eigenes, großzügiges
 * Overpass-Zeitbudget einbettet — der schwere {@code way["highway"]}+recurse-Query
 * lief unter dem alten 30-s-Budget regelmäßig in den Timeout (→ Luftlinien-Fallback,
 * kontaminierte Distanz-Baseline).
 */
class OverpassClientTest {

    private static Scenario.Region rastatt() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.825;
        region.minLon = 8.155;
        region.maxLat = 48.885;
        region.maxLon = 8.245;
        return region;
    }

    @Test
    void roadNetworkQueryEmbedsConfiguredTimeout() {
        SynthesisProperties props = new SynthesisProperties();
        OverpassClient client = new OverpassClient(props, new ObjectMapper());

        String query = client.roadNetworkQuery(rastatt());
        assertTrue(query.contains("[out:xml][timeout:180]"),
                "Default-Serverbudget 180 s wird eingebettet (nicht das allgemeine 30-s-Budget)");
        assertTrue(query.contains("way[\"highway\"]"), "Straßennetz-Query erwartet");

        props.getOverpass().setRoadNetworkTimeoutSeconds(240);
        assertTrue(client.roadNetworkQuery(rastatt()).contains("[out:xml][timeout:240]"),
                "Konfigurierter Wert wird durchgereicht");
    }

    @Test
    void lightQueriesKeepGeneralTimeout() {
        SynthesisProperties props = new SynthesisProperties();
        // Das allgemeine Budget bleibt für die leichten POI-/Place-Queries unverändert (Default 30 s).
        assertTrue(props.getOverpass().getTimeoutSeconds() == 30,
                "Allgemeines Overpass-Timeout unverändert");
        assertTrue(props.getOverpass().getRoadNetworkTimeoutSeconds() >= 120,
                "Straßennetz-Budget deutlich großzügiger als das allgemeine");
    }

    @Test
    void placeQueryCoversRemoteCandidatesWithoutExpandingPopulationFootprint() {
        SynthesisProperties props = new SynthesisProperties();
        OverpassClient client = new OverpassClient(props, new ObjectMapper());

        String query = client.placeQuery(rastatt(), true);

        assertTrue(query.contains("[out:json][timeout:30]"));
        String bbox = query.substring(query.indexOf("](") + 2, query.indexOf(");"));
        String[] coordinates = bbox.split(",");
        double minLat = Double.parseDouble(coordinates[0]);
        double maxLat = Double.parseDouble(coordinates[2]);
        assertTrue(minLat < 48.15);
        assertTrue(maxLat > 49.56);
        assertTrue(Double.parseDouble(coordinates[1]) < 7.12);
        assertTrue(Double.parseDouble(coordinates[3]) > 9.28);

        var local = new OverpassClient.Place("Local", "town", 48.85, 8.20, 20_000);
        var remote = new OverpassClient.Place("Remote", "city", 49.10, 8.20, 5_000_000);
        var filtered = OverpassClient.populationPlaces(rastatt(), List.of(local, remote));
        assertEquals(List.of(local), filtered);
        assertTrue(PopulationEstimator.estimateForRegion(rastatt(), List.of(local, remote))
                > PopulationEstimator.estimateForRegion(rastatt(), filtered));
        String localQuery = client.placeQuery(rastatt(), false);
        double localSouth = Double.parseDouble(localQuery.substring(
                localQuery.indexOf("](") + 2, localQuery.indexOf(',', localQuery.indexOf("]("))));
        assertEquals(48.765, localSouth, 1e-9);
    }

    @Test
    void configuredRoadNetworkFileReplacesOnlyTheNetworkRequest(@TempDir Path tempDir) throws Exception {
        Path fixture = tempDir.resolve("network.osm");
        String osm = "<?xml version=\"1.0\"?><osm version=\"0.6\"></osm>";
        Files.writeString(fixture, osm);

        SynthesisProperties props = new SynthesisProperties();
        props.getOverpass().setRoadNetworkFile(fixture.toString());
        OverpassClient client = new OverpassClient(props, new ObjectMapper());

        assertEquals(osm, client.fetchRoadNetworkOsm(rastatt()));
        assertTrue(client.roadNetworkSource().contains(fixture.toAbsolutePath().toString()));
    }

    @Test
    void localRastattExtractBuildsARoutableGraph() throws Exception {
        Path fixture = Path.of("../validation/rastatt-test.osm").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(fixture),
                "Lokales Rastatt-OSM-Fixture fehlt; vollständiger GraphHopper-Import wird übersprungen");

        SynthesisProperties props = new SynthesisProperties();
        props.getOverpass().setRoadNetworkFile(fixture.toString());
        OverpassClient client = new OverpassClient(props, new ObjectMapper());
        List<Zone> zones = GridZoning.generate(rastatt(), 1_000, 600);

        boolean routable = false;
        try (RoutingEngine engine = RoutingEngine.fromOsmXml(client.fetchRoadNetworkOsm(rastatt()))) {
            for (int from = 0; from < zones.size() && !routable; from++) {
                for (int to = from + 1; to < zones.size(); to++) {
                    if (engine.impedance(zones.get(from), zones.get(to),
                            MatrixGenerator.TravelMode.CAR) != null) {
                        routable = true;
                        break;
                    }
                }
            }
        }
        assertTrue(routable, "Lokales OSM-XML muss mindestens ein Zonenpaar routbar machen");
    }
}
