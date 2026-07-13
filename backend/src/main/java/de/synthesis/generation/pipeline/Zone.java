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

    /** INSPIRE-Zonen: SW-Ecke in EPSG:3035-Metern + Zellkante; {@code laeaRes > 0} → LAEA-Containment. */
    public long laeaE;
    public long laeaN;
    public int laeaRes;

    public double population;
    public int households;
    /** External destination zone outside the study area (no residents, beeline impedance). */
    public boolean external;
    /** Attractivity per mobiTopp activity category. */
    public Map<String, Double> attractivities = new LinkedHashMap<>();

    public boolean contains(double lat, double lon) {
        if (laeaRes > 0) {
            // Korrekte, überlappungsfreie Partition in LAEA-Metern (das Lat/Lon-Bbox eines
            // LAEA-Quadrats überlappt Nachbarn und würde falsch zuordnen).
            double[] en = Epsg3035.fromLatLon(lat, lon);
            return en[0] >= laeaE && en[0] < laeaE + laeaRes
                    && en[1] >= laeaN && en[1] < laeaN + laeaRes;
        }
        return lat >= minLat && lat < maxLat && lon >= minLon && lon < maxLon;
    }
}
