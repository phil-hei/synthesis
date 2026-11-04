package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.ScenarioRepository;
import de.synthesis.scenario.Scenario;
import de.synthesis.SynthesisProperties;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Source drafts stay in uploads; only explicitly selected prepared revisions enter generation. */
@Service
public class CompanyTourImportService {
    public record Stored(List<TourTableReader.Table> tables, CompanyTourMapper.Mapping confirmedMapping,
                         CompanyTourMapper.Draft draft, CompanyTourExecution.Context context,
                         TourSourceReader.Source source,TourSourceAnalysis.Analysis analysis) {
        public Stored(List<TourTableReader.Table> tables,CompanyTourMapper.Mapping mapping,CompanyTourMapper.Draft draft,CompanyTourExecution.Context context){this(tables,mapping,draft,context,null,null);}
        public Stored(List<TourTableReader.Table> tables, CompanyTourMapper.Mapping mapping, CompanyTourMapper.Draft draft) {
            this(tables, mapping, draft, null);
        }
    }
    public record SourceInfo(String contract,String sourceHash,String format,List<TourSourceReader.Block> blocks,List<TourSourceReader.Evidence> evidence) {}
    public record AnalysisInfo(String id,List<TourSourceAnalysis.RowEvidence> evidence,List<TourSourceAnalysis.Ignored> ignoredBlocks) {}
    private SourceInfo info(TourSourceReader.Source source){return source==null?null:new SourceInfo(source.contract(),source.sourceHash(),source.format(),source.blocks(),source.evidence());}
    private AnalysisInfo info(TourSourceAnalysis.Analysis analysis){return analysis==null?null:new AnalysisInfo(analysis.id(),analysis.evidence(),analysis.ignoredBlocks());}
    public record ImportView(String importId, List<CompanyTourMapper.Profile> tables,
                             CompanyTourMapper.Mapping mapping, CompanyTourMapper.Draft draft,
                             boolean mappingConfirmed, boolean llmEnabled, CompanyTourExecution.Context context,
                             CompanyTourExecution.Readiness execution, boolean active, List<CompanyTourExecution.VehicleOption> vehicles,
                             Scenario.CompanyTours selection,SourceInfo source,AnalysisInfo analysis,boolean sourceAnalysisEnabled,CompanyTourGeocoder.Info geocoding,CompanyTourPreflight.Summary preflight) {}
    public record MappingRequest(CompanyTourMapper.Mapping mapping,boolean sourceReviewed) { public MappingRequest(CompanyTourMapper.Mapping mapping){this(mapping,false);} }
    private final ScenarioRepository scenarios; private final ObjectMapper json; private final TourMappingPlanner planner;
    private final TourTableReader reader = new TourTableReader(); private final CompanyTourMapper mapper = new CompanyTourMapper();
    public CompanyTourImportService(ScenarioRepository scenarios, ObjectMapper json, TourMappingPlanner planner) {
        this(scenarios, json, planner, new SynthesisProperties());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CompanyTourImportService(ScenarioRepository scenarios, ObjectMapper json, TourMappingPlanner planner, SynthesisProperties properties) {
        this.scenarios = scenarios; this.json = json; this.planner = planner;
    }
    public synchronized ImportView upload(String id, byte[] bytes, String filename) throws IOException {
        requireScenario(id); String hash = CompanyTourMapper.hash(bytes); Path file = file(id, hash);
        if (Files.exists(file)) {
            var previous=read(id,hash);
            if(previous.source()==null){previous=new Stored(previous.tables(),previous.confirmedMapping(),previous.draft(),previous.context(),new TourSourceReader(json,planner.config()).read(bytes,filename),previous.analysis());write(file,previous);}
            return view(id,hash,previous);
        }
        var source=new TourSourceReader(json,planner.config()).read(bytes,filename);
        var tables=source.tables().isEmpty()?List.of(new TourTableReader.Table("document","Dokument: Analyse erforderlich",List.of(new TourTableReader.Row(1,CompanyTourMapper.FIELDS)))):source.tables();
        var mapping = mapper.bestMapping(tables);
        var draft = mapper.apply(table(tables, mapping.tableId()), mapping, hash);
        Stored stored = new Stored(tables, null, draft,null,source,null); write(file, stored); return view(id, hash, stored);
    }
    public synchronized List<ImportView> list(String id) throws IOException {
        requireScenario(id); Path dir = scenarios.uploadsDir(id).resolve("company-tours");
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            var hashes = stream.map(p -> p.getFileName().toString()).filter(s -> s.matches("[0-9a-f]{64}\\.json"))
                    .sorted().map(s -> s.substring(0, 64)).toList();
            List<ImportView> result = new ArrayList<>(); for (String h : hashes) result.add(view(id, h, read(id, h))); return result;
        }
    }
    public synchronized ImportView preview(String id, String hash, CompanyTourMapper.Mapping mapping) throws IOException {
        requireMapping(mapping);
        var stored = read(id, hash); var draft = mapper.apply(table(tables(stored), mapping.tableId()), mapping, hash);
        return new ImportView(hash, profiles(tables(stored), mapping), mapping, draft, false, planner.enabled(),
                null, CompanyTourExecution.prepare(draft, null, scenarios.findById(id).orElseThrow()).readiness(), false,
                CompanyTourExecution.vehicles(scenarios.findById(id).orElseThrow()), scenarios.findById(id).orElseThrow().companyTours,info(stored.source()),info(stored.analysis()!=null && stored.analysis().mapping().tableId().equals(mapping.tableId())?stored.analysis():null),planner.sourceEnabled(),new CompanyTourGeocoder(json,planner.config()).info(),CompanyTourPreflight.inspect(draft,null,scenarios.findById(id).orElseThrow()));
    }
    public synchronized ImportView confirm(String id,String hash,CompanyTourMapper.Mapping mapping)throws IOException{return confirm(id,hash,mapping,false);}
    public synchronized ImportView confirm(String id, String hash, CompanyTourMapper.Mapping mapping,boolean sourceReviewed) throws IOException {
        requireMapping(mapping);
        var stored = read(id, hash); var draft = mapper.apply(table(tables(stored), mapping.tableId()), mapping, hash);
        if (draft.status().equals("invalid")) throw new IllegalArgumentException("Mapping enthält ungültige oder unzugeordnete Zeilen; zuerst Vorschau korrigieren");
        if(mapping.tableId().startsWith("llm-")&&!sourceReviewed)throw new IllegalArgumentException("Quellbelege und ausgelassene Textblöcke vor Bestätigung prüfen");
        var next = new Stored(tables(stored), mapping, draft,null,stored.source(),stored.analysis()); write(file(id, hash), next); deactivate(id, hash); return view(id, hash, next);
    }
    public ImportView propose(String id, String hash, CompanyTourMapper.Mapping mapping) throws IOException {
        requireMapping(mapping);
        var stored = read(id, hash); var table = table(tables(stored), mapping.tableId());
        var profile = mapper.profile(table, mapping.headerRow()); mapper.validate(mapping, profile);
        long seed = scenarios.findById(id).orElseThrow().population.seed;
        return preview(id, hash, planner.propose(profile, mapping, seed));
    }
    private List<TourTableReader.Table> tables(Stored stored) {
        if(stored.analysis()==null)return stored.tables();
        var out=new ArrayList<>(stored.tables());
        for(var table:stored.analysis().tables())if(out.stream().noneMatch(t->t.id().equals(table.id())))out.add(table);
        return List.copyOf(out);
    }
    public ImportView analyze(String id,String hash,String mode)throws IOException{return analyze(id,hash,mode,null);}
    private ImportView analyze(String id,String hash,String mode,CompanyTourMapper.Mapping preferred) throws IOException {
        var stored=read(id,hash);
        if(stored.source()==null)throw new IllegalArgumentException("Für Quellinhaltsanalyse Datei erneut als neuen Import bereitstellen");
        if(!Set.of("auto","tables","document").contains(mode))throw new IllegalArgumentException("Unbekannter Analysemodus");
        var source=stored.source();
        if(mode.equals("document")){if(source.blocks().isEmpty())throw new IllegalArgumentException("Quelle enthält keine Dokumenttextblöcke");source=new TourSourceReader.Source(source.contract(),source.sourceHash(),source.format(),List.of(),source.blocks(),source.evidence());}
        var analysis=TourSourceAnalysis.analyze(source,planner,json,scenarios.findById(id).orElseThrow().population.seed,preferred);
        synchronized(this){var current=read(id,hash);write(file(id,hash),new Stored(current.tables(),current.confirmedMapping(),current.draft(),current.context(),current.source(),analysis));}
        return preview(id,hash,analysis.mapping());
    }
    private Path profileDir(String id){return scenarios.uploadsDir(id).resolve("company-tour-profiles");}
    public synchronized List<TourImportProfiles.Profile> profiles(String id)throws IOException{requireScenario(id);return TourImportProfiles.list(profileDir(id),json);}
    public synchronized TourImportProfiles.Profile saveProfile(String id,String hash,String name)throws IOException {
        var stored=read(id,hash);if(stored.confirmedMapping()==null)throw new IllegalArgumentException("Zuerst Mapping bestätigen");
        boolean document=stored.confirmedMapping().tableId().startsWith("llm-");
        return TourImportProfiles.save(profileDir(id),name,stored.source()==null?"legacy":stored.source().format(),document,table(stored.tables(),stored.confirmedMapping().tableId()),stored.confirmedMapping(),json);
    }
    public ImportView applyProfile(String id,String hash,String profileId)throws IOException {
        var profile=profiles(id).stream().filter(p->p.id().equals(profileId)).findFirst().orElseThrow(()->new IllegalArgumentException("Profil nicht gefunden"));var stored=read(id,hash);
        if(stored.source()!=null&&!stored.source().format().equals(profile.format()))throw new IllegalArgumentException("Quellformat geändert; Profil erneut prüfen statt automatisch übernehmen");
        if(profile.strategy().equals("document-evidence"))return analyze(id,hash,"document",profile.mapping());
        var table=stored.tables().stream().filter(t->t.id().equals(profile.mapping().tableId())).findFirst().orElseThrow(()->new IllegalArgumentException("Tabellenstruktur geändert; neues Mapping prüfen"));
        if(!TourImportProfiles.fingerprint(table,profile.mapping()).equals(profile.structure()))throw new IllegalArgumentException("Tabellenstruktur geändert; Spalten oder Kopfzeile erneut prüfen");
        return preview(id,hash,profile.mapping());
    }
    public synchronized CompanyTourMapper.Draft export(String id, String hash) throws IOException {
        var stored = read(id, hash);
        if (stored.confirmedMapping() == null) throw new IllegalArgumentException("Mapping zuerst prüfen und bestätigen");
        return stored.draft();
    }
    public synchronized void delete(String id, String hash) throws IOException { requireScenario(id); deactivate(id, hash); Files.deleteIfExists(file(id, hash)); }
    private ImportView view(String id, String hash, Stored s) {
        var scenario = scenarios.findById(id).orElseThrow();
        var readiness = CompanyTourExecution.prepare(s.draft(), s.context(), scenario).readiness();
        var mapping = s.confirmedMapping() == null ? s.draft().mapping() : s.confirmedMapping();
        return new ImportView(hash, profiles(s.tables(), mapping), mapping,
                s.draft(), s.confirmedMapping() != null, planner.enabled(), s.context(), readiness,
                scenario.companyTours != null && scenario.companyTours.enabled && scenario.companyTours.imports.stream()
                        .anyMatch(r -> r.importId().equals(hash) && r.executionHash().equals(readiness.executionHash())),
                CompanyTourExecution.vehicles(scenario), scenario.companyTours,info(s.source()),info(s.analysis()!=null && s.analysis().mapping().tableId().equals(mapping.tableId())?s.analysis():null),planner.sourceEnabled(),new CompanyTourGeocoder(json,planner.config()).info(),CompanyTourPreflight.inspect(s.draft(),s.context(),scenario));
    }
    public synchronized ImportView context(String id, String hash, CompanyTourExecution.Context context) throws IOException {
        var stored = read(id, hash);
        if (stored.confirmedMapping() == null) throw new IllegalArgumentException("Mapping zuerst bestätigen");
        if (context == null || context.tours() == null || context.locations() == null
                || context.tours().stream().anyMatch(p -> p == null || p.stopOrder() == null || p.start() == null || p.end() == null)
                || context.locations().values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Kontext benötigt Tourzuordnungen, Depotfelder und eine Haltorttabelle");
        // Also validate malformed requests before writing, while retaining incomplete but editable context.
        CompanyTourExecution.prepare(stored.draft(), context, scenarios.findById(id).orElseThrow());
        var next = new Stored(stored.tables(), stored.confirmedMapping(), stored.draft(), context,stored.source(),stored.analysis());
        deactivate(id, hash); write(file(id, hash), next); return view(id, hash, next);
    }
    public synchronized ImportView activate(String id, String hash, boolean enabled) throws IOException {
        var stored = read(id, hash); var scenario = scenarios.findById(id).orElseThrow();
        if (!enabled) { deactivate(id, hash); return view(id, hash, stored); }
        if (stored.confirmedMapping() == null) throw new IllegalArgumentException("Mapping zuerst bestätigen");
        var result = CompanyTourExecution.prepare(stored.draft(), stored.context(), scenario);
        if (!result.readiness().ready()) throw new IllegalArgumentException(result.readiness().problems().getFirst().message());
        requireScenarioDemand(scenario);
        if (scenario.companyTours == null) scenario.companyTours = new Scenario.CompanyTours();
        var selected = new ArrayList<>(scenario.companyTours.imports); selected.removeIf(r -> r.importId().equals(hash));
        selected.add(new CompanyTourExecution.Selection(hash, result.readiness().executionHash()));
        scenario.companyTours.imports = selected; scenario.companyTours.enabled = true;
        // Revision and physical-vehicle conflicts must fail before saving the candidate selection.
        loadForGeneration(scenarios, json, scenario);
        scenarios.save(scenario); return view(id, hash, stored);
    }
    private void deactivate(String id, String hash) {
        var scenario = scenarios.findById(id).orElseThrow();
        if (scenario.companyTours == null || scenario.companyTours.imports == null) return;
        var remaining = scenario.companyTours.imports.stream().filter(r -> !r.importId().equals(hash)).toList();
        scenario.companyTours.imports = remaining;
        if (remaining.isEmpty()) scenario.companyTours.enabled = false;
        scenarios.save(scenario);
    }
    public static void requireScenarioDemand(Scenario s) {
        if (s.population.samplingFraction != 1)
            throw new IllegalArgumentException("Unternehmenstouren benötigen vollständige Personennachfrage (100 %)");
        if (s.vehicleConfiguration == null || !de.synthesis.vehicle.VehicleCatalog.validate(s.vehicleConfiguration).valid())
            throw new IllegalArgumentException("Gültigen Fahrzeugkatalog mit expliziten Tourenfahrzeugen zuerst speichern");
    }
    /** Read and freeze exact selected revisions before queueing; later edits cannot change a running job. */
    public static CompanyTourExecution.Batch loadForGeneration(ScenarioRepository repo, ObjectMapper json, Scenario scenario) throws IOException {
        if (scenario.companyTours == null || !scenario.companyTours.enabled) return CompanyTourExecution.Batch.empty();
        requireScenarioDemand(scenario);
        if (scenario.companyTours.imports == null || scenario.companyTours.imports.isEmpty() || scenario.companyTours.imports.size() > 100)
            throw new IllegalArgumentException("Mindestens eine und höchstens 100 bestätigte Importrevisionen auswählen");
        var revisions = new CompanyTourRevisions();
        List<CompanyTourExecution.Prepared> inputs = new ArrayList<>(); Set<String> hashes = new HashSet<>(), physical = new HashSet<>(); String date = null;
        for (var selection : scenario.companyTours.imports) {
            if (selection == null || selection.importId() == null || !selection.importId().matches("[0-9a-f]{64}") || !hashes.add(selection.importId()))
                throw new IllegalArgumentException("Ungültige oder doppelte Importauswahl");
            var stored = json.readValue(repo.uploadsDir(scenario.id).resolve("company-tours").resolve(selection.importId() + ".json").toFile(), Stored.class);
            if (stored.confirmedMapping() == null) throw new IllegalArgumentException("Ausgewähltes Tourenmapping ist nicht bestätigt");
            var result = CompanyTourExecution.prepare(stored.draft(), stored.context(), scenario);
            if (!result.readiness().ready() || !Objects.equals(selection.executionHash(), result.readiness().executionHash()))
                throw new IllegalArgumentException("Tourenkontext oder Fahrzeugkonfiguration geändert; Import erneut prüfen und aktivieren");
            var input = result.prepared();
            if (date != null && !date.equals(input.simulationDate())) throw new IllegalArgumentException("Aktive Importe benötigen dasselbe Simulationsdatum");
            date = input.simulationDate();
            revisions.include(stored.draft(), input);
            Set<String> vehicles = new HashSet<>(); input.legs().forEach(l -> vehicles.add(l.vehicleId()));
            for (String id : vehicles) if (!physical.add(id)) throw new IllegalArgumentException("Dasselbe physische Fahrzeug darf nicht in mehreren aktiven Dateien vorkommen");
            inputs.add(input);
        }
        return new CompanyTourExecution.Batch(List.copyOf(inputs));
    }
    public CompanyTourGeocoder.Lookup geocode(String id,String hash,List<String> targetIds) throws IOException {
        return geocode(id,hash,targetIds,false);
    }
    public CompanyTourGeocoder.Lookup geocode(String id,String hash,List<String> targetIds,boolean refresh) throws IOException {
        Stored stored;
        synchronized(this){stored=read(id,hash);}
        if(stored.confirmedMapping()==null || stored.context()==null)throw new IllegalArgumentException("Mapping und Betriebskontext zuerst speichern");
        var geocoder=new CompanyTourGeocoder(json,planner.config());var available=CompanyTourPreflight.targets(stored.draft(),stored.context());
        List<CompanyTourGeocoder.Target> selected;
        if(targetIds==null) selected=available.stream().limit(geocoder.info().maxBatch()).toList();
        else {
            if(targetIds.isEmpty()||targetIds.size()>geocoder.info().maxBatch()||new HashSet<>(targetIds).size()!=targetIds.size())throw new IllegalArgumentException("Gültige, eindeutige Adressziele auswählen");
            selected=new ArrayList<>();for(String target:targetIds) selected.add(available.stream().filter(t->t.id().equals(target)).findFirst().orElseThrow(()->new IllegalArgumentException("Adressziel geändert, bereits verortet oder nicht ausgewählt; Kontext neu laden")));
        }
        return geocoder.lookup(hash,selected,scenarios.uploadsDir(id).resolve("company-tour-geocoding"),refresh);
    }
    public synchronized CompanyTourLocations.Lookup locations(String id, String hash, byte[] bytes, String filename) throws IOException {
        return CompanyTourLocations.match(bytes, filename, read(id, hash).draft());
    }
    private List<CompanyTourMapper.Profile> profiles(List<TourTableReader.Table> tables, CompanyTourMapper.Mapping mapping) {
        return mapper.profiles(tables).stream().map(p -> p.id().equals(mapping.tableId())
                ? mapper.profile(table(tables, p.id()), mapping.headerRow()) : p).toList();
    }
    private void requireMapping(CompanyTourMapper.Mapping mapping) {
        if (mapping == null) throw new IllegalArgumentException("Mapping fehlt");
    }
    private TourTableReader.Table table(List<TourTableReader.Table> tables, String key) {
        return tables.stream().filter(t -> t.id().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException("Tabelle nicht gefunden"));
    }
    private Stored read(String id, String hash) throws IOException {
        requireScenario(id); Path p = file(id, hash);
        if (!Files.exists(p)) throw new NoSuchFileException("Tourenimport nicht gefunden");
        return json.readValue(p.toFile(), Stored.class);
    }
    private Path file(String id, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Ungültige Import-ID");
        return scenarios.uploadsDir(id).resolve("company-tours").resolve(hash + ".json");
    }
    private void requireScenario(String id) {
        if (scenarios.findById(id).isEmpty()) throw new IllegalArgumentException("Szenario nicht gefunden");
    }
    private void write(Path file, Stored value) throws IOException {
        Files.createDirectories(file.getParent()); Path tmp = Files.createTempFile(file.getParent(), ".tour-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
}
