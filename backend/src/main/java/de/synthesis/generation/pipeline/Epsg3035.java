package de.synthesis.generation.pipeline;

/**
 * Inverse of the ETRS89-extended / LAEA Europe projection (EPSG:3035) used by
 * the German Zensus grid data, implemented after Snyder, "Map Projections - A
 * Working Manual", Lambert Azimuthal Equal-Area for the ellipsoid (GRS80).
 */
public final class Epsg3035 {

    private static final double A = 6_378_137.0;             // GRS80 semi-major axis
    private static final double E2 = 0.006_694_380_022_90;   // first eccentricity squared
    private static final double E = Math.sqrt(E2);
    private static final double LAT0 = Math.toRadians(52);
    private static final double LON0 = Math.toRadians(10);
    private static final double FALSE_EASTING = 4_321_000;
    private static final double FALSE_NORTHING = 3_210_000;

    private static final double QP = q(Math.PI / 2);
    private static final double Q0 = q(LAT0);
    private static final double BETA0 = Math.asin(Q0 / QP);
    private static final double RQ = A * Math.sqrt(QP / 2);
    private static final double D = A * Math.cos(LAT0)
            / Math.sqrt(1 - E2 * Math.sin(LAT0) * Math.sin(LAT0))
            / (RQ * Math.cos(BETA0));

    // Gültigkeitsbereich von EPSG:3035 (EPSG-Registry, Area of Use "Europe"):
    // außerhalb ist die LAEA-Projektion zwar rechenbar, aber stark verzerrt und nicht amtlich.
    private static final double MIN_LAT = 32.88;
    private static final double MAX_LAT = 84.73;
    private static final double MIN_LON = -16.1;
    private static final double MAX_LON = 40.18;

    private Epsg3035() {
    }

    /** @return true, wenn die Bounding-Box vollständig im EPSG:3035-Gültigkeitsbereich liegt. */
    public static boolean covers(double minLat, double minLon, double maxLat, double maxLon) {
        return minLat >= MIN_LAT && maxLat <= MAX_LAT && minLon >= MIN_LON && maxLon <= MAX_LON;
    }

    /** @return {lat, lon} in WGS84/ETRS89 degrees (difference is negligible at grid scale). */
    public static double[] toLatLon(double easting, double northing) {
        double x = easting - FALSE_EASTING;
        double y = northing - FALSE_NORTHING;
        double rho = Math.hypot(x / D, D * y);
        if (rho == 0) {
            return new double[]{Math.toDegrees(LAT0), Math.toDegrees(LON0)};
        }
        double ce = 2 * Math.asin(rho / (2 * RQ));
        double beta = Math.asin(Math.cos(ce) * Math.sin(BETA0)
                + D * y * Math.sin(ce) * Math.cos(BETA0) / rho);
        double lon = LON0 + Math.atan2(x * Math.sin(ce),
                D * rho * Math.cos(BETA0) * Math.cos(ce) - D * D * y * Math.sin(BETA0) * Math.sin(ce));
        // authalic latitude -> geodetic latitude (series expansion)
        double lat = beta
                + (E2 / 3 + 31 * E2 * E2 / 180 + 517 * E2 * E2 * E2 / 5040) * Math.sin(2 * beta)
                + (23 * E2 * E2 / 360 + 251 * E2 * E2 * E2 / 3780) * Math.sin(4 * beta)
                + (761 * E2 * E2 * E2 / 45360) * Math.sin(6 * beta);
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    /**
     * Forward-Projektion (Lat/Lon → LAEA-Meter), inverse zu {@link #toLatLon}; nötig, um Zonen
     * am INSPIRE-/Zensus-Gitter auszurichten. Gültig für den EU/EWR-Raum (EPSG:3035).
     *
     * @return {easting, northing} in Metern
     */
    public static double[] fromLatLon(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double beta = Math.asin(q(lat) / QP);
        double denom = 1 + Math.sin(BETA0) * Math.sin(beta)
                + Math.cos(BETA0) * Math.cos(beta) * Math.cos(lon - LON0);
        double b = RQ * Math.sqrt(2 / denom);
        double x = b * D * Math.cos(beta) * Math.sin(lon - LON0);
        double y = (b / D) * (Math.cos(BETA0) * Math.sin(beta)
                - Math.sin(BETA0) * Math.cos(beta) * Math.cos(lon - LON0));
        return new double[]{x + FALSE_EASTING, y + FALSE_NORTHING};
    }

    private static double q(double lat) {
        double sinLat = Math.sin(lat);
        return (1 - E2) * (sinLat / (1 - E2 * sinLat * sinLat)
                - 1 / (2 * E) * Math.log((1 - E * sinLat) / (1 + E * sinLat)));
    }
}
