package de.synthesis.data;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Shared lazy source selection, independent of a domain, transport or storage format. */
public final class ProviderChain {
    public record Attempt<T>(T value,String status,String reason) {
        public static <T> Attempt<T> available(T value){return new Attempt<>(value,"available","usable-input");}
        public static <T> Attempt<T> missing(String status,String reason){return new Attempt<>(null,status,reason);}
    }
    @FunctionalInterface public interface Loader<T> { Attempt<T> load()throws IOException,InterruptedException; }
    public record Provider<T>(String id,Loader<T> loader) { }
    public record Resolution<T>(T value,List<Map<String,Object>> candidates) { }
    public static final class Failure extends IllegalArgumentException {
        private final List<Map<String,Object>> candidates;
        Failure(String message,List<Map<String,Object>> candidates){super(message);this.candidates=List.copyOf(candidates);}
        public List<Map<String,Object>> candidates(){return candidates;}
    }
    private ProviderChain(){ }
    /** Exclusion is a choice, not evidence that a primary source failed. */
    public static String status(List<Map<String,Object>> candidates){
        return candidates.stream().anyMatch(c->Set.of("unavailable","not-applicable").contains(c.get("status")))?"resolved-with-fallback":"resolved";
    }
    public static void validate(List<SourceProfiles.Candidate> candidates,Collection<String> allowed){
        if(candidates==null||candidates.isEmpty()||candidates.size()>16)
            throw new IllegalArgumentException("1–16 Quellen in expliziter Reihenfolge erforderlich");
        Set<String> seen=new HashSet<>();
        for(var c:candidates)if(c==null||!allowed.contains(c.provider())||!seen.add(c.provider())
                ||!Set.of("fail","next").contains(Objects.toString(c.onUnavailable(),"")))
            throw new IllegalArgumentException("Unbekannte/doppelte Quelle oder fehlende Ausfallregel");
        if(candidates.stream().allMatch(SourceProfiles.Candidate::excluded))throw new IllegalArgumentException("Mindestens eine Quelle zulassen");
    }
    public static <T> Resolution<T> resolve(List<SourceProfiles.Candidate> candidates,List<Provider<T>> providers,Consumer<T> validator){
        Map<String,Loader<T>> loaders=new HashMap<>();
        for(var p:providers)if(loaders.putIfAbsent(p.id(),p.loader())!=null)throw new IllegalArgumentException("Doppelter Provider");
        validate(candidates,loaders.keySet());
        var decisions=new ArrayList<Map<String,Object>>();T selected=null;
        for(int i=0;i<candidates.size();i++){
            var c=candidates.get(i);String status,reason;
            if(c.excluded()){status="excluded";reason="explicitly-excluded";}
            else if(selected!=null){status="not-evaluated";reason="higher-priority-candidate-selected";}
            else{
                try{
                    var attempt=loaders.get(c.provider()).load();status=attempt.status();reason=attempt.reason();
                    if(attempt.value()!=null){selected=attempt.value();validator.accept(selected);status="selected";reason="first-usable-candidate";}
                    else if(!Set.of("unavailable","not-applicable").contains(status))throw new IllegalArgumentException("Ungültiger Providerzustand");
                }catch(RuntimeException invalid){
                    decisions.add(decision(c,i,"invalid",invalid.getMessage()));throw new Failure("Quelle ungültig: "+invalid.getMessage(),decisions);
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();decisions.add(decision(c,i,"unavailable","interrupted"));throw new Failure("Quellenauflösung unterbrochen",decisions);
                }catch(IOException unavailable){status="unavailable";reason=unavailable.getMessage();}
                if(status.equals("unavailable")&&c.onUnavailable().equals("fail")){
                    decisions.add(decision(c,i,status,reason));throw new Failure("Quelle nicht verfügbar; Ausfallregel verlangt Abbruch",decisions);
                }
            }
            decisions.add(decision(c,i,status,reason));
        }
        if(selected==null)throw new Failure("Keine zugelassene Quelle verwendbar; kein versteckter Ersatz",decisions);
        return new Resolution<>(selected,List.copyOf(decisions));
    }
    private static Map<String,Object> decision(SourceProfiles.Candidate c,int index,String status,String reason){
        return Map.of("provider",c.provider(),"rank",index+1,"status",status,"reason",Objects.toString(reason,"unknown"),"onUnavailable",c.onUnavailable());
    }
}
