package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalZonesTest {

    @Test
    void createsTwelveKilometreRingWithFourfoldAttractivity() {
        Zone west = internalZone(50, 48.85, 8.19, 10);
        Zone east = internalZone(60, 48.85, 8.21, 6);

        List<Zone> external = ExternalZones.create(List.of(west, east));

        assertEquals(8, external.size());
        for (int i = 0; i < external.size(); i++) {
            Zone zone = external.get(i);
            assertEquals(2 + i, zone.index);
            assertEquals(61 + i, zone.id);
            assertEquals("EXT-" + i, zone.name);
            assertTrue(zone.external);
            assertEquals(0, zone.population);
            assertEquals(0, zone.households);

            Zone centre = internalZone(0, 48.85, 8.20, 0);
            assertEquals(ExternalZones.radiusKm(), GridZoning.distanceKm(centre, zone), 0.05,
                    "jede externe Zone liegt auf demselben Ring");
        }

        for (String category : AttractivityCalculator.CATEGORIES) {
            double total = external.stream()
                    .mapToDouble(zone -> zone.attractivities.get(category))
                    .sum();
            assertEquals((10 + 6) * 4.0, total, 1e-9,
                    "externe Gesamtattraktivität für " + category);
        }
    }

    @Test
    void emptyGridCreatesNoExternalZones() {
        assertTrue(ExternalZones.create(List.of()).isEmpty());
    }

    @Test
    void adaptiveAnchorsStayOutsideLargeStudyAreaAndInsideBufferSectors() {
        var region=new de.synthesis.scenario.Scenario.Region();
        region.minLat=48.7;region.maxLat=49.0;region.minLon=7.9;region.maxLon=8.5;
        List<Zone> internal=GridZoning.generate(region,10_000,100);
        List<Zone> external=ExternalZones.create(internal,20d);
        var sectors=MobileCitySurroundingSource.sectorGeometries(region,20);

        assertEquals(8,external.size());
        for(int i=0;i<external.size();i++){
            Zone anchor=external.get(i);
            assertTrue(!internal.stream().anyMatch(z->z.contains(anchor.centroidLat,anchor.centroidLon)));
            assertTrue(sectors.get(i).covers(new org.locationtech.jts.geom.GeometryFactory()
                    .createPoint(new org.locationtech.jts.geom.Coordinate(anchor.centroidLon,anchor.centroidLat))));
        }
    }

    private static Zone internalZone(long id, double lat, double lon, double attractivity) {
        Zone zone = new Zone();
        zone.id = id;
        zone.centroidLat = lat;
        zone.centroidLon = lon;
        for (String category : AttractivityCalculator.CATEGORIES) {
            zone.attractivities.put(category, attractivity);
        }
        return zone;
    }
}
