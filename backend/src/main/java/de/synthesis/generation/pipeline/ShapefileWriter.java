package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * Minimaler, abhängigkeitsfreier ESRI-Shapefile-Writer für Polygon- und MultiPolygon-Geometrien
 * (Außenringe und Löcher) mit genau einem Textattribut.
 * Schreibt .shp/.shx/.dbf/.prj (WGS84) – exakt das Format, das der
 * MobileCity-/Takomat-Import erwartet (Attribut {@code districtID}).
 */
public final class ShapefileWriter {

    private static final int SHAPE_POLYGON = 5;
    private static final String WGS84_PRJ =
            "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\","
                    + "6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],"
                    + "UNIT[\"Degree\",0.0174532925199433]]";

    private ShapefileWriter() {}

    /** Legacy name retained; projected grid cells use their true inverse-projected corners. */
    public static void writeRectangles(Path base, List<Zone> zones, String fieldName,
                                       Function<Zone, String> idOf) throws IOException {
        writePolygons(base, zones.stream().map(idOf).toList(),
                zones.stream().map(z -> (org.locationtech.jts.geom.Geometry) MobileCityGeometry.zone(z)).toList(), fieldName);
    }

    public static void writePolygons(Path base, List<String> ids,
                                     List<org.locationtech.jts.geom.Geometry> geometries, String fieldName) throws IOException {
        if (fieldName.length() > 10 || ids.size() != geometries.size()) throw new IllegalArgumentException("Ungültige Shapefile-Attribute");
        if (ids.stream().anyMatch(id -> id.length() > 16 || !id.matches("[\\x20-\\x7E]+"))) throw new IllegalArgumentException("District-ID passt nicht in DBF-Textfeld (16 ASCII-Zeichen)");
        Files.createDirectories(base.getParent());
        var records = new java.util.ArrayList<byte[]>();
        var bounds = new org.locationtech.jts.geom.Envelope();
        for (var geometry : geometries) {
            if (!geometry.isValid() || geometry.isEmpty()) throw new IOException("Ungültiges District-Polygon");
            bounds.expandToInclude(geometry.getEnvelopeInternal());
            var rings = new java.util.ArrayList<org.locationtech.jts.geom.Coordinate[]>();
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                var polygon = (org.locationtech.jts.geom.Polygon) geometry.getGeometryN(i);
                rings.add(oriented(polygon.getExteriorRing().getCoordinates(), false));
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) rings.add(oriented(polygon.getInteriorRingN(h).getCoordinates(), true));
            }
            int points = rings.stream().mapToInt(r -> r.length).sum();
            ByteBuffer record = ByteBuffer.allocate(44 + rings.size() * 4 + points * 16).order(ByteOrder.LITTLE_ENDIAN);
            record.putInt(SHAPE_POLYGON); box(record, geometry.getEnvelopeInternal());
            record.putInt(rings.size()).putInt(points);
            int offset = 0;
            for (var ring : rings) { record.putInt(offset); offset += ring.length; }
            for (var ring : rings) for (var c : ring) record.putDouble(c.x).putDouble(c.y);
            records.add(record.array());
        }
        int size = 100 + records.stream().mapToInt(r -> 8 + r.length).sum();
        ByteBuffer shp = ByteBuffer.allocate(size); header(shp, size, bounds);
        ByteBuffer shx = ByteBuffer.allocate(100 + records.size()*8); header(shx, shx.capacity(), bounds);
        int offset = 50;
        for (int i = 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            shx.order(ByteOrder.BIG_ENDIAN).putInt(offset).putInt(record.length/2);
            shp.order(ByteOrder.BIG_ENDIAN).putInt(i+1).putInt(record.length/2).put(record);
            offset += 4 + record.length/2;
        }
        Files.write(Path.of(base+".shp"), shp.array()); Files.write(Path.of(base+".shx"), shx.array());
        writeDbf(Path.of(base+".dbf"), ids, fieldName);
        Files.writeString(Path.of(base+".prj"), WGS84_PRJ, StandardCharsets.US_ASCII);
    }

    private static org.locationtech.jts.geom.Coordinate[] oriented(org.locationtech.jts.geom.Coordinate[] ring, boolean ccw) {
        if (org.locationtech.jts.algorithm.Orientation.isCCW(ring) != ccw) {
            for (int a=0,b=ring.length-1; a<b; a++,b--) { var tmp=ring[a]; ring[a]=ring[b]; ring[b]=tmp; }
        }
        return ring;
    }
    private static void box(ByteBuffer out, org.locationtech.jts.geom.Envelope box) {
        out.putDouble(box.isNull()?0:box.getMinX()).putDouble(box.isNull()?0:box.getMinY())
                .putDouble(box.isNull()?0:box.getMaxX()).putDouble(box.isNull()?0:box.getMaxY());
    }
    private static void header(ByteBuffer out, int size, org.locationtech.jts.geom.Envelope bounds) {
        out.order(ByteOrder.BIG_ENDIAN).putInt(9994); out.position(24); out.putInt(size/2);
        out.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(SHAPE_POLYGON); box(out,bounds);
        out.putDouble(0).putDouble(0).putDouble(0).putDouble(0); out.position(100);
    }

    private static void writeDbf(Path file, List<String> ids, String fieldName) throws IOException {
        int fieldLength = 16;
        int headerSize = 32 + 32 + 1;             // Header + 1 Felddeskriptor + Terminator
        int recordSize = 1 + fieldLength;          // Löschflag + Feld
        ByteBuffer buf = ByteBuffer.allocate(headerSize + ids.size() * recordSize + 1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        LocalDate today = LocalDate.now();
        buf.put((byte) 0x03);
        buf.put((byte) (today.getYear() - 1900));
        buf.put((byte) today.getMonthValue());
        buf.put((byte) today.getDayOfMonth());
        buf.putInt(ids.size());
        buf.putShort((short) headerSize);
        buf.putShort((short) recordSize);
        buf.position(32);
        byte[] name = new byte[11];
        byte[] raw = fieldName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, name, 0, raw.length);
        buf.put(name);
        buf.put((byte) 'C');                       // Texttyp
        buf.position(buf.position() + 4);          // Feldadresse (unbenutzt)
        buf.put((byte) fieldLength);
        buf.put((byte) 0);                         // Dezimalstellen
        buf.position(32 + 32);
        buf.put((byte) 0x0D);                      // Ende der Felddeskriptoren
        for (String id : ids) {
            buf.put((byte) ' ');                   // nicht gelöscht
            byte[] value = String.format("%-" + fieldLength + "s", id)
                    .getBytes(StandardCharsets.US_ASCII);
            buf.put(value, 0, fieldLength);
        }
        buf.put((byte) 0x1A);                      // EOF
        Files.write(file, buf.array());
    }
}
