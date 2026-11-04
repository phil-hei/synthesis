package de.synthesis.generation;

import de.synthesis.data.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.data.importing.*;
import de.synthesis.generation.demand.TrafficDemandArtifacts;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DataSourcesTest {
    @TempDir Path dir;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    SynthesisProperties properties;ScenarioRepository repo;DataImportService service;Scenario scenario;
    static final String CSV="kennung;laenge;breite;arbeitsplaetze\na;8.21;48.86;12\nb;8.22;48.87;0\n";
    @BeforeEach void setup(){
        properties=new SynthesisProperties();properties.setDataDir(dir.toString());properties.getSimulation().setEnabled(false);properties.getCensus().setEnabled(false);properties.getOverpass().setLiveDataEnabled(false);properties.getSumo().setEnabled(false);
        repo=new ScenarioRepository(json,properties);service=new DataImportService(repo,json,ImportRegistry.builtins());scenario=new Scenario();scenario.id="sources";scenario.region=box(8.2,48.85,8.24,48.89);scenario.targetModels=new ArrayList<>(List.of("logitopp"));scenario.population.totalPersons=50;scenario.population.useRealRouting=false;scenario.population.useKitPopulation=false;scenario.population.externalTrips=false;scenario.population.behaviorSource="template";scenario.zoning.cellSizeMeters=1000;repo.save(scenario);
    }
    static Scenario.Region box(double west,double south,double east,double north){var r=new Scenario.Region();r.minLon=west;r.minLat=south;r.maxLon=east;r.maxLat=north;return r;}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    Metadata metadata(String unit){return new Metadata("Regionale Quelle","synthetische Testdaten; vollständige Arbeitsplatzdefinition","2026-01-01",unit,box(8.1,48.75,8.34,48.99),"unknown","Testquelle",unit.equals("jobs")?new CoverageDeclaration("point-register","complete","Vollständiges synthetisches Betriebsregister innerhalb der deklarierten Box"):null);}
    DataImportService.Draft upload(String schema,String data,String filename)throws Exception{return service.upload(scenario.id,schema,bytes(data),filename);}
    DataImportService.Request request(DataImportService.Draft d){return new DataImportService.Request(service.suggest(d),metadata(d.schemaId().equals("workplaces-v1")?"jobs":d.schemaId().equals("population-grid-v1")?"persons":"vehicle-trips/day"));}
    DataImportService.RevisionInfo publish(String schema,String data)throws Exception{var d=upload(schema,data,"input.csv");return service.publish(scenario.id,d.id(),request(d));}
    SourcePlan.Policy select(String aspect,String revision){scenario.dataPlan.enabled=true;var p=new SourcePlan.Policy();p.source="dataset";p.revision=revision;scenario.dataPlan.aspects.put(aspect,p);return p;}

    @Test void csvJsonXmlAndExcelUseSameReaderAndCanonicalDomainWithoutAutoSelection()throws Exception{
        var csv=upload("workplaces-v1",CSV,"a.csv");
        var j=upload("workplaces-v1","{\"items\":[{\"kennung\":\"a\",\"laenge\":8.21,\"breite\":48.86,\"arbeitsplaetze\":12},{\"kennung\":\"b\",\"laenge\":8.22,\"breite\":48.87,\"arbeitsplaetze\":0}]}","a.json");
        var xml=upload("workplaces-v1","<root><item kennung='a' laenge='8.21' breite='48.86' arbeitsplaetze='12'/><item kennung='b' laenge='8.22' breite='48.87' arbeitsplaetze='0'/></root>","a.xml");
        var excel=service.upload(scenario.id,"workplaces-v1",xlsx(),"a.xlsx");
        var expected=service.preview(scenario.id,csv.id(),request(csv)).sample().stream().map(DataRow::values).toList();
        for(var d:List.of(j,xml,excel)){
            var preview=service.preview(scenario.id,d.id(),request(d));assertEquals("valid",preview.status(),preview.issues().toString());
            assertEquals(expected,preview.sample().stream().map(DataRow::values).toList());
            var revision=service.publish(scenario.id,d.id(),request(d));assertEquals(2,revision.rows());
        }
        assertFalse(repo.findById(scenario.id).orElseThrow().dataPlan.enabled,"Import must not activate demand");
        assertEquals(0.0,expected.get(1).get("value"));
        assertArrayEquals(bytes(CSV),Files.readAllBytes(repo.uploadsDir(scenario.id).resolve("data-imports/raw").resolve(csv.source().sourceHash()+".bin")));
    }
    @Test void metadataUnitsMissingValuesSuppressionAndDuplicateColumnsAreNotSilentlyCoerced()throws Exception{
        var d=upload("workplaces-v1",CSV.replace(";12\n",";.\n"),"bad.csv");var req=request(d);
        var p=service.preview(scenario.id,d.id(),req);assertEquals("invalid",p.status());assertEquals(1,p.rejectedRows());
        assertThrows(IllegalArgumentException.class,()->service.publish(scenario.id,d.id(),req));
        assertThrows(IllegalArgumentException.class,()->service.preview(scenario.id,d.id(),new DataImportService.Request(req.mapping(),metadata("tonnes/month"))));
        var cols=new TreeMap<>(req.mapping().columns());cols.put("lat",cols.get("lon"));
        assertThrows(IllegalArgumentException.class,()->service.preview(scenario.id,d.id(),new DataImportService.Request(new Mapping(req.mapping().tableId(),1,cols,"."),req.metadata())));
    }
    @Test void explicitDecimalLocaleAndReusableProfilesRejectChangedSourceStructure()throws Exception{
        var d=upload("workplaces-v1",CSV.replace("8.21","8,21").replace("48.86","48,86").replace("8.22","8,22").replace("48.87","48,87"),"de.csv");var req=request(d);
        assertEquals("invalid",service.preview(scenario.id,d.id(),req).status());
        var comma=new DataImportService.Request(new Mapping(req.mapping().tableId(),1,req.mapping().columns(),","),req.metadata());
        assertEquals("valid",service.preview(scenario.id,d.id(),comma).status());
        var profile=service.saveProfile(scenario.id,d.id(),"Gemeinde",comma);
        assertEquals(comma.mapping(),service.applyProfile(scenario.id,d.id(),profile.id()));
        var changed=upload("workplaces-v1",CSV.replace("arbeitsplaetze","Beschaeftigte"),"changed.csv");
        assertThrows(IllegalArgumentException.class,()->service.applyProfile(scenario.id,changed.id(),profile.id()));
        assertFalse(json.writeValueAsString(profile).contains("synthetische Testdaten"),"Profiles contain rules, not source observations");
    }
    @Test void immutableRevisionsAreIdempotentDetectTamperingAndDoNotFollowNewUploads()throws Exception{
        var d=upload("workplaces-v1",CSV,"a.csv");var req=request(d);var r=service.publish(scenario.id,d.id(),req);
        assertEquals(r,service.publish(scenario.id,d.id(),req));select("opportunities.work",r.id());
        var newer=publish("workplaces-v1",CSV.replace(";12\n",";24\n"));assertNotEquals(r.id(),newer.id());
        var prepared=SourceResolver.prepare(scenario,repo,json);assertEquals(r.id(),prepared.datasets().get("opportunities.work").id());
        Path file=repo.uploadsDir(scenario.id).resolve("data-imports/revisions/"+r.id()+".json");Files.writeString(file,Files.readString(file).replace("\"value\":12.0","\"value\":99.0"));
        assertThrows(IOException.class,()->SourceResolver.prepare(scenario,repo,json));
        assertEquals(12.0,prepared.datasets().get("opportunities.work").data().rows().getFirst().values().get("value"));
    }
    @Test void wrongAspectFutureDatesAndInsufficientPopulationCoverageBlockBeforeQueueing()throws Exception{
        var work=publish("workplaces-v1",CSV);select("population.distribution",work.id());assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json));
        var d=upload("population-grid-v1",CSV.replace("arbeitsplaetze","population"),"p.csv");var req=new DataImportService.Request(service.suggest(d),new Metadata("p","test","2027-01-01","persons",scenario.region));
        var future=service.publish(scenario.id,d.id(),req);select("population.distribution",future.id());assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json));
        var narrow=service.publish(scenario.id,d.id(),new DataImportService.Request(req.mapping(),new Metadata("p","test","2026-01-01","persons",box(8.205,48.855,8.23,48.88))));select("population.distribution",narrow.id());assertThrows(IllegalArgumentException.class,()->SourceResolver.prepare(scenario,repo,json));
    }
    @Test void workplaceReplacementPreservesKnownZeroAndOtherPurposesWithExplicitGaps()throws Exception{
        var r=publish("workplaces-v1",CSV);var p=select("opportunities.work",r.id());var inputs=SourceResolver.prepare(scenario,repo,json);
        var zones=GridZoning.generate(scenario.region,1000,600);AttractivityCalculator.synthetic(zones,new Random(42));var leisure=zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList();
        var report=SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones);assertEquals(zones.size(),report.get("coveredZones"));
        assertEquals(12,zones.stream().mapToDouble(z->z.attractivities.get("Work")).sum());assertTrue(zones.stream().anyMatch(z->z.attractivities.get("Work")==0));
        assertEquals(leisure,zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList());
        p.gaps="none";var wider=GridZoning.generate(box(8,48.7,8.5,49.1),10000,600);AttractivityCalculator.synthetic(wider,new Random(1));assertThrows(IllegalArgumentException.class,()->SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,wider));
    }
    @Test void fullPipelineUsesPopulationAndWorkplaceRevisionsAndAssumedFreightThenRestoresLegacy()throws Exception{
        var work=publish("workplaces-v1",CSV);var pop=publish("population-grid-v1",CSV.replace("arbeitsplaetze","population").replace(";12\n",";30\n").replace(";0\n",";20\n"));
        select("opportunities.work",work.id());select("population.distribution",pop.id());scenario.population.totalPersons=null;
        var freight=new SourcePlan.Policy();freight.source="model";freight.demonstrationAccepted=true;freight.assumptionStatement="Expliziter synthetischer Demonstrationsfall";freight.internalTrips=3;freight.throughTrips=2;scenario.dataPlan.aspects.put("freight.vehicleOd",freight);repo.save(scenario);
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{
            var first=generation.start(scenario.id);await(first);assertEquals(GenerationJob.State.DONE,first.state,first.error);
            Path out=repo.artifactsDir(scenario.id);var manifest=json.readTree(out.resolve("manifest.json").toFile());assertEquals(50,manifest.path("populationEstimate").path("totalPersons").asInt());assertTrue(manifest.has("sources"));
            assertEquals(5,TrafficDemandArtifacts.read(out,json).freight().size());assertEquals(3,json.readTree(out.resolve("sources/lock.json").toFile()).path("decisions").size());
            var report=json.readTree(out.resolve("sources/resolution.json").toFile());
            assertEquals(SourceProvenance.CONTRACT,report.path("workplaces").path("provenance").path("contract").asText());
            assertEquals(2,report.path("workplaces").path("rowAssignments").size());
            assertEquals("complete",report.path("workplaces").path("coverageDeclaration").path("completeness").asText());
            var repeat=generation.start(scenario.id);await(repeat);assertEquals(GenerationJob.State.DONE,repeat.state,repeat.error);
            var bytesBefore=Files.readAllBytes(out.resolve("demand/traffic-demand.json"));
            var again=generation.start(scenario.id);await(again);assertArrayEquals(bytesBefore,Files.readAllBytes(out.resolve("demand/traffic-demand.json")));
            scenario.dataPlan.enabled=false;scenario.population.totalPersons=50;repo.save(scenario);var old=generation.start(scenario.id);await(old);assertEquals(GenerationJob.State.DONE,old.state,old.error);assertFalse(Files.exists(out.resolve("sources/lock.json")));assertFalse(json.readTree(out.resolve("manifest.json").toFile()).has("sources"));assertEquals(0,TrafficDemandArtifacts.read(out,json).freight().size());
        }finally{generation.shutdown();}
    }
    @Test void rejectedPlanDoesNotQueueAndExcludedFreightOverridesOldSelectedRelations()throws Exception{
        scenario.dataPlan.enabled=true;var p=new SourcePlan.Policy();p.source="model";scenario.dataPlan.aspects.put("freight.vehicleOd",p);repo.save(scenario);
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->generation.start(scenario.id));assertTrue(generation.status(scenario.id).isEmpty());}finally{generation.shutdown();}
        p.source="excluded";scenario.freight.enabled=true;scenario.population.samplingFraction=.1;
        assertDoesNotThrow(()->SourceResolver.prepare(scenario,repo,json));assertFalse(scenario.freight.enabled);
    }
    @Test void restImportPreviewAndRevisionUseSameCoreWithClientErrors()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new DataImportController(service,ImportRegistry.builtins(),repo,json)).build();
        mvc.perform(multipart("/api/scenarios/sources/data/imports").file(new MockMultipartFile("file","jobs.csv","text/csv",bytes(CSV))).param("schema","workplaces-v1")).andExpect(status().isOk()).andExpect(jsonPath("$.mapping.columns.value").value(3));
        var d=upload("workplaces-v1",CSV,"jobs.csv");var req=request(d);
        mvc.perform(post("/api/scenarios/sources/data/imports/"+d.id()+"/preview").contentType("application/json").content(json.writeValueAsBytes(req))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("valid"));
        mvc.perform(post("/api/scenarios/sources/data/imports/"+d.id()+"/revisions").contentType("application/json").content(json.writeValueAsBytes(req))).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2));
        mvc.perform(post("/api/scenarios/sources/data/imports/"+d.id()+"/preview").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }
    @Test void legacyCompositionPreservesAllCoreArtifactBytesWithSourceReportingEnabled()throws Exception{
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{
            var before=generation.start(scenario.id);await(before);assertEquals(GenerationJob.State.DONE,before.state,before.error);
            var baseline=coreArtifacts(repo.artifactsDir(scenario.id));
            scenario.dataPlan.enabled=true;repo.save(scenario);var after=generation.start(scenario.id);await(after);assertEquals(GenerationJob.State.DONE,after.state,after.error);
            assertEquals(baseline,coreArtifacts(repo.artifactsDir(scenario.id)),"Enabling reporting with only legacy providers must not alter demand inputs");
            assertTrue(Files.exists(repo.artifactsDir(scenario.id).resolve("sources/resolution.json")));
        }finally{generation.shutdown();}
    }
    @Test void workplaceCoverageMustBeExplicitCompleteAndPointBasedForPreviewAndPublication()throws Exception{
        var draft=upload("workplaces-v1",CSV,"jobs.csv");var base=metadata("jobs");
        var declarations=Arrays.asList(null,new CoverageDeclaration("point-register","unknown","Unbekannter Erhebungsumfang"),
                new CoverageDeclaration("point-register","partial","Nur ausgewählte Betriebe erfasst"),
                new CoverageDeclaration("municipality-aggregate","complete","Gemeindesummen mit AGS im Gebietsstand 2025"),
                new CoverageDeclaration("point-register","complete",""));
        for(var declaration:declarations){
            var meta=new Metadata(base.label(),base.source(),base.referenceDate(),base.unit(),base.coverage(),base.licence(),base.attribution(),declaration);
            var request=new DataImportService.Request(service.suggest(draft),meta);
            assertThrows(IllegalArgumentException.class,()->service.preview(scenario.id,draft.id(),request));
            assertThrows(IllegalArgumentException.class,()->service.publish(scenario.id,draft.id(),request));
        }
        assertTrue(service.revisions(scenario.id).isEmpty());
        var first=service.publish(scenario.id,draft.id(),request(draft));
        var changed=new Metadata(base.label(),base.source(),base.referenceDate(),base.unit(),base.coverage(),base.licence(),base.attribution(),new CoverageDeclaration("point-register","complete","Anderer ausdrücklich beschriebener Erhebungsumfang"));
        var next=service.publish(scenario.id,draft.id(),new DataImportService.Request(service.suggest(draft),changed));
        assertNotEquals(first.id(),next.id());
        assertEquals(base.coverageDeclaration(),DataImportService.readRevision(repo,json,scenario.id,first.id()).data().metadata().coverageDeclaration());
    }
    @Test void oldRevisionHashesRemainReadableButMissingDeclarationCannotBeSelectedOrQueued()throws Exception{
        var revision=publish("workplaces-v1",CSV);
        var canonical=json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)canonical.valueToTree(DataImportService.readRevision(repo,json,scenario.id,revision.id()).data());
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload.get("metadata")).remove("coverageDeclaration");
        String oldId=ImportSupport.hash(canonical.writeValueAsBytes(payload));var old=canonical.createObjectNode();old.put("id",oldId);old.set("data",payload);
        Path path=repo.uploadsDir(scenario.id).resolve("data-imports/revisions/"+oldId+".json");Files.write(path,canonical.writeValueAsBytes(old));byte[] retained=Files.readAllBytes(path);
        assertNull(DataImportService.readRevision(repo,json,scenario.id,oldId).data().metadata().coverageDeclaration());
        assertEquals(2,service.revisions(scenario.id).size());
        select("opportunities.work",oldId);repo.save(scenario);
        var mvc=MockMvcBuilders.standaloneSetup(new DataImportController(service,ImportRegistry.builtins(),repo,json)).build();
        mvc.perform(post("/api/scenarios/sources/data/plan/preview").contentType("application/json").content(json.writeValueAsBytes(scenario)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.issues[0]").value(org.hamcrest.Matchers.containsString("Vollständigkeitsangabe")));
        var generation=new GenerationService(repo,new OverpassClient(properties,json),properties,json);
        try{assertThrows(org.springframework.web.server.ResponseStatusException.class,()->generation.start(scenario.id));assertTrue(generation.status(scenario.id).isEmpty());}finally{generation.shutdown();}
        assertArrayEquals(retained,Files.readAllBytes(path));
    }
    @Test void partialAllocationHasDisjointProvenanceAndAccountsForEverySourceRow()throws Exception{
        var draft=upload("workplaces-v1","kennung;laenge;breite;arbeitsplaetze\na;8.21;48.86;12\nb;8.23;48.86;7\nc;8.195;48.86;3\n","partial.csv");
        var base=metadata("jobs");var meta=new Metadata(base.label(),base.source(),base.referenceDate(),base.unit(),box(8.19,48.84,8.235,48.90),base.licence(),base.attribution(),base.coverageDeclaration());
        var revision=service.publish(scenario.id,draft.id(),new DataImportService.Request(service.suggest(draft),meta));var policy=select("opportunities.work",revision.id());var inputs=SourceResolver.prepare(scenario,repo,json);
        var zones=List.of(workZone(0,100001,8.20,8.22),workZone(1,100002,8.22,8.24));
        policy.gaps="none";assertThrows(IllegalArgumentException.class,()->SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones));
        assertEquals(List.of(60.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList(),"Rejected composition must not partly overwrite zones");
        policy.gaps="legacy";var report=SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,zones);
        assertEquals(19.0,report.get("mappedJobs"));assertEquals(12.0,report.get("appliedJobs"));assertEquals(7.0,report.get("jobsInPartiallyCoveredZonesNotApplied"));assertEquals(1,report.get("outsideRows"));
        assertEquals(List.of(12.0,60.0),zones.stream().map(z->z.attractivities.get("Work")).toList());assertEquals(List.of(4.0,4.0),zones.stream().map(z->z.attractivities.get("Leisure_Indoor")).toList());
        var provenance=(SourceProvenance)report.get("provenance");var tree=json.readTree(json.writeValueAsBytes(provenance));assertEquals(SourceProvenance.CONTRACT,tree.path("contract").asText());
        var nodes=provenance.nodes();assertEquals("fill-gaps",nodes.getLast().operation());assertEquals(revision.id(),nodes.getFirst().source().revision());assertEquals("unknown",nodes.getFirst().evidence());
        assertEquals(List.of(new SourceProvenance.ZoneRef(100001,0)),nodes.get(1).scope().zones());assertEquals(List.of(new SourceProvenance.ZoneRef(100002,1)),nodes.get(2).scope().zones());assertEquals("legacy",nodes.get(2).source().kind());
        var rows=(List<SourceProvenance.RowAssignment>)report.get("rowAssignments");
        assertEquals(List.of("applied","partial-zone-not-applied","outside-model"),rows.stream().map(SourceProvenance.RowAssignment::status).toList());
        assertEquals(List.of(2,3,4),rows.stream().map(SourceProvenance.RowAssignment::sourceRow).toList());assertEquals(List.of("a","b","c"),rows.stream().map(SourceProvenance.RowAssignment::sourceId).toList());assertNull(rows.getLast().zone());
    }
    @Test void completeEmptyStudyAreaIsZeroAndProvenanceRejectsMixedOrUnresolvedInputs()throws Exception{
        var revision=publish("workplaces-v1",CSV);select("opportunities.work",revision.id());var inputs=SourceResolver.prepare(scenario,repo,json);
        var zone=workZone(0,900001,8.25,8.27);var report=SourceResolver.applyWorkplaces(inputs,scenario.dataPlan,List.of(zone));
        assertEquals(0.0,zone.attractivities.get("Work"));assertEquals(0,report.get("mappedRows"));assertEquals(2,report.get("outsideRows"));
        var scope=new SourceProvenance.Scope("Work",List.of());var bad=new SourceProvenance.Node("mixed","source","mixed","primary",null,List.of(),scope,Map.of());
        assertThrows(IllegalArgumentException.class,()->new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","mixed",List.of(bad)));
        var missing=new SourceProvenance.Node("derived","point-to-zone-sum","derived","primary",null,List.of("missing"),scope,Map.of());
        assertThrows(IllegalArgumentException.class,()->new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","derived",List.of(missing)));
    }
    static Zone workZone(int index,long id,double west,double east){
        var zone=new Zone();zone.index=index;zone.id=id;zone.minLon=west;zone.maxLon=east;zone.minLat=48.85;zone.maxLat=48.89;zone.attractivities.put("Work",60.0);zone.attractivities.put("Leisure_Indoor",4.0);return zone;
    }
    static Map<String,String> coreArtifacts(Path dir)throws IOException{
        var result=new TreeMap<String,String>();try(var files=Files.walk(dir)){
            for(var p:files.filter(Files::isRegularFile).toList()){
                String relative=dir.relativize(p).toString();
                if(List.of("population/","matrix/","zone-repository/","legacy/","logistics/").stream().anyMatch(relative::startsWith))result.put(relative,Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
            }
        }return result;
    }
    static void await(GenerationJob job)throws Exception{long end=System.nanoTime()+30_000_000_000L;while(job.finishedAt==null){assertTrue(System.nanoTime()<end,"Pipeline timeout");Thread.sleep(10);}}
    static byte[] xlsx()throws IOException{
        var files=new LinkedHashMap<String,String>();files.put("xl/workbook.xml","<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='jobs' sheetId='1' r:id='r1'/></sheets></workbook>");files.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='r1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/sheet1.xml'/></Relationships>");
        String[][] rows={{"kennung","laenge","breite","arbeitsplaetze"},{"a","8.21","48.86","12"},{"b","8.22","48.87","0"}};var sheet=new StringBuilder("<worksheet><sheetData>");
        for(int i=0;i<rows.length;i++){sheet.append("<row r='").append(i+1).append("'>");for(int c=0;c<rows[i].length;c++)sheet.append("<c r='").append((char)('A'+c)).append(i+1).append("' t='inlineStr'><is><t>").append(rows[i][c]).append("</t></is></c>");sheet.append("</row>");}sheet.append("</sheetData></worksheet>");files.put("xl/worksheets/sheet1.xml",sheet.toString());
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(bytes(e.getValue()));zip.closeEntry();}}return out.toByteArray();
    }
}
