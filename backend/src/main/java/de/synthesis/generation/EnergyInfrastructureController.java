package de.synthesis.generation;

import com.fasterxml.jackson.databind.*;
import de.synthesis.generation.pipeline.EnergyInfrastructure;
import de.synthesis.scenario.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Explicitly frozen input and refresh control; never replaces sources while a generation is active. */
@RestController
@RequestMapping("/api/scenarios/{id}/energy-infrastructure/osm-snapshot")
public class EnergyInfrastructureController {
    private final ScenarioRepository scenarios;
    private final GenerationService generation;
    private final ObjectMapper mapper;
    public EnergyInfrastructureController(ScenarioRepository scenarios,GenerationService generation,ObjectMapper mapper){this.scenarios=scenarios;this.generation=generation;this.mapper=mapper;}
    private Scenario editable(String id) {
        Scenario scenario=scenarios.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Szenario nicht gefunden"));
        if(generation.status(id).map(job->job.state==GenerationJob.State.RUNNING || job.state==GenerationJob.State.QUEUED).orElse(false))throw new ResponseStatusException(HttpStatus.CONFLICT,"Stationsquelle kann während einer Generierung nicht geändert werden");
        if(scenario.region==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Aktuelle Region zuerst speichern");
        return scenario;
    }
    @PostMapping public Map<String,Object> upload(@PathVariable String id,@RequestParam("file") MultipartFile file)throws IOException {
        Scenario scenario=editable(id);
        if(file.isEmpty() || file.getSize()>EnergyInfrastructure.MAX_BYTES)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stationssnapshot fehlt oder überschreitet 20 MB");
        JsonNode input;
        try {
            input=mapper.readTree(file.getBytes());
            // A previous exported snapshot is checked before replacing a good input.
            if(input!=null && input.has("contract")) {
                Path temp=Files.createTempFile("energy-snapshot-",".json");
                try {Files.write(temp,file.getBytes());EnergyInfrastructure.readSnapshot(temp,scenario.region);}finally{Files.deleteIfExists(temp);}
            }else input=EnergyInfrastructure.snapshot(input,scenario.region,"user-upload");
        }catch(IOException | IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ungültiger Stationssnapshot: "+e.getMessage(),e);}
        Path target=scenarios.uploadsDir(id).resolve(EnergyInfrastructure.SNAPSHOT);EnergyInfrastructure.writeSnapshot(target,input);
        var result=EnergyInfrastructure.readSnapshot(target,scenario.region);
        return Map.of("name",EnergyInfrastructure.SNAPSHOT,"objects",result.data().path("elements").size(),"sha256",result.sha256());
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) public void refresh(@PathVariable String id)throws IOException {
        editable(id);Files.deleteIfExists(scenarios.uploadsDir(id).resolve(EnergyInfrastructure.SNAPSHOT));
        // Previous output still belongs to its previous run; frontend hides it until regeneration.
    }
}
