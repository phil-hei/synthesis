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

    @Test
    void regionScopedBaDistributionCoversEveryInternalHomeZoneAndPreservesTotal() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> zones = new ArrayList<>(GridZoning.generate(region, 4_000, 600));
        int internalZones = zones.size();
        zones.addAll(ExternalZones.create(zones));
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(48.8577, 8.2025, 49.01, 8.40, 700,
                        CommuterMatrix.HomeScope.REGION),
                new CommuterMatrix.Flow(48.8577, 8.2025, 48.76, 8.24, 200,
                        CommuterMatrix.HomeScope.REGION)), 900);

        CommuterMatrix.Mapping mapping = CommuterMatrix.map(input, zones);

        assertEquals(internalZones, mapping.homeZones());
        assertEquals(2, mapping.mappedRows());
        assertEquals(900, mapping.mappedCommuters(), 1e-9);
        assertEquals(900, mapping.flows().stream()
                .mapToDouble(CommuterMatrix.MappedFlow::commuters).sum(), 1e-6);
    }

    @Test
    void externalScopeCreatesConditionalDistributionWithoutReplacingFullOd() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> zones = new ArrayList<>(GridZoning.generate(region, 4_000, 600));
        int internalZones = zones.size();
        zones.addAll(ExternalZones.create(zones));
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(48.8577, 8.2025, 49.01, 8.40, 700,
                        CommuterMatrix.HomeScope.EXTERNAL)), 700);

        CommuterMatrix.Mapping mapping = CommuterMatrix.map(input, zones);

        assertEquals(internalZones, mapping.homeZones());
        assertEquals(0, mapping.cumulativeByHome().size());
        assertEquals(internalZones, mapping.externalCumulativeByHome().size());
        assertTrue(mapping.hasExternalDistribution(0));
        assertEquals(-1, mapping.sampleWorkZone(0, new Random(42)));
        assertTrue(mapping.sampleExternalWorkZone(0, new Random(42)) >= 0);
    }

    @Test
    void normalizedWriterPreservesSourceAndHomeScope(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("commuters.csv");
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(48.85, 8.18, 49.01, 8.40, 100,
                        CommuterMatrix.HomeScope.REGION)), 100);

        CommuterMatrix.writeNormalized(file, input, BaCommuterAdapter.SOURCE);

        assertEquals(BaCommuterAdapter.SOURCE, CommuterMatrix.source(file));
        assertEquals(CommuterMatrix.HomeScope.REGION,
                CommuterMatrix.parse(file).flows().getFirst().homeScope());
    }
}
