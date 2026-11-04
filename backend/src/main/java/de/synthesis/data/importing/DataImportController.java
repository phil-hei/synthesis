package de.synthesis.data.importing;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.util.*;

@RestController
@RequestMapping(value="/api/scenarios/{scenario}/data",produces=MediaType.APPLICATION_JSON_VALUE)
public class DataImportController {
    public record View(DataImportService.Draft draft,AspectImportAdapter.Mapping mapping) { }
    public record ProfileRequest(String name,DataImportService.Request request) { }
    public record ProfileSelection(String profileId) { }
    private final de.synthesis.data.DataPacks packs;
    private final de.synthesis.data.SourceProfiles sourceProfiles;
    private final DataImportService imports;private final ImportRegistry registry;
    private final de.synthesis.scenario.ScenarioRepository repo;private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public DataImportController(DataImportService imports,ImportRegistry registry,de.synthesis.scenario.ScenarioRepository repo,com.fasterxml.jackson.databind.ObjectMapper mapper){this.imports=imports;this.registry=registry;this.repo=repo;this.mapper=mapper;this.packs=new de.synthesis.data.DataPacks(repo,mapper,registry);this.sourceProfiles=new de.synthesis.data.SourceProfiles(repo,mapper);}
    @PostMapping("/plan/preview") public Map<String,Object> check(@PathVariable String scenario,@RequestBody de.synthesis.scenario.Scenario request){
        request.id=scenario;
        try {var inputs=de.synthesis.data.SourceResolver.prepare(request,repo,mapper,registry);return Map.of("ready",true,"issues",List.of(),"decisions",inputs.decisions());}
        catch(de.synthesis.data.SourceResolver.PlanException e){return Map.of("ready",false,"issues",List.of(e.getMessage()),"decisions",e.decisions());}
        catch(IOException|IllegalArgumentException e){return Map.of("ready",false,"issues",List.of(e.getMessage()),"decisions",List.of());}
    }
    @GetMapping("/packs") public List<de.synthesis.data.DataPacks.Pack> packs(@PathVariable String scenario)throws IOException{return packs.list(scenario);}
    @PostMapping("/packs") public de.synthesis.data.DataPacks.Pack installPack(@PathVariable String scenario,@RequestParam MultipartFile file)throws IOException{
        if(file.isEmpty()||file.getSize()>de.synthesis.data.DataPacks.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("Datenpaket als ZIP erforderlich, höchstens 5 MB");
        return packs.install(scenario,file.getBytes());
    }
    @GetMapping("/source-profiles") public List<de.synthesis.data.SourceProfiles.Profile> sourceProfiles(@PathVariable String scenario)throws IOException{return sourceProfiles.list(scenario);}
    @PostMapping("/source-profiles") public de.synthesis.data.SourceProfiles.Profile saveSourceProfile(@PathVariable String scenario,@RequestBody de.synthesis.data.SourceProfiles.Definition definition)throws IOException{return sourceProfiles.save(scenario,definition);}
    @GetMapping("/schemas") public List<AspectImportAdapter.Schema> schemas(){return registry.schemas();}
    @GetMapping("/imports") public List<View> list(@PathVariable String scenario)throws IOException{
        var result=new ArrayList<View>();for(var d:imports.drafts(scenario))result.add(new View(d,imports.suggest(d)));return result;
    }
    @PostMapping("/imports") public View upload(@PathVariable String scenario,@RequestParam String schema,@RequestParam MultipartFile file)throws IOException{
        if(file.isEmpty()||file.getSize()>TabularReader.MAX_BYTES)throw new IllegalArgumentException("Importdatei erforderlich, höchstens 5 MB");
        var d=imports.upload(scenario,schema,file.getBytes(),file.getOriginalFilename());return new View(d,imports.suggest(d));
    }
    @PostMapping("/imports/{id}/preview") public DataImportService.Preview preview(@PathVariable String scenario,@PathVariable String id,@RequestBody DataImportService.Request request)throws IOException{return imports.preview(scenario,id,request);}
    @PostMapping("/imports/{id}/revisions") public DataImportService.RevisionInfo publish(@PathVariable String scenario,@PathVariable String id,@RequestBody DataImportService.Request request)throws IOException{return imports.publish(scenario,id,request);}
    @GetMapping("/revisions") public List<DataImportService.RevisionInfo> revisions(@PathVariable String scenario)throws IOException{return imports.revisions(scenario);}
    @GetMapping("/profiles") public List<DataImportService.Profile> profiles(@PathVariable String scenario)throws IOException{return imports.profiles(scenario);}
    @PostMapping("/imports/{id}/profiles") public DataImportService.Profile profile(@PathVariable String scenario,@PathVariable String id,@RequestBody ProfileRequest request)throws IOException{return imports.saveProfile(scenario,id,request.name(),request.request());}
    @PostMapping("/imports/{id}/profile") public AspectImportAdapter.Mapping apply(@PathVariable String scenario,@PathVariable String id,@RequestBody ProfileSelection request)throws IOException{return imports.applyProfile(scenario,id,request.profileId());}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,String>> io(IOException e){return ResponseEntity.status(e instanceof NoSuchFileException?404:422).body(Map.of("message",e instanceof NoSuchFileException?"Datenrevision oder Import nicht gefunden":"Datenimport konnte nicht gelesen oder gespeichert werden: "+e.getMessage()));}
}
