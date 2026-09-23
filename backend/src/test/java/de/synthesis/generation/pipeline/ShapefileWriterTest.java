package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifiziert das Shapefile-Binärformat (ESRI-Spezifikation) durch Zurücklesen:
 * Header, Record-Struktur, Ring-Koordinaten, .shx-Index und dBase-Attributwerte –
 * das Format, das der MobileCity-/Takomat-Import (WGS84, Attribut districtID) erwartet.
 */
class ShapefileWriterTest {

    @Test
    void writesActualProjectedCornersAndMultipartHoles(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Zone projected = zone(0, 48.8, 8.1, 48.9, 8.2);
        projected.laeaE = 4200000; projected.laeaN = 2900000; projected.laeaRes = 1000;
        ShapefileWriter.writeRectangles(dir.resolve("grid"), List.of(projected), "districtID", z -> "grid");
        ByteBuffer grid = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("grid.shp"))).order(ByteOrder.LITTLE_ENDIAN);
        double[] ll = Epsg3035.toLatLon(projected.laeaE, projected.laeaN);
        assertEquals(ll[1], grid.getDouble(156), 1e-9);
        assertEquals(ll[0], grid.getDouble(164), 1e-9);
        assertTrue(Math.abs(grid.getDouble(156) - projected.minLon) > .001);

        var geometry = new org.locationtech.jts.io.WKTReader().read(
                "MULTIPOLYGON (((0 0,0 4,4 4,4 0,0 0),(1 1,2 1,2 2,1 2,1 1)),((5 0,5 1,6 1,6 0,5 0)))");
        ShapefileWriter.writePolygons(dir.resolve("multi"), List.of("A"), List.of(geometry), "districtID");
        ByteBuffer multi = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("multi.shp"))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, multi.getInt(144));
        assertEquals(15, multi.getInt(148));
        assertEquals(0, multi.getInt(152)); assertEquals(5, multi.getInt(156)); assertEquals(10, multi.getInt(160));
        // Hole must be counterclockwise; outer rings clockwise, as required by ESRI.
        for (int ring=0; ring<3; ring++) {
            org.locationtech.jts.geom.Coordinate[] points = new org.locationtech.jts.geom.Coordinate[5];
            for (int p=0;p<5;p++) { int offset=164+(ring*5+p)*16; points[p]=new org.locationtech.jts.geom.Coordinate(multi.getDouble(offset),multi.getDouble(offset+8)); }
            assertEquals(ring==1,org.locationtech.jts.algorithm.Orientation.isCCW(points));
        }
        ByteBuffer shx=ByteBuffer.wrap(Files.readAllBytes(dir.resolve("multi.shx"))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6,shx.getDouble(52),1e-9);
    }

    private static Zone zone(int index, double minLat, double minLon, double maxLat, double maxLon) {
        Zone z = new Zone();
        z.index = index;
        z.minLat = minLat;
        z.minLon = minLon;
        z.maxLat = maxLat;
        z.maxLon = maxLon;
        return z;
    }

    @Test
    void writesSpecCompliantPolygonShapefile() throws Exception {
        Path dir = Files.createTempDirectory("shp-test");
        Path base = dir.resolve("districts");
        List<Zone> zones = List.of(
                zone(0, 48.85, 8.19, 48.86, 8.21),
                zone(1, 48.86, 8.19, 48.87, 8.21));
        ShapefileWriter.writeRectangles(base, zones, "districtID",
                z -> String.format("SYN-%03d", z.index + 1));

        // .shp: Hauptheader
        byte[] shp = Files.readAllBytes(Path.of(base + ".shp"));
        ByteBuffer buf = ByteBuffer.wrap(shp);
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(9994, buf.getInt(0), "Shapefile file code");
        assertEquals(shp.length / 2, buf.getInt(24), "Dateilänge in 16-Bit-Wörtern");
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1000, buf.getInt(28), "Version");
        assertEquals(5, buf.getInt(32), "Shape-Typ Polygon");
        assertEquals(8.19, buf.getDouble(36), 1e-9, "bbox minX");
        assertEquals(48.85, buf.getDouble(44), 1e-9, "bbox minY");
        assertEquals(8.21, buf.getDouble(52), 1e-9, "bbox maxX");
        assertEquals(48.87, buf.getDouble(60), 1e-9, "bbox maxY");

        // .shp: Record 1 (Header BE, Inhalt LE)
        buf.order(ByteOrder.BIG_ENDIAN);
        assertEquals(1, buf.getInt(100), "Record-Nummer 1-basiert");
        int contentWords = buf.getInt(104);
        assertEquals(64, contentWords, "Record-Inhalt 128 Bytes = 64 Wörter");
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5, buf.getInt(108), "Record-Shape-Typ Polygon");
        assertEquals(1, buf.getInt(108 + 36), "numParts");
        assertEquals(5, buf.getInt(108 + 40), "numPoints (geschlossener Ring)");
        // Ring: erster Punkt (minX,maxY), letzter == erster (geschlossen)
        int points = 108 + 48;
        assertEquals(8.19, buf.getDouble(points), 1e-9, "Ringstart X");
        assertEquals(48.86, buf.getDouble(points + 8), 1e-9, "Ringstart Y");
        assertEquals(buf.getDouble(points), buf.getDouble(points + 4 * 16), 1e-9, "Ring geschlossen (X)");
        assertEquals(buf.getDouble(points + 8), buf.getDouble(points + 4 * 16 + 8), 1e-9, "Ring geschlossen (Y)");

        // .shx: Index mit Offsets in Wörtern
        byte[] shx = Files.readAllBytes(Path.of(base + ".shx"));
        assertEquals(100 + 2 * 8, shx.length, ".shx-Länge");
        ByteBuffer idx = ByteBuffer.wrap(shx).order(ByteOrder.BIG_ENDIAN);
        assertEquals(50, idx.getInt(100), "Offset Record 1 (Wörter)");
        assertEquals(50 + 68, idx.getInt(108), "Offset Record 2 (Wörter)");

        // .dbf: 2 Records, Feld districtID, Werte passend zu REGdistricts
        byte[] dbf = Files.readAllBytes(Path.of(base + ".dbf"));
        ByteBuffer d = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x03, dbf[0], "dBase-III-Kennung");
        assertEquals(2, d.getInt(4), "Anzahl Records");
        int headerSize = d.getShort(8);
        int recordSize = d.getShort(10);
        assertEquals(65, headerSize, "Headergröße (32+32+1)");
        assertEquals(17, recordSize, "Recordgröße (Flag + 16 Zeichen)");
        String fieldName = new String(dbf, 32, 10, StandardCharsets.US_ASCII);
        assertEquals("districtID", fieldName, "Attributname exakt wie von Takomat erwartet");
        String value1 = new String(dbf, headerSize + 1, 16, StandardCharsets.US_ASCII).trim();
        String value2 = new String(dbf, headerSize + recordSize + 1, 16, StandardCharsets.US_ASCII).trim();
        assertEquals("SYN-001", value1);
        assertEquals("SYN-002", value2);

        // .prj: WGS84
        String prj = Files.readString(Path.of(base + ".prj"));
        assertTrue(prj.contains("WGS_1984"), "WGS84-Projektion");
    }
}
