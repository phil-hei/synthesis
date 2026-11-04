package de.synthesis.data;

import de.synthesis.generation.pipeline.FreightDemandGenerator;
import de.synthesis.scenario.Scenario;
import java.util.*;

/** Relation-level generation ledger; routing and physical execution remain separate evidence. */
public final class FreightSources {
    public static final String CONTRACT="synthesis-freight-source-resolution-v1";
    public record Contribution(String relationId,Integer sourceRow,SourceProvenance.Reference source,
            String evidence,int inputVehicleTrips,int generatedVehicleTrips,
            FreightDemandGenerator.Relation relation) { }
    private FreightSources(){ }
    public static Map<String,Object> report(SourceResolver.Inputs inputs,Scenario scenario,List<FreightDemandGenerator.Trip> trips){
        var decision=inputs.decisions().stream().filter(d->"freight.vehicleOd".equals(d.get("aspect"))).findFirst().orElse(Map.of("source","legacy"));
        String selected=decision.get("source").toString();boolean assumed=selected.equals("model");
        var revision=inputs.datasets().get("freight.vehicleOd");var rows=new HashMap<String,Integer>();
        if(revision!=null)for(var row:revision.data().rows())rows.put(row.values().get("id").toString(),row.sourceRow());
        var relations=new TreeMap<String,FreightDemandGenerator.Relation>();
        if(scenario.freight.enabled)for(var relation:scenario.freight.relations)if(relations.put(relation.id(),relation)!=null)throw new IllegalArgumentException("Doppelte Schwerlastrelation im Quellenbericht");
        var byRelation=new TreeMap<String,Integer>();var movements=new TreeMap<String,Integer>();var ids=new HashSet<String>();
        for(var trip:trips){
            var r=relations.get(trip.relationId());
            if(r==null||!ids.add(trip.id())||!r.vehicleClass().equals(trip.vehicleClass())||!r.movement().equals(trip.movement())
                    ||trip.departSec()<Math.floor(r.beginHour()*3600)||trip.departSec()>=Math.ceil(r.endHour()*3600)
                    ||!Objects.equals(trip.fromEdge(),r.fromEdge())||!Objects.equals(trip.toEdge(),r.toEdge())
                    ||trip.fromLon()!=r.fromLon()||trip.fromLat()!=r.fromLat()||trip.toLon()!=r.toLon()||trip.toLat()!=r.toLat())
                throw new IllegalArgumentException("Schwerlastfahrt passt nicht zu ihrer Quellenrelation");
            byRelation.merge(r.id(),1,Integer::sum);movements.merge(r.movement(),1,Integer::sum);
        }
        var contributions=new ArrayList<Contribution>();long input=0;
        for(var r:relations.values()){
            int generated=byRelation.getOrDefault(r.id(),0);if(generated!=r.trips())throw new IllegalArgumentException("Schwerlastmengenbilanz weicht von der Quellenrelation ab");input+=r.trips();
            SourceProvenance.Reference ref;
            if(revision!=null){
                if(!rows.containsKey(r.id()))throw new IllegalArgumentException("Schwerlastrelation fehlt in der gewählten Revision");
                var m=revision.data().metadata();ref=new SourceProvenance.Reference("dataset",revision.id(),revision.data().sourceHash(),m.label(),m.source(),m.referenceDate(),m.licence(),m.attribution());
            }else ref=new SourceProvenance.Reference(assumed?"model":"legacy",null,null,assumed?"Technische Lkw-Mengendemonstration":r.source(),r.source(),r.referenceDate(),"unknown","");
            contributions.add(new Contribution(r.id(),rows.get(r.id()),ref,assumed?"assumed":"unknown",r.trips(),generated,r));
        }
        var result=new TreeMap<String,Object>();result.put("contract",CONTRACT);result.put("source",selected);
        result.put("status",selected.equals("excluded")?"excluded":scenario.freight.enabled?"generated":"not-enabled");
        result.put("unit","vehicle-trips/day");result.put("day",scenario.freight.day);result.put("inputVehicleTrips",input);result.put("generatedVehicleTrips",trips.size());result.put("byMovement",movements);result.put("contributions",contributions);
        result.put("scope","heavy-freight-only");result.put("independentContributions",List.of("person-demand","company-tours","parcel-logistics"));
        result.put("tripLedger","freight/trips.csv");result.put("tripJoin","relationId");
        result.put("departureProfile",Map.of("method","stratified-uniform-within-input-window","evidence","assumed","seed",scenario.population.seed,"seedRule","scenario.population.seed XOR 0x66726569676874 XOR relationId.hashCode()"));
        result.put("targetEvidence",Map.of("status","not-verified-by-generation-ledger","routing","sumo/traffic-routing.json","execution","sumo/traffic-execution.json"));
        if(decision.containsKey("profile"))result.put("profile",decision.get("profile"));
        if(assumed){result.put("scientificUse","not-approved");result.put("assumptionStatement",scenario.dataPlan.policy("freight.vehicleOd").assumptionStatement);}
        return Collections.unmodifiableMap(result);
    }
}
