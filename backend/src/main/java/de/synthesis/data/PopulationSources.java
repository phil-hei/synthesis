package de.synthesis.data;

import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.Scenario;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/** Lazy, ordered provider resolution. It never draws from the pipeline random stream. */
public final class PopulationSources {
    public static final List<String> IDS=List.of("upload-grid","census-2022","osm-places","poi-density","uniform");
    public record Info(String source,String label,String version,String referenceDate,String url,
                       String sha256,String license,String attribution) { }
    public record Value(String distribution,Info info,double estimatedTotal,String totalSource,
                        double[] weights,boolean smooth,Map<String,Object> diagnostics) {
        public Value { weights=weights==null?null:weights.clone();diagnostics=Map.copyOf(diagnostics); }
        @Override public double[] weights(){return weights==null?null:weights.clone();}
    }
    public record Attempt(Value value,String status,String reason) {
        public static Attempt missing(String status,String reason){return new Attempt(null,status,reason);}
        public static Attempt available(Value value){return new Attempt(value,"available","usable-input");}
    }
    @FunctionalInterface public interface Loader { Attempt load() throws IOException,InterruptedException; }
    public record Provider(String id,Loader loader) { }
    public record Resolution(Value value,List<Map<String,Object>> candidates) { }
    public static final class ResolutionException extends IllegalArgumentException {
        private final List<Map<String,Object>> candidates;
        ResolutionException(String message,List<Map<String,Object>> candidates){super(message);this.candidates=List.copyOf(candidates);}
        public List<Map<String,Object>> candidates(){return candidates;}
    }
    private PopulationSources(){ }

