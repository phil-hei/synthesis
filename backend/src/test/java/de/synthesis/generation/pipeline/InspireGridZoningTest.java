package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die INSPIRE-/EPSG:3035-Zonierung: Einrasten auf das globale Gitter, INSPIRE-Zell-IDs,
 * überlappungsfreies Containment (in LAEA) und die Auflösungswahl.
 */
class InspireGridZoningTest {

    private static Scenario.Region rastatt() {
        Scenario.Region r = new Scenario.Region();
        r.minLat = 48.80;
        r.minLon = 8.15;
        r.maxLat = 48.90;
        r.maxLon = 8.27;
        return r;
    }

    @Test
    void cellsSnapToGlobalGridWithInspireIds() {
        List<Zone> zones = InspireGridZoning.generate(rastatt(), 1000, 600);
        assertTrue(zones.size() > 10, "erwarte mehrere Zonen, waren " + zones.size());
        for (Zone z : zones) {
            assertEquals(1000, z.laeaRes);
            assertEquals(0, z.laeaE % 1000, "SW-Ecke rastet aufs 1-km-Gitter (Easting)");
            assertEquals(0, z.laeaN % 1000, "SW-Ecke rastet aufs 1-km-Gitter (Northing)");
            assertTrue(z.name.startsWith("CRS3035RES1000mN"), "INSPIRE-Zell-ID: " + z.name);
            assertEquals(1.0, z.areaKm2, 1e-9, "1-km-Zelle = 1 km²");
        }
    }

    @Test
    void containmentIsUniqueAndNonOverlapping() {
        List<Zone> zones = InspireGridZoning.generate(rastatt(), 1000, 600);
        // Jeder Zellmittelpunkt liegt in GENAU einer Zone (saubere Partition, keine Überlappung)
        for (Zone z : zones) {
            long hits = zones.stream().filter(o -> o.contains(z.centroidLat, z.centroidLon)).count();
            assertEquals(1, hits, "Zentroid von " + z.name + " in genau einer Zone, waren " + hits);
        }
        // Ein Punkt in der Region wird zugeordnet
        int idx = GridZoning.zoneIndexOf(zones, 48.855, 8.20);
        assertTrue(idx >= 0, "Punkt in der Region muss einer Zone zugeordnet werden");
    }

    @Test
    void pickResolutionMatchesRequestAndCoarsensForLargeRegions() {
        // Default 1000 m → 1-km-Gitter für ein kleines Gebiet
        assertEquals(1000, InspireGridZoning.pickResolution(rastatt(), 1000, 600));
        // Sehr großes Gebiet (~5° ≈ 550 km) → 1 km spränge das Limit → 10 km
        Scenario.Region big = new Scenario.Region();
        big.minLat = 47.0;
        big.minLon = 7.0;
        big.maxLat = 52.0;
        big.maxLon = 12.0;
        assertEquals(10000, InspireGridZoning.pickResolution(big, 1000, 600));
    }
}
