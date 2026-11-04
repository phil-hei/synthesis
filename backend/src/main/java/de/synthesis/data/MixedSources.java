package de.synthesis.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.data.importing.*;
import de.synthesis.scenario.*;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.function.Function;

/** Frozen reachable revisions plus deferred native candidates. No runtime file lookup for revisions. */
public final class MixedSources {
    public record Candidate(SourceProfiles.Candidate config,String status,String reason,DataImportService.Revision revision) { }
    public record Prepared(SourceProfiles.Profile profile,List<Candidate> candidates,Map<String,DataImportService.Revision> references) {
        public Prepared { candidates=List.copyOf(candidates);references=Collections.unmodifiableMap(new TreeMap<>(references)); }
    }
    private MixedSources(){ }
    public static boolean mixed(SourceProfiles.Profile profile,String aspect){
        var chain=profile.definition().aspects().get(aspect);
        if(chain==null)throw new IllegalArgumentException("Quellenprofil enthält den gewählten Aspekt nicht");
        return chain.regional()||chain.candidates().stream().anyMatch(c->c.provider().startsWith("builtin:"));
    }
    public static Prepared prepare(SourceProfiles.Profile profile,String aspect,Scenario scenario,ScenarioRepository repo,
            ObjectMapper mapper,ImportRegistry registry)throws IOException {
        var rows=new ArrayList<Candidate>();var references=new TreeMap<String,DataImportService.Revision>();boolean terminal=false;
        boolean regional=profile.definition().aspects().get(aspect).regional();
        for(var c:profile.definition().aspects().get(aspect).candidates()){
            if(c.excluded()){rows.add(new Candidate(c,"excluded","explicitly-excluded",null));continue;}
            if(terminal){rows.add(new Candidate(c,"not-evaluated","earlier-retained-revision-is-terminal",null));continue;}
            if(c.provider().startsWith("builtin:")){rows.add(new Candidate(c,"pending","requires-runtime-inputs",null));continue;}
            try{
                String[] ref=c.provider().split(":");
                var revision=ref[0].equals("revision")?DataImportService.readRevision(repo,mapper,scenario.id,ref[1]):new DataPacks(repo,mapper,registry).resolve(scenario.id,ref[1],ref[2]);
                SourceResolver.validateRevision(revision,registry);
                String mismatch=SourceResolver.applicability(scenario,aspect,revision,registry);
                if(mismatch!=null){rows.add(new Candidate(c,"not-applicable",mismatch,null));continue;}
                references.putAll(SourceResolver.resolveReferences(revision,scenario,repo,mapper,registry));
                rows.add(new Candidate(c,"staged","revision-and-references-frozen",revision));terminal=!regional;
            }catch(FileNotFoundException|NoSuchFileException missing){
                rows.add(new Candidate(c,"unavailable","retained-source-missing",null));
                if(c.onUnavailable().equals("fail"))throw failure("Potenzielle Profilquelle fehlt; Ausfallregel verlangt Abbruch",rows);
            }catch(IOException|IllegalArgumentException invalid){
                rows.add(new Candidate(c,"invalid",Objects.toString(invalid.getMessage(),"source-invalid"),null));
                throw failure("Potenzielle Profilquelle ist ungültig: "+invalid.getMessage(),rows);
            }
        }
        if(rows.stream().noneMatch(r->Set.of("pending","staged").contains(r.status()))&&!(regional&&scenario.dataPlan.policy(aspect).gaps.equals("providers")))throw failure("Keine verwendbare Profilquelle vorhanden",rows);
        return new Prepared(profile,rows,references);
    }
    private static ProviderChain.Failure failure(String message,List<Candidate> rows){return new ProviderChain.Failure(message,decisions(rows));}
    public static List<Map<String,Object>> decisions(List<Candidate> rows){
        return java.util.stream.IntStream.range(0,rows.size()).mapToObj(i->{var r=rows.get(i);return Map.<String,Object>of("provider",r.config().provider(),"rank",i+1,"status",r.status(),"reason",r.reason(),"onUnavailable",r.config().onUnavailable());}).toList();
    }
    public static <T> ProviderChain.Resolution<T> resolve(Prepared prepared,List<ProviderChain.Provider<T>> natives,Function<DataImportService.Revision,T> imported,java.util.function.Consumer<T> validator){
        Map<String,ProviderChain.Loader<T>> nativeLoaders=new HashMap<>();for(var p:natives)nativeLoaders.put(p.id(),p.loader());
        List<ProviderChain.Provider<T>> providers=prepared.candidates().stream().map(row->new ProviderChain.Provider<T>(row.config().provider(),()->{
            if(row.revision()!=null)return ProviderChain.Attempt.available(imported.apply(row.revision()));
            if(row.config().provider().startsWith("builtin:")&&row.status().equals("pending")){
                var loader=nativeLoaders.get(row.config().provider().substring(8));
                if(loader==null)throw new IllegalArgumentException("Nativer Provider nicht registriert");return loader.load();
            }
            return ProviderChain.Attempt.missing(row.status(),row.reason());
        })).toList();
        return ProviderChain.resolve(prepared.candidates().stream().map(Candidate::config).toList(),providers,validator);
    }
}
