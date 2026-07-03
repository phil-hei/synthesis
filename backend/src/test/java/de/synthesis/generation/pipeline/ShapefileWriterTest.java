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
