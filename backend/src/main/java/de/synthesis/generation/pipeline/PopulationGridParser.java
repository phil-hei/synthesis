package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.synthesis.scenario.Scenario;

/**
 * Reads point-based population grids from CSV. Three dialects are detected via
 * the header row:
 *
 * 1. Zensus-Gitter (Zensus 2022, EPSG:3035): columns x_mp_100m;y_mp_100m and a
 *    population column (e.g. "Einwohner"); coordinates are converted from LAEA.
 * 2. INSPIRE-Zell-IDs (Eurostat Census Grid 2021 "GRD_ID", Zensus "GITTER_ID_*"):
 *    Langform CRS3035RES&lt;res&gt;mN&lt;northing&gt;E&lt;easting&gt; (Meter) oder
 *    GEOSTAT-Kurzform 1kmN2684E4334 (Koordinate x Aufloesung); verarbeitet wird der
 *    Zellmittelpunkt (Ecke + res/2). Einwohner-Spalte akzeptiert hier zusaetzlich
 *    die Eurostat-Namen "T"/"TOT_P"/"OBS_VALUE".
 * 3. Generic WGS84: columns lat/lon (or latitude/longitude) + population/einwohner.
 *
 * Point data is zoning-agnostic: the pipeline maps points into whatever zone
 * grid the scenario uses, which sidesteps the format heterogeneity of
 * zone-based inputs.
 */
public final class PopulationGridParser {

    public record GridPoint(double lat, double lon, double population) {
    }

    public record ParseResult(List<GridPoint> points, String format, long skippedLines) {
        public double totalPopulation() {
            return points.stream().mapToDouble(GridPoint::population).sum();
        }
    }

    private PopulationGridParser() {
    }

    public static ParseResult parse(Path file) throws IOException {
        return parse(file, null);
    }

