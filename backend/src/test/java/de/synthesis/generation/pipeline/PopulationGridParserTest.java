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
        assertEquals(1, result.points().size(), "Geheimhaltungszelle (-9999) wird übersprungen");
        assertEquals(1, result.skippedLines());
        assertEquals(1500, result.totalPopulation(), 1e-9);
        double[] centre = Epsg3035.toLatLon(4_334_500, 2_684_500); // Zellecke + res/2
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
        assertEquals(1, result.points().size());
        assertEquals(1, result.skippedLines());
        assertEquals(60, result.totalPopulation(), 1e-9);
    }

    @Test
    void unknownHeaderFailsWithHelpfulMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("unknown.csv", "foo;bar\n1;2\n"));
        assertTrue(e.getMessage().contains("GRD_ID"));
    }
}
