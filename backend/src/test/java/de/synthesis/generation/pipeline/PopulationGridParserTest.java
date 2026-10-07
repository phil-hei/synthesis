package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialekt-Erkennung des {@link PopulationGridParser}: Zensus-x/y-Spalten, INSPIRE-/
 * Eurostat-Zell-IDs (Langform CRS3035RES…, GEOSTAT-Kurzform 1kmN…E…) und generisches
 * WGS84 — inklusive der Invariante, dass Langform-ID, Kurzform-ID und x_mp-Mittelpunkt
 * derselben Zelle auf denselben Punkt abbilden.
 */
class PopulationGridParserTest {

    @TempDir
    Path dir;

    private PopulationGridParser.ParseResult parse(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return PopulationGridParser.parse(file);
    }

    @Test
    void readsEurostatCensusGrid2021Ids() throws IOException {
        PopulationGridParser.ParseResult result = parse("eurostat.csv",
                "GRD_ID,T,M,F\n"
                        + "CRS3035RES1000mN2684000E4334000,1500,700,800\n"
                        + "CRS3035RES1000mN2685000E4334000,-9999,0,0\n");
        assertEquals("inspire-grid-id-epsg3035", result.format());
        assertEquals(100, result.points().size(), "1 km wird gleichmäßig in 100-m-Gewichte aufgeteilt");
        assertEquals(1, result.skippedLines());
        assertEquals(1500, result.totalPopulation(), 1e-9);
        double[] centre = Epsg3035.toLatLon(4_334_050, 2_684_050); // Zellecke + res/2
        assertEquals(centre[0], result.points().get(0).lat(), 1e-9);
        assertEquals(centre[1], result.points().get(0).lon(), 1e-9);
    }

    @Test
    void geostatShortFormMatchesLongFormCell() throws IOException {
        PopulationGridParser.ParseResult longForm = parse("long.csv",
                "GRD_ID,T\nCRS3035RES1000mN2684000E4334000,850\n");
        PopulationGridParser.ParseResult shortForm = parse("short.csv",
                "GRD_ID;TOT_P\n1kmN2684E4334;850\n");
        assertEquals(longForm.points().get(0).lat(), shortForm.points().get(0).lat(), 1e-9);
        assertEquals(longForm.points().get(0).lon(), shortForm.points().get(0).lon(), 1e-9);
    }

    @Test
    void zensusGitterIdColumnMatchesXyDialect() throws IOException {
        PopulationGridParser.ParseResult viaId = parse("id.csv",
                "GITTER_ID_100m;Einwohner\nCRS3035RES100mN2691700E4341100;25\n");
        PopulationGridParser.ParseResult viaXy = parse("xy.csv",
                "x_mp_100m;y_mp_100m;Einwohner\n4341150;2691750;25\n");
        assertEquals("inspire-grid-id-epsg3035", viaId.format());
        assertEquals("zensus-gitter-epsg3035", viaXy.format());
        assertEquals(viaXy.points().get(0).lat(), viaId.points().get(0).lat(), 1e-9);
        assertEquals(viaXy.points().get(0).lon(), viaId.points().get(0).lon(), 1e-9);
    }

    @Test
    void uniformlyAllocatesOneKilometreCellToFourModelZones() throws IOException {
        var result = parse("coarse.csv", "x_mp_1km;y_mp_1km;Einwohner\n4334500;2684500;101\n");
        var zones = new java.util.ArrayList<Zone>();
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
            Zone zone = new Zone(); zone.index = zones.size();
            zone.laeaE = 4334000 + x * 500; zone.laeaN = 2684000 + y * 500;
            zone.laeaRes = 500; zones.add(zone);
        }
        double[] weights = new double[4];
        for (var point : result.points())
            weights[GridZoning.zoneIndexOf(zones, point.lat(), point.lon())] += point.population();
        for (double weight : weights) assertEquals(25.25, weight, 1e-7);
        assertEquals(101, result.totalPopulation(), 1e-7);
        new PopulationGenerator(zones, new java.util.Random(42), 42, 2026)
                .distributePopulation(101, null, weights, false);
        for (Zone zone : zones) assertEquals(25.25, zone.population, 1e-7);
    }

    @Test
    void preservesEmptyAndUnevenRasterZonesWithoutSmoothing() {
        var zones = new java.util.ArrayList<Zone>();
        for (int i = 0; i < 3; i++) {
            Zone zone = new Zone(); zone.index = i; zones.add(zone);
        }
        new PopulationGenerator(zones, new java.util.Random(42), 42, 2026)
                .distributePopulation(100, null, new double[]{0, 25, 75}, false);
        assertEquals(0, zones.get(0).population, 1e-9);
        assertEquals(25, zones.get(1).population, 1e-9);
        assertEquals(75, zones.get(2).population, 1e-9);
    }

    @Test
    void retainsPartialCoarseCellEvenWhenItsCentreIsOutsideRegion() throws IOException {
        Path file = dir.resolve("edge.csv");
        Files.writeString(file, "GRD_ID,T\nCRS3035RES1000mN2684000E4334000,100\n");
        double[] corner = Epsg3035.toLatLon(4334050, 2684050);
        var region = new de.synthesis.scenario.Scenario.Region();
        region.minLat = corner[0] - .00001; region.maxLat = corner[0] + .00001;
        region.minLon = corner[1] - .00001; region.maxLon = corner[1] + .00001;
        assertEquals(1, PopulationGridParser.parse(file, region).totalPopulation(), 1e-7);
    }

    @Test
    void readsGenericWgs84() throws IOException {
        PopulationGridParser.ParseResult result = parse("wgs84.csv",
                "lat,lon,population\n49.1,8.2,100\n");
        assertEquals("wgs84-punkte", result.format());
        assertEquals(49.1, result.points().get(0).lat(), 1e-9);
        assertEquals(8.2, result.points().get(0).lon(), 1e-9);
    }

    @Test
    void malformedGridIdRowsAreSkippedNotFatal() throws IOException {
        PopulationGridParser.ParseResult result = parse("mixed.csv",
                "GRD_ID,T\nkeine-id,50\nCRS3035RES1000mN2684000E4334000,60\n");
        assertEquals(100, result.points().size());
        assertEquals(1, result.skippedLines());
        assertEquals(60, result.totalPopulation(), 1e-9);
    }

    @Test
    void nationalGridCanBeFilteredToTheScenarioRegionWhileStreaming() throws IOException {
        double[] rastatt = Epsg3035.fromLatLon(48.86, 8.20);
        double[] berlin = Epsg3035.fromLatLon(52.52, 13.40);
        Path file = dir.resolve("deutschland.csv");
        Files.writeString(file, "GITTER_ID_100m;x_mp_100m;y_mp_100m;Einwohner\n"
                + "r;" + rastatt[0] + ";" + rastatt[1] + ";250\n"
                + "b;" + berlin[0] + ";" + berlin[1] + ";500\n");
        de.synthesis.scenario.Scenario.Region region = new de.synthesis.scenario.Scenario.Region();
        region.minLat = 48.84;
        region.maxLat = 48.88;
        region.minLon = 8.17;
        region.maxLon = 8.24;

        PopulationGridParser.ParseResult result = PopulationGridParser.parse(file, region);

        assertEquals(1, result.points().size());
        assertEquals(250, result.totalPopulation(), 1e-9);
        assertEquals(0, result.skippedLines(), "herausgefilterte Regionen sind keine Parsefehler");
    }

    @Test
    void unknownHeaderFailsWithHelpfulMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("unknown.csv", "foo;bar\n1;2\n"));
        assertTrue(e.getMessage().contains("GRD_ID"));
    }
}
