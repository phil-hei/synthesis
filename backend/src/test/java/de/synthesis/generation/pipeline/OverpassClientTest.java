package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
