package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MobileCityExporterTest {

    @Test
    void exportsPrivateCarStockByHouseholdHomeDistrict(@TempDir Path artifacts) throws Exception {
        Path population = Files.createDirectories(artifacts.resolve("population"));
        Files.writeString(population.resolve("household.csv"), """
                householdNumber;householdId;year;homeZone;homeLocation;domCode;type;income;economicalStatus
                1;1;2025;100001;8.1,48.1;0;1;2000;2
                2;2;2025;100001;8.1,48.1;0;2;3000;3
                3;3;2025;100002;8.2,48.2;0;1;1800;2
                4;4;2025;999999;9.0,49.0;0;1;1800;2
                """);
        Files.writeString(population.resolve("person.csv"), """
                personId;personNumber;householdId;age
                1;1;1;35
                2;2;2;12
                3;3;3;70
                """);
        Files.writeString(population.resolve("activity.csv"),
                "personOid;activityType;activityNr\n");
        Files.writeString(population.resolve("car.csv"), """
                ownerId;mainUserId;personalUserId;carType;car attributes
                1;1;1;conventional;car-1
                2;2;2;conventional;car-2
                2;2;2;conventional;car-3
                3;3;3;conventional;car-4
                4;4;4;conventional;car-outside
                """);

        de.synthesis.scenario.Scenario scenario = new de.synthesis.scenario.Scenario();
        scenario.referenceYear = 2025;
        int files = new MobileCityExporter(List.of(zone(0, 100001), zone(1, 100002)), scenario, null)
                .export(artifacts);

        assertThat(files).isEqualTo(12);
        Map<String, ParsedTable> tables = parseEpigeneTables(
                artifacts.resolve("mobilecity/TECCarFleet.csv"));
        assertThat(tables).containsOnlyKeys("stock.total");
        assertThat(tables.get("stock.total").header()).isEqualTo("districtID,2025");
        assertThat(tables.get("stock.total").rows()).containsExactly(
                "SYN-001,3", "SYN-002,1");

        JsonNode summary = new ObjectMapper().readTree(
                artifacts.resolve("mobilecity/summary.json").toFile());
        assertThat(summary.path("tables").asInt()).isEqualTo(6);
        assertThat(summary.path("privateCars").asLong()).isEqualTo(4);
    }

    @Test
    void preservesGlobalPopulationAndCarTotalsAcrossFractionalZoneWeights(@TempDir Path artifacts)
            throws Exception {
        List<Zone> zones = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Zone zone = zone(i, 100001 + i);
            zone.population = 250d / 9;
            zones.add(zone);
        }
        de.synthesis.scenario.Scenario scenario = new de.synthesis.scenario.Scenario();
        scenario.referenceYear = 2026;
        scenario.population.samplingFraction = 1;
        var generated = new PopulationGenerator(zones, new Random(42))
                .generate(Files.createDirectories(artifacts.resolve("population")), scenario);
        new ActivityGenerator(new Random(42)).generate(
                artifacts.resolve("population/activity.csv"), generated.persons());

        MobileCityPopulation mobile = new MobileCityPopulation(
                zones, artifacts.resolve("population"), 1, 2026);

        assertThat(java.util.Arrays.stream(mobile.ages)
                .flatMapToLong(java.util.Arrays::stream).sum()).isEqualTo(generated.persons().size());
        assertThat(java.util.Arrays.stream(mobile.cars).sum()).isEqualTo(generated.carCount());
    }

    /** Independent emulation of the Config Tool's padded separator/table-block logic. */
    private static Map<String, ParsedTable> parseEpigeneTables(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        int fields = lines.getFirst().split(",", -1).length;
        String separator = ",".repeat(fields - 1);
        Map<String, ParsedTable> tables = new LinkedHashMap<>();
        for (int i = 1; i < lines.size();) {
            if (!separator.equals(lines.get(i))) {
                i++;
                continue;
            }
            String name = lines.get(++i);
            String header = lines.get(++i);
            List<String> rows = new ArrayList<>();
            for (i++; i < lines.size() && !separator.equals(lines.get(i)); i++) {
                rows.add(lines.get(i));
            }
            tables.put(name, new ParsedTable(header, rows));
        }
        return tables;
    }

    private static Zone zone(int index, long id) {
        Zone zone = new Zone();
        zone.index = index;
        zone.id = id;
        zone.name = "Zone " + (index + 1);
        zone.minLat = 48.0 + index * 0.1;
        zone.maxLat = zone.minLat + 0.05;
        zone.minLon = 8.0 + index * 0.1;
        zone.maxLon = zone.minLon + 0.05;
        zone.centroidLat = (zone.minLat + zone.maxLat) / 2;
        zone.centroidLon = (zone.minLon + zone.maxLon) / 2;
        return zone;
    }

    private record ParsedTable(String header, List<String> rows) {
    }
}
