package de.synthesis.companytour;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.util.List;

@RestController
@RequestMapping(value = "/api/scenarios/{id}/company-tours", produces = MediaType.APPLICATION_JSON_VALUE)
public class CompanyTourController {
    private final CompanyTourImportService imports;
    public record Activation(boolean enabled) {}
    public record GeocodingRequest(List<String> targets, boolean refresh) {}
    public record AnalysisRequest(String mode) {}
    public record ProfileRequest(String name) {}
    public record ApplyProfileRequest(String profileId) {}
    public CompanyTourController(CompanyTourImportService imports) { this.imports = imports; }
    @PostMapping public CompanyTourImportService.ImportView upload(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > TourTableReader.MAX_BYTES) throw new IllegalArgumentException("Datei fehlt oder überschreitet 5 MB");
        return imports.upload(id, file.getBytes(), file.getOriginalFilename());
    }
    @GetMapping public List<CompanyTourImportService.ImportView> list(@PathVariable String id) throws IOException { return imports.list(id); }
    @PostMapping("/{hash}/preview") public CompanyTourImportService.ImportView preview(@PathVariable String id, @PathVariable String hash,
            @RequestBody CompanyTourImportService.MappingRequest request) throws IOException { return imports.preview(id, hash, request.mapping()); }
    @PostMapping("/{hash}/confirm") public CompanyTourImportService.ImportView confirm(@PathVariable String id, @PathVariable String hash,
            @RequestBody CompanyTourImportService.MappingRequest request) throws IOException { return imports.confirm(id, hash, request.mapping(),request.sourceReviewed()); }
    @PostMapping("/{hash}/suggest") public CompanyTourImportService.ImportView suggest(@PathVariable String id, @PathVariable String hash,
            @RequestBody CompanyTourImportService.MappingRequest request) throws IOException { return imports.propose(id, hash, request.mapping()); }
    @PostMapping("/{hash}/analyze") public CompanyTourImportService.ImportView analyze(@PathVariable String id,@PathVariable String hash,@RequestBody AnalysisRequest request)throws IOException{return imports.analyze(id,hash,request.mode()==null?"auto":request.mode());}
    @GetMapping("/profiles") public List<TourImportProfiles.Profile> profiles(@PathVariable String id)throws IOException{return imports.profiles(id);}
    @PostMapping("/{hash}/profile") public TourImportProfiles.Profile saveProfile(@PathVariable String id,@PathVariable String hash,@RequestBody ProfileRequest request)throws IOException{return imports.saveProfile(id,hash,request.name());}
    @PostMapping("/{hash}/apply-profile") public CompanyTourImportService.ImportView applyProfile(@PathVariable String id,@PathVariable String hash,@RequestBody ApplyProfileRequest request)throws IOException{return imports.applyProfile(id,hash,request.profileId());}
    @GetMapping("/{hash}/export") public ResponseEntity<CompanyTourMapper.Draft> export(@PathVariable String id, @PathVariable String hash) throws IOException {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=company-tours-draft.json")
                .cacheControl(CacheControl.noStore()).body(imports.export(id, hash));
    }
    @DeleteMapping("/{hash}") public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String hash) throws IOException {
        imports.delete(id, hash); return ResponseEntity.noContent().build();
    }
    @PutMapping("/{hash}/context") public CompanyTourImportService.ImportView context(@PathVariable String id, @PathVariable String hash,
            @RequestBody CompanyTourExecution.Context context) throws IOException { return imports.context(id, hash, context); }
    @PostMapping("/{hash}/activation") public CompanyTourImportService.ImportView activate(@PathVariable String id, @PathVariable String hash,
            @RequestBody Activation request) throws IOException { return imports.activate(id, hash, request.enabled()); }
    @PostMapping("/{hash}/geocode") public CompanyTourGeocoder.Lookup geocode(@PathVariable String id,@PathVariable String hash,
            @RequestBody GeocodingRequest request)throws IOException{return imports.geocode(id,hash,request.targets(),request.refresh());}
    @PostMapping("/{hash}/locations") public CompanyTourLocations.Lookup locations(@PathVariable String id, @PathVariable String hash,
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > TourTableReader.MAX_BYTES) throw new IllegalArgumentException("Ortstabelle fehlt oder überschreitet 5 MB");
        return imports.locations(id, hash, file.getBytes(), file.getOriginalFilename());
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<java.util.Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<java.util.Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message", e.getMessage()));
    }
    @ExceptionHandler(IOException.class) public ResponseEntity<java.util.Map<String, String>> io(IOException e) {
        return ResponseEntity.status(e instanceof NoSuchFileException ? HttpStatus.NOT_FOUND : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(java.util.Map.of("message", e instanceof NoSuchFileException ? "Import nicht gefunden" : "Tourenimport konnte nicht gespeichert oder gelesen werden"));
    }
}
