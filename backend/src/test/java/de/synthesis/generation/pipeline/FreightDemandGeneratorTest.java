package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FreightDemandGeneratorTest {
    @TempDir Path temp;
    static Scenario scenario() {
        Scenario s = new Scenario(); s.targetModels = List.of("sumo", "logitopp");
        s.freight.enabled = true;
        s.freight.relations = List.of(new FreightDemandGenerator.Relation("gate", "truck", "through",
                8.2002, 48.85, 8.2058, 48.85, 12, 6, 8, "Manuelle Testannahme", "2026-10-10"));
        return s;
    }
    @Test void preservesQuantitiesAndIndependentSeedAcrossRelationOrder() {
        Scenario s = scenario();
        var first = FreightDemandGenerator.generate(s);
        assertEquals(12, first.size());
        assertTrue(first.stream().allMatch(t -> t.departSec() >= 21600 && t.departSec() < 28800));
        assertEquals(first, FreightDemandGenerator.generate(s));
        s.population.seed++;
        assertNotEquals(first, FreightDemandGenerator.generate(s));
        s.population.seed--;
        var other = new FreightDemandGenerator.Relation("other", "trailer", "inbound", 8.2,48.85,8.206,48.85,
                2,0,1,"Manuelle Testannahme","2026-10-10");
        s.freight.relations = List.of(other,s.freight.relations.getFirst());
        assertEquals(first,FreightDemandGenerator.generate(s).stream().filter(t -> t.relationId().equals("gate")).toList());
    }
    @Test void sourcePreparationIsIndependentOfTargetDayAndInactiveExportChecks() {
        var s=scenario();s.targetModels=List.of("logitopp");s.freight.day=2;
        s.freight.profileChecksEnabled=true;s.freight.sevasEnabled=true;
        assertDoesNotThrow(()->FreightDemandGenerator.validateDemand(s));
        assertFalse(FreightDemandGenerator.generate(s).isEmpty());
        assertThrows(IllegalArgumentException.class,()->FreightDemandGenerator.validate(s));
    }
    @Test void rejectsMixedPopulationSampleAndMissingDemand() {
        Scenario s = scenario(); s.population.samplingFraction=.05;
        assertThrows(IllegalArgumentException.class, () -> FreightDemandGenerator.generate(s));
        s.population.samplingFraction=1; s.freight.relations=List.of();
        assertThrows(IllegalArgumentException.class, () -> FreightDemandGenerator.generate(s));
        s.freight.enabled=false; assertTrue(FreightDemandGenerator.generate(s).isEmpty());
    }
    @Test void disabledRelationsDoNotBlockRegionChange() {
        Scenario s = scenario();
        s.region = new Scenario.Region();
        s.region.minLon = 10; s.region.maxLon = 11; s.region.minLat = 50; s.region.maxLat = 51;
        assertThrows(IllegalArgumentException.class, () -> FreightDemandGenerator.validate(s));
        s.freight.enabled = false;
        assertDoesNotThrow(() -> FreightDemandGenerator.validate(s));
    }
    @Test void importsDirectedEdgesWithoutChangingQuantitiesOrDepartureStream() {
        String row="gate;truck;through;8.2;48.85;8.206;48.85;3;6;8;Test;2026-10-10";
        var legacy=FreightDemandGenerator.parseCsv((FreightDemandGenerator.HEADER+"\n"+row).getBytes()).getFirst();
        var directed=FreightDemandGenerator.parseCsv((FreightDemandGenerator.DIRECTED_HEADER+"\n"+row+";10;40").getBytes()).getFirst();
        assertNull(legacy.fromEdge()); assertEquals("10",directed.fromEdge());
        var scenario=scenario(); scenario.freight.relations=List.of(directed);
        assertTrue(FreightDemandGenerator.generate(scenario).stream().allMatch(t->"10".equals(t.fromEdge())));
        for(String suffix:List.of(";10;", ";10;10", ";bad edge;40"))
            assertThrows(IllegalArgumentException.class,()->FreightDemandGenerator.parseCsv((FreightDemandGenerator.DIRECTED_HEADER+"\n"+row+suffix).getBytes()));
    }
    @Test void strictImportRejectsDuplicatesBadDatesAndUnsupportedClasses() {
        String row="gate;truck;through;8.2;48.85;8.206;48.85;3;6;8;Testannahme;2026-10-10";
        assertEquals(3,FreightDemandGenerator.parseCsv((FreightDemandGenerator.HEADER+"\n"+row).getBytes()).getFirst().trips());
        for (String bad : List.of(row+"\n"+row, row.replace("truck","passenger"),row.replace("2026-10-10","2026-02-30"),row.replace(";3;6;8;",";3;9;8;")))
            assertThrows(IllegalArgumentException.class,()->FreightDemandGenerator.parseCsv((FreightDemandGenerator.HEADER+"\n"+bad).getBytes()));
    }
    @Test void writesProvenanceAndClearsDisabledDemand() throws Exception {
        Scenario s=scenario(); ObjectMapper mapper=new ObjectMapper();
        FreightDemandGenerator.write(temp,s,mapper);
        assertEquals(13,Files.readAllLines(temp.resolve("freight/trips.csv")).size());
        assertEquals("user-provided-unverified",mapper.readTree(temp.resolve("freight/sources.json").toFile()).path("quality").asText());
        s.freight.enabled=false; FreightDemandGenerator.write(temp,s,mapper);
        assertEquals(1,Files.readAllLines(temp.resolve("freight/trips.csv")).size());
        assertEquals(0,mapper.readTree(temp.resolve("freight/summary.json").toFile()).path("trips").asInt());
    }
}
