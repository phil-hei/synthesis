package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in end-to-end case: live BKG boundaries across three municipalities. */
class MobileCityCrossMunicipalitySourcesTest {
    private final ObjectMapper json = new ObjectMapper();

    private static Path input(String name) {
        String value = System.getProperty("synthesis.mobilecity.rastatt." + name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing property synthesis.mobilecity.rastatt." + name);
        return Path.of(value).toAbsolutePath();
    }

    @Test void exportsObservedCrossMunicipalitySourcesWithPerZoneEvidence() throws Exception {
        assumeTrue(System.getProperty("synthesis.mobilecity.rastatt.output") != null,
                "Opt-in: use scripts/check-mobilecity-rastatt-sources.py --case cross-municipality");
        Path work = input("output"), profile = input("profile"), feed = input("gtfs");
        Path populationCache = input("populationCache"), structureCache = input("structureCache");
        Files.createDirectories(work);
        Scenario s = new Scenario(); s.region = new Scenario.Region();
        s.region.minLat=48.864;s.region.maxLat=48.8998;s.region.minLon=8.19;s.region.maxLon=8.244;
        s.referenceYear=2026;s.population.samplingFraction=1;s.population.useCensusStructure=true;
        s.mobileCity.regionalExport=true;s.mobileCity.templateId="tramigo-v2";
        s.mobileCity.maxDistricts=4;s.mobileCity.useRegionalFleet=true;
        s.mobileCity.useRegionalCharging=true;s.mobileCity.allowSyntheticTransitFallback=false;
        List<Zone> zones = GridZoning.generate(s.region,2000,4);
        assertThat(zones).hasSize(4);
        List<String> log = new ArrayList<>();

        var population = new CensusPopulationSource(new SynthesisProperties().getCensus())
                .resolve(s.region,populationCache,log::add).orElseThrow(() ->
                        new IOException("Zensus-Bevölkerungsraster fehlt: " + log));
        long observed = 0;
        for (var point : population.grid().points()) {
            int index = GridZoning.zoneIndexOf(zones,point.lat(),point.lon());
            if (index >= 0) {
                zones.get(index).population += point.population();
                observed += Math.round(point.population());
            }
        }
        assertThat(observed).isPositive();
        var structure = new CensusStructureSource(new SynthesisProperties().getCensus())
                .resolve(s.region,zones,structureCache,log::add);
        assertThat(structure.unavailableReason()).as("Zensus-Struktur: " + log).isNull();
        assertThat(structure.ages().cells()).isPositive();
        assertThat(structure.households().cells()).isPositive();
        assertThat(structure.zones()).hasSize(zones.size());
        var generator = new PopulationGenerator(zones,new Random(42));
        generator.useCensusStructure(structure);
        var generated = generator.generate(work.resolve("population"),s);
        assertThat(generated.persons()).hasSize((int)observed);
        new ActivityGenerator(new Random(42)).generate(work.resolve("population/activity.csv"),generated.persons());
        new MatrixGenerator().generate(work.resolve("matrix"),zones);
        JsonNode structureReport = json.readTree(work.resolve("population/census-structure.json").toFile());
        assertThat(structureReport.path("weightedZones").asInt()).isPositive();

        GtfsSource gtfsSource = new GtfsSource(new SynthesisProperties().getTransit());
        var gtfs = gtfsSource.resolveFullDay(s.region,feed,work.resolve("raw-cache"),
                work.resolve("kvv-cross-municipality-crop.zip"),log::add);
        assertThat(gtfs.crop().trips()).isPositive();
        assertThat(gtfs.crop().stopsInBbox()).isPositive();

        Path fleetCache = profile.resolve("fleet-cache");
        assertThat(fleetCache).as("Live BKG call requires an empty cache").doesNotExist();
        var fleet = new MobileCityFleetSource().resolve(s,zones,fleetCache,log::add);
        assertThat(fleet.provenance().get("sourceFailure")).as("Live BKG WFS: " + log).isNull();
        assertThat(fleet.boundarySnapshot()).isNotEmpty();
        assertThat(fleet.provenance().get("boundarySource").toString())
                .startsWith("https://sgx.geodatenzentrum.de/wfs_vg250?");
        assertThat(fleet.shares()).hasSize(zones.size());
        Set<String> municipalities = new TreeSet<>();
        for (Zone zone : zones) {
            var share = fleet.shares().get(zone.index);
            assertThat(share).as("KBA/BKG zone " + zone.id).isNotNull();
            assertThat(share.source()).isEqualTo("kba-2026-07-bkg-area-transfer");
            assertThat(share.coverage()).isGreaterThanOrEqualTo(.999);
            municipalities.addAll(share.municipalities());
        }
        assertThat(municipalities).contains("08216043","08216039","08216033","08216052");
        assertThat(fleet.shares().values()).anyMatch(share -> share.municipalities().size() > 1);

        var unavailable = new MobileCityFleetSource(uri -> {throw new IOException("BKG unavailable");})
                .resolve(s,zones,work.resolve("missing-bkg-cache"),log::add);
        assertThat(unavailable.shares()).isEmpty();
        assertThat(unavailable.provenance().get("missingZoneIds"))
                .isEqualTo(zones.stream().map(zone -> zone.id).toList());
        assertThatThrownBy(() -> new MobileCityRegionalExporter(zones,s,profile,Map.of(),null,null)
                .export(work,work.resolve("missing-fleet-export"),
                        new MobileCityPopulation(zones,work.resolve("population"),1,2026),unavailable))
                .isInstanceOf(IOException.class).hasMessageContaining("BEV-Anteil fehlt");
        // A valid but incomplete BKG feature set must not silently fill crossing zones.
        ObjectNode partialResponse = (ObjectNode) json.readTree(fleet.boundarySnapshot()).deepCopy();
        ArrayNode rastattOnly = json.createArrayNode();
        for (JsonNode feature : partialResponse.path("features"))
            if ("08216043".equals(feature.path("properties").path("ags").asText()))
                rastattOnly.add(feature);
        assertThat(rastattOnly).isNotEmpty();
        partialResponse.set("features",rastattOnly);
        partialResponse.put("numberMatched",rastattOnly.size());
        partialResponse.put("numberReturned",rastattOnly.size());
        var partial = new MobileCityFleetSource(uri -> json.writeValueAsBytes(partialResponse))
                .resolve(s,zones,work.resolve("partial-bkg-cache"),log::add);
        assertThat(partial.shares()).containsKey(zones.getFirst().index);
        assertThat(partial.shares().size()).isLessThan(zones.size());
        assertThat((List<?>) partial.provenance().get("missingZoneIds")).isNotEmpty();
        assertThatThrownBy(() -> new MobileCityRegionalExporter(zones,s,profile,Map.of(),null,null)
                .export(work,work.resolve("partial-fleet-export"),
                        new MobileCityPopulation(zones,work.resolve("population"),1,2026),partial))
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
        JsonNode provenance = json.readTree(mobile.resolve("provenance.json").toFile());
        JsonNode fleetReport = json.readTree(mobile.resolve("fleet-provenance.json").toFile());
        JsonNode transit = json.readTree(mobile.resolve("transit-provenance.json").toFile());
        JsonNode charging = json.readTree(mobile.resolve("charging-provenance.json").toFile());
        assertThat(summary.path("inhabitants").path("total").asLong()).isEqualTo(observed);
        assertThat(summary.path("districts").path("studyArea").asInt()).isEqualTo(zones.size());
        assertThat(inventory.path("ready").asBoolean()).isTrue();
        assertThat(inventory.path("items").toString()).contains(
                "Destatis Zensus 2022","KBA FZ 27.17","GTFS KVV","BNetzA Ladesäulenregister");
        assertThat(fleetReport.path("boundarySha256").asText())
                .isEqualTo(MobileCityFleetSource.hash(Files.readAllBytes(mobile.resolve("fleet-boundaries.geojson"))));
        assertThat(transit.path("scheduledTrips").asInt()).isPositive();
        assertThat(charging.path("effectiveChargingPoints").asInt()).isPositive();

        Map<Long,String> districtByZone = new HashMap<>();
        for (JsonNode district : provenance.path("districts")) {
            assertThat(district.path("sourceZoneIds")).hasSize(1);
            districtByZone.put(district.path("sourceZoneIds").get(0).asLong(),
                    district.path("districtID").asText());
        }
        assertThat(districtByZone).hasSize(zones.size());
        var chargers = MobileCityCsv.read(mobile.resolve("REGchargers.csv"))
                .table("numberOfChargingStations");
        var nodes = MobileCityCsv.read(mobile.resolve("INFtransitNodes.csv"))
                .table("transitNodes");
        int districtColumn = nodes.header.indexOf("districtID");
        int referenceColumn = nodes.header.indexOf("refNodeTrue");
        assertThat(districtColumn).isPositive();
        assertThat(referenceColumn).isPositive();
        List<Map<String,Object>> perZone = new ArrayList<>();
        for (Zone zone : zones) {
            String district = districtByZone.get(zone.id);
            long accessNodes = nodes.rows.stream().filter(row -> district.equals(row.get(districtColumn))
                    && "1".equals(row.get(referenceColumn))).count();
            assertThat(accessNodes).as("GTFS access for " + district).isEqualTo(1);
            var margin = structure.zones().get(zone.index);
            assertThat(margin).isNotNull();
            var share = fleet.shares().get(zone.index);
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("zoneId",zone.id); item.put("districtId",district);
            item.put("bounds",Map.of("minLat",zone.minLat,"maxLat",zone.maxLat,
                    "minLon",zone.minLon,"maxLon",zone.maxLon));
            item.put("zensusPopulation",Math.round(zone.population));
            item.put("censusAgeLevel",margin.ageLevel());
            item.put("censusHouseholdLevel",margin.householdLevel());
            item.put("bevShare",share.bev()); item.put("bkgCoverage",share.coverage());
            item.put("municipalities",share.municipalities());
            item.put("publicChargingPoints2026",Math.round(chargers.atYear(district,2026)));
            item.put("gtfsAccessNodes",accessNodes);
            perZone.add(item);
        }
        assertThat(perZone.stream().mapToLong(row -> (long)row.get("zensusPopulation")).sum())
                .isEqualTo(observed);
        assertThat(perZone.stream().mapToLong(row -> (long)row.get("publicChargingPoints2026")).sum())
                .isEqualTo(charging.path("effectiveChargingPoints").asLong());
        Map<String,Object> check = new LinkedHashMap<>();
        check.put("region","Rastatt and four neighboring municipalities, four zones");
        check.put("censusGridCells",population.grid().points().size());
        check.put("censusObservedPopulation",observed);
        check.put("censusAgeCells",structure.ages().cells());
        check.put("censusHouseholdCells",structure.households().cells());
        check.put("censusWeightedZones",structureReport.path("weightedZones").asInt());
        check.put("kvvCropTrips",gtfs.crop().trips());
        check.put("kvvStopsInRegion",gtfs.crop().stopsInBbox());
        check.put("exportedPopulation",summary.path("inhabitants").path("total").asLong());
        check.put("municipalities",municipalities);
        check.put("partialBkgMissingZoneIds",partial.provenance().get("missingZoneIds"));
        check.put("zones",perZone);
        json.writerWithDefaultPrettyPrinter().writeValue(work.resolve("source-check.json").toFile(),check);
    }
}
