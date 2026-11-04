package de.synthesis.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.data.importing.ImportRegistry;
import de.synthesis.data.importing.ImportSupport;
import de.synthesis.scenario.ScenarioRepository;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Installation-local immutable packages; scenarios always receive their own retained copy. */
@Service
public final class DataPackLibrary {
    public record Entry(String id,String status,String issue,DataPacks.Pack pack) { }
    private final Path root;
    private final DataPacks packs;
    public DataPackLibrary(ScenarioRepository repo,ObjectMapper json,ImportRegistry registry,SynthesisProperties properties){
        root=Path.of(properties.getDataDir(),"source-library","packs").toAbsolutePath().normalize();
        packs=new DataPacks(repo,json,registry);
    }
    public synchronized DataPacks.Pack publish(byte[] archive)throws IOException {
        var pack=packs.validatePortable(archive);
        SourceFiles.immutable(root.resolve(pack.id()+".zip"),archive);
        return pack;
    }
    public DataPacks.Pack promote(String scenario,String id)throws IOException {
        return publish(packs.archive(scenario,id));
    }
    public DataPacks.Pack install(String scenario,String id)throws IOException {
        byte[] archive=read(id);
        packs.validatePortable(archive);
        return packs.install(scenario,archive);
    }
    /** Freeze bytes for a multi-package transfer before materializing anything in a scenario. */
    public byte[] archive(String id)throws IOException {
        byte[] bytes=read(id);packs.validatePortable(bytes);return bytes;
    }
    public List<Entry> list()throws IOException {
        if(!Files.isDirectory(root))return List.of();
        try(var files=Files.list(root)){
            var entries=new ArrayList<Entry>();
            for(var file:files.sorted().toList())if(file.getFileName().toString().matches("[0-9a-f]{64}\\.zip")){
                String id=file.getFileName().toString().replace(".zip","");
                try{entries.add(new Entry(id,"available","",packs.validatePortable(read(id))));}
                catch(IOException|IllegalArgumentException invalid){entries.add(new Entry(id,"invalid",Objects.toString(invalid.getMessage(),"Datenpaket ungültig"),null));}
            }
            return List.copyOf(entries);
        }
    }
    private byte[] read(String id)throws IOException {
        Path file=root.resolve(SourceFiles.hash(id)+".zip");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new NoSuchFileException(file.toString());
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>DataPacks.MAX_ARCHIVE_BYTES)
            throw new IOException("Bibliothekspaket ist keine reguläre ZIP-Datei innerhalb des Größenlimits");
        byte[] bytes=Files.readAllBytes(file);
        if(!ImportSupport.hash(bytes).equals(id))throw new IOException("Bibliothekspaket verändert");
        return bytes;
    }
}
