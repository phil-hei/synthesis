package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.assertThat;

class CensusStructureTest {
    @TempDir Path dir;

    @Test void resolvesPinnedFilesAndNormalizesPerturbedGroupsInsteadOfTotals() throws Exception {
        var region = region();
        var zones = new ArrayList<>(GridZoning.generate(region, 1000, 1000));
        Zone z = zones.getFirst();
        var age = archive("age", CensusStructureSource.AGES, z, "999;10;10;10;10;20\n");
        var hh = archive("hh", CensusStructureSource.HOUSEHOLDS, z, "999;10;10;10;10;10;–\n");
        int[] requests = {0};
        var source = new CensusStructureSource(new SynthesisProperties.Census(), url -> {
            requests[0]++; return Files.newInputStream(Path.of(url));
        }, List.of(age, hh));
        var result = source.resolve(region, zones, dir.resolve("cache"), s -> {});
        assertThat(result.unavailableReason()).isNull();
        assertThat(result.zones().get(z.index).ages()).containsExactly(1.0/6, .5, 1.0/3);
        assertThat(result.zones().get(z.index).households()).containsExactly(.2,.2,.2,.2,.2,0);
        assertThat(result.ages().inconsistentCells()).isEqualTo(1);
        assertThat(result.zones().values()).anyMatch(m -> m.ageLevel().equals("region-fallback"));
        source.resolve(region, zones, dir.resolve("cache"), s -> {});
        assertThat(requests[0]).isEqualTo(2);
    }

