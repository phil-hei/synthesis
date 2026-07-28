package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PopulationGeneratorTest {

    @Test
    void samplingFractionIsAppliedGloballyAcrossManyZones(@TempDir Path dir) throws IOException {
        int zoneCount = 576;
        List<Zone> zones = new ArrayList<>(zoneCount);
        for (int i = 0; i < zoneCount; i++) {
            Zone zone = new Zone();
            zone.index = i;
            zone.id = 100_000L + i;
            zone.minLat = 48.5 + (i / 24) * 0.02;
            zone.maxLat = zone.minLat + 0.02;
            zone.minLon = 8.0 + (i % 24) * 0.03;
            zone.maxLon = zone.minLon + 0.03;
            zone.centroidLat = (zone.minLat + zone.maxLat) / 2;
            zone.centroidLon = (zone.minLon + zone.maxLon) / 2;
            zone.population = 40_000.0 / zoneCount;
            zones.add(zone);
        }

        Scenario scenario = new Scenario();
        scenario.population.samplingFraction = 0.05;
        scenario.population.useKitPopulation = true;

        PopulationGenerator.Result result =
                new PopulationGenerator(zones, new Random(42)).generate(dir, scenario);

        assertEquals(2_000, result.persons().size(),
                "5 % von 40.000 müssen unabhängig von der Zonenzahl exakt 2.000 Personen ergeben");
        assertEquals(2_001, java.nio.file.Files.readAllLines(dir.resolve("person.csv")).size(),
                "CSV enthält Header plus exakt die globale Stichprobe");
    }
}
