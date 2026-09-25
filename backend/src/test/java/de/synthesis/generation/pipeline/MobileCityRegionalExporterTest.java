package de.synthesis.generation.pipeline;
import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

class MobileCityRegionalExporterTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();

    /** Repeatable geography suite; set matrixOutput to retain imports for native validation. */
    @Test void regionalMatrixExportsCompletePackagesAcrossGeographies() throws Exception {
        record Case(String name,double minLat,double maxLat,double minLon,double maxLon,int maxDistricts) {}
        List<Case> cases=List.of(
                new Case("city-berlin",52.50,52.52,13.40,13.43,4),
                new Case("rural-uckermark",53.15,53.18,13.80,13.86,4),
                new Case("border-kehl",48.55,48.57,7.78,7.81,4),
                new Case("clipped-rastatt",48.858,48.861,8.201,8.204,1),
                new Case("outside-germany-basel",47.55,47.57,7.58,7.61,4));
        String retained=System.getProperty("synthesis.mobilecity.matrixOutput");
        String localProfile=System.getProperty("synthesis.mobilecity.matrixProfile");
        Path profiles=localProfile==null?profile():Path.of(localProfile);
        for(Case sample:cases) {
            Scenario s=scenario();
            s.region.minLat=sample.minLat;s.region.maxLat=sample.maxLat;
            s.region.minLon=sample.minLon;s.region.maxLon=sample.maxLon;
            s.mobileCity.maxDistricts=sample.maxDistricts;
            s.mobileCity.templateId=localProfile==null?"fixture":"tramigo-v2";
            s.mobileCity.electricShare=.03; // explicit manual assumption, including outside Germany
            s.mobileCity.allowSyntheticTransitFallback=true;
            var zones=GridZoning.generate(s.region,1000,100);
            zones.forEach(z->{z.population=200;z.attractivities.put("Work",10.0);});
            Path out=(retained==null?dir:Path.of(retained)).resolve(sample.name);
            var pop=new PopulationGenerator(zones,new Random(42)).generate(out.resolve("population"),s);
            new ActivityGenerator(new Random(42)).generate(out.resolve("population/activity.csv"),pop.persons());
            new MatrixGenerator().generate(out.resolve("matrix"),zones);
            new MobileCityExporter(zones,s,profiles).export(out);
            Path mobile=out.resolve("mobilecity");
            var summary=json.readTree(mobile.resolve("summary.json").toFile());
            assertThat(summary.path("mode").asText()).as(sample.name).isEqualTo("regional");
            assertThat(summary.path("inhabitants").path("total").asInt()).as(sample.name).isEqualTo(pop.persons().size());
            assertThat(summary.path("privateCars").asInt()).as(sample.name).isEqualTo(pop.carCount());
            assertThat(summary.path("districts").path("studyArea").asInt()).as(sample.name)
                    .isBetween(1,sample.maxDistricts);
            assertThat(json.readTree(mobile.resolve("data-inventory.json").toFile()).path("ready").asBoolean())
                    .as(sample.name).isTrue();
            for(String extension:List.of("shp","shx","dbf","prj"))
                assertThat(mobile.resolve("districts."+extension)).as(sample.name).exists();
            try(var zip=new ZipFile(mobile.resolve("mobilecity-import.zip").toFile())) {
                var contract=json.readTree(getClass().getResourceAsStream("/mobilecity-contract.json"));
                var families=contract.fieldNames();
                while(families.hasNext()) {
                    String family=families.next();
                    assertThat(zip.getEntry("Import/"+family+".csv")).as(sample.name+" "+family).isNotNull();
                }
            }
            var provenance=json.readTree(mobile.resolve("provenance.json").toFile());
            List<Long> mapped=new ArrayList<>();
            for(var district:provenance.path("districts"))
                for(var id:district.path("sourceZoneIds"))mapped.add(id.asLong());
            assertThat(mapped).as(sample.name).containsExactlyInAnyOrderElementsOf(zones.stream().map(z->z.id).toList());
            byte[] first=Files.readAllBytes(mobile.resolve("mobilecity-import.zip"));
            new MobileCityExporter(zones,s,profiles).export(out);
            assertThat(Files.readAllBytes(mobile.resolve("mobilecity-import.zip"))).as(sample.name).isEqualTo(first);
        }
    }
    @Test void fullRegionalPackagePreservesStocksAreaMembershipAndReproducibility() throws Exception {
        Scenario s=scenario();var zones=GridZoning.generate(s.region,1000,100);
        zones.forEach(z->{z.population=z.index==0?0:200;z.attractivities.put("Education_Primary",z.index==1?100.0:0.0);});
        Path profiles=profile(),out=dir.resolve("run");
        var pop=new PopulationGenerator(zones,new Random(42)).generate(out.resolve("population"),s);
        new ActivityGenerator(new Random(42)).generate(out.resolve("population/activity.csv"),pop.persons());
        new MatrixGenerator().generate(out.resolve("matrix"),zones);
        var exporter=new MobileCityExporter(zones,s,profiles);exporter.export(out);
        var summary=json.readTree(out.resolve("mobilecity/summary.json").toFile());
        assertThat(summary.path("mode").asText()).isEqualTo("regional");
        assertThat(summary.path("districts").path("total").asInt()).isEqualTo(2);
        assertThat(summary.path("inhabitants").path("total").asInt()).isEqualTo(pop.persons().size());
        assertThat(summary.path("privateCars").asInt()).isEqualTo(pop.carCount());
        var provenance=json.readTree(out.resolve("mobilecity/provenance.json").toFile());
        var inventory=json.readTree(out.resolve("mobilecity/data-inventory.json").toFile());
        assertThat(inventory.path("ready").asBoolean()).isTrue();
        assertThat(inventory.path("inputSnapshot").path("referenceYear").asInt()).isEqualTo(2026);
        assertThat(inventory.path("items").findValue("resolution")).isNotNull();
        assertThat(inventory.path("items").toString()).contains("private-bev","manual","logistics","missing");
        assertThat(inventory.path("sourceSnapshots")).isEmpty();
        assertThat(summary.path("dataInventory")).isEqualTo(inventory);
        assertThat(provenance.path("financeReference").path("population").asDouble()).isEqualTo(3000);
        assertThat(provenance.path("financeReference").path("year").asInt()).isEqualTo(2018);
        assertThat(provenance.path("assumptions").path("syntheticTransitVehiclesPer1000").asDouble()).isEqualTo(1);
        var districtTable=MobileCityCsv.read(out.resolve("mobilecity/REGdistricts.csv")).table("districts");
        double actualArea=districtTable.rows.stream().mapToDouble(r->Double.parseDouble(r.get(3))).sum();
        double expectedArea=zones.stream().mapToDouble(z->MobileCityGeometry.project(MobileCityGeometry.zone(z)).getArea()/1_000_000).sum();
        assertThat(actualArea).isEqualTo(expectedArea,within(1e-6));
        try(var files=Files.list(out.resolve("mobilecity/GovernanceConfig"))){assertThat(files.map(f->f.getFileName().toString()).toList()).containsExactly("MeasureSchedule.csv");}
        assertThat(MobileCityCsv.read(out.resolve("mobilecity/GovernanceConfig/MeasureSchedule.csv")).table("governanceSchedule").rows).isEmpty();
        List<Long> mapped=new ArrayList<>();for(var d:provenance.path("districts"))for(var id:d.path("sourceZoneIds"))mapped.add(id.asLong());
        assertThat(mapped).containsExactlyInAnyOrderElementsOf(zones.stream().map(z->z.id).toList());
        var attraction=MobileCityCsv.read(out.resolve("mobilecity/REGattractivity.csv")).table("attractivity");
        assertThat(attraction.rows.stream().map(r->Double.parseDouble(r.get(2))).toList()).containsExactlyInAnyOrder(0.0,100.0);
        var fleet=MobileCityCsv.read(out.resolve("mobilecity/TECCarFleet.csv")).table("stock.shareElectric");
        for(var r:fleet.rows)assertThat(fleet.atYear(r.getFirst(),2026)).isEqualTo(.03,within(1e-12));
        var sharingFile=MobileCityCsv.read(out.resolve("mobilecity/TECCarsharing.csv"));
        var sharingMembers=sharingFile.table("carsharing.member");
        assertThat(sharingMembers.atYear("default",2026)).isEqualTo(Math.round(pop.persons().size()*.1));
        var sharingFleet=sharingFile.table("carsharing.stock.total");
        double sharedCars=0;
        for(var r:sharingFleet.rows) {
            sharedCars+=sharingFleet.atYear(r.getFirst(),2026);
            assertThat(sharingFleet.atYear(r.getFirst(),2030))
                    .isEqualTo(sharingFleet.atYear(r.getFirst(),2026));
        }
        assertThat(sharedCars).isGreaterThan(0);
        assertThat(provenance.path("carsharingAssumption").path("vehiclesPer1000").asDouble()).isEqualTo(5);
        assertThat(inventory.path("items").toString()).contains("Carsharing-Angebot","Nutzervorgabe");
        for(String mode:List.of("car","bike","transit")) {
            var links=MobileCityCsv.read(out.resolve("mobilecity/INF"+mode+"Links.csv")).table(mode+"Links");
            assertThat(links.rows).hasSize(1);
            if(mode.equals("car")) {
                var link=links.rows.getFirst();
                assertThat(Integer.parseInt(link.get(links.header.indexOf("maxAllowedSpeed")))).isGreaterThanOrEqualTo((int)Math.ceil(Double.parseDouble(link.get(links.header.indexOf("avSpeed")))));
            } // native graph adds reverse edge itself
            var nodes=MobileCityCsv.read(out.resolve("mobilecity/INF"+mode+"Nodes.csv")).table(mode+"Nodes");
            for(var r:nodes.rows)assertThat(Double.parseDouble(r.get(nodes.header.indexOf("lat")))).isBetween(52.5,52.52);
        }
        try(var zip=new ZipFile(out.resolve("mobilecity/mobilecity-import.zip").toFile())) {
            assertThat(zip.getEntry("Import/fleet-provenance.json")).isNotNull();
            assertThat(zip.getEntry("Import/data-inventory.json")).isNotNull();
            var contract=json.readTree(getClass().getResourceAsStream("/mobilecity-contract.json"));
            var names=contract.fieldNames();while(names.hasNext()){String f=names.next();assertThat(zip.getEntry("Import/"+f+".csv")).isNotNull();}
        }
        byte[] before=Files.readAllBytes(out.resolve("mobilecity/mobilecity-import.zip"));exporter.export(out);
        assertThat(Files.readAllBytes(out.resolve("mobilecity/mobilecity-import.zip"))).isEqualTo(before);
        s.mobileCity.electricShare=null;
        assertThatThrownBy(()->exporter.export(out)).hasMessageContaining("BEV-Annahme");
        assertThat(Files.readAllBytes(out.resolve("mobilecity/mobilecity-import.zip"))).isEqualTo(before);
        s.mobileCity.electricShare=.03;s.mobileCity.allowSyntheticTransitFallback=false;
        assertThatThrownBy(()->exporter.export(out)).hasMessageContaining("GTFS").hasMessageContaining("ÖV-Fallback");
        assertThat(Files.readAllBytes(out.resolve("mobilecity/mobilecity-import.zip"))).isEqualTo(before);

        s.mobileCity.allowSyntheticTransitFallback=true;
        Path invalidFeed=dir.resolve("invalid-auto-feed.zip");Files.writeString(invalidFeed,"not a zip");
        var autoFeed=new MobileCityGtfsAdapter.Feed(invalidFeed,"invalid-auto","fetch",null);
        new MobileCityExporter(zones,s,profiles,Map.of("transitSource","gtfs-fetch"),autoFeed).export(out);
        var fallbackProvenance=json.readTree(out.resolve("mobilecity/provenance.json").toFile());
        assertThat(fallbackProvenance.path("transitFallbackReason").asText()).isNotBlank();
        assertThat(json.readTree(out.resolve("mobilecity/data-inventory.json").toFile()).path("items").toString())
                .contains("Modellierter Fallback");

        var uploadFeed=new MobileCityGtfsAdapter.Feed(invalidFeed,"invalid-upload","upload",null);
        assertThatThrownBy(()->new MobileCityExporter(zones,s,profiles,Map.of(),uploadFeed).export(out))
                .hasMessageContaining("GTFS-Feed kann nicht");
    }
    @Test void rejectsNativeZeroDivisorsBeforePublishing() throws Exception {
        Scenario s=scenario();var zones=GridZoning.generate(s.region,1000,100);
        zones.forEach(z->z.population=10);
        Path profiles=profile(),out=dir.resolve("invalid"),pop=Files.createDirectories(out.resolve("population"));
        long home=zones.getFirst().id;
        Files.writeString(pop.resolve("household.csv"),"number;id;year;zone\n1;1;2026;"+home+"\n");
        Files.writeString(pop.resolve("person.csv"),"id;number;household;age\n1;1;1;70\n2;2;1;70\n");
        Files.writeString(pop.resolve("car.csv"),"ownerId\n1\n");
        Files.writeString(pop.resolve("activity.csv"),"person;type;number\n1;7;1\n1;4;2\n");
        assertThatThrownBy(()->new MobileCityExporter(zones,s,profiles).export(out)).hasMessageContaining("Erwachsene");
        Files.writeString(pop.resolve("person.csv"),"id;number;household;age\n1;1;1;35\n2;2;1;35\n");
        Files.writeString(pop.resolve("activity.csv"),"person;type;number\n");
        assertThatThrownBy(()->new MobileCityExporter(zones,s,profiles).export(out)).hasMessageContaining("Wegehäufigkeiten");
        Files.writeString(pop.resolve("car.csv"),"ownerId\n1\n1\n");
        assertThatThrownBy(()->new MobileCityExporter(zones,s,profiles).export(out)).hasMessageContaining("dritten Jahr");
        assertThat(out.resolve("mobilecity")).doesNotExist();
    }
    @Test void replacesSyntheticTransitWithGtfsAndSnapshotsItsSource() throws Exception {
        Scenario s=scenario();s.mobileCity.useRegionalCharging=true;var zones=GridZoning.generate(s.region,1000,100);
        zones.forEach(z->z.population=200);
        Path profiles=profile(),out=dir.resolve("gtfs-run");
        var pop=new PopulationGenerator(zones,new Random(42)).generate(out.resolve("population"),s);
        new ActivityGenerator(new Random(42)).generate(out.resolve("population/activity.csv"),pop.persons());
        new MatrixGenerator().generate(out.resolve("matrix"),zones);
        Path feed=MobileCityGtfsAdapterTest.fixture(dir.resolve("regional-feed.zip"));
        var source=new MobileCityGtfsAdapter.Feed(feed,"fixture","upload",new GtfsCropper.Result(4,3,4,2));

        new MobileCityExporter(zones,s,profiles,Map.of("transitSource","gtfs-upload"),source).export(out);

        Path mobile=out.resolve("mobilecity");
        assertThat(mobile.resolve("gtfs-feed.zip")).exists();
        assertThat(mobile.resolve("transit-provenance.json")).exists();
        assertThat(mobile.resolve("charging-points.csv")).exists();
        assertThat(mobile.resolve("charging-provenance.json")).exists();
        assertThat(json.readTree(mobile.resolve("charging-provenance.json").toFile())
                .path("effectiveChargingPoints").asInt()).isGreaterThan(0);
        var transit=json.readTree(mobile.resolve("transit-provenance.json").toFile());
        assertThat(transit.path("scheduledTrips").asInt()).isEqualTo(6);
        assertThat(transit.path("peakVehicles").path("trainAndTram").asInt()).isEqualTo(1);
        assertThat(json.readTree(mobile.resolve("provenance.json").toFile()).path("assumptions")
                .has("syntheticTransitVehiclesPer1000")).isFalse();
        var inventory=json.readTree(mobile.resolve("data-inventory.json").toFile());
        var transitItem=inventory.path("items").findValuesAsText("id").indexOf("transit");
        assertThat(inventory.path("items").get(transitItem).path("status").asText()).isEqualTo("observed");
        assertThat(inventory.path("sourceSnapshots").toString()).contains("gtfs-transit","gtfs-feed.zip");
        assertThat(inventory.path("sourceSnapshots").toString()).contains("bnetza-public-charging","charging-points.csv");
        assertThat(inventory.path("items").toString()).contains("Öffentliche Ladepunkte","BNetzA Ladesäulenregister");
        var nodes=MobileCityCsv.read(mobile.resolve("INFtransitNodes.csv")).table("transitNodes");
        assertThat(nodes.rows.stream().filter(r->"1".equals(r.get(nodes.header.indexOf("refNodeTrue"))))).hasSize(2);
        var fleet=MobileCityCsv.read(mobile.resolve("TECTransit.csv")).table("TECTransitYearlyParameters");
        assertThat(fleet.atYear("train.stock.total",2026)).isEqualTo(1);
        try(var zip=new ZipFile(mobile.resolve("mobilecity-import.zip").toFile())) {
            assertThat(zip.getEntry("Import/gtfs-feed.zip")).isNotNull();
            assertThat(zip.getEntry("Import/transit-provenance.json")).isNotNull();
            assertThat(zip.getEntry("Import/charging-points.csv")).isNotNull();
            assertThat(zip.getEntry("Import/charging-provenance.json")).isNotNull();
        }
    }
    @Test void exportsConfiguredSurroundingPopulationAndInboundPurposeShares() throws Exception {
        Scenario s=scenario();
        s.mobileCity.surroundingPopulation=803;
        s.mobileCity.surroundingEducationTripShare=.12;
        s.mobileCity.surroundingWorkTripShare=.42;
        s.mobileCity.surroundingShoppingTripShare=.18;
        s.mobileCity.surroundingLeisureTripShare=.09;
        var internal=GridZoning.generate(s.region,1000,100);
        internal.forEach(z->{z.population=200;z.attractivities.put("Work",10.0);});
        Path out=dir.resolve("surrounding-run");
        var pop=new PopulationGenerator(internal,new Random(42)).generate(out.resolve("population"),s);
        new ActivityGenerator(new Random(42)).generate(out.resolve("population/activity.csv"),pop.persons());
        List<Zone> zones=new ArrayList<>(internal);zones.addAll(ExternalZones.create(internal));
        new MatrixGenerator().generate(out.resolve("matrix"),zones);

        new MobileCityExporter(zones,s,profile()).export(out);

        Path mobile=out.resolve("mobilecity");
        var summary=json.readTree(mobile.resolve("summary.json").toFile());
        assertThat(summary.path("districts").path("studyArea").asInt()).isEqualTo(2);
        assertThat(summary.path("districts").path("surrounding").asInt()).isEqualTo(8);
        assertThat(summary.path("surroundingInhabitants").asInt()).isEqualTo(803);
        var parameters=MobileCityCsv.read(mobile.resolve("StudyAreaConfig.csv"))
                .table("studyAreaParametersList");
        assertThat(parameters.row("studyAreaDistrictCount").get(1)).isEqualTo("2");
        assertThat(parameters.row("surroundingDistrictCount").get(1)).isEqualTo("8");
        var districtTable=MobileCityCsv.read(mobile.resolve("REGdistricts.csv")).table("districts");
        assertThat(districtTable.rows.stream().filter(r->"0".equals(r.get(2)))).hasSize(8);
        var inhabitants=MobileCityCsv.read(mobile.resolve("POPinhabitants.csv"));
        long surroundingPeople=0;
        for(var table:inhabitants.tables.values())for(var row:table.rows)
            if(row.getFirst().startsWith("OUT-"))surroundingPeople+=Math.round(table.atYear(row.getFirst(),2026));
        assertThat(surroundingPeople).isEqualTo(803);
        var inbound=MobileCityCsv.read(mobile.resolve("POPtripsSurrounding.csv"))
                .table("tripShareToStudyArea");
        assertThat(inbound.header.subList(1,inbound.header.size())).allMatch(id->id.startsWith("OUT-"));
        assertThat(inbound.header).hasSize(9);
        assertThat(inbound.rows.stream().filter(r->"Work".equals(r.getFirst())).findFirst().orElseThrow()
                .subList(1,9)).allMatch(v->Double.parseDouble(v)==.42);
        var provenance=json.readTree(mobile.resolve("provenance.json").toFile());
        assertThat(provenance.path("assumptions").path("surrounding").path("population").asInt()).isEqualTo(803);
        assertThat(provenance.path("surroundingPolicy").asText()).contains("User-defined");
        assertThat(json.readTree(mobile.resolve("data-inventory.json").toFile()).path("items").toString())
                .contains("Umlandbevölkerung","Nutzervorgabe","803");
    }
    @Test void exportsAutomaticCensusBufferWithExactSectorPopulationAndAges() throws Exception {
        Scenario s=scenario();
        s.mobileCity.autoSurrounding=true;s.mobileCity.surroundingBufferKm=10;
        s.mobileCity.surroundingEducationTripShare=.12;
        s.mobileCity.surroundingWorkTripShare=.42;
        s.mobileCity.surroundingShoppingTripShare=.18;
        s.mobileCity.surroundingLeisureTripShare=.09;
        var internal=GridZoning.generate(s.region,1000,100);
        internal.forEach(z->{z.population=200;z.attractivities.put("Work",10.0);});
        List<Zone> zones=new ArrayList<>(internal);zones.addAll(ExternalZones.create(internal,10d));
        List<org.locationtech.jts.geom.Geometry> geometries=
                MobileCitySurroundingSource.sectorGeometries(s.region,10);
        List<MobileCitySurroundingSource.Sector> sectors=new ArrayList<>();long total=0;
        for(int i=0;i<8;i++){
            long people=100+i;total+=people;
            sectors.add(new MobileCitySurroundingSource.Sector(i,
                    MobileCitySurroundingSource.DIRECTIONS.get(i),geometries.get(i),people,
                    new double[]{20+i,60+i,20+i}));
        }
        var automatic=new MobileCitySurroundingSource.Result(sectors,10,total,true,true,null,
                Map.of("mode","census-buffer","populationArchiveSha256","fixture-population",
                        "ageArchiveSha256","fixture-ages"));
        Path out=dir.resolve("automatic-surrounding-run");
        var pop=new PopulationGenerator(internal,new Random(42)).generate(out.resolve("population"),s);
        new ActivityGenerator(new Random(42)).generate(out.resolve("population/activity.csv"),pop.persons());
        new MatrixGenerator().generate(out.resolve("matrix"),zones);

        new MobileCityExporter(zones,s,profile(),Map.of(),null,automatic).export(out);

        Path mobile=out.resolve("mobilecity");
        assertThat(json.readTree(mobile.resolve("summary.json").toFile())
                .path("surroundingInhabitants").asLong()).isEqualTo(total);
        var inhabitants=MobileCityCsv.read(mobile.resolve("POPinhabitants.csv"));
        long exported=0;for(var table:inhabitants.tables.values())for(var row:table.rows)
            if(row.getFirst().startsWith("OUT-"))exported+=Math.round(table.atYear(row.getFirst(),2026));
        assertThat(exported).isEqualTo(total);
        var districts=MobileCityCsv.read(mobile.resolve("REGdistricts.csv")).table("districts");
        assertThat(districts.rows.stream().filter(r->r.getFirst().startsWith("OUT-"))
                .mapToDouble(r->Double.parseDouble(r.get(3))).sum()).isGreaterThan(300);
        var provenance=json.readTree(mobile.resolve("provenance.json").toFile());
        assertThat(provenance.path("surroundingPolicy").asText()).contains("Census 2022");
        assertThat(provenance.path("surroundingAgesFromCensus").asBoolean()).isTrue();
        assertThat(mobile.resolve("surrounding-provenance.json")).exists();
        var inventory=json.readTree(mobile.resolve("data-inventory.json").toFile());
        assertThat(inventory.path("items").toString()).contains(
                "Destatis Zensus 2022, metrischer Pufferring","automatic");
        assertThat(inventory.path("sourceSnapshots").toString()).contains(
                "census-surrounding","surrounding-provenance.json");
    }
    @Test void matrixIdsUnitsAndInvalidNumbersAreChecked() throws Exception {
        var s=scenario();var zones=GridZoning.generate(s.region,1000,100);int n=zones.size();float[][] values=new float[n][n];for(var r:values)Arrays.fill(r,1250.5f);
        Path matrix=dir.resolve("test.mtx.bz2");VisumMatrixWriter.write(matrix,zones,values);
        assertThat(MobileCityRegionalExporter.readMatrix(matrix,zones)[0][0]).isEqualTo(1250.5);
        long id=zones.getFirst().id;zones.getFirst().id=99;
        assertThatThrownBy(()->MobileCityRegionalExporter.readMatrix(matrix,zones)).hasMessageContaining("Zonenreihenfolge");zones.getFirst().id=id;
        values[0][0]=Float.NaN;VisumMatrixWriter.write(matrix,zones,values);
        assertThatThrownBy(()->MobileCityRegionalExporter.readMatrix(matrix,zones)).hasMessageContaining("Reiseimpedanz");
    }
    private Scenario scenario(){var s=new Scenario();s.region=new Scenario.Region();s.region.minLat=52.5;s.region.maxLat=52.52;s.region.minLon=13.4;s.region.maxLon=13.43;s.population.samplingFraction=1;s.mobileCity.regionalExport=true;s.mobileCity.templateId="fixture";s.mobileCity.electricShare=.03;s.mobileCity.carsharingVehiclesPer1000=5d;s.mobileCity.carsharingMemberShare=.1;s.mobileCity.maxDistricts=2;return s;}
    private Path profile() throws Exception {
        Path root=Files.createDirectories(dir.resolve("profiles/fixture"));var contract=json.readTree(getClass().getResourceAsStream("/mobilecity-contract.json"));var families=contract.fields();
        while(families.hasNext()) {
            var f=families.next();var file=new MobileCityCsv();
            for(var name:f.getValue()) {
                var key=name.asText();List<String> h=List.of("parameterName","Value");List<List<String>> rows=List.of(List.of("placeholder","1"));
                if(key.equals("studyAreaParametersList")) rows=List.of(List.of("referenceYear","2018"),List.of("earliestDataYear","2016"),List.of("endYear","2050"),List.of("studyAreaDistrictCount","1"),List.of("surroundingDistrictCount","0"),List.of("centerLatitude","48"),List.of("centerLongitude","8"));
                else if(key.startsWith("population[")){h=List.of("districtID","2018","2026");rows=List.of(List.of("FOREIGN","1000","0"),List.of("OUTER","9000","9000"));}
                else if(key.equals("districts")){h=List.of("districtID","districtType");rows=List.of(List.of("FOREIGN","1"),List.of("OUTER","0"));}
                else if(key.equals("TECTransitYearlyParameters")){h=List.of("parameterName","2026");rows=List.of(List.of("bus.stock.total","5"),List.of("train.stock.total","0"));}
                file.tables.put(key,new MobileCityCsv.Table(new ArrayList<>(h),new ArrayList<>(rows.stream().map(ArrayList::new).toList())));
            }
            file.write(root.resolve(f.getKey()+".csv"));
        }
        Files.createDirectories(root.resolve("GovernanceConfig"));Files.writeString(root.resolve("GovernanceConfig/MeasureSchedule-test.csv"),"Schedule,,,,,,,\n,,,,,,,\ngovernanceSchedule\ngovernanceID,StartDateYear,StartDateMonth,Level\nold-measure,2027,1,2\n");
        Map<String,String> hashes=new TreeMap<>();try(var walk=Files.walk(root)){for(var f:walk.filter(Files::isRegularFile).toList())hashes.put(root.relativize(f).toString(),CensusStructureSource.sha256(f));}
        json.writeValue(root.resolve("template-manifest.json").toFile(),Map.of("schemaVersion",1,"id","fixture","files",hashes));return root.getParent();
    }
}
