package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds a GeoJSON FeatureCollection for the zone grid (map preview + artifact). */
public final class GeoJson {

    private GeoJson() {
    }

    public static ObjectNode zonesToFeatureCollection(ObjectMapper mapper, List<Zone> zones) {
        ObjectNode collection = mapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");
        for (Zone zone : zones) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("zoneId", zone.id);
            properties.put("name", zone.name);
            properties.put("population", Math.round(zone.population));
            properties.put("households", zone.households);
            zone.attractivities.forEach((category, value) ->
                    properties.put("attractivity" + category, Math.round(value)));
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "Polygon");
            ArrayNode ring = geometry.putArray("coordinates").addArray();
            if (zone.laeaRes > 0) {
                // INSPIRE-Zellen sind Quadrate in EPSG:3035. Ihre WGS84-Bounding-Boxen
                // ueberlappen bzw. lassen in der Leaflet-Vorschau scheinbare Spalten frei.
                // Deshalb die vier echten projizierten Ecken als Polygon ausgeben.
                addProjectedPoint(ring, zone.laeaE, zone.laeaN);
                addProjectedPoint(ring, zone.laeaE + zone.laeaRes, zone.laeaN);
                addProjectedPoint(ring, zone.laeaE + zone.laeaRes, zone.laeaN + zone.laeaRes);
                addProjectedPoint(ring, zone.laeaE, zone.laeaN + zone.laeaRes);
                addProjectedPoint(ring, zone.laeaE, zone.laeaN);
            } else {
                addPoint(ring, zone.minLon, zone.minLat);
                addPoint(ring, zone.maxLon, zone.minLat);
                addPoint(ring, zone.maxLon, zone.maxLat);
                addPoint(ring, zone.minLon, zone.maxLat);
                addPoint(ring, zone.minLon, zone.minLat);
            }
        }
        return collection;
    }

    private static void addProjectedPoint(ArrayNode ring, double east, double north) {
        double[] latLon = Epsg3035.toLatLon(east, north);
        addPoint(ring, latLon[1], latLon[0]);
    }

    private static void addPoint(ArrayNode ring, double lon, double lat) {
        ArrayNode point = ring.addArray();
        point.add(lon);
        point.add(lat);
    }
}