    public static void validate(List<SourceProfiles.Candidate> candidates){ProviderChain.validate(candidates,IDS);}
    public static Resolution resolve(List<SourceProfiles.Candidate> candidates,List<Provider> providers){
        validate(candidates);
        List<ProviderChain.Provider<Value>> adapters=providers.stream().map(p->new ProviderChain.Provider<Value>(p.id(),()->{
            try{var a=p.loader().load();return new ProviderChain.Attempt<>(a.value(),a.status(),a.reason());}
            catch(CensusPopulationSource.InvalidSourceException invalid){throw new IllegalArgumentException(invalid.getMessage(),invalid);}
        })).toList();
        try{var r=ProviderChain.resolve(candidates,adapters,PopulationSources::validateValue);return new Resolution(r.value(),r.candidates());}
        catch(ProviderChain.Failure failure){throw new ResolutionException(failure.getMessage(),failure.candidates());}
    }
    private static void validateValue(Value value){
        if(!Double.isFinite(value.estimatedTotal())||value.estimatedTotal()<0||value.estimatedTotal()>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Bevölkerung muss positiv und als Ganzzahl darstellbar sein");
        if(value.weights()!=null)for(double w:value.weights())if(!Double.isFinite(w)||w<0)throw new IllegalArgumentException("Ungültige Bevölkerungsgewichte");
    }
    public static Resolution resolveMixed(MixedSources.Prepared prepared,List<Provider> natives,List<Zone> zones){
        return resolveMixed(prepared,natives,zones,null);
    }
    public static Resolution resolveMixed(MixedSources.Prepared prepared,List<Provider> natives,List<Zone> zones,Scenario scenario){
        List<ProviderChain.Provider<Value>> adapters=natives.stream().map(p->new ProviderChain.Provider<Value>(p.id(),()->{
            try{var a=p.loader().load();return new ProviderChain.Attempt<>(a.value(),a.status(),a.reason());}
            catch(CensusPopulationSource.InvalidSourceException invalid){throw new IllegalArgumentException(invalid.getMessage(),invalid);}
        })).toList();
        try{var r=MixedSources.resolve(prepared,adapters,revision->revision.data().schemaId().equals(MaskedCells.POP)?MaskedPopulation.value(revision,scenario,zones,natives):fromRevision(revision,zones),PopulationSources::validateValue);return new Resolution(r.value(),r.candidates());}
        catch(ProviderChain.Failure failure){throw new ResolutionException(failure.getMessage(),failure.candidates());}
    }
    public static Resolution resolveRevision(de.synthesis.data.importing.DataImportService.Revision revision,Scenario scenario,List<Zone> zones,List<Provider> natives){
        try{return new Resolution(MaskedPopulation.value(revision,scenario,zones,natives),List.of(Map.of("provider","revision:"+revision.id(),"rank",1,"status","selected","reason","explicit-selection","onUnavailable","fail")));}
        catch(IllegalArgumentException invalid){throw new ResolutionException(invalid.getMessage(),List.of(Map.of("provider","revision:"+revision.id(),"rank",1,"status","invalid","reason",invalid.getMessage(),"onUnavailable","fail")));}
    }
    private static Value fromRevision(de.synthesis.data.importing.DataImportService.Revision revision,List<Zone> zones){
        var data=revision.data();double[] weights=new double[zones.size()];double mapped=0,outside=0;
        for(var row:data.rows()){
            var v=row.values();double amount=((Number)v.get("value")).doubleValue();int index=GridZoning.zoneIndexOf(zones,((Number)v.get("lat")).doubleValue(),((Number)v.get("lon")).doubleValue());
            if(index<0)outside+=amount;else{weights[index]+=amount;mapped+=amount;}
        }
        if(mapped<=0)throw new IllegalArgumentException("Ausgewählte Bevölkerungsrevision enthält keine positive Menge in den Modellzonen");
        var meta=data.metadata();String source="dataset:"+revision.id();
        return new Value(source,new Info(source,meta.label(),data.contract(),meta.referenceDate(),"",data.sourceHash(),meta.licence(),meta.attribution()),mapped,source,weights,false,
                Map.of("revision",revision.id(),"inputPopulation",mapped+outside,"mappedPopulation",mapped,"outsideModelPopulation",outside));
    }

    /** Native adapters retain their original units/algorithms. Unselected loaders have no side effects. */
    public static List<Provider> builtins(Scenario scenario,List<Zone> zones,List<OverpassClient.Poi> pois,
            List<OverpassClient.Place> allPlaces,Info osmInfo,Path upload,String expectedUploadHash,
            Path retainedUpload,CensusPopulationSource census,Path censusCache,Consumer<String> log){
        List<OverpassClient.Place> places=OverpassClient.populationPlaces(scenario.region,allPlaces);
        double densityTotal=zones.stream().mapToDouble(z->z.areaKm2).sum()*scenario.population.personsPerKm2;
        var assumed=new Info("density-estimate","Dichteannahme","density-v1","","","","unknown","");
        return List.of(
            new Provider("upload-grid",()->{
                if("missing".equals(expectedUploadHash))return Attempt.missing("unavailable","no-upload-at-queue-time");
                if(expectedUploadHash==null||!expectedUploadHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Eingabelock für Bevölkerungsupload fehlt");
                if(!Files.isRegularFile(upload))throw new IllegalArgumentException("Bevölkerungsupload seit Vorprüfung entfernt");
                Files.createDirectories(retainedUpload.getParent());
                try{Files.copy(upload,retainedUpload,StandardCopyOption.REPLACE_EXISTING);}
                catch(IOException unavailable){Files.deleteIfExists(retainedUpload);throw unavailable;}
                String actual=CensusPopulationSource.sha256(retainedUpload);
                if(!expectedUploadHash.equals(actual)){Files.deleteIfExists(retainedUpload);throw new IllegalArgumentException("Bevölkerungsupload seit Vorprüfung verändert");}
                var grid=PopulationGridParser.parseStrict(retainedUpload,scenario.region);
                var info=new Info("scenario-upload:population-grid.csv","Hochgeladenes Bevölkerungsraster",grid.format(),"","",actual,"unknown","");
                return grid(grid,zones,"upload-grid",info);
            }),
            new Provider("census-2022",()->{
                if(LocalDate.parse(census.referenceDate()).getYear()>scenario.referenceYear)return Attempt.missing("not-applicable","source-after-scenario-year");
                var resolved=census.resolveChecked(scenario.region,censusCache,log);
                if(resolved.isEmpty())return Attempt.missing("not-applicable","census-disabled-outside-coverage-or-empty");
                var p=resolved.get().provenance();
                if(LocalDate.parse(p.referenceDate()).getYear()>scenario.referenceYear)return Attempt.missing("not-applicable","source-after-scenario-year");
                return grid(resolved.get().grid(),zones,"census-2022",new Info(p.source(),"Destatis Zensus 2022",p.version(),p.referenceDate(),p.url(),p.archiveSha256(),p.license(),p.attribution()));
            }),
            new Provider("osm-places",()->places.isEmpty()?Attempt.missing("not-applicable","no-population-places"):
                Attempt.available(new Value("osm-places",osmInfo,PopulationEstimator.estimateForRegion(scenario.region,places),"osm-places-estimate",PopulationEstimator.zoneWeights(zones,places),true,Map.of("places",places.size())))),
            new Provider("poi-density",()->pois.isEmpty()?Attempt.missing("not-applicable","no-pois"):
                Attempt.available(new Value("poi-density",osmInfo,densityTotal,"density-estimate",null,true,Map.of("pois",pois.size(),"personsPerKm2",scenario.population.personsPerKm2,"zonePoiCounts",poiCounts(zones,pois))))),
            new Provider("uniform",()->Attempt.available(new Value("uniform-noise",assumed,densityTotal,"density-estimate",null,true,Map.of("personsPerKm2",scenario.population.personsPerKm2))))
        );
    }
    private static List<Integer> poiCounts(List<Zone> zones,List<OverpassClient.Poi> pois){Integer[] counts=new Integer[zones.size()];Arrays.fill(counts,0);for(var poi:pois){int index=GridZoning.zoneIndexOf(zones,poi.lat(),poi.lon());if(index>=0)counts[index]++;}return List.of(counts);}
    private static Attempt grid(PopulationGridParser.ParseResult grid,List<Zone> zones,String source,Info info){
        double[] weights=new double[zones.size()];double mapped=0,outside=0;
        for(var point:grid.points()){
            int index=GridZoning.zoneIndexOf(zones,point.lat(),point.lon());
            if(index<0)outside+=point.population();else{weights[index]+=point.population();mapped+=point.population();}
        }
        if(mapped<=0)return Attempt.missing("not-applicable","no-positive-population-in-model-zones");
        return Attempt.available(new Value(source,info,mapped,source,weights,false,
                Map.of("parsedPopulation",mapped+outside,"mappedPopulation",mapped,"outsideModelPopulation",outside,
                       "parsedPoints",grid.points().size(),"format",grid.format(),"skippedLines",grid.skippedLines(),
                       "parserScope","scenario-region; rows outside parsing scope are not part of this balance")));
    }

    public static Map<String,Object> report(Resolution resolution,Scenario scenario,List<Zone> zones,int total){
        var value=resolution.value();var info=value.info();
        String role=ProviderChain.status(resolution.candidates()).equals("resolved-with-fallback")?"fallback":"primary";
        var scope=new SourceProvenance.Scope("residents",zones.stream().map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList());
        boolean dataset=value.distribution().startsWith("dataset:");
        var ref=new SourceProvenance.Reference(dataset?"dataset":"builtin",dataset?value.distribution().substring(8):null,info.sha256(),info.label(),info.source(),info.referenceDate(),info.license(),info.attribution());
        var distribution=new ArrayList<SourceProvenance.Node>();
        distribution.add(new SourceProvenance.Node("input","source",value.distribution().equals("uniform-noise")?"assumed":"unknown",role,ref,List.of(),scope,Map.of("provider",value.distribution(),"providerVersion",info.version())));
        distribution.add(new SourceProvenance.Node("weights",value.smooth()?"modelled-spatial-weights":"grid-point-to-zone-sum",value.smooth()?"assumed":"derived",role,null,List.of("input"),scope,Map.of("smooth",value.smooth(),"diagnostics",value.diagnostics(),"seed",scenario.population.seed)));
        var totals=new ArrayList<SourceProvenance.Node>();
        boolean manual=scenario.population.totalPersons!=null;
        String totalSource=manual?"user-defined":value.totalSource();
        if(!manual&&!totalSource.equals("density-estimate"))totals.add(distribution.getFirst());
        Map<String,Object> totalParameters=new TreeMap<>();totalParameters.put("persons",total);
        if(!manual)totalParameters.put("unroundedSourceTotal",value.estimatedTotal());
        if(!manual&&totalSource.equals("density-estimate")){totalParameters.put("personsPerKm2",scenario.population.personsPerKm2);totalParameters.put("areaKm2",zones.stream().mapToDouble(z->z.areaKm2).sum());totalParameters.put("modelVersion","density-v1");}
        totals.add(new SourceProvenance.Node("total",manual?"manual-total":totalSource.equals("density-estimate")?"area-times-density":"source-total",manual||totalSource.contains("estimate")?"assumed":"derived","primary",null,totals.isEmpty()?List.of():List.of("input"),scope,totalParameters));
        var report=new TreeMap<String,Object>();report.put("contract","synthesis-population-resolution-v1");
        report.put("status",ProviderChain.status(resolution.candidates()));
        report.put("candidates",resolution.candidates());report.put("totalSource",totalSource);report.put("distributionSource",value.distribution());report.put("totalPersons",total);
        report.put("allocatedPersons",zones.stream().mapToDouble(z->z.population).sum());report.put("diagnostics",value.diagnostics());
        report.put("totalProvenance",new SourceProvenance(SourceProvenance.CONTRACT,"population.total","total",totals));
        report.put("distributionProvenance",new SourceProvenance(SourceProvenance.CONTRACT,"population.distribution","weights",distribution));
        if(value.diagnostics().containsKey("distributionProvenance"))report.put("distributionProvenance",value.diagnostics().get("distributionProvenance"));
        return Collections.unmodifiableMap(report);
    }
}
