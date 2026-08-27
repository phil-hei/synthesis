package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void inspireCellsUseTheirProjectedCornersInsteadOfLatLonBoundingBoxes() {
        Zone zone = new Zone();
        zone.id = 100_001;
        zone.name = "CRS3035RES1000mN3000000E4300000";
        zone.laeaE = 4_300_000;
        zone.laeaN = 3_000_000;
        zone.laeaRes = 1000;
        // Absichtlich unbrauchbare Bounding-Box: INSPIRE-GeoJSON darf sie nicht verwenden.
        zone.minLat = -1;
        zone.minLon = -2;
        zone.maxLat = 1;
        zone.maxLon = 2;

        JsonNode ring = GeoJson.zonesToFeatureCollection(mapper, List.of(zone))
                .path("features").path(0).path("geometry").path("coordinates").path(0);

        double[] sw = Epsg3035.toLatLon(zone.laeaE, zone.laeaN);
        double[] se = Epsg3035.toLatLon(zone.laeaE + zone.laeaRes, zone.laeaN);
        assertEquals(5, ring.size());
        assertEquals(sw[1], ring.path(0).path(0).doubleValue(), 1e-12);
        assertEquals(sw[0], ring.path(0).path(1).doubleValue(), 1e-12);
        assertEquals(se[1], ring.path(1).path(0).doubleValue(), 1e-12);
        assertEquals(se[0], ring.path(1).path(1).doubleValue(), 1e-12);
        assertEquals(ring.path(0), ring.path(4), "Polygonring muss geschlossen sein");
    }

    @Test
    void bboxGridKeepsItsRectangularCoordinates() {
        Zone zone = new Zone();
        zone.id = 100_001;
        zone.name = "Z0-0";
        zone.minLat = 48.84;
        zone.minLon = 8.18;
        zone.maxLat = 48.85;
        zone.maxLon = 8.19;

        JsonNode ring = GeoJson.zonesToFeatureCollection(mapper, List.of(zone))
                .path("features").path(0).path("geometry").path("coordinates").path(0);

        assertEquals(8.18, ring.path(0).path(0).doubleValue());
        assertEquals(48.84, ring.path(0).path(1).doubleValue());
        assertEquals(8.19, ring.path(2).path(0).doubleValue());
        assertEquals(48.85, ring.path(2).path(1).doubleValue());
    }
}
