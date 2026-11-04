package de.synthesis.data.spatial;

import com.fasterxml.jackson.databind.*;
import de.synthesis.generation.pipeline.Epsg3035;
import org.locationtech.jts.geom.Geometry;

/** Strict polygon parsing for user-provided sources; coordinates are GeoJSON lon/lat. */
public final class AreaGeometry {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AreaGeometry() { }
    public static Geometry read(Object value){
        JsonNode node=JSON.valueToTree(value);String type=node.path("type").asText();
        int[] count={0};
        if(type.equals("Polygon"))polygon(node.path("coordinates"),count);
        else if(type.equals("MultiPolygon")){
            var coordinates=node.path("coordinates");if(!coordinates.isArray()||coordinates.isEmpty())throw new IllegalArgumentException("Leeres MultiPolygon");
            for(var part:coordinates)polygon(part,count);
        }else throw new IllegalArgumentException("Gemeindegeometrie muss Polygon oder MultiPolygon sein");
        var geometry=SpatialGeometry.read(node);
        if(geometry.isEmpty()||!geometry.isValid())throw new IllegalArgumentException("Ungültige Gemeindegeometrie einschließlich Ringen/Löchern");
        var projected=SpatialGeometry.project(geometry);
        if(!projected.isValid()||!Double.isFinite(projected.getArea())||projected.getArea()<=0)throw new IllegalArgumentException("Gemeindegeometrie hat keine gültige Fläche in EPSG:3035");
        return geometry;
    }
    private static void polygon(JsonNode rings,int[] count){
        if(!rings.isArray()||rings.isEmpty())throw new IllegalArgumentException("Polygonringe fehlen");
        for(var ring:rings){
            if(!ring.isArray()||ring.size()<4)throw new IllegalArgumentException("Polygonring benötigt mindestens vier Koordinaten");
            for(var point:ring){
                if(++count[0]>50000)throw new IllegalArgumentException("Zu viele Geometriekoordinaten");
                if(!point.isArray()||point.size()!=2||!point.get(0).isNumber()||!point.get(1).isNumber())throw new IllegalArgumentException("Koordinaten als numerisches lon/lat-Paar erforderlich");
                double lon=point.get(0).asDouble(),lat=point.get(1).asDouble();
                if(!Double.isFinite(lon)||!Double.isFinite(lat)||!Epsg3035.covers(lat,lon,lat,lon))throw new IllegalArgumentException("Gemeindegeometrie liegt außerhalb des EPSG:3035-Gültigkeitsbereichs");
            }
            if(ring.get(0).get(0).asDouble()!=ring.get(ring.size()-1).get(0).asDouble()||ring.get(0).get(1).asDouble()!=ring.get(ring.size()-1).get(1).asDouble())throw new IllegalArgumentException("Polygonring muss explizit geschlossen sein");
        }
    }
}
