package de.synthesis.data;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;

@RestController
@RequestMapping(produces=MediaType.APPLICATION_JSON_VALUE)
public class DataLibraryController {
    private final DataPackLibrary library;
    public DataLibraryController(DataPackLibrary library){this.library=library;}
    @GetMapping("/api/data-library/packs") public List<DataPackLibrary.Entry> list()throws IOException{return library.list();}
    @PostMapping("/api/data-library/packs") public DataPacks.Pack publish(@RequestParam MultipartFile file)throws IOException{
        if(file.isEmpty()||file.getSize()>DataPacks.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("Datenpaket als ZIP erforderlich, höchstens 5 MB");
        return library.publish(file.getBytes());
    }
    @PostMapping("/api/scenarios/{scenario}/data/library/packs/{id}/install") public DataPacks.Pack install(@PathVariable String scenario,@PathVariable String id)throws IOException{return library.install(scenario,id);}
    @PostMapping("/api/scenarios/{scenario}/data/packs/{id}/library") public DataPacks.Pack promote(@PathVariable String scenario,@PathVariable String id)throws IOException{return library.promote(scenario,id);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,String>> io(IOException e){return ResponseEntity.status(e instanceof NoSuchFileException?404:422).body(Map.of("message",e instanceof NoSuchFileException?"Datenpaket nicht gefunden":"Datenbibliothek konnte nicht gelesen oder gespeichert werden: "+e.getMessage()));}
}
