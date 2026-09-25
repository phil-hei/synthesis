package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in offline acceptance case using pinned observations and the local KVV upload. */
class MobileCityRastattSourcesTest {
    private final ObjectMapper json = new ObjectMapper();
    private static Path input(String name) {
        String value = System.getProperty("synthesis.mobilecity.rastatt." + name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing property synthesis.mobilecity.rastatt." + name);
        return Path.of(value).toAbsolutePath();
    }

    @Test void exportsObservedRastattSourcesWithoutSilentFallback() throws Exception {
        assumeTrue(System.getProperty("synthesis.mobilecity.rastatt.output") != null,
                "Opt-in: use scripts/check-mobilecity-rastatt-sources.py");
        Path work = input("output"), profile = input("profile"), feed = input("gtfs");
        Path populationCache = input("populationCache"), structureCache = input("structureCache");
        Files.createDirectories(work);
        Scenario s = new Scenario(); s.region = new Scenario.Region();
        s.region.minLat=48.855;s.region.maxLat=48.86;s.region.minLon=8.20;s.region.maxLon=8.205;
        s.referenceYear=2026;s.population.samplingFraction=1;s.population.useCensusStructure=true;
        s.mobileCity.regionalExport=true;s.mobileCity.templateId="tramigo-v2";
        s.mobileCity.maxDistricts=1;s.mobileCity.useRegionalFleet=true;
        s.mobileCity.useRegionalCharging=true;s.mobileCity.allowSyntheticTransitFallback=false;
        List<Zone> zones = GridZoning.generate(s.region,1000,1);
        assertThat(zones).hasSize(1);

        List<String> sourceLog = new ArrayList<>();
        var population = new CensusPopulationSource(new SynthesisProperties().getCensus())
                .resolve(s.region,populationCache,sourceLog::add).orElseThrow(() ->
                        new IOException("Zensus-Bevölkerungsraster fehlt: " + sourceLog));
        long observed = 0;
        for (var point : population.grid().points()) {
            int index = GridZoning.zoneIndexOf(zones,point.lat(),point.lon());
            if (index >= 0) {zones.get(index).population += point.population();observed += Math.round(point.population());}
        }
        assertThat(observed).isPositive();
        var structure = new CensusStructureSource(new SynthesisProperties().getCensus())
                .resolve(s.region,zones,structureCache,sourceLog::add);
        assertThat(structure.unavailableReason()).as("Zensus-Struktur: " + sourceLog).isNull();
        assertThat(structure.ages().cells()).isPositive();
        assertThat(structure.households().cells()).isPositive();
        var generator = new PopulationGenerator(zones,new Random(42));
        generator.useCensusStructure(structure);
        var generated = generator.generate(work.resolve("population"),s);
        assertThat(generated.persons()).hasSize((int)observed);
        new ActivityGenerator(new Random(42)).generate(work.resolve("population/activity.csv"),generated.persons());
        new MatrixGenerator().generate(work.resolve("matrix"),zones);
        JsonNode structureReport = json.readTree(work.resolve("population/census-structure.json").toFile());
        assertThat(structureReport.path("weightedZones").asInt()).isPositive();

        GtfsSource gtfsSource = new GtfsSource(new SynthesisProperties().getTransit());
        assertThatThrownBy(() -> gtfsSource.resolveFullDay(s.region,work.resolve("absent-kvv.zip"),
                work.resolve("raw-cache"),work.resolve("absent-crop.zip"),sourceLog::add))
                .isInstanceOf(IOException.class).hasMessageContaining("GTFS-Upload fehlt");
        var gtfs = gtfsSource.resolveFullDay(s.region,feed,work.resolve("raw-cache"),
                work.resolve("kvv-rastatt-crop.zip"),sourceLog::add);
        assertThat(gtfs.crop().trips()).isPositive();
        assertThat(gtfs.crop().stopsInBbox()).isPositive();

        byte[] bkg = Objects.requireNonNull(getClass().getResourceAsStream("/mobilecity/bkg-rastatt.geojson")).readAllBytes();
        Path fleetCache = profile.resolve("fleet-cache");
        var missing = new MobileCityFleetSource(uri -> {throw new IOException("offline BKG fixture");})
                .resolve(s,zones,work.resolve("missing-bkg-cache"),sourceLog::add);
        assertThat(missing.shares()).isEmpty();
        assertThat(missing.provenance().get("sourceFailure")).isEqualTo("offline BKG fixture");
        var fleet = new MobileCityFleetSource(uri -> bkg).resolve(s,zones,fleetCache,sourceLog::add);
        assertThat(fleet.shares()).containsKey(0);
        assertThat(fleet.shares().get(0).source()).isEqualTo("kba-2026-07-bkg-area-transfer");
        assertThat(fleet.shares().get(0).coverage()).isGreaterThanOrEqualTo(.999);
        assertThatThrownBy(() -> new MobileCityRegionalExporter(zones,s,profile,Map.of(),null,null)
                .export(work,work.resolve("missing-fleet-export"),
                        new MobileCityPopulation(zones,work.resolve("population"),1,2026),missing))
                .isInstanceOf(IOException.class).hasMessageContaining("BEV-Anteil fehlt");

        Map<String,Object> sources = Map.of(
                "populationSource","census-2022",
                "populationSourceLabel","Destatis Zensus 2022",
                "populationReferenceDate",population.provenance().referenceDate(),
                "populationSourceSha256",population.provenance().archiveSha256(),
                "transitSource","gtfs-upload","transitFeedName","KVV",
                "matrixSource","SyntheSis grid centroid matrix");
        new MobileCityExporter(zones,s,profile,sources,
                new MobileCityGtfsAdapter.Feed(gtfs.croppedFeed(),"KVV","upload",gtfs.crop())).export(work);
        Path mobile = work.resolve("mobilecity");
        JsonNode summary = json.readTree(mobile.resolve("summary.json").toFile());
        JsonNode inventory = json.readTree(mobile.resolve("data-inventory.json").toFile());
        JsonNode transit = json.readTree(mobile.resolve("transit-provenance.json").toFile());
        JsonNode charging = json.readTree(mobile.resolve("charging-provenance.json").toFile());
        JsonNode fleetReport = json.readTree(mobile.resolve("fleet-provenance.json").toFile());
        assertThat(summary.path("inhabitants").path("total").asLong()).isEqualTo(observed);
        assertThat(inventory.path("ready").asBoolean()).isTrue();
        assertThat(inventory.path("items").toString()).contains("Destatis Zensus 2022","KBA FZ 27.17","GTFS KVV","BNetzA Ladesäulenregister");
        assertThat(transit.path("scheduledTrips").asInt()).isPositive();
        assertThat(charging.path("effectiveChargingPoints").asInt()).isPositive();
        assertThat(fleetReport.path("sourceFailure").isNull()).isTrue();
        for (String artifact : List.of("mobilecity-import.zip","census-structure.json","fleet-boundaries.geojson",
                "gtfs-feed.zip","charging-points.csv","charging-provenance.json"))
            assertThat(mobile.resolve(artifact)).as(artifact).isRegularFile();
        json.writerWithDefaultPrettyPrinter().writeValue(work.resolve("source-check.json").toFile(),Map.of(
                "region","Rastatt center, one bounded zone", "censusGridCells",population.grid().points().size(),
                "censusObservedPopulation",observed,"censusAgeCells",structure.ages().cells(),
                "censusHouseholdCells",structure.households().cells(),"censusWeightedZones",structureReport.path("weightedZones").asInt(),
                "bkgCoverage",fleet.shares().get(0).coverage(),"kvvCropTrips",gtfs.crop().trips(),
                "kvvStopsInRegion",gtfs.crop().stopsInBbox(),"exportedPopulation",summary.path("inhabitants").path("total").asLong()));
    }
}
