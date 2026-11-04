package de.synthesis.data;

import de.synthesis.generation.pipeline.*;
import java.util.*;

/** Native Work weights only. Other purposes and the pipeline RNG are never modified. */
public final class WorkplaceSources {
    public static final List<String> IDS=List.of("osm-pois","synthetic");
    public static final long SYNTHETIC_SEED_SALT=0x574f524b5f56314cL;
    public record Value(String provider,Map<Integer,Double> weights,SourceProvenance.Reference source,
                        Map<String,Object> parameters) { }
    private WorkplaceSources(){ }
    public static Map<String,Object> apply(List<SourceProfiles.Candidate> candidates,List<Zone> zones,
            List<OverpassClient.Poi> pois,long seed,String osmOrigin,String osmHash){
        var resolution=resolve(candidates,zones,pois,seed,osmOrigin,osmHash);
        var report=report(resolution.value(),resolution.candidates(),zones,null);
        for(var zone:zones)zone.attractivities.put("Work",resolution.value().weights().get(zone.index));
        return report;
    }
    public static ProviderChain.Resolution<Value> resolve(List<SourceProfiles.Candidate> candidates,List<Zone> zones,
            List<OverpassClient.Poi> pois,long seed,String osmOrigin,String osmHash){
        ProviderChain.validate(candidates,IDS);
        return ProviderChain.resolve(candidates,providers(zones,pois,seed,osmOrigin,osmHash),value->validate(value,zones));
    }
    public static List<ProviderChain.Provider<Value>> providers(List<Zone> zones,List<OverpassClient.Poi> pois,long seed,String osmOrigin,String osmHash){
        return List.of(
            new ProviderChain.Provider<Value>("osm-pois",()->{
                if(pois.isEmpty())return ProviderChain.Attempt.missing("not-applicable","no-osm-pois");
                // Stage 3 has already applied the unchanged POI classifier to all purposes.
                var weights=new TreeMap<Integer,Double>();for(var zone:zones)weights.put(zone.index,zone.attractivities.get("Work"));
                return ProviderChain.Attempt.available(new Value("osm-pois",weights,
                    new SourceProvenance.Reference("builtin",null,osmHash,"OSM-Arbeitsgewichte",osmOrigin,"","ODbL","OpenStreetMap contributors"),
                    Map.of("modelVersion","osm-work-weights-v1","poiCount",pois.size(),"coefficients","AttractivityCalculator.classify; model weights, not observed workplace counts")));
            }),
            new ProviderChain.Provider<Value>("synthetic",()->{
                // Reuse the native algorithm on private zones and a separate deterministic stream.
                var copies=new ArrayList<Zone>();for(var original:zones){var z=new Zone();z.index=original.index;z.row=original.row;z.col=original.col;z.areaKm2=original.areaKm2;copies.add(z);}
                long derivedSeed=seed^SYNTHETIC_SEED_SALT;
                AttractivityCalculator.synthetic(copies,new Random(derivedSeed));
                var weights=new TreeMap<Integer,Double>();for(var zone:copies)weights.put(zone.index,zone.attractivities.get("Work"));
                return ProviderChain.Attempt.available(new Value("synthetic",weights,
                    new SourceProvenance.Reference("model",null,null,"Synthetische Arbeitsgewichte","synthetic-work-weights-v1","","unknown",""),
                    Map.of("modelVersion","synthetic-work-weights-v1","seed",derivedSeed,"seedRule","scenario.population.seed XOR 0x574f524b5f56314c","baseWeightPerKm2",400,"gaussianSigma",0.5,"centralitySlope",0.8)));
            })
        );
    }
    public static void validate(Value value,List<Zone> zones){
        if(value.weights().size()!=zones.size())throw new IllegalArgumentException("Arbeitsgewichte passen nicht zur Zonierung");
        for(var zone:zones){Double w=value.weights().get(zone.index);if(w==null||!Double.isFinite(w)||w<0)throw new IllegalArgumentException("Ungültige Arbeitsgewichte");}
    }
    public static Map<String,Object> report(Value value,List<Map<String,Object>> candidates,List<Zone> zones,String forcedRole){
        var scope=new SourceProvenance.Scope("Work",zones.stream().map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList());
        boolean synthetic=value.provider().equals("synthetic");
        String role=forcedRole!=null?forcedRole:ProviderChain.status(candidates).equals("resolved-with-fallback")?"fallback":"primary";
        var nodes=List.of(
            new SourceProvenance.Node("input",synthetic?"model-profile":"source",synthetic?"assumed":"unknown",role,value.source(),List.of(),scope,value.parameters()),
            new SourceProvenance.Node("weights",synthetic?"synthetic-work-weights":"poi-to-work-weights",synthetic?"assumed":"derived",role,null,List.of("input"),scope,Map.of("unit","model-weight","observedJobTotalAvailable",false)));
        var report=new TreeMap<String,Object>();report.put("contract","synthesis-workplace-resolution-v1");report.put("provider",value.provider());report.put("candidates",candidates);
        report.put("status",ProviderChain.status(candidates));
        report.put("unit","model-weight");report.put("sumWeights",zones.stream().mapToDouble(z->value.weights().get(z.index)).sum());
        report.put("provenance",new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","weights",nodes));
        report.put("zoneWeights",zones.stream().map(z->Map.of("externalId",z.id,"modelIndex",z.index,"weight",value.weights().get(z.index))).toList());
        return Collections.unmodifiableMap(report);
    }
}
