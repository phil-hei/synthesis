package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming inspection of an OSM-XML road-network extract. This validates the
 * upload contract without loading a potentially large extract into memory.
 */
public final class RoadNetworkFile {

    public static final String NAME = "road-network.osm";

    public record Inspection(long nodes, long ways, long highwayWays,
                             double minLat, double minLon, double maxLat, double maxLon,
                             String sha256) {

        public boolean covers(Scenario.Region region) {
            return minLat <= region.minLat && minLon <= region.minLon
                    && maxLat >= region.maxLat && maxLon >= region.maxLon;
        }
    }

    private RoadNetworkFile() {
    }

    public static Inspection inspect(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("Externe XML-Ressourcen sind nicht erlaubt");
        });

        long nodes = 0;
        long ways = 0;
        long highwayWays = 0;
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        boolean osmRoot = false;
        boolean rootSeen = false;
        boolean inWay = false;
        boolean highway = false;

        try (InputStream input = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (!rootSeen) {
                        rootSeen = true;
                        if (!"osm".equals(name)) {
                            throw new IllegalArgumentException("Datei ist kein OSM-XML-Dokument");
                        }
                    }
                    if ("osm".equals(name)) {
                        osmRoot = true;
                    } else if ("node".equals(name)) {
                        double lat = coordinate(reader, "lat");
                        double lon = coordinate(reader, "lon");
                        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                            throw new IllegalArgumentException("OSM-Datei enthält ungültige Koordinaten");
                        }
                        nodes++;
                        minLat = Math.min(minLat, lat);
                        minLon = Math.min(minLon, lon);
                        maxLat = Math.max(maxLat, lat);
                        maxLon = Math.max(maxLon, lon);
                    } else if ("way".equals(name)) {
                        inWay = true;
                        highway = false;
                    } else if (inWay && "tag".equals(name)
                            && "highway".equals(reader.getAttributeValue(null, "k"))) {
                        highway = true;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "way".equals(reader.getLocalName())) {
                    ways++;
                    if (highway) {
                        highwayWays++;
                    }
                    inWay = false;
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Ungültiges OSM-XML: " + e.getMessage(), e);
        }

        if (!osmRoot) {
            throw new IllegalArgumentException("Datei ist kein OSM-XML-Dokument");
        }
        if (nodes == 0 || highwayWays == 0) {
            throw new IllegalArgumentException("OSM-Datei enthält kein routbares Straßennetz");
        }
        return new Inspection(nodes, ways, highwayWays, minLat, minLon, maxLat, maxLon, sha256(file));
    }

    public static Inspection inspectForRegion(Path file, Scenario.Region region) throws IOException {
        Inspection inspection = inspect(file);
        if (!inspection.covers(region)) {
            throw new IllegalArgumentException("OSM-Netz deckt die Szenarioregion nicht vollständig ab");
        }
        return inspection;
    }

    private static double coordinate(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("OSM-Node ohne gültiges " + name, e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
