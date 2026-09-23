package de.synthesis.generation.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends a ring of "external" zones representing destinations outside the study
 * area (jobs, shopping, leisure in the surrounding region). They carry no
 * residents but a pool of attractivity, so mobiTopp's destination choice can send
 * a share of flexible activities outward. The ring approximates surrounding
 * destinations; it does not by itself reproduce the MiD long-distance tail. The
 * zones flow unchanged through zones.csv, attractivities.csv, the matrices
 * (beeline, since they lie outside the routed network) and the legacy
 * opportunities.
 */
public final class ExternalZones {

    /** Distance of the external zone centroids from the region centroid, in km. */
    private static final double DISTANCE_KM = 12;
    /** External attractivity pool as a multiple of the internal total, per category. */
    private static final double ATTRACTIVITY_FACTOR = 4.0;
    /** Eight compass directions (unit vectors: north component, east component). */
    private static final double[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private ExternalZones() {
    }

    /** Radius used by the generated ring and by user-facing diagnostics. */
    public static double radiusKm() {
        return DISTANCE_KM;
    }

    /** Number of directional anchors generated for an enabled scenario. */
    public static int count() {
        return DIRECTIONS.length;
    }

    /**
     * Creates the external zones for the given internal grid. Indices and ids
     * continue after the internal zones; population/households stay zero.
     */
    public static List<Zone> create(List<Zone> internal) {
        return create(internal, null);
    }

    /**
     * Creates directional anchors inside an automatic MobileCity buffer. A null buffer keeps
     * the legacy fixed 12-km ring used by ordinary external destination choice.
     */
    public static List<Zone> create(List<Zone> internal, Double surroundingBufferKm) {
        if (internal.isEmpty()) {
            return List.of();
        }
        boolean adaptive = surroundingBufferKm != null && surroundingBufferKm > 0;
        double minLat=internal.stream().mapToDouble(z->z.minLat).min().orElseThrow();
        double maxLat=internal.stream().mapToDouble(z->z.maxLat).max().orElseThrow();
        double minLon=internal.stream().mapToDouble(z->z.minLon).min().orElseThrow();
        double maxLon=internal.stream().mapToDouble(z->z.maxLon).max().orElseThrow();
        double centroidLat = adaptive ? (minLat+maxLat)/2
                : internal.stream().mapToDouble(z -> z.centroidLat).average().orElse(0);
        double centroidLon = adaptive ? (minLon+maxLon)/2
                : internal.stream().mapToDouble(z -> z.centroidLon).average().orElse(0);
        double kmPerLat=GridZoning.METERS_PER_DEGREE_LAT/1000;
        double kmPerLon=kmPerLat*Math.cos(Math.toRadians(centroidLat));
        double halfHeight=(maxLat-minLat)/2*kmPerLat;
        double halfWidth=(maxLon-minLon)/2*kmPerLon;

        // Attraktivität je Kategorie als Anteil des internen Gesamtwerts, gleich auf alle Ringzonen verteilt
        List<Zone> external = new ArrayList<>(DIRECTIONS.length);
        int nextIndex = internal.size();
        long nextId = internal.stream().mapToLong(z -> z.id).max().orElse(100_000) + 1;

        for (int d = 0; d < DIRECTIONS.length; d++) {
            // diagonale Richtungen auf Einheitslänge normieren, damit der Ring rund bleibt
            double norm = Math.hypot(DIRECTIONS[d][0], DIRECTIONS[d][1]);
            double north = DIRECTIONS[d][0] / norm;
            double east = DIRECTIONS[d][1] / norm;
            double distance=DISTANCE_KM;
            if(adaptive){
                double toNorth=Math.abs(north)<1e-12?Double.POSITIVE_INFINITY:halfHeight/Math.abs(north);
                double toEast=Math.abs(east)<1e-12?Double.POSITIVE_INFINITY:halfWidth/Math.abs(east);
                distance=Math.min(toNorth,toEast)+surroundingBufferKm/2;
            }

            Zone zone = new Zone();
            zone.index = nextIndex++;
            zone.id = nextId++;
            zone.row = -1;
            zone.col = -1;
            zone.name = "EXT-" + d;
            zone.centroidLat = centroidLat + north * distance/kmPerLat;
            zone.centroidLon = centroidLon + east * distance/kmPerLon;
            // kleine Bounding-Box um den Schwerpunkt (weit außerhalb der Region; fängt keine internen Punkte)
            zone.minLat = zone.centroidLat - 0.02;
            zone.maxLat = zone.centroidLat + 0.02;
            zone.minLon = zone.centroidLon - 0.02;
            zone.maxLon = zone.centroidLon + 0.02;
            zone.areaKm2 = 25;
            zone.population = 0;
            zone.households = 0;
            zone.external = true;
            external.add(zone);
        }

        double share = ATTRACTIVITY_FACTOR / DIRECTIONS.length;
        for (String category : AttractivityCalculator.CATEGORIES) {
            double internalTotal = internal.stream()
                    .mapToDouble(z -> z.attractivities.getOrDefault(category, 0.0)).sum();
            double perZone = internalTotal * share;
            for (Zone zone : external) {
                zone.attractivities.put(category, perZone);
            }
        }
        return external;
    }
}
