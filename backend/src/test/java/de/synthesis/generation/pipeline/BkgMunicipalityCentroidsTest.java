package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BkgMunicipalityCentroidsTest {

    @Test
    void parsesOfficialWfsCsvContract() throws Exception {
        Map<String, MunicipalityCentroidSource.Point> points = BkgMunicipalityCentroids.parse("""
                FID,ags,lon_dez,lat_dez
                vg250_pk.5589,08216043,8.202529,48.857703
                vg250_pk.5511,08212000,8.4037,49.0069
                """);

        assertEquals(2, points.size());
        assertEquals(48.857703, points.get("08216043").lat(), 1e-9);
        assertEquals(8.202529, points.get("08216043").lon(), 1e-9);
    }

    @Test
    void rejectsSchemaDrift() {
        assertThrows(IllegalArgumentException.class,
                () -> BkgMunicipalityCentroids.parse("AGS,LAT,LON\n08216043,48.85,8.2\n"));
    }
}
