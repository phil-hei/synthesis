package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Zonierung, die am INSPIRE-/Zensus-Gitter (EPSG:3035, ETRS89-LAEA) ausgerichtet ist: die
 * Zellkanten liegen auf dem europaweit einheitlichen, flächengleichen Standardgitter statt an
 * der Regions-Bbox. Vorteile gegenüber {@link GridZoning}: flächengleiche Zellen, europaweit
 * vergleichbare/mergebare Zell-IDs (INSPIRE-Code) und verlustfreie Aggregation der Zensus-
 * Rasterdaten (die {@link PopulationGridParser} liest).
 *
 * <p><b>Nur Europa:</b> EPSG:3035 ist für den EU/EWR-Raum definiert. Außerhalb → {@link GridZoning}.
 *
 * <p>Die Auflösung ist auf die kanonischen INSPIRE-Stufen (100 m / 1 km / 10 km) quantisiert.
 */
public final class InspireGridZoning {

    /** Kanonische INSPIRE-/Zensus-Auflösungen in Metern. */
    static final int[] RESOLUTIONS = {100, 1000, 10000};
    private static final long FIRST_ZONE_ID = 100_001;

    private InspireGridZoning() {
    }

    /**
     * Auflösung, die der gewünschten Zellgröße am nächsten kommt (log-Maß) und deren Gitter unter
     * dem Zonenlimit bleibt; nötigenfalls gröber. Der praktische Neuanlagen-Default ist das frei
     * auflösbare Bbox-Raster; diese Methode wird nur bei bewusst gewähltem INSPIRE-Modus genutzt.
     */
    public static int pickResolution(Scenario.Region region, double requestedMeters, int maxZones) {
        int target = RESOLUTIONS[0];
        double best = Double.MAX_VALUE;
        for (int r : RESOLUTIONS) {
            double d = Math.abs(Math.log(r) - Math.log(Math.max(1, requestedMeters)));
            if (d < best) {
                best = d;
                target = r;
            }
        }
        for (int r : RESOLUTIONS) {
            if (r >= target && fits(region, r, maxZones)) {
                return r;
            }
        }
        return RESOLUTIONS[RESOLUTIONS.length - 1];
    }

    public static List<Zone> generate(Scenario.Region region, int resolutionMeters, int maxZones) {
        long[] b = snappedBounds(region, resolutionMeters);
        long e0 = b[0];
        long n0 = b[1];
        long cols = (b[2] - e0) / resolutionMeters;
        long rows = (b[3] - n0) / resolutionMeters;
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Region ist leer");
        }
        if (cols * rows > maxZones) {
            throw new IllegalArgumentException(String.format(
                    "INSPIRE-Zonierung ergäbe %d Zonen (max. %d). Region verkleinern oder gröbere "
                            + "Auflösung wählen.", cols * rows, maxZones));
        }
        double areaKm2 = (resolutionMeters / 1000.0) * (resolutionMeters / 1000.0);
        List<Zone> zones = new ArrayList<>((int) (cols * rows));
        int index = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                long east = e0 + (long) c * resolutionMeters;
                long north = n0 + (long) r * resolutionMeters;
                double[] centre = Epsg3035.toLatLon(east + resolutionMeters / 2.0,
                        north + resolutionMeters / 2.0);
                double[] sw = Epsg3035.toLatLon(east, north);
                double[] se = Epsg3035.toLatLon(east + resolutionMeters, north);
                double[] nw = Epsg3035.toLatLon(east, north + resolutionMeters);
                double[] ne = Epsg3035.toLatLon(east + resolutionMeters, north + resolutionMeters);

                Zone zone = new Zone();
                zone.index = index;
                zone.id = FIRST_ZONE_ID + index;
                zone.row = r;
                zone.col = c;
                // INSPIRE-Zell-ID (Zensus-2022-Format): Auflösung + SW-Ecke in EPSG:3035-Metern
                zone.name = String.format("CRS3035RES%dmN%dE%d", resolutionMeters, north, east);
                zone.laeaE = east;
                zone.laeaN = north;
                zone.laeaRes = resolutionMeters;
                zone.centroidLat = centre[0];
                zone.centroidLon = centre[1];
                // Lat/Lon-Bounding-Box des LAEA-Quadrats (für Anzeige/Export; Containment läuft in LAEA)
                zone.minLat = Math.min(Math.min(sw[0], se[0]), Math.min(nw[0], ne[0]));
                zone.maxLat = Math.max(Math.max(sw[0], se[0]), Math.max(nw[0], ne[0]));
                zone.minLon = Math.min(Math.min(sw[1], nw[1]), Math.min(se[1], ne[1]));
                zone.maxLon = Math.max(Math.max(sw[1], nw[1]), Math.max(se[1], ne[1]));
                zone.areaKm2 = areaKm2;
                zones.add(zone);
                index++;
            }
        }
        return zones;
    }

    private static boolean fits(Scenario.Region region, int resolutionMeters, int maxZones) {
        long[] b = snappedBounds(region, resolutionMeters);
        long cols = (b[2] - b[0]) / resolutionMeters;
        long rows = (b[3] - b[1]) / resolutionMeters;
        return cols > 0 && rows > 0 && cols * rows <= maxZones;
    }

    /**
     * Projiziert Regions-Ecken + Kantenmitten nach LAEA, bildet die Bounding-Box und rastet sie
     * nach außen auf Vielfache der Auflösung ein (Zellkanten auf dem globalen Gitter).
     *
     * @return {e0, n0, e1, n1} (SW- und NE-Ecke der gesnappten Box in Metern)
     */
    private static long[] snappedBounds(Scenario.Region region, int res) {
        double midLat = (region.minLat + region.maxLat) / 2;
        double midLon = (region.minLon + region.maxLon) / 2;
        double[][] pts = {
                {region.minLat, region.minLon}, {region.minLat, region.maxLon},
                {region.maxLat, region.minLon}, {region.maxLat, region.maxLon},
                {region.minLat, midLon}, {region.maxLat, midLon},
                {midLat, region.minLon}, {midLat, region.maxLon}};
        double minE = Double.MAX_VALUE;
        double minN = Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE;
        double maxN = -Double.MAX_VALUE;
        for (double[] p : pts) {
            double[] en = Epsg3035.fromLatLon(p[0], p[1]);
            minE = Math.min(minE, en[0]);
            maxE = Math.max(maxE, en[0]);
            minN = Math.min(minN, en[1]);
            maxN = Math.max(maxN, en[1]);
        }
        long e0 = (long) Math.floor(minE / res) * res;
        long n0 = (long) Math.floor(minN / res) * res;
        long e1 = (long) Math.ceil(maxE / res) * res;
        long n1 = (long) Math.ceil(maxN / res) * res;
        return new long[]{e0, n0, e1, n1};
    }
}
