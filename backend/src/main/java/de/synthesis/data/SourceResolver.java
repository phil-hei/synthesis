package de.synthesis.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.data.importing.*;
import de.synthesis.generation.pipeline.*;
import de.synthesis.scenario.*;
import java.io.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Frozen, typed contributions. Called before queueing; no global RNG consumption. */
public final class SourceResolver {
    public record Inputs(Map<String,DataImportService.Revision> datasets,List<Map<String,Object>> decisions,
                         Map<String,DataImportService.Revision> references,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String populationUploadHash,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) Map<String,MixedSources.Prepared> chains) {
        public Inputs { chains=chains==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(chains)); }
        public Inputs(Map<String,DataImportService.Revision> datasets,List<Map<String,Object>> decisions,Map<String,DataImportService.Revision> references,String populationUploadHash){this(datasets,decisions,references,populationUploadHash,Map.of());}
        public Inputs(Map<String,DataImportService.Revision> datasets,List<Map<String,Object>> decisions){this(datasets,decisions,Map.of(),null);}
        public Inputs(Map<String,DataImportService.Revision> datasets,List<Map<String,Object>> decisions,Map<String,DataImportService.Revision> references){this(datasets,decisions,references,null);}
        public static Inputs empty(){return new Inputs(Map.of(),List.of(),Map.of());}
    }
    private SourceResolver(){ }
    public static Inputs prepare(Scenario scenario,ScenarioRepository repo,ObjectMapper mapper)throws IOException{
        return prepare(scenario,repo,mapper,ImportRegistry.builtins());
    }
    public static Inputs prepare(Scenario scenario,ScenarioRepository repo,ObjectMapper mapper,ImportRegistry registry)throws IOException{
        SourcePlan.validate(scenario.dataPlan);if(scenario.dataPlan==null||!scenario.dataPlan.enabled)return Inputs.empty();
        Map<String,DataImportService.Revision> datasets=new TreeMap<>();var report=new ArrayList<Map<String,Object>>();var effectiveSources=new TreeMap<String,String>();var references=new TreeMap<String,DataImportService.Revision>();var chains=new TreeMap<String,MixedSources.Prepared>();String populationUploadHash=null;
        for(String aspect:new TreeSet<>(SourcePlan.ASPECTS)){
            var p=scenario.dataPlan.policy(aspect);
            if(p.source.equals("profile")){
                var profile=new SourceProfiles(repo,mapper).read(scenario.id,p.profile);
                if(MixedSources.mixed(profile,aspect)){
                    if(aspect.equals("opportunities.work")&&p.gaps.equals("legacy"))throw new IllegalArgumentException("Gemischte Arbeitsplatzprofile benötigen eine explizite Lückenregel: none oder providers");
                    final MixedSources.Prepared prepared;
                    try{prepared=MixedSources.prepare(profile,aspect,scenario,repo,mapper,registry);}
                    catch(ProviderChain.Failure failure){var failed=new ArrayList<>(report);failed.add(Map.of("aspect",aspect,"source","profile","status","insufficient-input","evidence","unknown","candidates",failure.candidates()));throw new PlanException(failure.getMessage(),failed);}
                    chains.put(aspect,prepared);references.putAll(prepared.references());effectiveSources.put(aspect,"mixed-profile");
                    var decision=new TreeMap<String,Object>();decision.put("aspect",aspect);decision.put("source","profile");decision.put("profile",profile.id());decision.put("profileDefinition",profile.definition());decision.put("status","pending-resolution");decision.put("evidence","not-assessed");decision.put("applicationStatus","pending-runtime-inputs");decision.put("gaps",p.gaps);decision.put("candidates",MixedSources.decisions(prepared.candidates()));
                    if(aspect.equals("population.distribution")&&prepared.candidates().stream().anyMatch(c->c.config().provider().equals("builtin:upload-grid")&&c.status().equals("pending"))){
                        var upload=repo.uploadsDir(scenario.id).resolve("population-grid.csv");populationUploadHash=java.nio.file.Files.isRegularFile(upload)?CensusPopulationSource.sha256(upload):"missing";decision.put("uploadHashAtQueueTime",populationUploadHash);
                    }
                    if(p.gaps.equals("providers"))decision.put("gapProviders",p.gapProviders);
                    if(profile.definition().aspects().get(aspect).regional())decision.put("strategy","fill-gaps");
                    report.add(Collections.unmodifiableMap(decision));continue;
                }
            }
            ProfileChoice choice=null;
            if(p.source.equals("profile"))choice=chooseProfile(scenario,aspect,p.profile,repo,mapper,registry,report);
            String effective=choice==null?p.source:choice.revision()==null?"legacy":"dataset";
            effectiveSources.put(aspect,effective);
            var decision=new TreeMap<String,Object>();decision.put("aspect",aspect);decision.put("source",effective);decision.put("gaps",p.gaps);decision.put("qualityMode",scenario.dataPlan.qualityMode);
            if(p.gaps.equals("providers"))decision.put("gapProviders",p.gapProviders);
            if(effective.equals("model")){decision.put("scientificUse","not-approved");decision.put("assumptionStatement",p.assumptionStatement);}
            if(choice!=null){decision.put("profile",p.profile);decision.put("profileDefinition",choice.profile().definition());decision.put("candidates",choice.candidates());}
            if(effective.equals("dataset")){
                var revision=choice==null?DataImportService.readRevision(repo,mapper,scenario.id,p.revision):choice.revision();
                validateRevision(revision,registry);
                var data=revision.data();
                if(data.schemaId().equals(MaskedCells.WORK)&&p.gaps.equals("legacy"))throw new IllegalArgumentException("Raster mit Wertstatus benötigen eine explizite Lückenregel: none oder providers");
                String mismatch=applicability(scenario,aspect,revision,registry);
                if(mismatch!=null)throw new IllegalArgumentException(mismatch);
                var resolvedReferences=resolveReferences(revision,scenario,repo,mapper,registry);
                references.putAll(resolvedReferences);
                if(data.metadata().geography()!=null)decision.put("geography",data.metadata().geography());
                if(!resolvedReferences.isEmpty())decision.put("references",resolvedReferences.values().stream().map(ref->Map.of("revision",ref.id(),"schema",ref.data().schemaId(),"label",ref.data().metadata().label(),"sourceHash",ref.data().sourceHash())).toList());
                var coverage=data.metadata().coverage();
                datasets.put(aspect,revision);decision.put("revision",revision.id());decision.put("sourceHash",data.sourceHash());decision.put("referenceDate",data.metadata().referenceDate());decision.put("label",data.metadata().label());decision.put("status","resolved");decision.put("evidence","user-provided-unverified");decision.put("coverage",coverage);
                decision.put("licence",data.metadata().licence());decision.put("attribution",data.metadata().attribution());
                decision.put("temporalTransfer",LocalDate.parse(data.metadata().referenceDate()).getYear()!=scenario.referenceYear);
                boolean profileFallback=choice!=null&&choice.candidates().stream().anyMatch(c->"selected".equals(c.get("status"))&&((Number)c.get("rank")).intValue()>1);
                if(profileFallback)decision.put("status","resolved-with-fallback");
                decision.put("selectionRole",choice==null?"explicit-selection":profileFallback?"profile-fallback":"profile-primary");
                if(data.metadata().coverageDeclaration()!=null)decision.put("coverageDeclaration",data.metadata().coverageDeclaration());
                decision.put("applicationStatus","pending-spatial-allocation");decision.put("skipped",choice==null?List.of(Map.of("source","legacy","reason","replaced-by-explicit-dataset-in-covered-area")):choice.candidates().stream().filter(c->!"selected".equals(c.get("status"))).toList());
            }else if(effective.equals("builtins")){
                decision.put("status","pending-resolution");decision.put("evidence","not-assessed");decision.put("selectionRole","explicit-provider-order");
                var parameters=new TreeMap<String,Object>();if(aspect.equals("population.distribution"))parameters.put("personsPerKm2",scenario.population.personsPerKm2);parameters.put("seed",scenario.population.seed);
                if(aspect.equals("population.distribution")&&scenario.population.totalPersons!=null)parameters.put("manualTotalPersons",scenario.population.totalPersons);
                decision.put("parameters",parameters);
                decision.put("applicationStatus","pending-runtime-inputs");
                decision.put("candidates",java.util.stream.IntStream.range(0,p.providers.size()).mapToObj(i->{var c=p.providers.get(i);return Map.<String,Object>of("provider",c.provider(),"rank",i+1,"status",c.excluded()?"excluded":"pending","reason",c.excluded()?"explicitly-excluded":"requires-runtime-inputs","onUnavailable",c.onUnavailable());}).toList());
                if(aspect.equals("population.distribution")&&p.providers.stream().anyMatch(c->c.provider().equals("upload-grid")&&!c.excluded())){
                    var upload=repo.uploadsDir(scenario.id).resolve("population-grid.csv");
                    populationUploadHash=java.nio.file.Files.isRegularFile(upload)?CensusPopulationSource.sha256(upload):"missing";
                    decision.put("uploadHashAtQueueTime",populationUploadHash);
                }
            }else{decision.put("status",effective.equals("excluded")?"disabled":effective.equals("model")?"resolved-with-fallback":"legacy");decision.put("evidence",effective.equals("model")?"assumed":"not-assessed");decision.put("selectionRole",effective.equals("model")?"explicit-fallback":effective.equals("excluded")?"excluded":"legacy-delegation");decision.put("skipped",choice==null?List.of():choice.candidates());}
            report.add(Collections.unmodifiableMap(decision));
        }
        var freight=scenario.dataPlan.policy("freight.vehicleOd");
        if(effectiveSources.get("freight.vehicleOd").equals("excluded")){scenario.freight.enabled=false;scenario.freight.relations=List.of();}
        if(effectiveSources.get("freight.vehicleOd").equals("model")){scenario.freight.enabled=true;scenario.freight.relations=assumedFreight(scenario,freight);}
        if(effectiveSources.get("freight.vehicleOd").equals("dataset")){
            var revision=datasets.get("freight.vehicleOd");scenario.freight.enabled=true;var data=revision.data();
            scenario.freight.relations=data.rows().stream().map(row->{var v=row.values();return new FreightDemandGenerator.Relation(v.get("id").toString(),v.get("vehicleClass").toString(),v.get("movement").toString(),n(v,"fromLon"),n(v,"fromLat"),n(v,"toLon"),n(v,"toLat"),(int)n(v,"trips"),n(v,"beginHour"),n(v,"endHour"),"dataset:"+revision.id(),data.metadata().referenceDate());}).toList();
        }
        FreightDemandGenerator.validateDemand(scenario);return new Inputs(Collections.unmodifiableMap(datasets),List.copyOf(report),Collections.unmodifiableMap(references),populationUploadHash,chains);
    }
    /** Failed profile checks retain candidate diagnostics for the UI; no job is queued. */
    public static final class PlanException extends IllegalArgumentException {
        private final List<Map<String,Object>> decisions;
        PlanException(String message,List<Map<String,Object>> decisions){super(message);this.decisions=List.copyOf(decisions);}
        public List<Map<String,Object>> decisions(){return decisions;}
    }
    private record ProfileChoice(DataImportService.Revision revision,SourceProfiles.Profile profile,List<Map<String,Object>> candidates) { }
    private static ProfileChoice chooseProfile(Scenario scenario,String aspect,String profileId,ScenarioRepository repo,
            ObjectMapper mapper,ImportRegistry registry,List<Map<String,Object>> previous)throws IOException {
        var profile=new SourceProfiles(repo,mapper).read(scenario.id,profileId);
        var chain=profile.definition().aspects().get(aspect);
        if(chain==null)throw new IllegalArgumentException("Quellenprofil enthält den gewählten Aspekt nicht: "+aspect);
        var evaluations=new ArrayList<Map<String,Object>>();
        DataImportService.Revision selected=null;
        for(int index=0;index<chain.candidates().size();index++){
            var candidate=chain.candidates().get(index);
            String status,reason;
            if(candidate.excluded()){status="excluded";reason="explicitly-excluded";}
            else if(selected!=null){status="not-evaluated";reason="higher-priority-candidate-selected";}
            else try{
                String[] reference=candidate.provider().split(":");
                var revision=reference[0].equals("revision")?DataImportService.readRevision(repo,mapper,scenario.id,reference[1])
                        :new DataPacks(repo,mapper,registry).resolve(scenario.id,reference[1],reference[2]);
                validateRevision(revision,registry);
                String mismatch=applicability(scenario,aspect,revision,registry);
                if(mismatch!=null){status="not-applicable";reason=mismatch;}
                else{resolveReferences(revision,scenario,repo,mapper,registry);selected=revision;status="selected";reason="first-applicable-candidate";}
            }catch(FileNotFoundException|java.nio.file.NoSuchFileException missing){
                status="unavailable";reason="retained-source-missing";
                if(candidate.onUnavailable().equals("fail")){
                    evaluations.add(evaluation(candidate,index,status,reason));
                    throw profileFailure("Ausgewählte Profilquelle fehlt; Ausfallregel verlangt Abbruch",aspect,profile,evaluations,previous);
                }
            }catch(PlanException failure){throw failure;}
            catch(IOException|IllegalArgumentException invalid){
                evaluations.add(evaluation(candidate,index,"invalid",Objects.toString(invalid.getMessage(),"source-invalid")));
                throw profileFailure("Profilquelle ist ungültig: "+invalid.getMessage(),aspect,profile,evaluations,previous);
            }
            evaluations.add(evaluation(candidate,index,status,reason));
        }
        if(selected==null&&chain.fallback().equals("none"))
            throw profileFailure("Quellenprofil hat keine verwendbare Quelle; kein Ersatz erlaubt",aspect,profile,evaluations,previous);
        return new ProfileChoice(selected,profile,List.copyOf(evaluations));
    }
    private static Map<String,Object> evaluation(SourceProfiles.Candidate candidate,int index,String status,String reason){
        return Map.of("provider",candidate.provider(),"rank",index+1,"status",status,"reason",reason,"onUnavailable",candidate.onUnavailable());
    }
    private static PlanException profileFailure(String message,String aspect,SourceProfiles.Profile profile,
            List<Map<String,Object>> evaluations,List<Map<String,Object>> previous){
        var decisions=new ArrayList<>(previous);
        decisions.add(Map.of("aspect",aspect,"source","profile","profile",profile.id(),"status","insufficient-input", "evidence","unknown",
                "profileDefinition",profile.definition(),"candidates",List.copyOf(evaluations)));
        return new PlanException(message,decisions);
    }
    static void validateRevision(DataImportService.Revision revision,ImportRegistry registry){
        var data=revision.data();var adapter=registry.require(data.schemaId());
        DataImportService.validateMetadata(adapter.schema(),data.metadata());
        if(data.rows().isEmpty())throw new IllegalArgumentException("Ausgewählte Datenrevision ist leer");
        for(var row:data.rows())if(!adapter.validate(row,data.metadata()).isEmpty())throw new IllegalArgumentException("Datenrevision ist fachlich ungültig");
        if(!adapter.validateDataset(data.rows(),data.metadata()).isEmpty())throw new IllegalArgumentException("Datenrevision enthält widersprüchliche Datensätze");
    }
    static Map<String,DataImportService.Revision> resolveReferences(DataImportService.Revision revision,Scenario scenario,
            ScenarioRepository repo,ObjectMapper mapper,ImportRegistry registry)throws IOException{
        var references=new TreeMap<String,DataImportService.Revision>();
        resolveReferences(revision,scenario,repo,mapper,registry,references,new HashSet<>());
        if(revision.data().schemaId().equals("municipal-workplaces-v1"))
            MunicipalWorkplaces.validateBinding(revision,references.get(revision.data().metadata().geography().allocationRevision()));
        return references;
    }
    private static void resolveReferences(DataImportService.Revision revision,Scenario scenario,ScenarioRepository repo,
            ObjectMapper mapper,ImportRegistry registry,Map<String,DataImportService.Revision> references,Set<String> visiting)throws IOException{
        if(visiting.size()>=16)throw new IllegalArgumentException("Zu tiefe Datenabhängigkeit");
        if(!visiting.add(revision.id()))throw new IllegalArgumentException("Zyklische Datenabhängigkeit");
        try{
            for(var dependency:registry.require(revision.data().schemaId()).dependencies(revision.data().metadata())){
                if(visiting.contains(dependency.revision()))throw new IllegalArgumentException("Zyklische Datenabhängigkeit: "+dependency.role());
                if(references.containsKey(dependency.revision())){if(!references.get(dependency.revision()).data().schemaId().equals(dependency.schema()))throw new IllegalArgumentException("Widersprüchlicher Vertrag einer Datenabhängigkeit");continue;}
                if(references.size()>=16)throw new IllegalArgumentException("Zu viele Datenabhängigkeiten");
                var value=DataImportService.readRevision(repo,mapper,scenario.id,dependency.revision());validateRevision(value,registry);
                if(!value.data().schemaId().equals(dependency.schema()))throw new IllegalArgumentException("Falscher Fachvertrag der Datenabhängigkeit: "+dependency.role());
                if(LocalDate.parse(value.data().metadata().referenceDate()).getYear()>scenario.referenceYear)throw new IllegalArgumentException("Datenabhängigkeit liegt nach dem Szenariojahr");
                resolveReferences(value,scenario,repo,mapper,registry,references,visiting);references.put(value.id(),value);
            }
        }finally{visiting.remove(revision.id());}
    }
    static String applicability(Scenario scenario,String aspect,DataImportService.Revision revision,ImportRegistry registry){
        var data=revision.data();
        if(!registry.require(data.schemaId()).schema().aspect().equals(aspect))return "Fachvertrag passt nicht zum Aspekt "+aspect;
        if(LocalDate.parse(data.metadata().referenceDate()).getYear()>scenario.referenceYear)return "Quellenjahr liegt nach dem Szenariojahr: "+aspect;
        var coverage=data.metadata().coverage();var region=scenario.region;
        if(region==null)throw new IllegalArgumentException("Szenarioregion fehlt");
        if(aspect.equals("population.distribution")&&!data.schemaId().equals(MaskedCells.POP)&&(coverage.minLat>region.minLat||coverage.maxLat<region.maxLat||coverage.minLon>region.minLon||coverage.maxLon<region.maxLon))
            return "Bevölkerungsraster muss die gesamte Szenarioregion abdecken";
        if(coverage.maxLat<=region.minLat||coverage.minLat>=region.maxLat||coverage.maxLon<=region.minLon||coverage.minLon>=region.maxLon)
            return "Datenabdeckung liegt außerhalb der Szenarioregion";
        return null;
    }
    private static double n(Map<String,Object> v,String key){return ((Number)v.get(key)).doubleValue();}
    public static List<FreightDemandGenerator.Relation> assumedFreight(Scenario s,SourcePlan.Policy p){
        var r=s.region;if(r==null)throw new IllegalArgumentException("Region für Schwerlast-Ersatzmodell fehlt");
        double dx=(r.maxLon-r.minLon),dy=(r.maxLat-r.minLat),cx=r.minLon+dx*.5,cy=r.minLat+dy*.5;
        double[][] points={{r.minLon+dx*.35,r.minLat+dy*.35},{r.minLon+dx*.65,r.minLat+dy*.65},{r.minLon+dx*.01,cy},{r.maxLon-dx*.01,cy}};
        String date=LocalDate.of(s.referenceYear,1,1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).plusDays(s.freight.day).toString();
        var out=new ArrayList<FreightDemandGenerator.Relation>();String[] movements={"internal","inbound","outbound","through"};int[] counts={p.internalTrips,p.inboundTrips,p.outboundTrips,p.throughTrips};int[][] ends={{0,1},{2,1},{0,3},{2,3}};
        for(int i=0;i<4;i++)if(counts[i]>0){var a=points[ends[i][0]];var b=points[ends[i][1]];out.add(new FreightDemandGenerator.Relation("assumed_"+movements[i],p.vehicleClass,movements[i],a[0],a[1],b[0],b[1],counts[i],0,24,"assumption:explicit-vehicle-od-v1;representative-points;uniform-day".replace(';',':'),date));}
        return List.copyOf(out);
    }
    /** Resolve the whole assignment before changing any zone; failures must not partially apply. */
    public static Map<String,Object> applyWorkplaces(Inputs inputs,SourcePlan plan,List<Zone> zones){
        var revision = inputs.datasets.get("opportunities.work");
        if (revision == null) return Map.of();
        var data = revision.data();
        if(data.schemaId().equals(MaskedCells.WORK))return MaskedCells.workplaces(revision,plan.policy("opportunities.work"),zones);
        if(data.schemaId().equals("municipal-workplaces-v1"))return MunicipalWorkplaces.apply(revision,inputs.references().get(data.metadata().geography().allocationRevision()),plan.policy("opportunities.work"),zones);
        DataImportService.validateWorkplaceCoverage(data.metadata());
        var coverage = data.metadata().coverage();
        var policy = plan.policy("opportunities.work");
        var covered = new ArrayList<SourceProvenance.ZoneRef>();
        var fallback = new ArrayList<SourceProvenance.ZoneRef>();
        Set<Integer> coveredIndices = new HashSet<>();
        for (var zone : zones) if (!zone.external) {
            var reference = new SourceProvenance.ZoneRef(zone.id, zone.index);
            if (zone.minLon >= coverage.minLon && zone.maxLon <= coverage.maxLon
                    && zone.minLat >= coverage.minLat && zone.maxLat <= coverage.maxLat) {
                covered.add(reference);
                coveredIndices.add(zone.index);
            } else fallback.add(reference);
        }
        if (covered.isEmpty()&&!policy.gaps.equals("providers")) throw new IllegalArgumentException("Keine vollständige Modellzone durch Arbeitsplatzdaten abgedeckt");
        if (!fallback.isEmpty() && policy.gaps.equals("none"))
            throw new IllegalArgumentException("Arbeitsplatzabdeckung unvollständig; Lückenregel auswählen oder vollständig abdeckende Quelle nutzen");

        double[] weights = new double[zones.size()];
        int mapped = 0, outside = 0;
        double mappedJobs = 0, appliedJobs = 0;
        var assignments = new ArrayList<SourceProvenance.RowAssignment>();
        for (var row : data.rows()) {
            var values = row.values();
            double quantity = n(values,"value");
            int index = GridZoning.zoneIndexOf(zones,n(values,"lat"),n(values,"lon"));
            Zone zone = index < 0 ? null : zones.get(index);
            String status;
            if (zone == null || zone.external) {
                outside++;
                status = "outside-model";
            } else {
                mapped++;
                mappedJobs += quantity;
                if (coveredIndices.contains(zone.index)) {
                    weights[zone.index] += quantity;
                    appliedJobs += quantity;
                    status = "applied";
                } else status = "partial-zone-not-applied";
            }
            assignments.add(new SourceProvenance.RowAssignment(row.sourceRow(), values.get("id").toString(),
                    zone == null ? null : new SourceProvenance.ZoneRef(zone.id,zone.index), status, quantity));
        }
        // An empty part of an explicitly complete register is a declared zero, even if no row maps here.
        for (var zone : zones) if (coveredIndices.contains(zone.index))
            zone.attractivities.put("Work",weights[zone.index]);

        var report = new TreeMap<String,Object>();
        report.put("revision",revision.id());
        report.put("coveredZones",covered.size());
        report.put("fallbackZones",fallback.size());
        report.put("coverageDenominator","internal model zones fully covered by declared source bbox");
        report.put("coverageBasis","source-owner-declaration; not independently verified");
        report.put("coverageDeclaration",data.metadata().coverageDeclaration());
        report.put("mappedRows",mapped);
        report.put("outsideRows",outside);
        report.put("mappedJobs",mappedJobs);
        report.put("appliedJobs",appliedJobs);
        report.put("jobsInPartiallyCoveredZonesNotApplied",mappedJobs-appliedJobs);
        report.put("allocation","point-to-zone; declared workplace counts used as Work weights");
        report.put("rowAssignments",List.copyOf(assignments));
        report.put("provenance",workplaceProvenance(revision,covered,fallback));
        return Collections.unmodifiableMap(report);
    }

    private static SourceProvenance workplaceProvenance(DataImportService.Revision revision,
            List<SourceProvenance.ZoneRef> covered,List<SourceProvenance.ZoneRef> fallback) {
        var data = revision.data();
        var meta = data.metadata();
        var scope = new SourceProvenance.Scope("Work",covered);
        var reference = new SourceProvenance.Reference("dataset",revision.id(),data.sourceHash(),meta.label(),
                meta.source(),meta.referenceDate(),meta.licence(),meta.attribution());
        var nodes = new ArrayList<SourceProvenance.Node>();
        nodes.add(new SourceProvenance.Node("register","source","unknown","primary",reference,List.of(),scope,
                Map.of("coverageDeclaration",meta.coverageDeclaration(),"declaredCoverage",meta.coverage(),
                        "schema",data.schemaId(),"mapping",data.mapping())));
        nodes.add(new SourceProvenance.Node("point-allocation","point-to-zone-sum","derived","primary",null,
                List.of("register"),scope,Map.of("unit","jobs","emptyZonePolicy","zero-by-completeness-declaration",
                        "modelIndexBase",0)));
        var rootInputs = new ArrayList<>(List.of("point-allocation"));
        if (!fallback.isEmpty()) {
            var legacy = new SourceProvenance.Reference("legacy",null,null,"Bisherige Arbeitsplatzgewichte",
                    "legacy-delegation; individual providers not resolved",null,"unknown","");
            nodes.add(new SourceProvenance.Node("legacy-work","legacy-delegation","unknown","fallback",legacy,
                    List.of(),new SourceProvenance.Scope("Work",fallback),Map.of("unit","model-weight")));
            rootInputs.add("legacy-work");
        }
        var all = new ArrayList<>(covered);
        all.addAll(fallback);
        nodes.add(new SourceProvenance.Node("work-weights",fallback.isEmpty()?"choose":"fill-gaps","derived","composition",
                null,rootInputs,new SourceProvenance.Scope("Work",all),Map.of("quantitiesComparable",fallback.isEmpty(),
                        "semantics","purpose-specific weights; no observed total inferred")));
        return new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","work-weights",nodes);
    }
}
