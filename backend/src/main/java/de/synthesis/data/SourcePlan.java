package de.synthesis.data;

import java.util.*;

/** Opt-in source composition; absent/disabled retains all existing pipeline decisions. */
public class SourcePlan {
    public boolean enabled=false;
    /** Technical intent guard; analysis mode does not by itself certify empirical quality. */
    public String qualityMode="exploration";
    public Map<String,Policy> aspects=new TreeMap<>();
    public static final Set<String> ASPECTS=Set.of("population.distribution","opportunities.work","freight.vehicleOd");
    public static class Policy {
        /** legacy, dataset, profile, builtins (population/work), excluded/model (freight only). */
        public String source="legacy";
        /** Ordered, explicit population providers; only used by source=builtins. */
        public List<SourceProfiles.Candidate> providers=new ArrayList<>();
        /** Explicit native fillers for uncovered Work zones; only used by gaps=providers. */
        public List<SourceProfiles.Candidate> gapProviders=new ArrayList<>();
        public String revision;
        /** Immutable source-profile hash, distinct from an import mapping profile. */
        public String profile;
        /** legacy or none: fill only uncovered workplace areas; population raster must cover study area. */
        public String gaps="legacy";
        public int internalTrips=0,inboundTrips=0,outboundTrips=0,throughTrips=0;
        public String vehicleClass="truck";
        public boolean demonstrationAccepted=false;
        public String assumptionStatement="";
    }
    public Policy policy(String aspect){return aspects==null?new Policy():aspects.getOrDefault(aspect,new Policy());}
    public static void validate(SourcePlan plan){
        if(plan==null)return;if(!Set.of("exploration","analysis").contains(Objects.toString(plan.qualityMode,"")))throw new IllegalArgumentException("Unbekannter Auswertungszweck");if(plan.aspects==null||plan.aspects.size()>ASPECTS.size())throw new IllegalArgumentException("Ungültiger Quellenplan");
        for(var e:plan.aspects.entrySet()){
            var p=e.getValue();if(!ASPECTS.contains(e.getKey())||p==null||!Set.of("legacy","dataset","profile","excluded","model","builtins").contains(Objects.toString(p.source,""))||!Set.of("legacy","none","providers").contains(Objects.toString(p.gaps,"")))throw new IllegalArgumentException("Unbekannter Aspekt oder Quellenmodus");
            if(p.gaps.equals("providers")){
                if(!Set.of("opportunities.work","population.distribution").contains(e.getKey())||!Set.of("dataset","profile").contains(p.source))throw new IllegalArgumentException("Explizite Lückenfüller benötigen Register-/Rasterdaten oder ein Quellenprofil");
                ProviderChain.validate(p.gapProviders,e.getKey().equals("opportunities.work")?WorkplaceSources.IDS:MaskedPopulation.MODELS);
            }
            if(p.source.equals("builtins")){
                if(e.getKey().equals("population.distribution"))PopulationSources.validate(p.providers);
                else if(e.getKey().equals("opportunities.work"))ProviderChain.validate(p.providers,WorkplaceSources.IDS);
                else throw new IllegalArgumentException("Einzelne Bestandsprovider werden bisher nur für Bevölkerung und Arbeitsgewichte unterstützt");
            }
            if(!e.getKey().equals("freight.vehicleOd")&&Set.of("excluded","model").contains(p.source))throw new IllegalArgumentException("Dieser Aspekt unterstützt weder Ausschluss noch Güterverkehrs-Ersatzmodell");
            if(plan.enabled&&p.source.equals("dataset")&&(p.revision==null||!p.revision.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("Eine konkrete Datenrevision auswählen");
            if(plan.enabled&&p.source.equals("profile")&&(p.profile==null||!p.profile.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("Eine konkrete Quellenprofilversion auswählen");
            long sum=(long)p.internalTrips+p.inboundTrips+p.outboundTrips+p.throughTrips;
            if(p.internalTrips<0||p.inboundTrips<0||p.outboundTrips<0||p.throughTrips<0||sum>200000||!Set.of("truck","trailer").contains(Objects.toString(p.vehicleClass,"")))throw new IllegalArgumentException("Ungültige Güterverkehrs-Ersatzmengen oder Fahrzeugklasse");
            if(plan.enabled&&p.source.equals("model")&&sum==0)throw new IllegalArgumentException("Schwerlast-Ersatzmodell benötigt explizite Mengen (Binnen/Quell/Ziel/Durchgang)");
            if(plan.enabled&&p.source.equals("model")){
                if(!plan.qualityMode.equals("exploration"))throw new IllegalArgumentException("Technische Lkw-Mengendemonstration ist für fachliche Auswertungen nicht freigegeben");
                if(!p.demonstrationAccepted||p.assumptionStatement==null||p.assumptionStatement.strip().length()<10||p.assumptionStatement.length()>1000)throw new IllegalArgumentException("Lkw-Mengendemonstration ausdrücklich für Exploration freigeben und ihre Annahmen beschreiben");
            }
        }
    }
}
