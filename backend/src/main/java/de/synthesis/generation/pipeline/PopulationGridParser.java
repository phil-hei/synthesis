package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads point-based population grids from CSV. Two dialects are detected via
 * the header row:
 *
 * 1. Zensus-Gitter (Zensus 2022, EPSG:3035): columns x_mp_100m;y_mp_100m and a
 *    population column (e.g. "Einwohner"); coordinates are converted from LAEA.
 * 2. Generic WGS84: columns lat/lon (or latitude/longitude) + population/einwohner.
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
            int popCol = indexOf(header, "einwohner", "population", "pop", "anzahl", "insgesamt");

            boolean zensus = xCol >= 0 && yCol >= 0;
            if (!zensus && (latCol < 0 || lonCol < 0)) {
                throw new IllegalArgumentException(
                        "Spalten nicht erkannt. Erwartet: Zensus-Gitter (x_mp_100m;y_mp_100m;Einwohner) "
                                + "oder WGS84 (lat;lon;population). Gefunden: " + String.join(", ", header));
            }
            if (popCol < 0) {
                throw new IllegalArgumentException(
                        "Keine Einwohner-Spalte gefunden (erwartet z. B. 'Einwohner' oder 'population')");
            }

            List<GridPoint> points = new ArrayList<>();
            long skipped = 0;
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
                        double[] latLon = Epsg3035.toLatLon(
                                parseNumber(cells[xCol]), parseNumber(cells[yCol]));
                        lat = latLon[0];
                        lon = latLon[1];
                    } else {
                        lat = parseNumber(cells[latCol]);
                        lon = parseNumber(cells[lonCol]);
                    }
                    points.add(new GridPoint(lat, lon, population));
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
            return new ParseResult(points, zensus ? "zensus-gitter-epsg3035" : "wgs84-punkte", skipped);
        }
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
