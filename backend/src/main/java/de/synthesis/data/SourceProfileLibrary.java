package de.synthesis.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.data.importing.ImportRegistry;
import de.synthesis.scenario.ScenarioRepository;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Portable selection recipes plus pinned package dependencies; copying never activates a policy. */
@Service
public final class SourceProfileLibrary {
    public static final int MAX_BYTES=256*1024,MAX_PACKAGES=16;
    public record Entry(String id,String status,String issue,SourceProfiles.Profile profile,List<DataPacks.Pack> requiredPacks) { }
    private record Prepared(SourceProfiles.Profile profile,Map<String,byte[]> archives,List<DataPacks.Pack> packs) { }
    private final Path root;
    private final ObjectMapper json;
    private final ScenarioRepository repo;
    private final SourceProfiles profiles;
    private final DataPackLibrary library;
    private final DataPacks packs;
    public SourceProfileLibrary(ScenarioRepository repo,ObjectMapper json,ImportRegistry registry,SynthesisProperties properties,DataPackLibrary library){
        this.repo=repo;this.json=json;this.profiles=new SourceProfiles(repo,json);this.library=library;
        this.packs=new DataPacks(repo,json,registry);
        root=Path.of(properties.getDataDir(),"source-library","profiles").toAbsolutePath().normalize();
    }
    public synchronized SourceProfiles.Profile publish(SourceProfiles.Definition definition)throws IOException {
        var prepared=prepare(profiles.prepare(definition));byte[] bytes=json.writeValueAsBytes(prepared.profile());
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Quellenprofil ist zu groß");
        SourceFiles.immutable(root.resolve(prepared.profile().id()+".json"),bytes);
        return prepared.profile();
    }
    public SourceProfiles.Profile promote(String scenario,String id)throws IOException {return publish(profiles.read(scenario,id).definition());}
    public SourceProfiles.Profile install(String scenario,String id)throws IOException {
        SourceFiles.root(repo,scenario);
        var prepared=prepare(read(id)); // Freeze and validate every active package before any scenario write.
        for(var archive:prepared.archives().values())packs.install(scenario,archive);
        return profiles.save(scenario,prepared.profile().definition());
    }
    public List<Entry> list()throws IOException {
        if(!Files.isDirectory(root))return List.of();
        try(var files=Files.list(root)){
            var result=new ArrayList<Entry>();
            for(var file:files.sorted().toList())if(file.getFileName().toString().matches("[0-9a-f]{64}\\.json")){
                String id=file.getFileName().toString().replace(".json","");SourceProfiles.Profile profile=null;
                try{profile=read(id);var prepared=prepare(profile);result.add(new Entry(id,"available","",profile,prepared.packs()));}
                catch(IOException|IllegalArgumentException invalid){result.add(new Entry(id,"invalid",Objects.toString(invalid.getMessage(),"Quellenprofil ungültig"),profile,List.of()));}
            }
            return List.copyOf(result);
        }
    }
    private SourceProfiles.Profile read(String id)throws IOException {
        Path file=root.resolve(SourceFiles.hash(id)+".json");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new NoSuchFileException(file.toString());
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw new IOException("Quellenprofil ist keine reguläre Datei innerhalb des Größenlimits");
        var profile=profiles.decode(Files.readAllBytes(file));if(!id.equals(profile.id()))throw new IOException("Quellenprofil verändert");return profile;
    }
    private Prepared prepare(SourceProfiles.Profile profile)throws IOException {
        var archives=new LinkedHashMap<String,byte[]>();var available=new LinkedHashMap<String,DataPacks.Pack>();
        for(var aspect:profile.definition().aspects().entrySet())for(var candidate:aspect.getValue().candidates()){
            if(candidate.provider().startsWith("revision:"))throw new IllegalArgumentException("Gemeinsame Quellenprofile benötigen Paketquellen statt szenarioeigener Revisionskandidaten");
            if(candidate.excluded()||candidate.provider().startsWith("builtin:"))continue;
            String[] parts=candidate.provider().split(":");String id=parts[1];
            if(!archives.containsKey(id)){
                if(archives.size()>=MAX_PACKAGES)throw new IllegalArgumentException("Gemeinsames Quellenprofil darf höchstens 16 aktive Paketversionen benötigen");
                byte[] bytes=library.archive(id);archives.put(id,bytes);available.put(id,packs.validatePortable(bytes));
            }
            var entry=available.get(id).sources().stream().filter(e->e.id().equals(parts[2])).findFirst().orElseThrow(()->new IllegalArgumentException("Quellenprofil referenziert einen fehlenden Paketbeitrag: "+parts[2]));
            if(!entry.aspect().equals(aspect.getKey()))throw new IllegalArgumentException("Paketbeitrag passt nicht zum Profilaspekt: "+aspect.getKey());
        }
        return new Prepared(profile,Collections.unmodifiableMap(archives),List.copyOf(available.values()));
    }
}
