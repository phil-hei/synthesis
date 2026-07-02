package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a square grid zoning over the selected region. Square cells keep the
 * MVP independent of any external boundary data; OSM admin boundaries can be
 * added later as an alternative provider (see docs/03-architektur.md).
 */
public final class GridZoning {

    public static final double METERS_PER_DEGREE_LAT = 111_320;
    private static final long FIRST_ZONE_ID = 100_001;

    private GridZoning() {
    }

    /**
     * Smallest cell size (in 50 m steps, at least the requested size) whose grid
     * stays within the zone limit. Lets users pick any region without running
     * into the O(n²) matrix limit; the requested size wins whenever it fits.
     */
    public static double autoCellSize(Scenario.Region region, double requestedCellSize, int maxZones) {
        double midLat = (region.minLat + region.maxLat) / 2;
        double heightM = (region.maxLat - region.minLat) * METERS_PER_DEGREE_LAT;
        double widthM = (region.maxLon - region.minLon) * METERS_PER_DEGREE_LAT
                * Math.cos(Math.toRadians(midLat));
        double cellSize = Math.max(100, requestedCellSize);
        if (fits(heightM, widthM, cellSize, maxZones)) {
            return cellSize;
        }
        cellSize = Math.ceil(Math.sqrt(heightM * widthM / maxZones) / 50) * 50;
        while (!fits(heightM, widthM, cellSize, maxZones)) {
            cellSize += 50;
        }
        return cellSize;
    }

    private static boolean fits(double heightM, double widthM, double cellSize, int maxZones) {
        return Math.ceil(heightM / cellSize) * Math.ceil(widthM / cellSize) <= maxZones;
    }

    public static List<Zone> generate(Scenario.Region region, double cellSizeMeters, int maxZones) {
        double midLat = (region.minLat + region.maxLat) / 2;
        double dLat = cellSizeMeters / METERS_PER_DEGREE_LAT;
        double dLon = cellSizeMeters / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(midLat)));

        int rows = (int) Math.ceil((region.maxLat - region.minLat) / dLat);
        int cols = (int) Math.ceil((region.maxLon - region.minLon) / dLon);
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("Region ist leer");
        }
        if ((long) rows * cols > maxZones) {
            throw new IllegalArgumentException(String.format(
                    "Zonierung ergäbe %d Zonen (max. %d). Bitte Region verkleinern oder Zellgröße erhöhen.",
                    (long) rows * cols, maxZones));
        }

        double cellAreaKm2 = (cellSizeMeters / 1000) * (cellSizeMeters / 1000);
        List<Zone> zones = new ArrayList<>(rows * cols);
        int index = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Zone zone = new Zone();
                zone.index = index;
                zone.id = FIRST_ZONE_ID + index;
                zone.row = r;
                zone.col = c;
                zone.name = "Z" + r + "-" + c;
                zone.minLat = region.minLat + r * dLat;
                zone.maxLat = Math.min(zone.minLat + dLat, region.maxLat);
                zone.minLon = region.minLon + c * dLon;
                zone.maxLon = Math.min(zone.minLon + dLon, region.maxLon);
                zone.centroidLat = (zone.minLat + zone.maxLat) / 2;
                zone.centroidLon = (zone.minLon + zone.maxLon) / 2;
                zone.areaKm2 = cellAreaKm2;
                zones.add(zone);
                index++;
            }
        }
        return zones;
    }

    /** Index of the zone containing the coordinate, or -1 if outside the region. */
    public static int zoneIndexOf(List<Zone> zones, double lat, double lon) {
        for (Zone zone : zones) {
            if (zone.contains(lat, lon)) {
                return zone.index;
            }
        }
        return -1;
    }

    /** Beeline distance between two zone centroids in kilometers (haversine). */
    public static double distanceKm(Zone a, Zone b) {
        double dLat = Math.toRadians(b.centroidLat - a.centroidLat);
        double dLon = Math.toRadians(b.centroidLon - a.centroidLon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.centroidLat)) * Math.cos(Math.toRadians(b.centroidLat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h));
    }
}