    @Test void hashMismatchFallsBackAndLeavesNoPartialDownload() throws Exception {
        var dataset = new CensusStructureSource.Dataset("bad", "unused", "0000", "unused", List.of());
        var source = new CensusStructureSource(new SynthesisProperties.Census(),
                url -> new ByteArrayInputStream(new byte[]{1,2,3}), List.of(dataset, dataset));
        var result = source.resolve(region(), GridZoning.generate(region(),1000,1000), dir, s -> {});
        assertThat(result.zones()).isEmpty();
        assertThat(result.unavailableReason()).contains("SHA-256");
        try (var files = Files.list(dir)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void suppressedGroupsAreNotFabricatedFromPositiveTotal() throws Exception {
        var zones = GridZoning.generate(region(), 1000, 1000);
        var age = archive("suppressed", CensusStructureSource.AGES, zones.getFirst(), "30;–;–;–;–;–\n");
        var grid = CensusStructureSource.read(Path.of(age.url()), age, region(), zones);
        assertThat(grid.zeroStructureCells()).isEqualTo(1);
        assertThat(Arrays.stream(grid.counts()[0]).skip(1).sum()).isZero();
    }

    @Test void aggregatesAgeGroupsIntoDirectionalBufferPolygons() throws Exception {
        var study = region();
        var areas = MobileCitySurroundingSource.sectorGeometries(study, 10);
        var point = areas.getFirst().getInteriorPoint();
        Zone location = new Zone(); location.centroidLat = point.getY(); location.centroidLon = point.getX();
        var age = archive("area-age", CensusStructureSource.AGES, location,
                "70;10;10;10;20;20\n");
        var source = new CensusStructureSource(new SynthesisProperties.Census(),
                url -> Files.newInputStream(Path.of(url)), List.of(age));
        var bounds = new Scenario.Region();
        bounds.minLat=study.minLat-.3;bounds.maxLat=study.maxLat+.3;
        bounds.minLon=study.minLon-.5;bounds.maxLon=study.maxLon+.5;

        var result = source.resolveAgeAreas(bounds, areas, dir.resolve("area-cache"), s -> {});

        assertThat(result.available()).isTrue();
        assertThat(result.cells()).isEqualTo(1);
        assertThat(result.counts()[0]).containsExactly(10,40,20);
        assertThat(Arrays.stream(result.counts()).skip(1).flatMapToDouble(Arrays::stream).sum()).isZero();
    }

    @Test void exactBoundaryMarginsWorkAndImpossibleSixPersonTargetIsRejected() {
        var pool = PopulationSample.fromResource("/kit-population-sample.csv");
        var seniors = new CensusHouseholdSampler(pool, margins(new double[]{0,0,1},new double[]{1,0,0,0,0,0}));
        assertThat(seniors.usable()).isTrue();
        for (int i=0;i<100;i++) assertThat(seniors.draw(new Random(i),10)).allMatch(m -> m.age()>=65).hasSize(1);
        var impossible = new CensusHouseholdSampler(pool, margins(new double[]{.2,.6,.2},new double[]{0,0,0,0,0,1}));
        assertThat(impossible.usable()).isFalse();
        assertThat(((List<?>)impossible.report().get("unsupportedGroups")).contains("household-size-6+")).isTrue();
    }

    @Test void rejectedFitRetainsCompleteBoundaryHousehold() {
        var pool = PopulationSample.fromResource("/kit-population-sample.csv");
        var target = margins(new double[]{.1,.4,.5},new double[]{.4,.3,.12,.08,.06,.04});
        var sampler = new CensusHouseholdSampler(pool,target);
        assertThat(sampler.usable()).isFalse();
        double[] counts = new double[3];
        for (var hh : pool.households()) for (var member : hh)
            counts[member.age()<18 ? 0 : member.age()<65 ? 1 : 2]++;
        assertThat((double[])sampler.report().get("expectedAgeShares"))
                .containsExactly(CensusStructureSource.shares(counts), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(pool.randomHousehold(new Random(0))).hasSizeGreaterThan(1);
        assertThat(sampler.draw(new Random(0),1)).isEqualTo(pool.randomHousehold(new Random(0)));
        assertThat(sampler.report().get("truncatedHouseholds")).isEqualTo(0);
        assertThat(sampler.report().get("completedBoundaryHouseholds")).isEqualTo(1);
    }

    @Test void rastattMarginsMeetDeclaredFitAndSamplingLimitsAcrossSeeds() {
        for (int seed : new int[]{42,43,44}) {
            var sampler = new CensusHouseholdSampler(PopulationSample.fromResource("/kit-population-sample.csv"),
                    margins(CensusStructureSource.shares(new double[]{7662,27133,9355}),
                            CensusStructureSource.shares(new double[]{8383,6236,2648,1894,657,369})));
            assertThat(sampler.usable()).isTrue();
            assertThat((double[])sampler.report().get("expectedAgeShares")).containsExactly(
                    CensusStructureSource.shares(new double[]{7662,27133,9355}),
                    org.assertj.core.data.Offset.offset(.001));
            Random random = new Random(seed); int remaining = 20000;
            while (remaining>0) remaining -= sampler.draw(random,remaining).size();
            assertThat((Double)sampler.report().get("fitMaxShareError")).isLessThanOrEqualTo(.02);
            assertThat((Double)sampler.report().get("sampleMaxShareError")).isLessThan(.04);
            assertThat((Double)sampler.report().get("samplePersons")).isBetween(20000.0, 20004.0);
        }
    }

    @Test void weightedPopulationReachesMobileCityAndRepeatsExactly() throws Exception {
        var scenario = new Scenario(); scenario.population.useCensusStructure = true;
        scenario.population.useKitPopulation = true; scenario.population.samplingFraction=1;
        var zones = GridZoning.generate(region(), 1000, 1000);
        for (var z : zones) z.population=0;
        Zone home = zones.getFirst(); home.population=200;
        var source = new CensusStructureSource.Resolved(Map.of(home.index,
                margins(new double[]{0,0,1},new double[]{1,0,0,0,0,0})),null,null,List.of(),null);
        for (String run : List.of("a","b")) {
            var generator = new PopulationGenerator(zones,new Random(42)); generator.useCensusStructure(source);
            Path population = dir.resolve(run).resolve("population");
            var result = generator.generate(population,scenario);
            assertThat(result.persons()).hasSizeBetween(200,204).allMatch(p -> p.age()>=65);
            Files.writeString(population.resolve("activity.csv"), "personOid;activityType;activityNr\n");
            var exported = new MobileCityPopulation(zones,population,1,2026);
            assertThat(exported.ages[home.index]).containsExactly(0,0,result.persons().size());
            new MobileCityExporter(zones,scenario,null).export(dir.resolve(run));
            assertThat(Files.readString(dir.resolve(run).resolve("mobilecity/POPinhabitants.csv"))).contains("Seniors");
        }
        for (String file : List.of("population/person.csv","population/household.csv","population/census-structure.json","mobilecity/POPinhabitants.csv"))
            assertThat(Files.mismatch(dir.resolve("a/"+file),dir.resolve("b/"+file))).isEqualTo(-1);
    }

    @Test void unavailableDataPreservesExistingPopulationBytes() throws Exception {
        var zones = GridZoning.generate(region(),1000,500);
        zones.getFirst().population=500;
        var scenario = new Scenario(); scenario.population.useKitPopulation=true;
        new PopulationGenerator(zones,new Random(42)).generate(dir.resolve("baseline"),scenario);
        scenario.population.useCensusStructure=true;
        var generator = new PopulationGenerator(zones,new Random(42));
        generator.useCensusStructure(CensusStructureSource.Resolved.unavailable("offline"));
        generator.generate(dir.resolve("fallback"),scenario);
        for (String file : List.of("person.csv","household.csv","car.csv"))
            assertThat(Files.mismatch(dir.resolve("baseline/"+file),dir.resolve("fallback/"+file))).isEqualTo(-1);
        assertThat(generator.censusStructureReport().get("status")).isEqualTo("fallback");
        assertThat(generator.censusStructureReport().get("unavailableReason")).isEqualTo("offline");
    }

    private CensusStructureSource.Dataset archive(String id, CensusStructureSource.Dataset schema, Zone z, String counts) throws Exception {
        Path zip = dir.resolve(id+".zip");
        double[] xy = Epsg3035.fromLatLon(z.centroidLat,z.centroidLon);
        String csv = "x_mp_100m;y_mp_100m;"+String.join(";",schema.columns())+"\n"+xy[0]+";"+xy[1]+";"+counts;
        try (var out = new ZipOutputStream(Files.newOutputStream(zip), java.nio.charset.Charset.forName("IBM437"))) {
            out.putNextEntry(new ZipEntry("Größe-Beschreibung.txt")); out.write(0); out.closeEntry();
            out.putNextEntry(new ZipEntry("data.csv")); out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
        return new CensusStructureSource.Dataset(id,zip.toString(),CensusStructureSource.sha256(zip),"data.csv",schema.columns());
    }
    private static CensusStructureSource.Margins margins(double[] ages,double[] hh) {
        return new CensusStructureSource.Margins(ages,hh,"zone","zone");
    }
    private static Scenario.Region region() {
        var r = new Scenario.Region(); r.minLat=48.84; r.maxLat=48.88; r.minLon=8.17; r.maxLon=8.23; return r;
    }
}
