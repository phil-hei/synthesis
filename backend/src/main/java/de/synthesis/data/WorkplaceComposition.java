package de.synthesis.data;

import de.synthesis.data.importing.DataImportService;
import de.synthesis.generation.pipeline.*;
import java.util.*;

/** Atomic regional composition: declared register zones plus explicitly scoped native fillers. */
public final class WorkplaceComposition {
    private record Choice(DataImportService.Revision revision,WorkplaceSources.Value nativeValue) { }
    private WorkplaceComposition(){ }
    public static Map<String,Object> apply(SourceResolver.Inputs inputs,SourcePlan plan,List<Zone> zones,
            List<OverpassClient.Poi> pois,long seed,String osmOrigin,String osmHash){
        var policy=plan.policy("opportunities.work");var mixed=inputs.chains().get("opportunities.work");
        if(mixed!=null&&mixed.profile().definition().aspects().get("opportunities.work").regional())return RegionalWorkplaces.apply(inputs,plan,zones,pois,seed,osmOrigin,osmHash);
        List<Map<String,Object>> candidates=List.of();DataImportService.Revision revision=inputs.datasets().get("opportunities.work");
        if(mixed!=null){
            List<ProviderChain.Provider<Choice>> natives=WorkplaceSources.providers(zones,pois,seed,osmOrigin,osmHash).stream().map(p->new ProviderChain.Provider<Choice>(p.id(),()->{
                var a=p.loader().load();return new ProviderChain.Attempt<>(a.value()==null?null:new Choice(null,a.value()),a.status(),a.reason());
            })).toList();
            var resolved=MixedSources.resolve(mixed,natives,r->new Choice(r,null),choice->{if(choice.nativeValue()!=null)WorkplaceSources.validate(choice.nativeValue(),zones);});
            candidates=resolved.candidates();revision=resolved.value().revision();
            if(revision==null){
                var report=new TreeMap<String,Object>(WorkplaceSources.report(resolved.value().nativeValue(),candidates,zones,null));
                if(policy.gaps.equals("providers")){report.put("gapPolicy","providers");report.put("gapResolution",Map.of("status","not-required","reason","native-primary-covers-all-model-zones"));}
                for(var zone:zones)zone.attractivities.put("Work",resolved.value().nativeValue().weights().get(zone.index));
                return Collections.unmodifiableMap(report);
            }
        }
        if(revision==null)return Map.of();
        var copies=copy(zones);
        var selected=new SourceResolver.Inputs(Map.of("opportunities.work",revision),List.of(),inputs.references());
        final Map<String,Object> primary;
        try{primary=SourceResolver.applyWorkplaces(selected,plan,copies);}
        catch(IllegalArgumentException invalid){
            var errors=new ArrayList<Map<String,Object>>();
            if(candidates.isEmpty())errors.add(Map.of("provider","revision:"+revision.id(),"rank",1,"status","invalid","reason",invalid.getMessage(),"onUnavailable","fail"));
            else for(var c:candidates){var d=new TreeMap<>(c);if("selected".equals(c.get("status"))){d.put("status","invalid");d.put("reason",invalid.getMessage());}errors.add(d);}
            throw new ProviderChain.Failure("Arbeitsplatzzuordnung ungültig: "+invalid.getMessage(),errors);
        }
        var report=new TreeMap<String,Object>(primary);if(!candidates.isEmpty())report.put("candidates",candidates);
        if(policy.gaps.equals("providers")){
            var provenance=(SourceProvenance)primary.get("provenance");
            var legacy=provenance.nodes().stream().filter(n->n.id().equals("legacy-work")).findFirst();
            if(legacy.isEmpty())report.put("gapResolution",Map.of("status","not-required","candidates",java.util.stream.IntStream.range(0,policy.gapProviders.size()).mapToObj(i->{var c=policy.gapProviders.get(i);return Map.of("provider",c.provider(),"rank",i+1,"status",c.excluded()?"excluded":"not-evaluated","reason",c.excluded()?"explicitly-excluded":"no-uncovered-zones","onUnavailable",c.onUnavailable());}).toList()));
            else{
                var gap=WorkplaceSources.resolve(policy.gapProviders,zones,pois,seed,osmOrigin,osmHash);
                Set<Integer> uncovered=new HashSet<>();for(var z:legacy.get().scope().zones())uncovered.add(z.modelIndex());
                var gapZones=copies.stream().filter(z->uncovered.contains(z.index)).toList();
                for(var zone:gapZones)zone.attractivities.put("Work",gap.value().weights().get(zone.index));
                var gapReport=WorkplaceSources.report(gap.value(),gap.candidates(),gapZones,"fallback");
                report.put("gapResolution",gapReport);report.put("provenance",replaceLegacy(provenance,(SourceProvenance)gapReport.get("provenance")));
            }
            report.put("compositionContract","synthesis-workplace-composition-v1");report.put("gapPolicy","providers");
        }
        // No mutation of actual zones until every selected contribution and report is valid.
        for(int i=0;i<zones.size();i++)zones.get(i).attractivities.put("Work",copies.get(i).attractivities.get("Work"));
        return Collections.unmodifiableMap(report);
    }
    private static SourceProvenance replaceLegacy(SourceProvenance primary,SourceProvenance gap){
        var nodes=new ArrayList<SourceProvenance.Node>();String root="gap/"+gap.root();
        for(var n:primary.nodes()){
            if(n.id().equals("legacy-work")){
                for(var g:gap.nodes())nodes.add(new SourceProvenance.Node("gap/"+g.id(),g.operation(),g.evidence(),"fallback",g.source(),g.inputs().stream().map(i->"gap/"+i).toList(),g.scope(),g.parameters()));
            }else nodes.add(new SourceProvenance.Node(n.id(),n.operation(),n.evidence(),n.selectionRole(),n.source(),n.inputs().stream().map(i->i.equals("legacy-work")?root:i).toList(),n.scope(),n.parameters()));
        }
        return new SourceProvenance(primary.contract(),primary.aspect(),primary.root(),nodes);
    }
    static List<Zone> copy(List<Zone> zones){
        var result=new ArrayList<Zone>();for(var a:zones){var b=new Zone();b.index=a.index;b.id=a.id;b.name=a.name;b.row=a.row;b.col=a.col;b.minLat=a.minLat;b.maxLat=a.maxLat;b.minLon=a.minLon;b.maxLon=a.maxLon;b.centroidLat=a.centroidLat;b.centroidLon=a.centroidLon;b.areaKm2=a.areaKm2;b.laeaE=a.laeaE;b.laeaN=a.laeaN;b.laeaRes=a.laeaRes;b.population=a.population;b.households=a.households;b.external=a.external;b.attractivities.putAll(a.attractivities);result.add(b);}return result;
    }
}
