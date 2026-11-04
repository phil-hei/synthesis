package de.synthesis.data;

import de.synthesis.data.importing.DataImportService;
import de.synthesis.generation.pipeline.Zone;
import de.synthesis.scenario.Scenario;
import java.util.*;

/** Manual total anchors only the unknown residual; known zero cells remain untouched. */
public final class MaskedPopulation {
    public static final List<String> MODELS=List.of("osm-places","poi-density","uniform");
    public static final long SEED_SALT=0x504f505f4d41534bL;
    private record Gap(PopulationSources.Value value,double[] weights,double denominator,String provider) { }
    private MaskedPopulation(){ }
    public static PopulationSources.Value value(DataImportService.Revision revision,Scenario scenario,List<Zone> zones,List<PopulationSources.Provider> providers){
        if(scenario==null)throw new IllegalArgumentException("Bevölkerungsraster mit Wertstatus benötigt den Szenariokontext für Mengenanker und Lückenregel");
        var a=MaskedCells.allocate(revision,zones);var meta=revision.data().metadata();String source="dataset:"+revision.id();
        double known=((Number)a.report().get("knownApplied")).doubleValue();double[] weights=a.counts().clone();
        for(var z:zones)if(!a.covered().contains(z.index))weights[z.index]=0;
        var valid=new SourceProvenance.Scope("residents",zones.stream().filter(z->a.covered().contains(z.index)).map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList());
        var all=new SourceProvenance.Scope("residents",zones.stream().filter(z->!z.external).map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList());
        var nodes=new ArrayList<SourceProvenance.Node>();nodes.add(MaskedCells.source(revision,valid));
        nodes.add(new SourceProvenance.Node("known-allocation","uniform-source-cell-area","assumed","primary",null,List.of("cells"),valid,Map.of("unit","persons","crs","EPSG:3035")));
        var roots=new ArrayList<>(List.of("known-allocation"));Map<String,Object> gapReport=Map.of("status","not-required");double estimate=known;
        if(a.gaps().isEmpty()&&scenario.population.totalPersons!=null&&Math.abs(scenario.population.totalPersons-known)>1e-7)
            throw new IllegalArgumentException("Manuelle Gesamtzahl widerspricht dem vollständig bekannten Zellraster; Gesamtzahl anpassen oder automatische Summe verwenden");
        if(!a.gaps().isEmpty()){
            var policy=scenario.dataPlan.policy("population.distribution");
            if(!policy.gaps.equals("providers"))throw new IllegalArgumentException("Bevölkerungsraster enthält Maskenlücken; explizite Modellquellen und manuelle Gesamtzahl erforderlich");
            if(scenario.population.totalPersons==null)throw new IllegalArgumentException("Unbekannte Bevölkerungswerte benötigen eine manuelle Gesamtzahl als Mengenanker");
            double residual=scenario.population.totalPersons-known;
            if(residual < -1e-7)throw new IllegalArgumentException("Manuelle Gesamtzahl liegt unter der bereits bekannten Bevölkerung in vollständig belegten Zonen");
            residual=Math.max(0,residual);estimate=scenario.population.totalPersons;
            nodes.add(new SourceProvenance.Node("total-anchor","manual-total","assumed","primary",null,List.of(),all,Map.of("persons",estimate,"knownApplied",known,"residualPersons",residual)));
            var unknown=new SourceProvenance.Scope("residents",a.gaps());
            if(residual==0){
                nodes.add(new SourceProvenance.Node("mask-fill","zero-residual-by-manual-total","assumed","fallback",null,List.of("total-anchor"),unknown,Map.of("persons",0)));
                gapReport=Map.of("status","not-required-zero-residual","persons",0);
            }else{
                var gap=resolve(policy.gapProviders,providers,zones,a.gaps(),scenario.population.seed);double amount=residual;
                for(var z:a.gaps())weights[z.modelIndex()]=amount*gap.value().weights()[z.modelIndex()]/gap.value().denominator();
                var info=gap.value().value().info();var ref=new SourceProvenance.Reference(gap.value().provider().equals("uniform")?"model":"builtin",null,info.sha256(),gap.value().provider().equals("uniform")?"Räumliche Ersatzgewichte ohne Ortsdaten":info.label(),info.source(),info.referenceDate(),info.license(),info.attribution());
                nodes.add(new SourceProvenance.Node("model-input","source",gap.value().provider().equals("uniform")?"assumed":"unknown","fallback",ref,List.of(),unknown,Map.of("provider",gap.value().provider(),"version",info.version())));
                nodes.add(new SourceProvenance.Node("mask-fill","manual-residual-by-model-weights-v1","assumed","fallback",null,List.of("total-anchor","model-input"),unknown,Map.of("persons",amount,"modelWeightDenominator",gap.value().denominator(),"seed",scenario.population.seed^SEED_SALT,"seedRule","scenario.population.seed XOR 0x504f505f4d41534b")));
                gapReport=Map.of("status",ProviderChain.status(gap.candidates()),"provider",gap.value().provider(),"persons",amount,"candidates",gap.candidates(),"zonePersons",a.gaps().stream().map(z->Map.of("zone",z,"persons",weights[z.modelIndex()])).toList());
            }
            roots.add("mask-fill");
        }
        if(estimate<=0)throw new IllegalArgumentException("Bevölkerungsraster enthält keine positive bekannte Menge und keinen positiven Mengenanker");
        nodes.add(new SourceProvenance.Node("distribution",a.gaps().isEmpty()?"choose":"fill-gaps","derived","composition",null,roots,all,Map.of("unit","persons","knownZerosPreserved",true)));
        var diagnostics=new TreeMap<String,Object>();diagnostics.put("maskedCells",a.report());diagnostics.put("gapResolution",gapReport);diagnostics.put("distributionProvenance",new SourceProvenance(SourceProvenance.CONTRACT,"population.distribution","distribution",nodes));
        return new PopulationSources.Value(source,new PopulationSources.Info(meta.source(),meta.label(),revision.data().contract(),meta.referenceDate(),"",revision.data().sourceHash(),meta.licence(),meta.attribution()),estimate,source,weights,false,diagnostics);
    }
    private static ProviderChain.Resolution<Gap> resolve(List<SourceProfiles.Candidate> candidates,List<PopulationSources.Provider> providers,List<Zone> zones,List<SourceProvenance.ZoneRef> gaps,long seed){
        ProviderChain.validate(candidates,MODELS);
        List<ProviderChain.Provider<Gap>> adapters=providers.stream().filter(p->MODELS.contains(p.id())).map(p->new ProviderChain.Provider<Gap>(p.id(),()->{
            var a=p.loader().load();if(a.value()==null)return ProviderChain.Attempt.missing(a.status(),a.reason());
            double[] weights=a.value().weights();if(weights==null){
                weights=new double[zones.size()];var random=new Random(seed^SEED_SALT);Object counts=a.value().diagnostics().get("zonePoiCounts");
                for(var z:zones){double poi=counts instanceof List<?> list?((Number)list.get(z.index)).doubleValue():0;weights[z.index]=1+.5*random.nextDouble()+(p.id().equals("poi-density")?Math.min(poi,50)*.06:0);}
            }
            double sum=0;for(var z:gaps){double w=weights[z.modelIndex()];if(!Double.isFinite(w)||w<0)throw new IllegalArgumentException("Ungültige Modellgewichte für Maskenlücken");sum+=w;}
            if(sum<=0)return ProviderChain.Attempt.missing("not-applicable","no-positive-model-weight-in-mask");
            return ProviderChain.Attempt.available(new Gap(a.value(),weights,sum,p.id()));
        })).toList();
        return ProviderChain.resolve(candidates,adapters,value->{});
    }
}
