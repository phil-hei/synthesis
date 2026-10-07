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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        int maxHouseholdSize = PopulationSample.fromResource("/kit-population-sample.csv")
                .households().stream().mapToInt(List::size).max().orElseThrow();
        assertTrue(result.persons().size() >= 2000 && result.persons().size() < 2000 + maxHouseholdSize,
                "Rundung ganzer Haushalte darf sich nicht über 576 Zonen aufsummieren");
        assertEquals(result.persons().size() + 1,
                java.nio.file.Files.readAllLines(dir.resolve("person.csv")).size());
        var rounding = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(dir.resolve("household-rounding.json").toFile());
        assertEquals(2000, rounding.path("targetPersons").asInt());
        assertEquals(result.persons().size() - 2000, rounding.path("difference").asInt());

    }

    @Test
    void commuterMappingControlsGeneratedWorkDestinations(@TempDir Path dir) throws IOException {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> zones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        zones.getFirst().population = 1_000;
        zones.addAll(ExternalZones.create(zones));
        Zone home = zones.getFirst();
        Zone internalWork = zones.stream().filter(zone -> !zone.external && zone.index != home.index)
                .findFirst().orElseThrow();
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(home.centroidLat, home.centroidLon,
                        internalWork.centroidLat, internalWork.centroidLon, 75),
                new CommuterMatrix.Flow(home.centroidLat, home.centroidLon, 49.20, 8.18, 25)), 100);
        CommuterMatrix.Mapping mapping = CommuterMatrix.map(input, zones);
        PopulationGenerator generator = new PopulationGenerator(zones, new Random(42));
        generator.useCommuterMapping(mapping);
        Scenario scenario = new Scenario();

        PopulationGenerator.Result result = generator.generate(dir, scenario);

        long external = result.persons().stream()
                .filter(person -> zones.get(person.workZoneIndex()).external)
                .count();
        assertTrue(external > 200 && external < 300,
                "Beobachtete 25-%-Außenrelation muss die erzeugten WORK-Fixziele steuern");
    }

    @Test
    void outboundOnlyMappingIsAppliedAfterGravitySelectsExternal(@TempDir Path dir)
            throws IOException {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> zones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        Zone home = zones.getFirst();
        home.population = 1_000;
        Zone observedWork = zones.stream()
                .filter(zone -> zone.index != home.index)
                .findFirst().orElseThrow();
        zones.addAll(ExternalZones.create(zones));
        zones.stream().filter(zone -> zone.external)
                .forEach(zone -> zone.attractivities.put("Work", 1e15));
        CommuterMatrix.ParseResult input = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(home.centroidLat, home.centroidLon,
                        observedWork.centroidLat, observedWork.centroidLon, 100,
                        CommuterMatrix.HomeScope.EXTERNAL)), 100);
        PopulationGenerator generator = new PopulationGenerator(zones, new Random(42));
        generator.useCommuterMapping(CommuterMatrix.map(input, zones));

        PopulationGenerator.Result result = generator.generate(dir, new Scenario());

        List<PopulationGenerator.PersonRef> workers = result.persons().stream()
                .filter(person -> person.workZoneIndex() >= 0)
                .toList();
        assertTrue(workers.size() > 300);
        assertTrue(workers.stream().allMatch(
                person -> person.workZoneIndex() == observedWork.index),
                "Der BA-Anker darf erst nach einer externen Gravitationswahl eingesetzt werden");
    }

    @Test
    void workplaceMarginControlsExternalShareWithoutPerturbingDemographics(@TempDir Path dir)
            throws IOException {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> controlZones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        List<Zone> treatmentZones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        controlZones.getFirst().population = 2_500;
        treatmentZones.getFirst().population = 2_500;
        controlZones.addAll(ExternalZones.create(controlZones));
        treatmentZones.addAll(ExternalZones.create(treatmentZones));

        PopulationGenerator control = new PopulationGenerator(controlZones, new Random(17), 17);
        PopulationGenerator treatment = new PopulationGenerator(treatmentZones, new Random(17), 17);
        Zone home = treatmentZones.getFirst();
        treatment.useCommuterMapping(CommuterMatrix.map(new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(home.centroidLat, home.centroidLon, 49.01, 8.40, 100,
                        CommuterMatrix.HomeScope.EXTERNAL)), 100), treatmentZones));
        treatment.useExternalWorkShare(13_723.0 / 22_971.0);

        PopulationGenerator.Result a = control.generate(dir.resolve("control"), new Scenario());
        PopulationGenerator.Result b = treatment.generate(dir.resolve("treatment"), new Scenario());

        double actualShare = (double) b.externalWorkDestinations() / b.persons().size();
        assertEquals(13_723.0 / 22_971.0, actualShare, 0.02);
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("control/household.csv"), dir.resolve("treatment/household.csv")));
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("control/person.csv"), dir.resolve("treatment/person.csv")));
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("control/car.csv"), dir.resolve("treatment/car.csv")));
    }

    @Test
    void commuterAlternativeDoesNotPerturbPopulationRandomStream(@TempDir Path dir)
            throws IOException {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        List<Zone> controlZones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        List<Zone> treatmentZones = new ArrayList<>(GridZoning.generate(region, 2_000, 600));
        controlZones.getFirst().population = 1_000;
        treatmentZones.getFirst().population = 1_000;
        controlZones.addAll(ExternalZones.create(controlZones));
        treatmentZones.addAll(ExternalZones.create(treatmentZones));

        PopulationGenerator control = new PopulationGenerator(
                controlZones, new Random(42), 42);
        PopulationGenerator treatment = new PopulationGenerator(
                treatmentZones, new Random(42), 42);
        control.freezeWorkGravity();
        treatment.freezeWorkGravity();
        Zone home = treatmentZones.getFirst();
        treatment.useCommuterMapping(CommuterMatrix.map(new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(home.centroidLat, home.centroidLon, 49.50, 8.20, 100,
                        CommuterMatrix.HomeScope.EXTERNAL)), 100), treatmentZones));

        PopulationGenerator.Result a = control.generate(dir.resolve("a"), new Scenario());
        PopulationGenerator.Result b = treatment.generate(dir.resolve("b"), new Scenario());

        assertEquals(a.householdCount(), b.householdCount());
        assertEquals(a.carCount(), b.carCount());
        assertEquals(a.persons().stream().map(person -> List.of(person.id(), person.householdId(),
                        person.age(), person.employment(), person.homeZoneIndex()))
                        .toList(),
                b.persons().stream().map(person -> List.of(person.id(), person.householdId(),
                        person.age(), person.employment(), person.homeZoneIndex()))
                        .toList());
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("a/household.csv"), dir.resolve("b/household.csv")));
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("a/person.csv"), dir.resolve("b/person.csv")));
        assertEquals(-1, java.nio.file.Files.mismatch(
                dir.resolve("a/car.csv"), dir.resolve("b/car.csv")));
        assertNotEquals(a.persons().stream().map(PopulationGenerator.PersonRef::workZoneIndex).toList(),
                b.persons().stream().map(PopulationGenerator.PersonRef::workZoneIndex).toList(),
                "Nur die behandelte Zielwahl soll sich zwischen A und B unterscheiden");
        assertEquals(a.persons().stream()
                        .filter(person -> controlZones.get(person.workZoneIndex()).external).count(),
                b.persons().stream()
                        .filter(person -> treatmentZones.get(person.workZoneIndex()).external).count(),
                "Anker-Geometrie darf die vorab gewählte Außenquote nicht verändern");
    }
    @Test
    void fixedDestinationsPreferFastNetworkConnectionOverEqualGeometry(@TempDir Path dir) throws Exception {
        List<Zone> zones = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Zone z = new Zone(); z.index = i; z.id = 100000 + i;
            z.minLat = 48; z.maxLat = 48.01; z.minLon = 8; z.maxLon = 8.01;
            z.centroidLat = 48.005; z.centroidLon = 8.005;
            z.population = i == 0 ? 2000 : 0;
            z.attractivities.put("Work", i == 0 ? 0.0 : 1000.0);
            zones.add(z);
        }
        var generator = new PopulationGenerator(zones, new Random(42), 42);
        generator.useDestinationTravelTimes(new float[][]{{5, 5, 120}, {5, 5, 120}, {120, 120, 5}});
        Scenario scenario = new Scenario(); scenario.population.useKitPopulation = false;
        var result = generator.generate(dir, scenario);
        long fast = result.persons().stream().filter(p -> p.workZoneIndex() == 1).count();
        long slow = result.persons().stream().filter(p -> p.workZoneIndex() == 2).count();
        assertTrue(fast > slow * 10, "Identische Geometrie, aber schnelle Netzverbindung muss Zielwahl prägen");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> generator.useDestinationTravelTimes(new float[][]{{5}}));
    }

    @Test void unreachableFixedDestinationsDoNotSilentlyChooseLastZone(@TempDir Path dir) {
        Zone home = new Zone(); home.index = 0; home.id = 100000; home.population = 1;
        home.minLat = 48; home.maxLat = 48.01; home.minLon = 8; home.maxLon = 8.01;
        var generator = new PopulationGenerator(List.of(home), new Random(42));
        generator.useDestinationTravelTimes(new float[][]{{Float.POSITIVE_INFINITY}});
        Scenario scenario = new Scenario(); scenario.population.useKitPopulation = false;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> generator.generate(dir, scenario));
    }

    @Test void completesLastPoolHouseholdInsteadOfRemovingFamilyMembers(@TempDir Path dir) throws Exception {
        var original = PopulationSample.fromResource("/kit-population-sample.csv").randomHousehold(new Random(0));
        assertTrue(original.size() > 1);
        Zone home = new Zone(); home.index = 0; home.id = 100000; home.population = 1;
        home.minLat = 48; home.maxLat = 48.01; home.minLon = 8; home.maxLon = 8.01;
        Scenario scenario = new Scenario(); scenario.population.useKitPopulation = true;
        var result = new PopulationGenerator(List.of(home), new Random(0)).generate(dir, scenario);
        assertEquals(original.stream().map(PopulationSample.Member::age).toList(),
                result.persons().stream().map(PopulationGenerator.PersonRef::age).toList());
        assertEquals(1, result.householdCount());
    }

}
