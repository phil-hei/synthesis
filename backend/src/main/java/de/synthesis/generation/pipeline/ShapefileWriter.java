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
 * Minimaler, abhängigkeitsfreier ESRI-Shapefile-Writer für achsenparallele
 * Rechteck-Polygone (unsere Rasterzonen) mit genau einem Textattribut.
 * Schreibt .shp/.shx/.dbf/.prj (WGS84) – exakt das Format, das der
 * MobileCity-/Takomat-Import erwartet (Attribut {@code districtID}).
 */
public final class ShapefileWriter {

    private static final int SHAPE_POLYGON = 5;
    /** Record-Inhalt eines Rechtecks: type + box + numParts + numPoints + parts[1] + 5 Punkte. */
    private static final int CONTENT_BYTES = 4 + 32 + 4 + 4 + 4 + 5 * 16;
    private static final String WGS84_PRJ =
            "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\","
                    + "6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],"
                    + "UNIT[\"Degree\",0.0174532925199433]]";

    private ShapefileWriter() {
    }

    /**
     * Schreibt {@code base}.shp/.shx/.dbf/.prj für die Rechtecke der Zonen.
     *
     * @param base      Zielpfad ohne Endung (z. B. …/mobilecity/districts)
     * @param fieldName dBase-Attributname (max. 10 Zeichen, z. B. districtID)
     * @param idOf      liefert den Attributwert je Zone
     */
    public static void writeRectangles(Path base, List<Zone> zones, String fieldName,
                                       Function<Zone, String> idOf) throws IOException {
        if (fieldName.length() > 10) {
            throw new IllegalArgumentException("dBase-Feldnamen sind auf 10 Zeichen begrenzt: " + fieldName);
        }
        Files.createDirectories(base.getParent());
        writeShp(Path.of(base + ".shp"), zones);
        writeShx(Path.of(base + ".shx"), zones.size());
        writeDbf(Path.of(base + ".dbf"), zones, fieldName, idOf);
        Files.writeString(Path.of(base + ".prj"), WGS84_PRJ, StandardCharsets.US_ASCII);
    }

    // --- .shp -------------------------------------------------------------------

    private static void writeShp(Path file, List<Zone> zones) throws IOException {
        int fileBytes = 100 + zones.size() * (8 + CONTENT_BYTES);
        ByteBuffer buf = ByteBuffer.allocate(fileBytes);
        writeMainHeader(buf, fileBytes, zones);
        int recordNumber = 1;
        for (Zone zone : zones) {
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(recordNumber++);
            buf.putInt(CONTENT_BYTES / 2); // Länge in 16-Bit-Wörtern
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(SHAPE_POLYGON);
            buf.putDouble(zone.minLon).putDouble(zone.minLat);
            buf.putDouble(zone.maxLon).putDouble(zone.maxLat);
            buf.putInt(1); // numParts
            buf.putInt(5); // numPoints (geschlossener Ring)
            buf.putInt(0); // parts[0]
            // Außenring im Uhrzeigersinn (Shapefile-Konvention)
            putPoint(buf, zone.minLon, zone.maxLat);
            putPoint(buf, zone.maxLon, zone.maxLat);
            putPoint(buf, zone.maxLon, zone.minLat);
            putPoint(buf, zone.minLon, zone.minLat);
            putPoint(buf, zone.minLon, zone.maxLat);
        }
        Files.write(file, buf.array());
    }

    private static void writeShx(Path file, int records) throws IOException {
        int fileBytes = 100 + records * 8;
        ByteBuffer buf = ByteBuffer.allocate(fileBytes);
        writeMainHeader(buf, fileBytes, null);
        int offsetWords = 50; // erster Record direkt nach dem 100-Byte-Header
        for (int i = 0; i < records; i++) {
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(offsetWords);
            buf.putInt(CONTENT_BYTES / 2);
            offsetWords += (8 + CONTENT_BYTES) / 2;
        }
        Files.write(file, buf.array());
    }

    /** Gemeinsamer 100-Byte-Header; Bounding Box nur für die .shp (zones != null). */
    private static void writeMainHeader(ByteBuffer buf, int fileBytes, List<Zone> zones) {
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(9994);
        buf.position(24);
        buf.putInt(fileBytes / 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1000);
        buf.putInt(SHAPE_POLYGON);
        double minX = 0;
        double minY = 0;
        double maxX = 0;
        double maxY = 0;
        if (zones != null && !zones.isEmpty()) {
            minX = zones.stream().mapToDouble(z -> z.minLon).min().orElse(0);
            minY = zones.stream().mapToDouble(z -> z.minLat).min().orElse(0);
            maxX = zones.stream().mapToDouble(z -> z.maxLon).max().orElse(0);
            maxY = zones.stream().mapToDouble(z -> z.maxLat).max().orElse(0);
        }
        buf.putDouble(minX).putDouble(minY).putDouble(maxX).putDouble(maxY);
        buf.putDouble(0).putDouble(0).putDouble(0).putDouble(0); // Z/M unbenutzt
        buf.position(100);
    }

    private static void putPoint(ByteBuffer buf, double x, double y) {
        buf.putDouble(x).putDouble(y);
    }

    // --- .dbf (dBase III, ein Textfeld) ------------------------------------------

    private static void writeDbf(Path file, List<Zone> zones, String fieldName,
                                 Function<Zone, String> idOf) throws IOException {
        int fieldLength = 16;
        int headerSize = 32 + 32 + 1;             // Header + 1 Felddeskriptor + Terminator
        int recordSize = 1 + fieldLength;          // Löschflag + Feld
        ByteBuffer buf = ByteBuffer.allocate(headerSize + zones.size() * recordSize + 1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        LocalDate today = LocalDate.now();
        buf.put((byte) 0x03);
        buf.put((byte) (today.getYear() - 1900));
        buf.put((byte) today.getMonthValue());
        buf.put((byte) today.getDayOfMonth());
        buf.putInt(zones.size());
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
        for (Zone zone : zones) {
            buf.put((byte) ' ');                   // nicht gelöscht
            byte[] value = String.format("%-" + fieldLength + "s", idOf.apply(zone))
                    .getBytes(StandardCharsets.US_ASCII);
            buf.put(value, 0, fieldLength);
        }
        buf.put((byte) 0x1A);                      // EOF
        Files.write(file, buf.array());
    }
}
