package de.synthesis.data;

import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.*;
import java.util.*;

/** Municipality totals remain observations at their source level; point weights are an explicit model. */
public final class MunicipalWorkplaces {
    private MunicipalWorkplaces() { }
    public static void validateBinding(DataImportService.Revision totals,DataImportService.Revision allocation) {
        if(totals.data().metadata().geography().allocationMethod().equals("uniform-area-v1")){MunicipalAreas.validateBinding(totals,allocation);return;}
        if(allocation==null||!allocation.data().schemaId().equals("municipality-allocation-v1"))
            throw new IllegalArgumentException("Revision der räumlichen Gemeindegewichte fehlt oder hat den falschen Vertrag");
        var first=totals.data().metadata().geography();var second=allocation.data().metadata().geography();
        if(second==null||!Objects.equals(first.codeSystem(),second.codeSystem())||!Objects.equals(first.edition(),second.edition())
                ||!Objects.equals(first.allocationMethod(),second.allocationMethod()))
            throw new IllegalArgumentException("Schlüsselraum, Gebietsstand und Verteilungsmodell müssen für Gemeindeaggregate und Gewichte übereinstimmen");
        var keys=new HashSet<String>();for(var row:allocation.data().rows())keys.add(row.values().get("ags").toString());
        for(var row:totals.data().rows())if(!keys.contains(row.values().get("ags").toString()))
            throw new IllegalArgumentException("Keine räumliche Zuordnung für AGS "+row.values().get("ags"));
        var requested=new HashSet<String>();for(var row:totals.data().rows())requested.add(row.values().get("ags").toString());
        for(var row:allocation.data().rows())if(requested.contains(row.values().get("ags").toString())
                &&!ImportAdapters.contains(totals.data().metadata().coverage(),n(row,"lon"),n(row,"lat")))
            throw new IllegalArgumentException("Zuordnungsort liegt außerhalb des deklarierten Aggregatgebiets");
    }
    private static double n(AspectImportAdapter.DataRow row,String field){return ((Number)row.values().get(field)).doubleValue();}
    public static Map<String,Object> apply(DataImportService.Revision totals,DataImportService.Revision allocation,
            SourcePlan.Policy policy,List<Zone> zones){
        validateBinding(totals,allocation);
        if(totals.data().metadata().geography().allocationMethod().equals("uniform-area-v1"))return MunicipalAreas.apply(totals,allocation,policy,zones);
        var groups=new TreeMap<String,List<AspectImportAdapter.DataRow>>();
        for(var row:allocation.data().rows())groups.computeIfAbsent(row.values().get("ags").toString(),key->new ArrayList<>()).add(row);
        double[] values=new double[zones.size()];Set<Integer> assigned=new HashSet<>();
        var rows=new ArrayList<Map<String,Object>>();var quantities=new ArrayList<Map<String,Object>>();
        double input=0,applied=0;int outsideRows=0;
        for(var total:totals.data().rows()){
            String ags=total.values().get("ags").toString();double jobs=n(total,"value");input+=jobs;
            var points=groups.get(ags);double denominator=points.stream().mapToDouble(row->n(row,"weight")).sum();
            double represented=0;
            for(var point:points){
                double share=n(point,"weight")/denominator,quantity=jobs*share;
                int index=GridZoning.zoneIndexOf(zones,n(point,"lat"),n(point,"lon"));
                Zone zone=index<0?null:zones.get(index);boolean included=zone!=null&&!zone.external;
                if(included){values[zone.index]+=quantity;assigned.add(zone.index);represented+=quantity;}else outsideRows++;
                var row=new TreeMap<String,Object>();row.put("ags",ags);row.put("aggregateId",total.values().get("id"));row.put("aggregateRow",total.sourceRow());
                row.put("allocationId",point.values().get("id"));row.put("allocationRow",point.sourceRow());row.put("weight",n(point,"weight"));row.put("share",share);row.put("quantity",quantity);
                row.put("status",included?"applied":"outside-model");row.put("zone",included?new SourceProvenance.ZoneRef(zone.id,zone.index):null);rows.add(Collections.unmodifiableMap(row));
            }
            applied+=represented;
            quantities.add(Map.of("ags",ags,"sourceRow",total.sourceRow(),"inputJobs",jobs,"appliedJobs",represented,"outsideJobs",Math.max(0,jobs-represented),"weightDenominator",denominator));
        }
        var covered=new ArrayList<SourceProvenance.ZoneRef>();var fallback=new ArrayList<SourceProvenance.ZoneRef>();
        for(var zone:zones)if(!zone.external)(assigned.contains(zone.index)?covered:fallback).add(new SourceProvenance.ZoneRef(zone.id,zone.index));
        if(!fallback.isEmpty()&&policy.gaps.equals("none"))throw new IllegalArgumentException("Gemeindegewichte weisen nicht jeder Modellzone einen Wert zu; Lückenregel erforderlich");
        // No polygon or bbox is used to invent zero coverage. Only explicitly assigned zones change.
        for(var zone:zones)if(assigned.contains(zone.index))zone.attractivities.put("Work",values[zone.index]);
        var result=new TreeMap<String,Object>();result.put("contract","synthesis-municipal-workplace-allocation-v1");
        result.put("revision",totals.id());result.put("allocationRevision",allocation.id());result.put("geography",totals.data().metadata().geography());
        result.put("inputJobs",input);result.put("appliedJobs",applied);result.put("outsideJobs",Math.max(0,input-applied));result.put("unresolvedJobs",0);
        result.put("coveredZones",covered.size());result.put("fallbackZones",fallback.size());result.put("outsideAllocationRows",outsideRows);
        result.put("coverageDenominator","internal model zones explicitly assigned by allocation points; not municipal polygon coverage");
        result.put("allocation","explicit-point-weights-v1; normalized across ALL supplied points for each AGS, including points outside the model");
        result.put("municipalities",List.copyOf(quantities));result.put("rowAssignments",List.copyOf(rows));
        result.put("provenance",provenance(totals,allocation,covered,fallback));return Collections.unmodifiableMap(result);
    }
    static SourceProvenance provenance(DataImportService.Revision totals,DataImportService.Revision allocation,
            List<SourceProvenance.ZoneRef> covered,List<SourceProvenance.ZoneRef> fallback){
        var scope=new SourceProvenance.Scope("Work",covered);var nodes=new ArrayList<SourceProvenance.Node>();
        boolean area=totals.data().metadata().geography().allocationMethod().equals("uniform-area-v1");
        String referenceId=area?"municipal-geometries":"allocation-weights";
        nodes.add(source("municipal-totals",totals,scope));nodes.add(source(referenceId,allocation,scope));
        nodes.add(new SourceProvenance.Node("municipal-allocation",totals.data().metadata().geography().allocationMethod(),"assumed","primary",null,
                List.of("municipal-totals",referenceId),scope,Map.of("normalization",area?"full municipality polygon area in EPSG:3035":"all points per AGS, including outside-model points","unit","jobs","geography",totals.data().metadata().geography())));
        var rootInputs=new ArrayList<>(List.of("municipal-allocation"));
        if(!fallback.isEmpty()){
            var ref=new SourceProvenance.Reference("legacy",null,null,"Bisherige Arbeitsplatzgewichte","legacy-delegation",null,"unknown","");
            nodes.add(new SourceProvenance.Node("legacy-work","legacy-delegation","unknown","fallback",ref,List.of(),new SourceProvenance.Scope("Work",fallback),Map.of("unit","model-weight")));rootInputs.add("legacy-work");
        }
        var all=new ArrayList<>(covered);all.addAll(fallback);
        nodes.add(new SourceProvenance.Node("work-weights",fallback.isEmpty()?"choose":"fill-gaps","derived","composition",null,rootInputs,
                new SourceProvenance.Scope("Work",all),Map.of("quantitiesComparable",fallback.isEmpty(),"semantics","model weights; no observed point locations inferred")));
        return new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","work-weights",nodes);
    }
    private static SourceProvenance.Node source(String id,DataImportService.Revision revision,SourceProvenance.Scope scope){
        var data=revision.data();var meta=data.metadata();var reference=new SourceProvenance.Reference("dataset",revision.id(),data.sourceHash(),meta.label(),meta.source(),meta.referenceDate(),meta.licence(),meta.attribution());
        return new SourceProvenance.Node(id,"source","unknown","primary",reference,List.of(),scope,Map.of("schema",data.schemaId(),"geography",meta.geography(),"mapping",data.mapping()));
    }
}
