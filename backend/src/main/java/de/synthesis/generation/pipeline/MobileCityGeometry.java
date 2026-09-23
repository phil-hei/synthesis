package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.*;

/** Shared true zone corners and Polygon/MultiPolygon rings, including holes. */
final class MobileCityGeometry {
    static final GeometryFactory FACTORY = new GeometryFactory();
    static Polygon zone(Zone z) {
        double[][] corners = {{z.minLon,z.maxLat},{z.maxLon,z.maxLat},{z.maxLon,z.minLat},{z.minLon,z.minLat},{z.minLon,z.maxLat}};
        if (z.laeaRes > 0) {
            double[][] en = {{z.laeaE,z.laeaN},{z.laeaE,z.laeaN+z.laeaRes},{z.laeaE+z.laeaRes,z.laeaN+z.laeaRes},{z.laeaE+z.laeaRes,z.laeaN},{z.laeaE,z.laeaN}};
            for (int i = 0; i < en.length; i++) {
                double[] ll = Epsg3035.toLatLon(en[i][0], en[i][1]); corners[i] = new double[]{ll[1], ll[0]};
            }
        }
        return FACTORY.createPolygon(java.util.Arrays.stream(corners).map(p -> new Coordinate(p[0],p[1])).toArray(Coordinate[]::new));
    }
    static Geometry read(JsonNode node) {
        if ("Polygon".equals(node.path("type").asText())) return polygon(node.get("coordinates"));
        if (!"MultiPolygon".equals(node.path("type").asText())) throw new IllegalArgumentException("District muss Polygon/MultiPolygon sein");
        JsonNode coords = node.get("coordinates"); Polygon[] polygons = new Polygon[coords.size()];
        for (int i = 0; i < polygons.length; i++) polygons[i] = polygon(coords.get(i));
        return FACTORY.createMultiPolygon(polygons);
    }
    private static Polygon polygon(JsonNode coords) {
        LinearRing[] holes = new LinearRing[coords.size() - 1];
        for (int i = 0; i < holes.length; i++) holes[i] = ring(coords.get(i+1));
        return FACTORY.createPolygon(ring(coords.get(0)), holes);
    }
    private static LinearRing ring(JsonNode coords) {
        Coordinate[] points = new Coordinate[coords.size()];
        for (int i = 0; i < points.length; i++) points[i] = new Coordinate(coords.get(i).get(0).asDouble(),coords.get(i).get(1).asDouble());
        return FACTORY.createLinearRing(points);
    }
    static Geometry project(Geometry wgs84) {
        Geometry projected = wgs84.copy();
        projected.apply((CoordinateFilter) c -> { double[] en = Epsg3035.fromLatLon(c.y,c.x); c.x=en[0]; c.y=en[1]; });
        projected.geometryChanged(); return projected;
    }
    static Geometry unproject(Geometry epsg3035) {
        Geometry wgs84 = epsg3035.copy();
        wgs84.apply((CoordinateFilter) c -> { double[] ll = Epsg3035.toLatLon(c.x,c.y); c.x=ll[1]; c.y=ll[0]; });
        wgs84.geometryChanged(); return wgs84;
    }
}
