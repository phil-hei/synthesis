package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommuterMatrixTest {

    @Test
    void parsesNormalizedCoordinateContractWithDecimalComma(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("commuters.csv");
        Files.writeString(file, """
                homeLat;homeLon;workLat;workLon;commuters
                48,85;8,18;48,87;8,22;75
                48,85;8,18;49,20;8,18;25
                """);

        CommuterMatrix.ParseResult parsed = CommuterMatrix.parse(file);

        assertEquals(2, parsed.flows().size());
        assertEquals(100, parsed.totalCommuters());
        assertEquals(48.85, parsed.flows().getFirst().homeLat());
    }

    @Test
    void observedExternalWorkplacesMoveAnchorsToRealCentroid() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> zones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        zones.addAll(ExternalZones.create(zones));
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(48.85, 8.18, 48.87, 8.22, 75),
                new CommuterMatrix.Flow(48.85, 8.18, 49.20, 8.18, 25)), 100);

        CommuterMatrix.Mapping mapping = CommuterMatrix.map(input, zones);

        assertEquals(2, mapping.mappedRows());
        assertEquals(25, mapping.externalCommuters());
        CommuterMatrix.MappedFlow external = mapping.flows().stream()
                .filter(flow -> zones.get(flow.workZoneIndex()).external)
                .findFirst().orElseThrow();
        Zone anchor = zones.get(external.workZoneIndex());
        assertEquals(49.20, anchor.centroidLat, 1e-9);
        assertEquals(8.18, anchor.centroidLon, 1e-9);

        int home = external.homeZoneIndex();
        int externalDraws = 0;
        Random random = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            if (zones.get(mapping.sampleWorkZone(home, random)).external) {
                externalDraws++;
            }
        }
        assertTrue(externalDraws > 400 && externalDraws < 600,
                "25-%-Relation muss in der Stichprobe sichtbar bleiben");
    }
}
