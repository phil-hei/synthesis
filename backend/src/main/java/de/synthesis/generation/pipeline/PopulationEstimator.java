package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;

import java.util.List;

/**
 * Estimates population from OSM settlement nodes (place=* with population tag).
 * Each place is modeled as a 2D Gaussian footprint around its center node whose
 * spread derives from the population at a typical urban density. Integrating the
 * Gaussian over the region (or a zone) yields that area's population share, so
 * the same model serves both the region estimate and the zone distribution.
 */
public final class PopulationEstimator {

    /** Assumed mean settlement density used to size a place's footprint. */
    private static final double FOOTPRINT_DENSITY_PER_KM2 = 3500;
    private static final double MIN_SIGMA_KM = 0.6;
    private static final double KM_PER_DEGREE_LAT = 111.32;

    private PopulationEstimator() {
    }

    public static long estimateForRegion(Scenario.Region region, List<OverpassClient.Place> places) {
        double total = 0;
        for (OverpassClient.Place place : places) {
            total += place.population() * shareInsideRegion(region, place);
        }
        return Math.round(total);
    }

    /** Relative population weight per zone; zones far from any settlement get ~0. */
    public static double[] zoneWeights(List<Zone> zones, List<OverpassClient.Place> places) {
        double[] weights = new double[zones.size()];
        for (Zone zone : zones) {
            Scenario.Region cell = new Scenario.Region();
            cell.minLat = zone.minLat;
            cell.maxLat = zone.maxLat;
            cell.minLon = zone.minLon;
            cell.maxLon = zone.maxLon;
            double weight = 0;
            for (OverpassClient.Place place : places) {
                weight += place.population() * shareInsideRegion(cell, place);
            }
            weights[zone.index] = weight;
        }
        return weights;
    }

    /** Fraction of the place's Gaussian footprint falling inside the given box. */
    private static double shareInsideRegion(Scenario.Region region, OverpassClient.Place place) {
        double sigmaKm = Math.max(MIN_SIGMA_KM,
                Math.sqrt(place.population() / (FOOTPRINT_DENSITY_PER_KM2 * Math.PI)));
        double sigmaLat = sigmaKm / KM_PER_DEGREE_LAT;
        double sigmaLon = sigmaKm / (KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(place.lat())));
        double latShare = normalCdf((region.maxLat - place.lat()) / sigmaLat)
                - normalCdf((region.minLat - place.lat()) / sigmaLat);
        double lonShare = normalCdf((region.maxLon - place.lon()) / sigmaLon)
                - normalCdf((region.minLon - place.lon()) / sigmaLon);
        return latShare * lonShare;
    }

    /** Standard normal CDF via Abramowitz-Stegun approximation (max error ~7.5e-8). */
    private static double normalCdf(double x) {
        if (x < -8) {
            return 0;
        }
        if (x > 8) {
            return 1;
        }
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI) * poly;
        return x >= 0 ? 1 - tail : tail;
    }
}
