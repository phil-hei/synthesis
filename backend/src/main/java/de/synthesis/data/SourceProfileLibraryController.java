package de.synthesis.data;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;

@RestController
@RequestMapping(produces=MediaType.APPLICATION_JSON_VALUE)
public class SourceProfileLibraryController {
    private final SourceProfileLibrary library;
    public SourceProfileLibraryController(SourceProfileLibrary library){this.library=library;}
    @GetMapping("/api/data-library/source-profiles") public List<SourceProfileLibrary.Entry> list()throws IOException{return library.list();}
    @PostMapping("/api/data-library/source-profiles") public SourceProfiles.Profile publish(@RequestBody SourceProfiles.Definition definition)throws IOException{return library.publish(definition);}
    @PostMapping("/api/scenarios/{scenario}/data/source-profiles/{id}/library") public SourceProfiles.Profile promote(@PathVariable String scenario,@PathVariable String id)throws IOException{return library.promote(scenario,id);}
    @PostMapping("/api/scenarios/{scenario}/data/library/source-profiles/{id}/install") public SourceProfiles.Profile install(@PathVariable String scenario,@PathVariable String id)throws IOException{return library.install(scenario,id);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,String>> io(IOException e){return ResponseEntity.status(e instanceof NoSuchFileException?404:422).body(Map.of("message",e instanceof NoSuchFileException?"Quellenprofil oder benötigtes Bibliothekspaket nicht gefunden":"Quellenprofilbibliothek konnte nicht gelesen oder gespeichert werden: "+e.getMessage()));}
}