    /** Liest nur Punkte innerhalb der Region; wichtig für das deutschlandweite 100-m-Raster. */
    public static ParseResult parse(Path file, Scenario.Region region) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Datei ist leer");
            }
            char delimiter = headerLine.chars().filter(c -> c == ';').count()
                    >= headerLine.chars().filter(c -> c == ',').count() ? ';' : ',';
            String[] header = headerLine.replace("﻿", "").split(String.valueOf(delimiter), -1);

            int xCol = indexOf(header, "x_mp_100m", "x_mp_1km", "x");
            int yCol = indexOf(header, "y_mp_100m", "y_mp_1km", "y");
            int latCol = indexOf(header, "lat", "latitude", "breite");
            int lonCol = indexOf(header, "lon", "lng", "longitude", "laenge");
            int gridIdCol = indexOf(header, "grd_id", "gitter_id_100m", "gitter_id_1km",
                    "gitter_id_10km", "gitter_id");

            boolean zensus = xCol >= 0 && yCol >= 0;
            boolean gridId = !zensus && gridIdCol >= 0;
            if (!zensus && !gridId && (latCol < 0 || lonCol < 0)) {
                throw new IllegalArgumentException(
                        "Spalten nicht erkannt. Erwartet: Zensus-Gitter (x_mp_100m;y_mp_100m;Einwohner), "
                                + "INSPIRE-/Eurostat-Gitter (GRD_ID bzw. GITTER_ID + Einwohner/T) "
                                + "oder WGS84 (lat;lon;population). Gefunden: " + String.join(", ", header));
            }
            int popCol = indexOf(header, "einwohner", "population", "pop", "anzahl", "insgesamt");
            if (popCol < 0 && gridId) {
                // Eurostat-Namen: Census Grid 2021 "T", GEOSTAT "TOT_P", SDMX-Exporte "OBS_VALUE" —
                // bewusst nur im Gitter-ID-Dialekt, damit das generische "t" nichts falsch matcht.
                popCol = indexOf(header, "t", "tot_p", "obs_value");
            }
            if (popCol < 0) {
                throw new IllegalArgumentException(
                        "Keine Einwohner-Spalte gefunden (erwartet z. B. 'Einwohner' oder 'population')");
            }

            List<GridPoint> points = new ArrayList<>();
            long skipped = 0;
            double[] laeaBounds = region != null && (zensus || gridId)
                    ? laeaBounds(region) : null;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(String.valueOf(delimiter), -1);
                try {
                    double population = parseNumber(cells[popCol]);
                    if (population <= 0) {
                        skipped++; // Zensus nutzt negative Werte/"–" für geheim gehaltene Zellen
                        continue;
                    }
                    double lat;
                    double lon;
                    if (zensus) {
                        double easting = parseNumber(cells[xCol]);
                        double northing = parseNumber(cells[yCol]);
                        if (!withinLaeaBounds(easting, northing, laeaBounds)) {
                            continue;
                        }
                        double[] latLon = Epsg3035.toLatLon(easting, northing);
                        lat = latLon[0];
                        lon = latLon[1];
                    } else if (gridId) {
                        double[] centre = gridIdToCentre(cells[gridIdCol]);
                        if (!withinLaeaBounds(centre[0], centre[1], laeaBounds)) {
                            continue;
                        }
                        double[] latLon = Epsg3035.toLatLon(centre[0], centre[1]);
                        lat = latLon[0];
                        lon = latLon[1];
                    } else {
                        lat = parseNumber(cells[latCol]);
                        lon = parseNumber(cells[lonCol]);
                    }
                    if (region != null && (lat < region.minLat || lat > region.maxLat
                            || lon < region.minLon || lon > region.maxLon)) {
                        continue;
                    }
                    points.add(new GridPoint(lat, lon, population));
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
            String format = zensus ? "zensus-gitter-epsg3035"
                    : gridId ? "inspire-grid-id-epsg3035" : "wgs84-punkte";
            return new ParseResult(points, format, skipped);
        }
    }

    private static double[] laeaBounds(Scenario.Region region) {
        double[][] samples = {
                Epsg3035.fromLatLon(region.minLat, region.minLon),
                Epsg3035.fromLatLon(region.minLat, region.maxLon),
                Epsg3035.fromLatLon(region.maxLat, region.minLon),
                Epsg3035.fromLatLon(region.maxLat, region.maxLon),
                Epsg3035.fromLatLon((region.minLat + region.maxLat) / 2, region.minLon),
                Epsg3035.fromLatLon((region.minLat + region.maxLat) / 2, region.maxLon),
                Epsg3035.fromLatLon(region.minLat, (region.minLon + region.maxLon) / 2),
                Epsg3035.fromLatLon(region.maxLat, (region.minLon + region.maxLon) / 2)
        };
        double minE = Double.POSITIVE_INFINITY;
        double maxE = Double.NEGATIVE_INFINITY;
        double minN = Double.POSITIVE_INFINITY;
        double maxN = Double.NEGATIVE_INFINITY;
        for (double[] sample : samples) {
            minE = Math.min(minE, sample[0]);
            maxE = Math.max(maxE, sample[0]);
            minN = Math.min(minN, sample[1]);
            maxN = Math.max(maxN, sample[1]);
        }
        // Großzügiger Rand fängt die leichte Krümmung geografischer Bbox-Kanten in LAEA ab;
        // die exakte Lat/Lon-Prüfung erfolgt danach.
        return new double[]{minE - 50_000, maxE + 50_000, minN - 50_000, maxN + 50_000};
    }

    private static boolean withinLaeaBounds(double easting, double northing, double[] bounds) {
        return bounds == null || (easting >= bounds[0] && easting <= bounds[1]
                && northing >= bounds[2] && northing <= bounds[3]);
    }

    /** Langform: volle LAEA-Meter (Eurostat Census 2021, Zensus 2022 GITTER_ID). */
    private static final Pattern GRID_ID_FULL =
            Pattern.compile("CRS3035RES(\\d+)mN(\\d+)E(\\d+)", Pattern.CASE_INSENSITIVE);
    /** Kurzform (GEOSTAT/Zensus 2011): Koordinate in Einheiten der Aufloesung, z. B. 1kmN2684E4334. */
    private static final Pattern GRID_ID_SHORT =
            Pattern.compile("(\\d+)(m|km)N(\\d+)E(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * INSPIRE-Zell-ID → LAEA-Zellmittelpunkt {easting, northing}. Beide ID-Formen adressieren
     * die Zell-Ecke (links unten); der Mittelpunkt (+res/2) haelt die Punkt-in-Zone-Zuordnung
     * deckungsgleich mit den x_mp/y_mp-Mittelpunktspalten der Zensus-CSVs.
     */
    private static double[] gridIdToCentre(String value) {
        String id = value.trim().replace("\"", "");
        Matcher full = GRID_ID_FULL.matcher(id);
        if (full.matches()) {
            double res = Double.parseDouble(full.group(1));
            double northing = Double.parseDouble(full.group(2));
            double easting = Double.parseDouble(full.group(3));
            return new double[]{easting + res / 2, northing + res / 2};
        }
        Matcher shortForm = GRID_ID_SHORT.matcher(id);
        if (shortForm.matches()) {
            double res = Double.parseDouble(shortForm.group(1))
                    * ("km".equalsIgnoreCase(shortForm.group(2)) ? 1000 : 1);
            double northing = Double.parseDouble(shortForm.group(3)) * res;
            double easting = Double.parseDouble(shortForm.group(4)) * res;
            return new double[]{easting + res / 2, northing + res / 2};
        }
        throw new IllegalArgumentException("Keine INSPIRE-Zell-ID: " + value);
    }

    private static int indexOf(String[] header, String... candidates) {
        for (int i = 0; i < header.length; i++) {
            String name = header[i].trim().toLowerCase(Locale.ROOT).replace("\"", "");
            for (String candidate : candidates) {
                if (name.equals(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static double parseNumber(String value) {
        return Double.parseDouble(value.trim().replace("\"", "").replace(',', '.'));
    }
}
