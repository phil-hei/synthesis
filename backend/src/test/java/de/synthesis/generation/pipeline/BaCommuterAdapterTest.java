package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaCommuterAdapterTest {

    @Test
    void parsesOnlyMunicipalityLevelWithoutDoubleCountingHierarchy(@TempDir Path tempDir)
            throws Exception {
        Path file = tempDir.resolve("ba.csv");
        Files.writeString(file, baExport());

        BaCommuterAdapter.Export export = BaCommuterAdapter.parse(file);

        assertEquals(2, export.relations().size());
        assertEquals(900, export.municipalityCommuters());
        assertEquals(1_000, export.exportTotalCommuters());
        assertEquals(2, export.aggregateRows());
        assertEquals("Juni 2025", export.geographicDate());
    }

    @Test
    void resolvesKeysToRegionScopedCoordinateOd(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ba.csv");
        Files.writeString(file, baExport());
        Map<String, MunicipalityCentroidSource.Point> points = Map.of(
                "08216043", new MunicipalityCentroidSource.Point("08216043", 48.857703, 8.202529),
                "08212000", new MunicipalityCentroidSource.Point("08212000", 49.0069, 8.4037),
                "08211000", new MunicipalityCentroidSource.Point("08211000", 48.7606, 8.2398));

        BaCommuterAdapter.Adapted adapted =
                BaCommuterAdapter.adapt(BaCommuterAdapter.parse(file), points);

        assertEquals(2, adapted.matrix().flows().size());
        assertEquals(900, adapted.matrix().totalCommuters());
        assertEquals(0.9, adapted.coverage(), 1e-9);
        assertEquals(CommuterMatrix.HomeScope.EXTERNAL,
                adapted.matrix().flows().getFirst().homeScope());
    }

    @Test
    void rejectsMultipleHomeMunicipalities(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ba.csv");
        Files.writeString(file, baExport()
                + "\"Bühl, Stadt\";\"08216007\";\"Karlsruhe, Stadt\";\"08212000\";\"50\"\n");

        assertThrows(IllegalArgumentException.class, () -> BaCommuterAdapter.parse(file));
    }

    private static String baExport() {
        return """
                \uFEFF"Gebietsstand";"Juni 2025"
                "Datenstand";"Januar 2026"
                "Thema:";"Auspendler Gemeinden"
                "Wohnort";"Regionalschlüssel";"Arbeitsort";"Regionalschlüssel";"Insgesamt";"Männer"
                "Rastatt, Stadt";"08216043";"Auspendler insgesamt";"0";"1.000";"600"
                "Rastatt, Stadt";"08216043";"Baden-Württemberg";"08";"900";"500"
                "Rastatt, Stadt";"08216043";"Reg.-Bez. Karlsruhe";"082";"900";"500"
                "Rastatt, Stadt";"08216043";"Karlsruhe, Stadt";"08212000";"700";"400"
                "Rastatt, Stadt";"08216043";"Baden-Baden, Stadt";"08211000";"200";"100"
                """;
    }
}
