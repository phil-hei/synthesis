package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifiziert die neue Forward-Projektion {@link Epsg3035#fromLatLon} gegen die vorhandene
 * Inverse: Ursprungs-Anker (Projektionszentrum 52° N/10° E → False-Origin) und Roundtrip in
 * beide Richtungen für mehrere europäische Punkte.
 */
class Epsg3035Test {

    @Test
    void projectionCentreMapsToFalseOrigin() {
        double[] en = Epsg3035.fromLatLon(52.0, 10.0);
        assertEquals(4_321_000.0, en[0], 1e-3, "Easting am Zentrum = False-Easting");
        assertEquals(3_210_000.0, en[1], 1e-3, "Northing am Zentrum = False-Northing");
    }

    @Test
    void roundTripLatLonToLaeaAndBack() {
        double[][] pts = {
                {48.855, 8.20},   // Rastatt
                {52.52, 13.405},  // Berlin
                {48.137, 11.575}, // München
                {43.30, 5.37},    // Marseille
                {59.33, 18.07}};  // Stockholm
        for (double[] p : pts) {
            double[] en = Epsg3035.fromLatLon(p[0], p[1]);
            double[] back = Epsg3035.toLatLon(en[0], en[1]);
            assertEquals(p[0], back[0], 1e-6, "Lat-Roundtrip @ " + p[0] + "," + p[1]);
            assertEquals(p[1], back[1], 1e-6, "Lon-Roundtrip @ " + p[0] + "," + p[1]);
        }
    }

    @Test
    void roundTripLaeaToLatLonAndBack() {
        // Eine INSPIRE-1-km-Zellecke in Deutschland (Vielfache von 1000 m)
        double e = 4_341_000;
        double n = 2_692_000;
        double[] latLon = Epsg3035.toLatLon(e, n);
        double[] en = Epsg3035.fromLatLon(latLon[0], latLon[1]);
        assertTrue(Math.abs(en[0] - e) < 0.01 && Math.abs(en[1] - n) < 0.01,
                "LAEA-Roundtrip < 1 cm: " + en[0] + "," + en[1]);
    }

    @Test
    void coversOnlyTheEuropeanAreaOfUse() {
        assertTrue(Epsg3035.covers(48.80, 8.15, 48.90, 8.27), "Rastatt liegt im EU/EWR-Raum");
        assertTrue(Epsg3035.covers(59.2, 17.9, 59.4, 18.2), "Stockholm liegt im EU/EWR-Raum");
        assertTrue(!Epsg3035.covers(40.6, -74.1, 40.8, -73.9), "New York liegt außerhalb");
        assertTrue(!Epsg3035.covers(35.0, -20.0, 48.0, 8.0),
                "Boxen, die den Gültigkeitsrand überlappen, gelten als außerhalb");
    }
}
