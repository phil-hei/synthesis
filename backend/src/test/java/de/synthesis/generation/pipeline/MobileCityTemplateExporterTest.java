package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

class MobileCityTemplateExporterTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path dir;

    @Test void expandsSamplePreservesHistoryAndOnlyUpdatesCompleteDistricts() throws Exception {
        Path template=template(); Scenario s=scenario();
        Zone z=zone(); z.population=1500;
        Path artifacts=Files.createDirectory(dir.resolve("artifacts")); population(artifacts);
        new MobileCityExporter(List.of(z),s,template.getParent()).export(artifacts);
        Path out=artifacts.resolve("mobilecity");
        assertThat(MobileCityCsv.read(out.resolve("StudyAreaConfig.csv")).table("studyAreaParametersList")
                .row("referenceYear").get(1)).isEqualTo("2025");
        var pop=MobileCityCsv.read(out.resolve("POPinhabitants.csv")).table("population[AgeGroups.Adults]");
        assertThat(pop.atYear("A",2026)).isCloseTo(1000,within(1d));
        assertThat(pop.atYear("B",2026)).isEqualTo(200);
        assertThat(pop.atYear("A",2025)).isEqualTo(100);
        assertThat(pop.atYear("A",2030)).isEqualTo(100);
        var fleet=MobileCityCsv.read(out.resolve("TECCarFleet.csv"));
        assertThat(fleet.table("stock.total").atYear("A",2026)).isCloseTo(1000,within(1d));
        assertThat(fleet.table("stock.total").atYear("B",2026)).isEqualTo(80);
        assertThat(fleet.table("registrations.total").atYear("A",2025)).isEqualTo(7);
        assertThat(fleet.table("stock.shareElectric").atYear("A",2026)).isEqualTo(.2);
        assertThat(fleet.table("stock.shareElectric").row("B").get(fleet.table("stock.shareElectric").header.indexOf("2026"))).isEmpty();
        assertThat(Files.readString(out.resolve("REGattractivity.csv"))).isEqualTo(Files.readString(template.resolve("REGattractivity.csv")));
        double expected=(1000*2d/7+200*4)/1200;
        assertThat(MobileCityCsv.read(out.resolve("POPtrips.csv")).table("averageDailyTrips[AgeGroups.Adults]").atYear("Work",2026))
                .isCloseTo(expected,within(.002));
        var provenance=JSON.readTree(out.resolve("provenance.json").toFile());
        assertThat(provenance.path("updatedDistricts").toString()).isEqualTo("[\"A\"]");
        assertThat(JSON.readTree(out.resolve("summary.json").toFile()).path("validationStatus").asText()).isEqualTo("pending-config-tool");
        try(var zip=new ZipFile(out.resolve("mobilecity-import.zip").toFile())) {
            assertThat(zip.getEntry("Import/INFcarLinks.csv")).isNotNull();
            assertThat(zip.getEntry("Import/provenance.json")).isNotNull();
        }
    }

    @Test void rejectsChangedTemplateBeforeWritingImportFiles() throws Exception {
        Path template=template(); Files.writeString(template.resolve("ENV.csv"),"changed");
        Path artifacts=Files.createDirectory(dir.resolve("artifacts")); population(artifacts);
        assertThatThrownBy(() -> new MobileCityExporter(List.of(zone()),scenario(),template.getParent()).export(artifacts))
                .hasMessageContaining("verändert/ungültig");
        assertThat(Files.exists(artifacts.resolve("mobilecity/POPinhabitants.csv"))).isFalse();
    }

    @Test void rejectsPartialDistrictAndMissingBlockEvenWithMatchingHash() throws Exception {
        Path template=template(); Scenario s=scenario(); s.region.maxLon=8.005;
        Path artifacts=Files.createDirectory(dir.resolve("artifacts")); population(artifacts);
        assertThatThrownBy(() -> new MobileCityExporter(List.of(zone()),s,template.getParent()).export(artifacts))
                .hasMessageContaining("Kein vollständiger");
        Files.writeString(template.resolve("TECCarFleet.csv"),"fleet,,,,,,,\n,,,,,,,\nstock.total\ndistrictID,2025\nA,40\nB,80\n"); pin(template);
        assertThatThrownBy(() -> new MobileCityExporter(List.of(zone()),scenario(),template.getParent()).export(artifacts))
                .hasMessageContaining("Pflichtblock fehlt");
    }

    @Test void expandsEmptySampleZoneExplicitlyAndRejectsWrongYear() throws Exception {
        Path artifacts=Files.createDirectory(dir.resolve("artifacts")); population(artifacts);
        Zone a=zone(); a.population=1000; Zone b=zone(); b.index=1; b.id=2; b.population=200;
        var pop=new MobileCityPopulation(List.of(a,b),artifacts.resolve("population"),.01,2026);
        assertThat(pop.ages[0]).containsExactly(0,1000,0);
        assertThat(pop.ages[1]).containsExactly(0,200,0);
        assertThat(pop.imputedZones).containsExactly(2L);
        assertThat(pop.cars).containsExactly(1000,200);
        assertThatThrownBy(() -> new MobileCityPopulation(List.of(a),artifacts.resolve("population"),.01,2025))
                .hasMessageContaining("Haushaltsjahr");
    }

    @Test void rejectsDanglingNetworkEndpointAndKeepsPreviousCompleteExport() throws Exception {
        Path template=template(); Path artifacts=Files.createDirectory(dir.resolve("artifacts")); population(artifacts);
        var exporter=new MobileCityExporter(List.of(zone()),scenario(),template.getParent());
        exporter.export(artifacts);
        String before=Files.readString(artifacts.resolve("mobilecity/summary.json"));
        Path links=template.resolve("INFcarLinks.csv");
        Files.writeString(links,Files.readString(links).replace("a,b,1,1", "a,missing,1,1")); pin(template);
        assertThatThrownBy(() -> exporter.export(artifacts)).hasMessageContaining("Netzkante");
        assertThat(Files.readString(artifacts.resolve("mobilecity/summary.json"))).isEqualTo(before);
        try(var files=Files.list(artifacts)) { assertThat(files.noneMatch(p -> p.getFileName().toString().startsWith(".mobilecity-export-"))).isTrue(); }
    }

    @Test void emitsDecimalNumbersAcceptedByEpigeneAndPreservesQuotedNames() throws Exception {
        assertThat(MobileCityCsv.number(0.00000001)).isEqualTo("0.00000001");
        MobileCityCsv csv=new MobileCityCsv();
        csv.tables.put("districts",new MobileCityCsv.Table(new ArrayList<>(List.of("id","name")),
                new ArrayList<>(List.of(new ArrayList<>(List.of("A","West, \"Mitte\""))))));
        Path file=dir.resolve("quoted.csv"); csv.write(file);
        assertThat(MobileCityCsv.read(file).table("districts").row("A").get(1)).isEqualTo("West, \"Mitte\"");
    }

    private static Zone zone() {
        Zone z=new Zone(); z.index=0; z.id=1; z.name="Probe"; z.population=1500;
        z.minLat=49; z.maxLat=49.01; z.minLon=8; z.maxLon=8.015; return z;
    }
    private static Scenario scenario() {
        Scenario s=new Scenario(); s.referenceYear=2026; s.population.samplingFraction=.01;
        s.mobileCity.useTemplate=true; s.mobileCity.templateId="fixture";
        s.region=new Scenario.Region(); s.region.minLat=49; s.region.maxLat=49.01; s.region.minLon=8; s.region.maxLon=8.015; return s;
    }
    static void population(Path artifacts) throws Exception {
        Path pop=Files.createDirectories(artifacts.resolve("population"));
        Files.writeString(pop.resolve("household.csv"),"number;householdId;year;homeZone\n1;1;2026;1\n");
        Files.writeString(pop.resolve("person.csv"),"personId;number;householdId;age\n1;1;1;30\n");
        Files.writeString(pop.resolve("car.csv"),"ownerId\n1\n");
        Files.writeString(pop.resolve("activity.csv"),"person;type\n1;7\n1;1\n1;7\n");
    }
    private Path template() throws Exception {
        Path root=Files.createDirectory(dir.resolve("fixture"));
        JsonNode contract=JSON.readTree(getClass().getResourceAsStream("/mobilecity-contract.json"));
        var entries=contract.fields();
        while(entries.hasNext()) {
            var family=entries.next(); MobileCityCsv csv=new MobileCityCsv();
            for(JsonNode block:family.getValue()) {
                String name=block.asText(); List<String> head=List.of("key","value"); List<List<String>> rows=List.of(List.of("placeholder","1"));
                if(name.equals("studyAreaParametersList")) rows=List.of(List.of("referenceYear","2025"),List.of("earliestDataYear","2021"),List.of("endYear","2050"),List.of("studyAreaDistrictCount","2"),List.of("surroundingDistrictCount","0"));
                else if(name.equals("districts")) { head=List.of("districtID","name","districtType"); rows=List.of(List.of("A","Alpha","2"),List.of("B","Beta","1")); }
                else if(name.startsWith("population[")) { head=List.of("districtID","2025","2030"); rows=List.of(List.of("A","100","100"),List.of("B","200","200")); }
                else if(name.startsWith("averageDailyTrips[")) { head=List.of("purpose","2025","2030"); rows=Arrays.stream(MobileCityPopulation.PURPOSES).map(p -> List.of(p,"4","4")).toList(); }
                else if(name.equals("stock.total") || name.equals("registrations.total")) { head=List.of("districtID","2025","2030"); rows=List.of(List.of("A",name.equals("stock.total")?"40":"7",name.equals("stock.total")?"40":"7"),List.of("B","80","80")); }
                else if(name.equals("stock.shareElectric")) { head=List.of("districtID","2025","2030"); rows=List.of(List.of("A","0.2","0.2"),List.of("B","0.3","0.3")); }
                else if(name.endsWith("Nodes")) { head=List.of("nodeID","districtID","lon","lat","refNodeTrue"); rows=List.of(List.of("a","A","8","49","1"),List.of("b","B","8.01","49","1")); }
                else if(name.endsWith("Links")) { head=List.of("fromNodeID","toNodeID","time","distance","active","avSpeed"); rows=List.of(List.of("a","b","1","1","1","60"),List.of("b","a","1","1","1","60")); }
                csv.tables.put(name,new MobileCityCsv.Table(new ArrayList<>(head),new ArrayList<>(rows.stream().map(ArrayList::new).toList())));
            }
            csv.write(root.resolve(family.getKey()+".csv"));
        }
        List<Object> features=new ArrayList<>();
        for(int i=0;i<2;i++) { double x=8+i*.01;
            features.add(Map.of("type","Feature","properties",Map.of("districtID",i==0?"A":"B"),"geometry",Map.of("type","Polygon","coordinates",List.of(List.of(List.of(x,49),List.of(x+.01,49),List.of(x+.01,49.01),List.of(x,49.01),List.of(x,49))))));
        }
        JSON.writeValue(root.resolve("districts.geojson").toFile(),Map.of("type","FeatureCollection","features",features)); pin(root); return root;
    }
    private void pin(Path root) throws Exception {
        Map<String,String> files=new TreeMap<>();
        try(var stream=Files.list(root)) { for(Path file:stream.filter(p -> !p.getFileName().toString().equals("template-manifest.json")).toList()) files.put(file.getFileName().toString(),MobileCityTemplateExporter.sha256(file)); }
        JSON.writeValue(root.resolve("template-manifest.json").toFile(),Map.of("schemaVersion",1,"id","fixture","files",files));
    }
}
