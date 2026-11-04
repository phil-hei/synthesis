package de.synthesis.data;

import com.fasterxml.jackson.databind.*;
import de.synthesis.data.importing.*;
import de.synthesis.scenario.ScenarioRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Local, bounded ZIP packs. The archive hash is their immutable identity, never id/edition alone. */
public final class DataPacks {
    public static final String CONTRACT="synthesis-data-pack-v1";
    public static final int MAX_ARCHIVE_BYTES=5*1024*1024;
    private static final int MAX_EXPANDED_BYTES=15*1024*1024;
    public record Entry(String id,String schema,String file,String sha256,
                        AspectImportAdapter.Mapping mapping,AspectImportAdapter.Metadata metadata) { }
    public record Manifest(String contract,String id,String edition,String title,List<Entry> sources) { }
    public record InstalledSource(String id,String aspect,String schema,String revision,String label,
                                  String referenceDate,String licence,String attribution) { }
    public record Pack(String id,String packId,String edition,String title,List<InstalledSource> sources) { }
    private record Prepared(Manifest manifest,Map<String,byte[]> files,Map<String,DataImportService.Revision> revisions) { }
    private final ScenarioRepository repo;
    private final ObjectMapper json;
    private final ImportRegistry registry;
    private final DataImportService imports;
    public DataPacks(ScenarioRepository repo,ObjectMapper mapper,ImportRegistry registry) {
        this.repo=repo;this.json=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.registry=registry;this.imports=new DataImportService(repo,mapper,registry);
    }
    public synchronized Pack install(String scenario,byte[] archive)throws IOException {
        SourceFiles.root(repo,scenario);
        var prepared=prepare(archive); // Validate the entire archive before publishing even one revision.
        for (var entry:prepared.manifest().sources()) {
            var draft=imports.upload(scenario,entry.schema(),prepared.files().get(entry.file()),entry.file());
            var published=imports.publish(scenario,draft.id(),new DataImportService.Request(entry.mapping(),entry.metadata()));
            if (!published.id().equals(prepared.revisions().get(entry.id()).id())) throw new IOException("Paketmaterialisierung hat sich verändert");
        }
        String id=ImportSupport.hash(archive);
        SourceFiles.immutable(SourceFiles.root(repo,scenario).resolve("packs").resolve(id+".zip"),archive);
        return describe(id,prepared);
    }
    public Pack read(String scenario,String id)throws IOException {
        var prepared=retained(scenario,id);
        return describe(id,prepared);
    }
    /** A reusable package must carry every referenced revision, independent of a scenario. */
    public Pack validatePortable(byte[] archive)throws IOException {
        var prepared=prepare(archive);
        Map<String,DataImportService.Revision> revisions=new HashMap<>();
        prepared.revisions().values().forEach(r->revisions.put(r.id(),r));
        for(var revision:revisions.values())validateDependencies(revision,revisions,new HashSet<>());
        return describe(ImportSupport.hash(archive),prepared);
    }
    private void validateDependencies(DataImportService.Revision revision,Map<String,DataImportService.Revision> revisions,Set<String> visiting){
        if(visiting.size()>=16||!visiting.add(revision.id()))throw new IllegalArgumentException("Zyklische oder zu tiefe Paketabhängigkeit");
        try{
            for(var dependency:registry.require(revision.data().schemaId()).dependencies(revision.data().metadata())){
                var reference=revisions.get(dependency.revision());
                if(reference==null)throw new IllegalArgumentException("Bibliothekspaket muss alle Referenzrevisionen enthalten: "+dependency.role());
                if(!reference.data().schemaId().equals(dependency.schema()))throw new IllegalArgumentException("Falscher Fachvertrag einer Paketabhängigkeit: "+dependency.role());
                validateDependencies(reference,revisions,visiting);
            }
            if(revision.data().schemaId().equals("municipal-workplaces-v1"))
                MunicipalWorkplaces.validateBinding(revision,revisions.get(revision.data().metadata().geography().allocationRevision()));
        }finally{visiting.remove(revision.id());}
    }
    public byte[] archive(String scenario,String id)throws IOException {
        retained(scenario,id); // Check the installed revisions as well as the ZIP.
        return archiveBytes(scenario,id);
    }
    public DataImportService.Revision resolve(String scenario,String id,String entry)throws IOException {
        var prepared=retained(scenario,id);
        var revision=prepared.revisions().get(entry);
        if (revision==null) throw new IllegalArgumentException("Datenpaket enthält den ausgewählten Beitrag nicht: "+entry);
        return revision;
    }
    private Prepared retained(String scenario,String id)throws IOException {
        byte[] archive=archiveBytes(scenario,id);
        var prepared=prepare(archive);
        for (var revision:prepared.revisions().values())
            DataImportService.readRevision(repo,json,scenario,revision.id());
        return prepared;
    }
    private byte[] archiveBytes(String scenario,String id)throws IOException {
        Path file=SourceFiles.root(repo,scenario).resolve("packs").resolve(SourceFiles.hash(id)+".zip");
        if (Files.size(file)>MAX_ARCHIVE_BYTES) throw new IOException("Gespeichertes Datenpaket ist zu groß");
        byte[] archive=Files.readAllBytes(file);
        if (!ImportSupport.hash(archive).equals(id)) throw new IOException("Datenpaket verändert");
        return archive;
    }
    public List<Pack> list(String scenario)throws IOException {
        Path directory=SourceFiles.root(repo,scenario).resolve("packs");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files=Files.list(directory)) {
            var result=new ArrayList<Pack>();
            for (var file:files.sorted().toList()) if (file.getFileName().toString().matches("[0-9a-f]{64}\\.zip"))
                result.add(read(scenario,file.getFileName().toString().replace(".zip","")));
            return List.copyOf(result);
        }
    }
    private Prepared prepare(byte[] archive)throws IOException {
        if (archive==null||archive.length==0||archive.length>MAX_ARCHIVE_BYTES)
            throw new IllegalArgumentException("Datenpaket als ZIP erforderlich, höchstens 5 MB");
        Map<String,byte[]> files=new LinkedHashMap<>();int expanded=0;
        try (var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                String name=entry.getName();
                if (entry.isDirectory()||!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")||files.containsKey(name)||files.size()>=16)
                    throw new IllegalArgumentException("Paket benötigt eindeutige Dateien direkt im ZIP, keine Pfade/Verzeichnisse");
                int limit=name.equals("manifest.json")?256*1024:TabularReader.MAX_BYTES;
                byte[] bytes=zip.readNBytes(limit+1);expanded+=bytes.length;
                if (bytes.length>limit||expanded>MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Entpacktes Datenpaket überschreitet Größenlimit");
                files.put(name,bytes);
            }
        }
        if (!files.containsKey("manifest.json")) throw new IllegalArgumentException("manifest.json fehlt im Datenpaket");
        var manifest=json.readValue(files.get("manifest.json"),Manifest.class);
        if (!CONTRACT.equals(manifest.contract())||manifest.id()==null||!manifest.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
                ||manifest.edition()==null||manifest.edition().isBlank()||manifest.edition().length()>100
                ||manifest.title()==null||manifest.title().isBlank()||manifest.title().length()>200
                ||manifest.sources()==null||manifest.sources().isEmpty()||manifest.sources().size()>10)
            throw new IllegalArgumentException("Paketmanifest: Vertrag, ID, Edition, Titel und 1–10 Quellen erforderlich");
        Set<String> ids=new HashSet<>(),referenced=new HashSet<>(Set.of("manifest.json"));
        Map<String,DataImportService.Revision> revisions=new LinkedHashMap<>();
        for (var entry:manifest.sources()) {
            if (entry==null||entry.id()==null||!entry.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")||!ids.add(entry.id())
                    ||entry.file()==null||!entry.file().matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:csv|tsv|xlsx|json|xml|docx|geojson)")
                    ||entry.file().equals("manifest.json")||!files.containsKey(entry.file())
                    ||!ImportSupport.hash(files.get(entry.file())).equals(entry.sha256()))
                throw new IllegalArgumentException("Paketquelle: ID, lokale strukturierte Datei und passender SHA-256 erforderlich");
            referenced.add(entry.file());registry.require(entry.schema());
            byte[] raw=files.get(entry.file());
            var source=imports.readSource(entry.schema(),raw,entry.file());
            var draft=new DataImportService.Draft(ImportSupport.hash(entry.schema()+"\n"+source.sourceHash()),entry.file(),entry.schema(),source);
            revisions.put(entry.id(),imports.prepareRevision(draft,new DataImportService.Request(entry.mapping(),entry.metadata())));
        }
        if (!referenced.equals(files.keySet())) throw new IllegalArgumentException("Paket enthält nicht deklarierte Dateien");
        return new Prepared(manifest,files,revisions);
    }
    private Pack describe(String id,Prepared prepared) {
        var manifest=prepared.manifest();
        var sources=manifest.sources().stream().map(entry -> {
            var revision=prepared.revisions().get(entry.id());var meta=revision.data().metadata();
            return new InstalledSource(entry.id(),registry.require(entry.schema()).schema().aspect(),entry.schema(),revision.id(),
                    meta.label(),meta.referenceDate(),meta.licence(),meta.attribution());
        }).toList();
        return new Pack(id,manifest.id(),manifest.edition(),manifest.title(),sources);
    }
}
