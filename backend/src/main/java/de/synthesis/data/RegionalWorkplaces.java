package de.synthesis.data;

import de.synthesis.generation.pipeline.*;
import java.util.*;

/** Ordered, disjoint register contributions; overlapping values are shadowed, never summed. */
public final class RegionalWorkplaces {
    private RegionalWorkplaces(){ }
    public static Map<String,Object> apply(SourceResolver.Inputs inputs,SourcePlan plan,List<Zone> zones,
            List<OverpassClient.Poi> pois,long seed,String osmOrigin,String osmHash){
        var prepared=inputs.chains().get("opportunities.work");var policy=plan.policy("opportunities.work");
        var output=WorkplaceComposition.copy(zones);var remaining=new LinkedHashSet<Integer>();
        for(var z:zones)if(!z.external)remaining.add(z.index);
        var parts=new ArrayList<Map<String,Object>>();var decisions=new ArrayList<Map<String,Object>>();
        var nodes=new ArrayList<SourceProvenance.Node>();var roots=new ArrayList<String>();
        for(int i=0;i<prepared.candidates().size();i++){
            var row=prepared.candidates().get(i);var decision=new TreeMap<String,Object>();
            decision.put("provider",row.config().provider());decision.put("rank",i+1);decision.put("onUnavailable",row.config().onUnavailable());
            if(row.config().excluded()){decision.put("status","excluded");decision.put("reason","explicitly-excluded");decisions.add(decision);continue;}
            if(remaining.isEmpty()){decision.put("status","not-evaluated");decision.put("reason","higher-priority-registers-cover-all-zones");decisions.add(decision);continue;}
            if(row.revision()==null){decision.put("status",row.status());decision.put("reason",row.reason());decisions.add(decision);continue;}
            final Map<String,Object> allocation;var candidateZones=WorkplaceComposition.copy(zones);
            try{
                // A probe permits uncovered zones but never executes any native provider.
                var probePlan=new SourcePlan();var probePolicy=new SourcePlan.Policy();probePolicy.gaps="providers";probePlan.aspects.put("opportunities.work",probePolicy);
                allocation=SourceResolver.applyWorkplaces(new SourceResolver.Inputs(Map.of("opportunities.work",row.revision()),List.of(),inputs.references()),probePlan,candidateZones);
            }catch(IllegalArgumentException invalid){
                decision.put("status","invalid");decision.put("reason",invalid.getMessage());decisions.add(decision);throw new ProviderChain.Failure("Regionale Registerzuordnung ungültig: "+invalid.getMessage(),decisions);
            }
            var graph=(SourceProvenance)allocation.get("provenance");var legacy=graph.nodes().stream().filter(n->n.id().equals("legacy-work")).findFirst();
            Set<Integer> uncovered=new HashSet<>();legacy.ifPresent(n->n.scope().zones().forEach(z->uncovered.add(z.modelIndex())));
            var complete=zones.stream().filter(z->!z.external&&!uncovered.contains(z.index)).toList();
            var applied=complete.stream().filter(z->remaining.contains(z.index)).toList();
            var shadowed=complete.stream().filter(z->!remaining.contains(z.index)).toList();
            double appliedJobs=sum(candidateZones,applied),shadowedJobs=sum(candidateZones,shadowed);
            var ledger=new TreeMap<String,Object>();ledger.put("provider",row.config().provider());ledger.put("revision",row.revision().id());ledger.put("rank",i+1);ledger.put("unit","jobs");
            ledger.put("inputJobs",allocation.containsKey("inputJobs")?allocation.get("inputJobs"):row.revision().data().rows().stream().mapToDouble(r->((Number)r.values().get("value")).doubleValue()).sum());
            ledger.put("appliedJobs",appliedJobs);ledger.put("shadowedJobs",shadowedJobs);ledger.put("outsideJobs",outside(allocation));
            if(allocation.containsKey("quantityBasis"))ledger.put("quantityBasis",allocation.get("quantityBasis"));
            ledger.put("unappliedPartialJobs",number(allocation,"unappliedPartialJobs",number(allocation,"jobsInPartiallyCoveredZonesNotApplied",0)));
            ledger.put("appliedZones",refs(applied));ledger.put("shadowedZones",refs(shadowed));
            // Allocation diagnostics describe this source before priority masking, not final application.
            var diagnostics=new TreeMap<>(allocation);diagnostics.remove("provenance");ledger.put("allocationBeforePriority",diagnostics);
            if(ledger.values().stream().filter(Number.class::isInstance).map(Number.class::cast).anyMatch(n->!Double.isFinite(n.doubleValue())))
                throw new ProviderChain.Failure("Registermengen sind nicht endlich",List.of(Map.of("provider",row.config().provider(),"rank",i+1,"status","invalid","reason","non-finite-quantity","onUnavailable",row.config().onUnavailable())));
            if(applied.isEmpty()){decision.put("status","not-applicable");decision.put("reason",complete.isEmpty()?"no-complete-model-zone":"covered-by-higher-priority-registers");}
            else{
                String prefix="register-"+(i+1)+"/";String role=roots.isEmpty()?"primary":"fallback";
                appendGraph(nodes,roots,graph,prefix,applied,role);
                for(var z:applied){output.get(z.index).attractivities.put("Work",candidateZones.get(z.index).attractivities.get("Work"));remaining.remove(z.index);}
                decision.put("status","selected");decision.put("reason","regional-register-contribution");decision.put("appliedZones",applied.size());
            }
            parts.add(Collections.unmodifiableMap(ledger));decisions.add(Collections.unmodifiableMap(decision));
        }
        final Map<String,Object> gap;
        if(remaining.isEmpty())gap=Map.of("status","not-required");
        else if(!policy.gaps.equals("providers"))throw new ProviderChain.Failure("Regionale Register decken nicht jede Modellzone vollständig ab; explizite Lückenquellen erforderlich",decisions);
        else{
            var fill=WorkplaceSources.resolve(policy.gapProviders,zones,pois,seed,osmOrigin,osmHash);
            var targets=zones.stream().filter(z->remaining.contains(z.index)).toList();
            gap=WorkplaceSources.report(fill.value(),fill.candidates(),targets,"fallback");
            appendGraph(nodes,roots,(SourceProvenance)gap.get("provenance"),"gap/",targets,"fallback");
            for(var z:targets)output.get(z.index).attractivities.put("Work",fill.value().weights().get(z.index));
        }
        var all=zones.stream().filter(z->!z.external).toList();
        nodes.add(new SourceProvenance.Node("work-weights","fill-gaps","derived","composition",null,roots,new SourceProvenance.Scope("Work",refs(all)),
                Map.of("quantitiesComparable",false,"semantics","per-source ledgers; no combined observed job total","strategy","ordered-register-fill-gaps-v1")));
        var report=new TreeMap<String,Object>();report.put("contract","synthesis-regional-workplace-resolution-v1");report.put("strategy","fill-gaps");report.put("candidates",decisions);report.put("parts",parts);
        report.put("coveredZones",all.size()-remaining.size());report.put("fallbackZones",remaining.size());report.put("gapResolution",gap);report.put("gapPolicy",policy.gaps);report.put("unit","model-weight");
        report.put("status",remaining.isEmpty()?"resolved":"resolved-with-fallback");report.put("zoneWeights",output.stream().filter(z->!z.external).map(z->Map.of("externalId",z.id,"modelIndex",z.index,"weight",z.attractivities.get("Work"))).toList());
        report.put("provenance",new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","work-weights",nodes));
        for(int i=0;i<zones.size();i++)if(!zones.get(i).external)zones.get(i).attractivities.put("Work",output.get(i).attractivities.get("Work"));
        return Collections.unmodifiableMap(report);
    }
    private static double sum(List<Zone> candidate,List<Zone> selected){return selected.stream().mapToDouble(z->candidate.get(z.index).attractivities.get("Work")).sum();}
    private static List<SourceProvenance.ZoneRef> refs(List<Zone> zones){return zones.stream().map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList();}
    private static double number(Map<String,Object> report,String key,double fallback){return report.get(key) instanceof Number n?n.doubleValue():fallback;}
    private static double outside(Map<String,Object> allocation){
        if(allocation.containsKey("outsideJobs"))return number(allocation,"outsideJobs",0);
        if(allocation.get("rowAssignments") instanceof List<?> rows)return rows.stream().filter(SourceProvenance.RowAssignment.class::isInstance).map(SourceProvenance.RowAssignment.class::cast).filter(r->r.status().equals("outside-model")).mapToDouble(SourceProvenance.RowAssignment::quantity).sum();
        return 0;
    }
    private static void appendGraph(List<SourceProvenance.Node> target,List<String> roots,SourceProvenance source,String prefix,List<Zone> applied,String role){
        Set<Integer> selected=new HashSet<>();for(var z:applied)selected.add(z.index);
        var root=source.nodes().stream().filter(n->n.id().equals(source.root())).findFirst().orElseThrow();
        for(var n:source.nodes()){
            if(n.id().equals("legacy-work")||n.id().equals(source.root()))continue;
            var scope=new SourceProvenance.Scope(n.scope().purpose(),n.scope().zones().stream().filter(z->selected.contains(z.modelIndex())).toList());
            target.add(new SourceProvenance.Node(prefix+n.id(),n.operation(),n.evidence(),role,n.source(),n.inputs().stream().map(id->prefix+id).toList(),scope,n.parameters()));
        }
        // Native graphs have a meaningful root and must retain its model operation.
        if(root.inputs().contains("legacy-work")||root.operation().equals("choose")||root.id().equals("work-weights")){
            for(var id:root.inputs())if(!id.equals("legacy-work"))roots.add(prefix+id);
        }else{
            var scope=new SourceProvenance.Scope(root.scope().purpose(),root.scope().zones().stream().filter(z->selected.contains(z.modelIndex())).toList());
            target.add(new SourceProvenance.Node(prefix+root.id(),root.operation(),root.evidence(),role,root.source(),root.inputs().stream().map(id->prefix+id).toList(),scope,root.parameters()));roots.add(prefix+root.id());
        }
    }
}
