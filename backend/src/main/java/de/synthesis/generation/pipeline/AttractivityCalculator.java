package de.synthesis.generation.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Derives zonal attractivities per mobiTopp activity category, either from OSM
 * POIs (preferred) or synthetically. Categories match the tutorial's
 * attractivities.csv header (Attractivity:&lt;Category&gt;).
 */
public final class AttractivityCalculator {

    public static final List<String> CATEGORIES = List.of(
            "Business", "Leisure_Indoor", "Leisure_Outdoor", "Private_Business", "Private_Visit",
            "Service", "Shopping_Daily", "Shopping_Other", "Work",
            "Education_Primary", "Education_Secondary", "Education_Tertiary");

    private static final List<String> DAILY_SHOPS = List.of(
            "supermarket", "bakery", "convenience", "butcher", "greengrocer", "chemist", "beverages", "kiosk");
    private static final List<String> SERVICE_AMENITIES = List.of(
            "bank", "pharmacy", "post_office", "doctors", "dentist", "clinic", "veterinary", "townhall");
    private static final List<String> LEISURE_AMENITIES = List.of(
            "restaurant", "cafe", "bar", "pub", "fast_food", "cinema", "theatre", "nightclub", "ice_cream");

    private AttractivityCalculator() {
    }

    public static void fromPois(List<Zone> zones, List<OverpassClient.Poi> pois) {
        initialize(zones);
        Zone[] grid = zones.toArray(new Zone[0]);
        for (OverpassClient.Poi poi : pois) {
            int index = GridZoning.zoneIndexOf(zones, poi.lat(), poi.lon());
            if (index < 0) {
                continue;
            }
            classify(grid[index], poi.tags());
        }
        addPopulationDerived(zones);
    }

    /** Fallback without OSM: center-weighted synthetic attractivities, reproducible via seed. */
    public static void synthetic(List<Zone> zones, Random random) {
        initialize(zones);
        double midRow = zones.stream().mapToInt(z -> z.row).average().orElse(0);
        double midCol = zones.stream().mapToInt(z -> z.col).average().orElse(0);
        double maxDist = Math.max(1, Math.hypot(midRow, midCol));
        for (Zone zone : zones) {
            // central zones are more attractive, mimicking a typical urban gradient
            double centrality = 1 - Math.hypot(zone.row - midRow, zone.col - midCol) / maxDist * 0.8;
            double base = zone.areaKm2 * centrality;
            add(zone, "Work", base * 400 * lognormalish(random));
            add(zone, "Business", base * 120 * lognormalish(random));
            add(zone, "Service", base * 60 * lognormalish(random));
            add(zone, "Shopping_Daily", base * 250 * lognormalish(random));
            add(zone, "Shopping_Other", base * 150 * lognormalish(random));
            add(zone, "Leisure_Indoor", base * 180 * lognormalish(random));
            add(zone, "Leisure_Outdoor", base * 120 * lognormalish(random));
            if (random.nextDouble() < 0.25) {
                add(zone, "Education_Primary", 300 + random.nextInt(300));
            }
            if (random.nextDouble() < 0.12) {
                add(zone, "Education_Secondary", 500 + random.nextInt(700));
            }
            if (random.nextDouble() < 0.03) {
                add(zone, "Education_Tertiary", 2000 + random.nextInt(4000));
            }
        }
        addPopulationDerived(zones);
    }

    private static void classify(Zone zone, Map<String, String> tags) {
        String shop = tags.get("shop");
        String amenity = tags.get("amenity");
        String office = tags.get("office");
        String leisure = tags.get("leisure");
        String tourism = tags.get("tourism");
        String craft = tags.get("craft");

        if (shop != null) {
            add(zone, DAILY_SHOPS.contains(shop) ? "Shopping_Daily" : "Shopping_Other", 25);
            add(zone, "Work", 8);
        }
        if (office != null) {
            add(zone, "Business", 30);
            add(zone, "Work", 40);
        }
        if (craft != null) {
            add(zone, "Work", 15);
            add(zone, "Service", 10);
        }
        if (amenity != null) {
            if (SERVICE_AMENITIES.contains(amenity)) {
                add(zone, "Service", 30);
                add(zone, "Work", 10);
            } else if (LEISURE_AMENITIES.contains(amenity)) {
                add(zone, "Leisure_Indoor", 30);
                add(zone, "Work", 8);
            } else if (amenity.equals("school")) {
                add(zone, "Education_Primary", 300);
                add(zone, "Education_Secondary", 400);
                add(zone, "Work", 30);
            } else if (amenity.equals("kindergarten")) {
                add(zone, "Education_Primary", 150);
                add(zone, "Work", 10);
            } else if (amenity.equals("university") || amenity.equals("college")) {
                add(zone, "Education_Tertiary", 3000);
                add(zone, "Work", 200);
            } else {
                add(zone, "Service", 5);
                add(zone, "Work", 5);
            }
        }
        if (leisure != null) {
            boolean indoor = leisure.contains("centre") || leisure.contains("hall") || leisure.equals("bowling_alley");
            add(zone, indoor ? "Leisure_Indoor" : "Leisure_Outdoor", 20);
        }
        if (tourism != null) {
            add(zone, "Leisure_Outdoor", 15);
        }
    }

    /** Private visits/business scale with residents, not with POIs. Run after population distribution. */
    private static void addPopulationDerived(List<Zone> zones) {
        for (Zone zone : zones) {
            add(zone, "Private_Visit", zone.population);
            add(zone, "Private_Business", zone.population * 0.6 + zone.attractivities.get("Service") * 0.3);
        }
    }

    private static void initialize(List<Zone> zones) {
        for (Zone zone : zones) {
            CATEGORIES.forEach(category -> zone.attractivities.put(category, 0.0));
        }
    }

    private static void add(Zone zone, String category, double value) {
        zone.attractivities.merge(category, value, Double::sum);
    }

    private static double lognormalish(Random random) {
        return Math.exp(random.nextGaussian() * 0.5);
    }
}
