package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Confirmed, scenario-local mapping profiles contain rules and structure, never sample customer values. */
public final class TourImportProfiles {
    public record Profile(String contract,String id,String name,String format,String strategy,String structure,
                          CompanyTourMapper.Mapping mapping) {}
    public static String fingerprint(TourTableReader.Table table,CompanyTourMapper.Mapping mapping){
        var header=table.rows().stream().filter(r->r.sourceRow()==mapping.headerRow()).findFirst().orElseThrow();
        return CompanyTourMapper.hash(String.join("\u001f",header.cells())+"\n"+header.sourceRow()+"\n"+table.id());
    }
    public static List<Profile> list(Path dir,ObjectMapper json)throws IOException{if(!Files.isDirectory(dir))return List.of();try(var files=Files.list(dir)){List<Profile> out=new ArrayList<>();for(var file:files.filter(f->f.getFileName().toString().matches("[0-9a-f]{64}\\.json")).sorted().toList())out.add(json.readValue(file.toFile(),Profile.class));return List.copyOf(out);}}
    public static Profile save(Path dir,String name,String format,boolean document,TourTableReader.Table table,CompanyTourMapper.Mapping mapping,ObjectMapper json)throws IOException{
        if(name==null||name.isBlank()||name.length()>100)throw new IllegalArgumentException("Profilname mit höchstens 100 Zeichen erforderlich");
        String structure=document?"fresh-evidence-required":fingerprint(table,mapping);String id=CompanyTourMapper.hash(name.strip()+"\n"+format+"\n"+structure);
        var profile=new Profile("synthesis-tour-import-profile-v1",id,name.strip(),format,document?"document-evidence":"table-rules",structure,mapping);
        Files.createDirectories(dir);Path tmp=Files.createTempFile(dir,".profile-",".tmp");try{json.writeValue(tmp.toFile(),profile);Files.move(tmp,dir.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}return profile;
    }
}
