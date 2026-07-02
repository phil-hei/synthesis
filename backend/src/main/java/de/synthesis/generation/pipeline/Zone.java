package de.synthesis.generation.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/** A square grid cell acting as a traffic analysis zone. */
public class Zone {

    public int index;
    /** External zone id as used in zones.csv and the matrices. */
    public long id;
    public String name;
    public int row;
    public int col;
    public double minLat;
    public double minLon;
    public double maxLat;
    public double maxLon;
    public double centroidLat;
    public double centroidLon;
    public double areaKm2;

    public double population;
    public int households;
    /** External destination zone outside the study area (no residents, beeline impedance). */
    public boolean external;
    /** Attractivity per mobiTopp activity category. */
    public Map<String, Double> attractivities = new LinkedHashMap<>();

    public boolean contains(double lat, double lon) {
        return lat >= minLat && lat < maxLat && lon >= minLon && lon < maxLon;
    }
}
