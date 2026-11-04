package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import de.synthesis.data.spatial.SpatialGeometry;
import org.locationtech.jts.geom.*;

/** Compatibility facade; the same geometry operations also serve source adapters. */
final class MobileCityGeometry {
    static final GeometryFactory FACTORY=SpatialGeometry.FACTORY;
    static Polygon zone(Zone zone){return SpatialGeometry.zone(zone);}
    static Geometry read(JsonNode node){return SpatialGeometry.read(node);}
    static Geometry project(Geometry geometry){return SpatialGeometry.project(geometry);}
    static Geometry unproject(Geometry geometry){return SpatialGeometry.unproject(geometry);}
}
