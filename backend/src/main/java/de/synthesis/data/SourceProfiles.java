package de.synthesis.data;

import com.fasterxml.jackson.databind.*;
import de.synthesis.data.importing.ImportSupport;
import de.synthesis.scenario.ScenarioRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable source order, deliberately separate from import mapping profiles. */
public final class SourceProfiles {
    public static final String CONTRACT="synthesis-source-profile-v1";
    public record Candidate(String provider,boolean excluded,String onUnavailable) { }
    public record Chain(List<Candidate> candidates,String fallback,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String strategy) {
        public Chain(List<Candidate> candidates,String fallback){this(candidates,fallback,null);}
        public boolean regional(){return "fill-gaps".equals(strategy);}
    }
    public record Definition(String contract,String name,Map<String,Chain> aspects) { }
    public record Profile(String id,Definition definition) { }
    private final ScenarioRepository repo;
    private final ObjectMapper json;
    public SourceProfiles(ScenarioRepository repo,ObjectMapper mapper) {
        this.repo=repo;this.json=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public static void validate(Definition definition) {
        if (definition==null||!CONTRACT.equals(definition.contract())||definition.name()==null
                ||definition.name().isBlank()||definition.name().length()>100||definition.aspects()==null
                ||definition.aspects().isEmpty()||!SourcePlan.ASPECTS.containsAll(definition.aspects().keySet()))
            throw new IllegalArgumentException("Quellenprofil: Name, Vertrag und bekannte Aspekte erforderlich");
        for (var entry:definition.aspects().entrySet()) {
            var chain=entry.getValue();
            if (chain==null||chain.candidates()==null||chain.candidates().isEmpty()||chain.candidates().size()>16
                    ||!Set.of("legacy","none").contains(Objects.toString(chain.fallback(),"")))
                throw new IllegalArgumentException("Quellenprofil: 1–16 Kandidaten und explizite Ersatzregel erforderlich");
            Set<String> seen=new HashSet<>();
            if(chain.strategy()!=null&&!Set.of("choose","fill-gaps").contains(chain.strategy()))throw new IllegalArgumentException("Unbekannte Quellenstrategie");
            if(chain.regional()&&(!entry.getKey().equals("opportunities.work")||!chain.fallback().equals("none")||chain.candidates().stream().allMatch(c->c!=null&&c.excluded())))
                throw new IllegalArgumentException("Regionale Registerergänzung benötigt aktive Arbeitsplatzregister und explizite Lückenregeln");
            for (var candidate:chain.candidates()) {
                if (candidate==null||candidate.provider()==null
                        ||!candidate.provider().matches("(?:revision:[0-9a-f]{64}|pack:[0-9a-f]{64}:[A-Za-z0-9][A-Za-z0-9_-]{0,63}|builtin:[a-z][a-z0-9-]{0,63})")
                        ||!seen.add(candidate.provider())
                        ||!Set.of("fail","next").contains(Objects.toString(candidate.onUnavailable(),"")))
                    throw new IllegalArgumentException("Quellenprofil: ungültiger/doppelter Kandidat oder fehlende Ausfallregel");
                if(candidate.provider().startsWith("builtin:")){
                    if(chain.regional())throw new IllegalArgumentException("Native Ersatzquellen gehören bei regionalen Registern in die separate Lückenliste");
                    var allowed=entry.getKey().equals("population.distribution")?PopulationSources.IDS:entry.getKey().equals("opportunities.work")?WorkplaceSources.IDS:List.<String>of();
                    if(!allowed.contains(candidate.provider().substring(8)))throw new IllegalArgumentException("Nativer Provider passt nicht zum Aspekt");
                    if(!chain.fallback().equals("none"))throw new IllegalArgumentException("Profile mit nativen Kandidaten benötigen explizite Ersatzkandidaten und fallback=none");
                }
            }
        }
    }
    public Profile prepare(Definition definition)throws IOException {
        validate(definition);
        // Snapshot mutable request maps/lists via serialization before hashing and writing.
        byte[] bytes=json.writeValueAsBytes(definition);
        String id=ImportSupport.hash(bytes);
        return new Profile(id,json.readValue(bytes,Definition.class));
    }
    public synchronized Profile save(String scenario,Definition definition)throws IOException {
        var profile=prepare(definition);String id=profile.id();
        SourceFiles.immutable(SourceFiles.root(repo,scenario).resolve("profiles").resolve(id+".json"),json.writeValueAsBytes(profile));
        return profile;
    }
    public Profile read(String scenario,String id)throws IOException {
        Path path=SourceFiles.root(repo,scenario).resolve("profiles").resolve(SourceFiles.hash(id)+".json");
        var profile=decode(Files.readAllBytes(path));
        if(!id.equals(profile.id()))throw new IOException("Quellenprofil verändert");
        return profile;
    }
    public Profile decode(byte[] bytes)throws IOException {
        var profile=json.readValue(bytes,Profile.class);
        if (profile==null||profile.id()==null||!profile.id().equals(prepare(profile.definition()).id()))
            throw new IOException("Quellenprofil verändert");
        validate(profile.definition());return profile;
    }
    public List<Profile> list(String scenario)throws IOException {
        Path directory=SourceFiles.root(repo,scenario).resolve("profiles");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files=Files.list(directory)) {
            var result=new ArrayList<Profile>();
            for (var file:files.sorted().toList()) if (file.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
                result.add(read(scenario,file.getFileName().toString().replace(".json","")));
            return List.copyOf(result);
        }
    }
}
