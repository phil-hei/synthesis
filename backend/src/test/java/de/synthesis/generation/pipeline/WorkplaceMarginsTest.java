package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkplaceMarginsTest {

    @Test
    void parsesOfficialIso88591FlatFileAndSelectsLatestRegionalMargin(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("13111-08-02-5-B_flat.csv");
        Files.write(file, officialRows().getBytes(StandardCharsets.ISO_8859_1));

        WorkplaceMargins.Export export = WorkplaceMargins.parseOfficial(file);
        WorkplaceMargins.Margin margin = WorkplaceMargins.selectLatestForRegion(export,
                Set.of("08216043")::contains, code -> true);

        assertEquals(2, export.observations().size());
        assertEquals(LocalDate.of(2025, 6, 30), margin.date());
        assertEquals(22_971, margin.residentWorkers());
        assertEquals(13_723, margin.outboundWorkers());
        assertEquals(13_723.0 / 22_971.0, margin.externalShare(), 1e-12);

        Path normalized = dir.resolve(WorkplaceMargins.NAME);
        WorkplaceMargins.writeNormalized(normalized, margin);
        assertEquals(margin.externalShare(),
                WorkplaceMargins.readNormalized(normalized).externalShare(), 1e-9);
    }

    @Test
    void rejectsFilesWithoutRequiredMeasures(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("wrong.csv");
        Files.writeString(file, officialRows().replace("ERW033", "ERW032")
                .replace("PEN092", "PEN091"));

        assertThrows(IllegalArgumentException.class,
                () -> WorkplaceMargins.parseOfficial(file));
    }

    @Test
    void resolvesRastattFromBundledBa2025Reference() throws Exception {
        WorkplaceMargins.Margin margin = WorkplaceMargins.bundledBaMargin("08216043")
                .orElseThrow();

        assertEquals(WorkplaceMargins.BA_SOURCE, margin.source());
        assertEquals(LocalDate.of(2025, 6, 30), margin.date());
        assertEquals(22_971, margin.residentWorkers());
        assertEquals(13_723, margin.outboundWorkers());
        assertEquals(13_723.0 / 22_971.0, margin.externalShare(), 1e-12);
        assertTrue(WorkplaceMargins.bundledBaMargin("01051035").isEmpty(),
                "Amtlich geheim gehaltene Auspendlerwerte bleiben absichtlich aus");
    }

    private static String officialRows() {
        return "statistics_code;statistics_label;time_code;time_label;time;1_variable_code;"
                + "1_variable_label;1_variable_attribute_code;1_variable_attribute_label;value;"
                + "value_unit;value_variable_code;value_variable_label\n"
                + "13111;Statistik der sozialversicherungspflichtig Beschäftigten;STAG;Stichtag;"
                + "2025-06-30;GEMEIN;Gemeinden;08216043;Rastatt, Stadt;22971;Anzahl;ERW033;"
                + "Sozialversicherungspfl. Beschäftigte (Wohnort)\n"
                + "13111;Statistik der sozialversicherungspflichtig Beschäftigten;STAG;Stichtag;"
                + "2025-06-30;GEMEIN;Gemeinden;08216043;Rastatt, Stadt;13723;Anzahl;PEN092;"
                + "Auspendelnde über Gemeindegrenzen (Wohnort)\n"
                + "13111;Statistik der sozialversicherungspflichtig Beschäftigten;STAG;Stichtag;"
                + "2024-06-30;GEMEIN;Gemeinden;08216043;Rastatt, Stadt;22000;Anzahl;ERW033;"
                + "Sozialversicherungspfl. Beschäftigte (Wohnort)\n"
                + "13111;Statistik der sozialversicherungspflichtig Beschäftigten;STAG;Stichtag;"
                + "2024-06-30;GEMEIN;Gemeinden;08216043;Rastatt, Stadt;12000;Anzahl;PEN092;"
                + "Auspendelnde über Gemeindegrenzen (Wohnort)\n";
    }
}
