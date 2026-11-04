package de.synthesis.data.importing;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;

/** Scenario-local import drafts and content-addressed, immutable normalized revisions. */
@Service
public class DataImportService {
    public static final String CONTRACT="synthesis-aspect-dataset-v1";
    public record Draft(String id,String filename,String schemaId,SourceReader.Source source) { }
    public record Request(Mapping mapping,Metadata metadata) { }
    public record Preview(String status,int inputRows,int mappedRows,int rejectedRows,int blankRows,List<Issue> issues,List<DataRow> sample) { }
    public record Payload(String contract,String schemaId,String sourceHash,String sourceFormat,String structure,Mapping mapping,Metadata metadata,List<DataRow> rows) { }
    public record Revision(String id,Payload data) { }
    public record RevisionInfo(String id,String schemaId,String label,String source,String referenceDate,String unit,int rows) { }
    public record Profile(String id,String name,String schemaId,String format,String structure,Mapping mapping) { }
    private final ScenarioRepository repo;private final ObjectMapper json;private final ImportRegistry registry;
    public DataImportService(ScenarioRepository repo,ObjectMapper json,ImportRegistry registry){this.repo=repo;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.registry=registry;}
    private Path root(String id){repo.findById(id).orElseThrow(()->new IllegalArgumentException("Szenario nicht gefunden"));return repo.uploadsDir(id).resolve("data-imports");}
    private static String key(String id){if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Ungültige Daten-ID");return id;}
    public synchronized Draft upload(String scenario,String schema,byte[] bytes,String filename)throws IOException{
        registry.require(schema);if(filename==null||filename.length()>200)throw new IllegalArgumentException("Dateiname fehlt oder ist zu lang");
        var source=readSource(schema,bytes,filename);
        if(source.tables().isEmpty())throw new IllegalArgumentException("Keine strukturierten Datensätze: Dokumenttext benötigt einen fachlichen Extraktionsadapter; der Tourenimport unterstützt die belegte Textanalyse");
        String hash=source.sourceHash(),id=ImportSupport.hash(schema+"\n"+hash);Path base=root(scenario);
        immutable(base.resolve("raw").resolve(hash+".bin"),bytes);
        var draft=new Draft(id,Path.of(filename).getFileName().toString(),schema,source);Path dest=base.resolve("drafts").resolve(id+".json");
        if(Files.isRegularFile(dest))return readDraft(scenario,id);
        immutable(dest,json.writeValueAsBytes(draft));return draft;
    }
    public SourceReader.Source readSource(String schema,byte[] bytes,String filename){
        return schema.equals("municipality-areas-v1")?new GeometrySourceReader(json).read(bytes):new SourceReader(json,SourceReader.ExtractionSettings.defaults()).read(bytes,filename);
    }
    public Draft readDraft(String scenario,String id)throws IOException{
        Path base=root(scenario);var draft=json.readValue(base.resolve("drafts").resolve(key(id)+".json").toFile(),Draft.class);
        if(!draft.id().equals(id)||!ImportSupport.hash(draft.schemaId()+"\n"+draft.source().sourceHash()).equals(id))throw new IOException("Importentwurf verändert");
        byte[] raw=Files.readAllBytes(base.resolve("raw").resolve(key(draft.source().sourceHash())+".bin"));
        if(!ImportSupport.hash(raw).equals(draft.source().sourceHash()))throw new IOException("Rohdatenhash stimmt nicht überein");
        // Extracted drafts are rechecked against their retained source, rather than trusted after file edits.
        var extracted=readSource(draft.schemaId(),raw,draft.filename());
        return new Draft(draft.id(),draft.filename(),draft.schemaId(),extracted);
    }
    public List<Draft> drafts(String scenario)throws IOException{
        Path dir=root(scenario).resolve("drafts");if(!Files.isDirectory(dir))return List.of();
        try(var files=Files.list(dir)){var result=new ArrayList<Draft>();for(var file:files.sorted().toList())if(file.getFileName().toString().matches("[0-9a-f]{64}\\.json"))result.add(readDraft(scenario,file.getFileName().toString().replace(".json","")));return result;}
    }
    public Mapping suggest(Draft d){
        var schema=registry.require(d.schemaId()).schema();Mapping best=null;int count=-1;
        for(var table:d.source().tables())for(var row:table.rows().stream().limit(30).toList()){
            Map<String,Integer> columns=new TreeMap<>();Set<Integer> used=new HashSet<>();
            for(var field:schema.fields())for(int i=0;i<row.cells().size();i++){
                String header=normal(row.cells().get(i));
                if(!used.contains(i)&&(header.equals(normal(field.id()))||field.aliases().stream().anyMatch(a->normal(a).equals(header)))){columns.put(field.id(),i);used.add(i);break;}
            }
            if(columns.size()>count){best=new Mapping(table.id(),row.sourceRow(),columns,".");count=columns.size();}
        }
        if(best==null)throw new IllegalArgumentException("Keine Tabelle gefunden");return best;
    }
    public Preview preview(String scenario,String id,Request req)throws IOException{return normalize(readDraft(scenario,id),req).preview;}
    private record Normalized(Preview preview,List<DataRow> rows,String structure) { }
    private Normalized normalize(Draft draft,Request req){
        if(req==null||req.mapping()==null||req.metadata()==null)throw new IllegalArgumentException("Mapping und Quellenmetadaten fehlen");
        var adapter=registry.require(draft.schemaId());var schema=adapter.schema();var m=req.mapping();var meta=req.metadata();validateMetadata(schema,meta);
        if(!Set.of(".",",").contains(m.decimalSeparator()==null?"":m.decimalSeparator()))throw new IllegalArgumentException("Dezimalzeichen muss Punkt oder Komma sein");
        var table=draft.source().tables().stream().filter(t->t.id().equals(m.tableId())).findFirst().orElseThrow(()->new IllegalArgumentException("Tabelle nicht gefunden"));
        var header=table.rows().stream().filter(r->r.sourceRow()==m.headerRow()).findFirst().orElseThrow(()->new IllegalArgumentException("Kopfzeile nicht gefunden"));
        ImportSupport.validateColumns(m.columns(),new HashSet<>(schema.fields().stream().map(Field::id).toList()),header.cells().size());
        List<Issue> issues=new ArrayList<>();for(var field:schema.fields())if(field.required()&&!m.columns().containsKey(field.id()))issues.add(new Issue(0,field.id(),"missing-column",field.label()+": Spalte fehlt"));
        List<DataRow> rows=new ArrayList<>();Set<String> ids=new HashSet<>();int input=0,blank=0,rejected=0;double quantity=0;
        for(var row:table.rows())if(row.sourceRow()>m.headerRow()){
            input++;if(row.cells().stream().allMatch(String::isBlank)){blank++;continue;}int before=issues.size();Map<String,Object> values=new TreeMap<>();
            for(var field:schema.fields()){
                Integer c=m.columns().get(field.id());String value=c==null?"":ImportSupport.value(row,c).strip();
                if(value.isEmpty()){if(field.required())issues.add(new Issue(row.sourceRow(),field.id(),"missing-value",field.label()+": Wert fehlt"));continue;}
                if(field.type().equals("text")){if(value.length()>500)issues.add(new Issue(row.sourceRow(),field.id(),"length","Text zu lang"));else values.put(field.id(),value);}
                else if(field.type().equals("geometry"))try{values.put(field.id(),json.convertValue(json.readTree(value),Object.class));}catch(Exception e){issues.add(new Issue(row.sourceRow(),field.id(),"geometry","Geometrie muss ein GeoJSON-Objekt sein"));}
                else try{
                    String pattern=m.decimalSeparator().equals(".")?"[+-]?[0-9]+(?:\\.[0-9]+)?":"[+-]?[0-9]+(?:,[0-9]+)?";
                    if(!value.matches(pattern)){if(field.type().equals("masked-number")){values.put(field.id(),value);continue;}throw new IllegalArgumentException();}double n=Double.parseDouble(value.replace(',','.'));
                    if(!Double.isFinite(n)||Math.abs(n)>1e12||(field.type().equals("integer")&&n!=Math.rint(n)))throw new IllegalArgumentException();values.put(field.id(),n);
                }catch(IllegalArgumentException e){issues.add(new Issue(row.sourceRow(),field.id(),"number","Zahl/Dezimalzeichen ungültig (keine Tausendertrennzeichen)"));}
            }
            if(before==issues.size()){
                var canonical=adapter.normalize(new DataRow(row.sourceRow(),Collections.unmodifiableMap(values)));issues.addAll(adapter.validate(canonical,meta));
                if(!ids.add(values.get("id").toString()))issues.add(new Issue(row.sourceRow(),"id","duplicate","Doppelte Quell-ID"));
                if(before==issues.size()){rows.add(canonical);Object amount=canonical.values().getOrDefault("trips",canonical.values().get("value"));if(amount instanceof Number n)quantity+=n.doubleValue();}
            }
            if(before!=issues.size())rejected++;
        }
        if(input==blank)issues.add(new Issue(0,"","empty","Keine Datensätze vorhanden"));
        if(schema.id().equals("vehicle-od-v1")&&(rows.size()>10000||quantity>200000))issues.add(new Issue(0,"trips","limit","Höchstens 200.000 Fahrzeugfahrten pro Tag zulässig"));
        if(schema.id().equals("population-grid-v1")&&quantity<=0)issues.add(new Issue(0,"value","zero-total","Bevölkerungsverteilung benötigt eine positive Gesamtmenge"));
        if(issues.isEmpty()){
            var datasetIssues=adapter.validateDataset(rows,meta);issues.addAll(datasetIssues);
            var invalidRows=new HashSet<Integer>();for(var issue:datasetIssues)if(issue.sourceRow()>0)invalidRows.add(issue.sourceRow());
            int previous=rows.size();rows.removeIf(row->invalidRows.contains(row.sourceRow()));rejected+=previous-rows.size();
        }
        var preview=new Preview(issues.isEmpty()?"valid":"invalid",input,rows.size(),rejected,blank,List.copyOf(issues),rows.stream().limit(20).toList());
        return new Normalized(preview,List.copyOf(rows),ImportSupport.structure(table,m.headerRow()));
    }
    public synchronized RevisionInfo publish(String scenario,String id,Request req)throws IOException{
        var result=prepareRevision(readDraft(scenario,id),req);
        immutable(root(scenario).resolve("revisions").resolve(result.id()+".json"),json.writeValueAsBytes(result));return info(result);
    }
    /** Pure materialization also validates all entries of a data pack before publication. */
    public Revision prepareRevision(Draft draft,Request req)throws IOException{
        var n=normalize(draft,req);if(!n.preview.status().equals("valid"))throw new IllegalArgumentException("Import enthält Fehler: "+n.preview.issues().getFirst().message());
        var payload=new Payload(CONTRACT,draft.schemaId(),draft.source().sourceHash(),draft.source().format(),n.structure,req.mapping(),req.metadata(),n.rows);
        return new Revision(ImportSupport.hash(json.writeValueAsBytes(payload)),payload);
    }
    public static Revision readRevision(ScenarioRepository repo,ObjectMapper mapper,String scenario,String revision)throws IOException{
        var json=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var value=json.readValue(repo.uploadsDir(scenario).resolve("data-imports/revisions").resolve(key(revision)+".json").toFile(),Revision.class);
        if(!value.id().equals(revision)||!CONTRACT.equals(value.data().contract())||!ImportSupport.hash(json.writeValueAsBytes(value.data())).equals(revision))throw new IOException("Datenrevision verändert oder inkompatibel");
        return value;
    }
    public List<RevisionInfo> revisions(String scenario)throws IOException{
        Path dir=root(scenario).resolve("revisions");if(!Files.isDirectory(dir))return List.of();try(var files=Files.list(dir)){List<RevisionInfo> result=new ArrayList<>();for(var p:files.sorted().toList())if(p.getFileName().toString().matches("[0-9a-f]{64}\\.json"))result.add(info(readRevision(repo,json,scenario,p.getFileName().toString().replace(".json",""))));return result;}
    }
    private static RevisionInfo info(Revision r){var p=r.data();return new RevisionInfo(r.id(),p.schemaId(),p.metadata().label(),p.metadata().source(),p.metadata().referenceDate(),p.metadata().unit(),p.rows().size());}
    public List<Profile> profiles(String scenario)throws IOException{
        Path dir=root(scenario).resolve("profiles");if(!Files.isDirectory(dir))return List.of();try(var files=Files.list(dir)){var result=new ArrayList<Profile>();for(var p:files.sorted().toList())if(p.getFileName().toString().matches("[0-9a-f]{64}\\.json"))result.add(json.readValue(p.toFile(),Profile.class));return result;}
    }
    public synchronized Profile saveProfile(String scenario,String id,String name,Request req)throws IOException{
        if(name==null||name.isBlank()||name.length()>100)throw new IllegalArgumentException("Profilname erforderlich (höchstens 100 Zeichen)");
        var draft=readDraft(scenario,id);var n=normalize(draft,req);if(!n.preview.status().equals("valid"))throw new IllegalArgumentException("Zuerst ein gültiges Mapping prüfen");
        String hash=ImportSupport.hash(draft.schemaId()+"\n"+name+"\n"+n.structure+"\n"+json.writeValueAsString(req.mapping()));
        var profile=new Profile(hash,name,draft.schemaId(),draft.source().format(),n.structure,req.mapping());immutable(root(scenario).resolve("profiles").resolve(hash+".json"),json.writeValueAsBytes(profile));return profile;
    }
    public Mapping applyProfile(String scenario,String id,String profileId)throws IOException{
        var draft=readDraft(scenario,id);var profile=profiles(scenario).stream().filter(p->p.id().equals(key(profileId))).findFirst().orElseThrow(()->new IllegalArgumentException("Profil nicht gefunden"));
        if(!profile.schemaId().equals(draft.schemaId())||!profile.format().equals(draft.source().format()))throw new IllegalArgumentException("Quellformat oder Fachvertrag geändert");
        var table=draft.source().tables().stream().filter(t->t.id().equals(profile.mapping().tableId())).findFirst().orElseThrow(()->new IllegalArgumentException("Tabellenstruktur geändert"));
        if(!ImportSupport.structure(table,profile.mapping().headerRow()).equals(profile.structure()))throw new IllegalArgumentException("Tabellenstruktur geändert; Zuordnung neu prüfen");return profile.mapping();
    }
    public static void validateMetadata(Schema schema,Metadata m){
        if(m==null||m.label()==null||m.label().isBlank()||m.label().length()>200||m.source()==null||m.source().isBlank()||m.source().length()>500)throw new IllegalArgumentException("Quellenbezeichnung und Herkunft erforderlich");
        if(m.licence().length()>500||m.attribution().length()>1000)throw new IllegalArgumentException("Lizenz-/Attributionsangabe zu lang");
        if(!schema.unit().equals(m.unit()))throw new IllegalArgumentException("Einheit passt nicht zum Fachvertrag; erforderlich: "+schema.unit());
        try{LocalDate.parse(m.referenceDate());}catch(Exception e){throw new IllegalArgumentException("Bezugsdatum als JJJJ-MM-TT erforderlich");}
        var r=m.coverage();if(r==null||!Double.isFinite(r.minLat)||!Double.isFinite(r.maxLat)||!Double.isFinite(r.minLon)||!Double.isFinite(r.maxLon)||r.minLat>=r.maxLat||r.minLon>=r.maxLon||r.minLat< -90||r.maxLat>90||r.minLon< -180||r.maxLon>180)throw new IllegalArgumentException("Gültige WGS84-Abdeckungsbox erforderlich");
        if(schema.id().equals("workplaces-v1"))validateWorkplaceCoverage(m);
        if(Set.of("municipal-workplaces-v1","municipality-allocation-v1","municipality-areas-v1").contains(schema.id())){
            var geography=m.geography();
            if(geography==null||!"de:AGS".equals(geography.codeSystem()))throw new IllegalArgumentException("Gemeindedaten benötigen den Schlüsselraum de:AGS und einen Gebietsstand");
            try{LocalDate.parse(geography.edition());}catch(Exception e){throw new IllegalArgumentException("Gebietsstand als JJJJ-MM-TT erforderlich");}
            if(!Set.of("explicit-point-weights-v1","uniform-area-v1").contains(Objects.toString(geography.allocationMethod(),"")))throw new IllegalArgumentException("Explizites Punktgewichts- oder Flächenverteilungsmodell auswählen");
            if(schema.id().equals("municipality-allocation-v1")&&!geography.allocationMethod().equals("explicit-point-weights-v1")||schema.id().equals("municipality-areas-v1")&&!geography.allocationMethod().equals("uniform-area-v1"))throw new IllegalArgumentException("Referenzvertrag und Verteilungsmodell passen nicht zusammen");
            if(schema.id().equals("municipal-workplaces-v1")){
                if(geography.allocationRevision()==null||!geography.allocationRevision().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Konkrete Revision der räumlichen Gemeindegewichte erforderlich");
                var declaration=m.coverageDeclaration();
                if(declaration==null||!"municipality-aggregate".equals(declaration.kind())||!"complete".equals(declaration.completeness())||declaration.statement()==null||declaration.statement().strip().length()<10||declaration.statement().length()>1000)throw new IllegalArgumentException("Vollständige Gemeindeaggregate für die enthaltenen Schlüssel und Erhebungsumfang deklarieren");
            }
        }
    }
    /** Required both when publishing and when selecting a retained revision before queueing. */
    public static void validateWorkplaceCoverage(Metadata metadata){
        var declaration=metadata.coverageDeclaration();
        if(declaration==null)throw new IllegalArgumentException("Arbeitsplatzdaten benötigen eine explizite Vollständigkeitsangabe; bestehende Revision mit ergänzten Metadaten neu veröffentlichen");
        if(!"point-register".equals(declaration.kind()))throw new IllegalArgumentException("Arbeitsplatzimport unterstützt nur Betriebspunkte; Gemeindeaggregate benötigen Gemeindeschlüssel, Gebietsstand und einen eigenen Fachadapter");
        if(!"complete".equals(declaration.completeness()))throw new IllegalArgumentException("Arbeitsplatzregister muss für den deklarierten Umfang vollständig sein; fehlende Punkte eines lückenhaften oder unbekannten Bestands sind nicht null");
        if(declaration.statement()==null||declaration.statement().strip().length()<10||declaration.statement().length()>1000)throw new IllegalArgumentException("Erhebungsumfang und Grundlage der Vollständigkeitsangabe beschreiben (10–1.000 Zeichen)");
    }
    private static String normal(String value){return Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace("ß","ss"),Normalizer.Form.NFD).replaceAll("[^a-z0-9]","");}
    private static void immutable(Path file,byte[] bytes)throws IOException{
        Files.createDirectories(file.getParent());if(Files.exists(file)){if(!Arrays.equals(Files.readAllBytes(file),bytes))throw new IOException("Unveränderliche Datei enthält andere Daten");return;}
        Path temp=Files.createTempFile(file.getParent(),".import-",".part");try{Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
}
