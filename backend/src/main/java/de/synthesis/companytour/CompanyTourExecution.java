package de.synthesis.companytour;

import de.synthesis.scenario.Scenario;
import de.synthesis.vehicle.*;
import java.time.*;
import java.util.*;

/** Explicit execution context. Source plans remain immutable; no inferred depots, fleets or date shifts. */
public final class CompanyTourExecution {
    public static final String CONTRACT = "synthesis-company-tour-execution-v1";
    public record Selection(String importId, String executionHash) {}
    public record Point(Double lon, Double lat, String provenance) {}
    public record TourPlan(String tourId, boolean included, String vehicleId, String startTime, String returnTime,
                           Point start, Point end, List<String> stopOrder) {}
    public record DepotAddresses(String start, String end) {}
    public record Context(String companyId, String simulationDate, boolean dateShiftConfirmed,
                          boolean sequenceConfirmed, boolean windowsConfirmed, boolean locationsConfirmed,
                          String windowPolicy, List<TourPlan> tours, Map<String, Point> locations,
                          boolean regionalProjectionConfirmed,boolean networkProjectionRequested,Map<String,DepotAddresses> depotAddresses) {
        public Context {
            if (depotAddresses != null) {
                var addresses = new TreeMap<String,DepotAddresses>();
                for (var entry : depotAddresses.entrySet()) {
                    var value = entry.getValue();
                    if (value == null || entry.getKey() == null) throw new IllegalArgumentException("Depotadressen benötigen eine Tourkennung und Adressfelder");
                    String start = Objects.toString(value.start(), "").strip(), end = Objects.toString(value.end(), "").strip();
                    if (start.length() > 500 || end.length() > 500) throw new IllegalArgumentException("Depotadressen dürfen höchstens 500 Zeichen enthalten");
                    if (!start.isEmpty() || !end.isEmpty()) addresses.put(entry.getKey(),new DepotAddresses(start,end));
                }
                depotAddresses = addresses.isEmpty() ? null : Collections.unmodifiableMap(addresses);
            }
        }
        public Context(String companyId,String simulationDate,boolean dateShiftConfirmed,boolean sequenceConfirmed,boolean windowsConfirmed,boolean locationsConfirmed,String windowPolicy,List<TourPlan> tours,Map<String,Point> locations,boolean regionalProjectionConfirmed,boolean networkProjectionRequested){this(companyId,simulationDate,dateShiftConfirmed,sequenceConfirmed,windowsConfirmed,locationsConfirmed,windowPolicy,tours,locations,regionalProjectionConfirmed,networkProjectionRequested,null);}
        public Context(String companyId,String simulationDate,boolean dateShiftConfirmed,boolean sequenceConfirmed,boolean windowsConfirmed,boolean locationsConfirmed,String windowPolicy,List<TourPlan> tours,Map<String,Point> locations){this(companyId,simulationDate,dateShiftConfirmed,sequenceConfirmed,windowsConfirmed,locationsConfirmed,windowPolicy,tours,locations,false,false);}
        public Context(String companyId,String simulationDate,boolean dateShiftConfirmed,boolean sequenceConfirmed,boolean windowsConfirmed,boolean locationsConfirmed,String windowPolicy,List<TourPlan> tours,Map<String,Point> locations,boolean regionalProjectionConfirmed){this(companyId,simulationDate,dateShiftConfirmed,sequenceConfirmed,windowsConfirmed,locationsConfirmed,windowPolicy,tours,locations,regionalProjectionConfirmed,false);}
    }
    private record LegacyContext(String companyId,String simulationDate,boolean dateShiftConfirmed,boolean sequenceConfirmed,boolean windowsConfirmed,boolean locationsConfirmed,String windowPolicy,List<TourPlan> tours,Map<String,Point> locations) {}
    private record LegacyRegionalContext(String companyId,String simulationDate,boolean dateShiftConfirmed,boolean sequenceConfirmed,boolean windowsConfirmed,boolean locationsConfirmed,String windowPolicy,List<TourPlan> tours,Map<String,Point> locations,boolean regionalProjectionConfirmed) {}
    public record Problem(String code, String tourId, String stopId, String message) {}
    public record VehicleOption(String vehicleId, String vehicleClass, String typeKey) {}
    public record Window(int startSec, int endSec) {}
    public record Leg(String sourceKey, String importId, String tourId, String stopId, String vehicleId,
                      String vehicleClass, int departSec, int plannedArrivalSec, int plannedEndSec, int serviceSeconds,
                      Point from, Point to, String windowPolicy, List<Window> windows, boolean returning) {}
    public record Prepared(String contract, String importId, String mappingHash, String executionHash,
                           String companyId, String simulationDate, int sourceTours, List<String> excludedTours,
                           List<Leg> legs, CompanyTourRegion.Bounds regionalScope,boolean networkProjectionRequested) {
        public Prepared(String contract,String importId,String mappingHash,String executionHash,String companyId,String simulationDate,int sourceTours,List<String> excludedTours,List<Leg> legs){this(contract,importId,mappingHash,executionHash,companyId,simulationDate,sourceTours,excludedTours,legs,null,false);}
        public Prepared(String contract,String importId,String mappingHash,String executionHash,String companyId,String simulationDate,int sourceTours,List<String> excludedTours,List<Leg> legs,CompanyTourRegion.Bounds scope){this(contract,importId,mappingHash,executionHash,companyId,simulationDate,sourceTours,excludedTours,legs,scope,false);}
    }
    public record Readiness(boolean ready, boolean networkVerified, String executionHash, int selectedTours,
                            int legs, List<Problem> problems) {}
    public record Result(Readiness readiness, Prepared prepared) {}
    public record Batch(List<Prepared> imports) {
        public static Batch empty() { return new Batch(List.of()); }
        public List<Leg> legs() { return imports.stream().flatMap(p -> p.legs().stream()).toList(); }
        public boolean active() { return !imports.isEmpty(); }
    }
    private CompanyTourExecution() {}

    public static List<VehicleOption> vehicles(Scenario scenario) {
        var report = VehicleCatalog.validate(scenario.vehicleConfiguration);
        if (!report.valid()) return List.of();
        var types = new HashMap<String, VehicleType>(); report.resolved().vehicleTypes().forEach(t -> types.put(t.key(), t));
        return report.resolved().initialVehicles().stream().map(v -> new VehicleOption(v.vehicleId(),
                types.get(v.typeRef().key()).routing().sumoVehicleClass(), v.typeRef().key()))
                .filter(v -> Set.of("delivery", "truck", "trailer").contains(v.vehicleClass())).toList();
    }

    public static Result prepare(CompanyTourMapper.Draft draft, Context context, Scenario scenario) {
        List<Problem> problems = new ArrayList<>(); List<Leg> legs = new ArrayList<>(); List<String> excluded = new ArrayList<>();
        if(scenario.region==null)return failed("model-region","Modellregion fehlt");
        if (context == null) return failed("missing-context", "Depot, Fahrzeuge und Haltorte ergänzen");
        if (!Objects.equals(draft.status(), "needs-context")) return failed("invalid-mapping", "Zuerst ein gültiges Mapping bestätigen");
        if (context.companyId() == null || !context.companyId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))
            problems.add(problem("company-id", "Unternehmenskennung mit höchstens 80 Buchstaben/Ziffern erforderlich"));
        LocalDate day;
        try { day = LocalDate.parse(context.simulationDate()); }
        catch (Exception e) { return failed("simulation-date", "Gültiges Simulationsdatum erforderlich"); }
        if(context.networkProjectionRequested()&&!context.regionalProjectionConfirmed())problems.add(problem("network-region-confirmation","Netzbasierte Grenzübergänge benötigen die bestätigte regionale Projektion"));
        if (!context.sequenceConfirmed()) problems.add(problem("sequence-review", "Haltreihenfolge ausdrücklich bestätigen"));
        if (!context.windowsConfirmed()) problems.add(problem("window-review", "Bedeutung und Umgang mit Kundenfenstern bestätigen"));
        if (!context.locationsConfirmed()) problems.add(problem("locations-review", "Koordinaten und Depotorte ausdrücklich bestätigen"));
        if (context.windowPolicy() == null || !Set.of("report-only", "arrival", "service").contains(context.windowPolicy()))
            problems.add(problem("window-policy", "Fensterregel wählen: nur berichten, Ankunft oder gesamte Bedienung"));
        if (context.tours() == null || context.locations() == null || context.tours().size() != draft.tours().size()
                || context.locations().size() > 10_000) return failed("context-shape", "Jede Quelltour benötigt eine ausdrückliche Auswahl; Haltorte als Liste zuordnen");
        Map<String, TourPlan> plans = new HashMap<>();
        for (var plan : context.tours()) {
            if (plan == null || plan.tourId() == null || plans.putIfAbsent(plan.tourId(), plan) != null)
                return failed("duplicate-tour-plan", "Tourzuordnungen fehlen oder sind doppelt");
        }
        if (context.depotAddresses() != null && !plans.keySet().containsAll(context.depotAddresses().keySet()))
            throw new IllegalArgumentException("Depotadressen passen nicht zu den Touren dieses Imports");
        Map<String, VehicleOption> fleet = new HashMap<>(); vehicles(scenario).forEach(v -> fleet.put(v.vehicleId(), v));
        Map<String, List<TimedPlan>> duties = new HashMap<>(); int selected = 0;
        for (var tour : draft.tours()) {
            TourPlan plan = plans.remove(tour.tourId());
            if (plan == null) return failed("unknown-tour", "Tourzuordnung passt nicht zur Importversion");
            if (!plan.included()) { excluded.add(tour.tourId()); continue; } selected++;
            int before = problems.size();
            if (!tour.serviceDate().equals(context.simulationDate()) && !context.dateShiftConfirmed())
                problems.add(new Problem("date-shift", tour.tourId(), "", "Abweichendes Quelldatum benötigt eine bestätigte Datumsverschiebung"));
            var vehicle = fleet.get(plan.vehicleId());
            if (vehicle == null) problems.add(new Problem("vehicle", tour.tourId(), "", "Passendes explizites Fahrzeug in der Szenario-Fahrzeugkonfiguration auswählen"));
            if (scenario.vehicleOperations != null && scenario.vehicleOperations.bindings() != null
                    && scenario.vehicleOperations.bindings().stream().anyMatch(b -> b != null && Objects.equals(b.vehicleId(), plan.vehicleId())))
                problems.add(new Problem("vehicle-already-bound", tour.tourId(), "", "Fahrzeug ist bereits anderen Fahrten zugeordnet; separates Tourenfahrzeug verwenden"));
            Integer start = time(plan.startTime(), day, draft.timezone(), problems, tour.tourId());
            Integer end = time(plan.returnTime(), day, draft.timezone(), problems, tour.tourId());
            checkPoint(plan.start(), context.regionalProjectionConfirmed() ? null : scenario.region, problems, tour.tourId(), "depot-start");
            checkPoint(plan.end(), context.regionalProjectionConfirmed() ? null : scenario.region, problems, tour.tourId(), "depot-end");
            var stops = new HashMap<String, CompanyTourMapper.Stop>(); tour.stops().forEach(s -> stops.put(s.stopId(), s));
            if (plan.stopOrder() == null || plan.stopOrder().size() != stops.size() || new HashSet<>(plan.stopOrder()).size() != stops.size()
                    || !stops.keySet().equals(new HashSet<>(plan.stopOrder()))) {
                problems.add(new Problem("stop-order", tour.tourId(), "", "Haltreihenfolge muss jeden Quellhalt genau einmal enthalten")); continue;
            }
            if (problems.size() != before) continue;
            int depart = start; Point previous = plan.start(); int ordinal = 0;
            for (String stopId : plan.stopOrder()) {
                var stop = stops.get(stopId); var point = context.locations().get(stopId);
                checkPoint(point, context.regionalProjectionConfirmed() ? null : scenario.region, problems, tour.tourId(), stopId);
                int arrival = OffsetDateTime.parse(stop.plannedArrival()).toLocalTime().toSecondOfDay();
                int departure = OffsetDateTime.parse(stop.plannedDeparture()).toLocalTime().toSecondOfDay();
                // Re-check local times on the selected calendar date, including DST gaps/ambiguities.
                time(OffsetDateTime.parse(stop.plannedArrival()).toLocalTime().toString(), day, draft.timezone(), problems, tour.tourId());
                time(OffsetDateTime.parse(stop.plannedDeparture()).toLocalTime().toString(), day, draft.timezone(), problems, tour.tourId());
                if (arrival < depart || departure < arrival || stop.plannedDwellSec() != departure - arrival)
                    problems.add(new Problem("tour-timing", tour.tourId(), stopId, "Haltreihenfolge oder Planzeiten überschneiden sich"));
                List<Window> windows = new ArrayList<>();
                for (var order : stop.orders()) {
                    int ws = order.windowStart().isBlank() ? 0 : LocalTime.parse(order.windowStart()).toSecondOfDay();
                    int we = order.windowEnd().isBlank() ? 86399 : LocalTime.parse(order.windowEnd()).toSecondOfDay();
                    if (!order.windowStart().isBlank() || !order.windowEnd().isBlank()) {
                        windows.add(new Window(ws, we));
                        if ("arrival".equals(context.windowPolicy()) && (arrival < ws || arrival > we)
                                || "service".equals(context.windowPolicy()) && (arrival < ws || departure > we))
                            problems.add(new Problem("planned-window-violation", tour.tourId(), stopId, "Planzeiten liegen außerhalb der gewählten Fensterregel; Planung korrigieren oder ausdrückliche Berichtsregel wählen"));
                    }
                }
                if (point != null) legs.add(new Leg(key(draft.sourceHash(), tour.tourId(), ordinal++), draft.sourceHash(), tour.tourId(), stopId,
                        plan.vehicleId(), vehicle.vehicleClass(), depart, arrival, departure, Math.toIntExact(stop.plannedDwellSec()),
                        previous, point, context.windowPolicy(), List.copyOf(windows), false));
                previous = point; depart = departure;
            }
            if (end < depart) problems.add(new Problem("return-time", tour.tourId(), "", "Geplante Rückkehr muss nach dem letzten Halt innerhalb desselben Tages liegen"));
            legs.add(new Leg(key(draft.sourceHash(), tour.tourId(), ordinal), draft.sourceHash(), tour.tourId(), "return", plan.vehicleId(),
                    vehicle.vehicleClass(), depart, end, end, 0, previous, plan.end(), context.windowPolicy(), List.of(), true));
            duties.computeIfAbsent(plan.vehicleId(), ignored -> new ArrayList<>()).add(new TimedPlan(start, end, plan.start(), plan.end(), tour.tourId()));
        }
        if (!plans.isEmpty()) problems.add(problem("unknown-tour", "Unbekannte Tourzuordnung"));
        if (selected == 0) problems.add(problem("no-tours", "Mindestens eine Tour auswählen"));
        for (var duty : duties.values()) {
            duty.sort(Comparator.comparingInt(TimedPlan::start));
            for (int i = 1; i < duty.size(); i++) {
                var previous = duty.get(i - 1); var next = duty.get(i);
                if (next.start < previous.end || distance(previous.to, next.from) > 25)
                    problems.add(new Problem("vehicle-duty", next.tourId, "", "Fahrzeugtouren überschneiden sich oder haben unverbundene Depotorte"));
            }
        }
        if (draft.tours().stream().filter(t -> context.tours().stream().anyMatch(p -> p.included() && p.tourId().equals(t.tourId())))
                .map(CompanyTourMapper.Tour::serviceDate).distinct().count() > 1)
            problems.add(problem("multiple-source-dates", "Touren mehrerer Quelltage separat importieren oder genau einen Quelltag auswählen"));
        if(context.regionalProjectionConfirmed()&&problems.isEmpty()&&legs.stream().anyMatch(l->Math.abs(l.from().lon()-l.to().lon())>180))problems.add(problem("regional-dateline","Datumsgrenzenüberschreitende Verbindungen benötigen einen eigenen Routenvertrag"));
        Object hashContext=context.regionalProjectionConfirmed()?(context.networkProjectionRequested()?context:new LegacyRegionalContext(context.companyId(),context.simulationDate(),context.dateShiftConfirmed(),context.sequenceConfirmed(),context.windowsConfirmed(),context.locationsConfirmed(),context.windowPolicy(),context.tours(),context.locations(),true)):new LegacyContext(context.companyId(),context.simulationDate(),context.dateShiftConfirmed(),context.sequenceConfirmed(),context.windowsConfirmed(),context.locationsConfirmed(),context.windowPolicy(),context.tours(),context.locations());
        var scope=context.regionalProjectionConfirmed()?CompanyTourRegion.Bounds.of(scenario.region):null;
        if (context.depotAddresses() != null && !context.networkProjectionRequested())
            hashContext=Map.of("executionContext",hashContext,"depotAddresses",context.depotAddresses());
        var hashFields=new LinkedHashMap<String,Object>();hashFields.put("contract",CONTRACT);hashFields.put("source",draft.sourceHash());hashFields.put("mapping",draft.mappingHash());hashFields.put("context",hashContext);hashFields.put("vehicles",scenario.vehicleConfiguration==null?Map.of():scenario.vehicleConfiguration);
        if(scope!=null)hashFields.put("regionalScope",scope);
        String hash = VehicleJson.hash(scope==null?Map.of("contract",CONTRACT,"source",draft.sourceHash(),"mapping",draft.mappingHash(),"context",hashContext,"vehicles",scenario.vehicleConfiguration==null?Map.of():scenario.vehicleConfiguration):hashFields);
        var readiness = new Readiness(problems.isEmpty(), false, hash, selected, problems.isEmpty() ? legs.size() : 0, List.copyOf(problems));
        return new Result(readiness, problems.isEmpty() ? new Prepared(CONTRACT, draft.sourceHash(), draft.mappingHash(), hash,
                context.companyId(), context.simulationDate(), draft.tours().size(), List.copyOf(excluded), List.copyOf(legs),scope,context.networkProjectionRequested()) : null);
    }
    private record TimedPlan(int start, int end, Point from, Point to, String tourId) {}
    private static Result failed(String code, String message) { return new Result(new Readiness(false, false, "", 0, 0, List.of(problem(code, message))), null); }
    private static Problem problem(String code, String message) { return new Problem(code, "", "", message); }
    private static Integer time(String raw, LocalDate day, String timezone, List<Problem> issues, String tour) {
        try {
            var t = LocalTime.parse(raw); var offsets = ZoneId.of(timezone).getRules().getValidOffsets(day.atTime(t));
            if (offsets.size() != 1) throw new IllegalArgumentException();
            // SUMO's first adapter is a fixed 86400-second day; a clock-changing day needs its own horizon contract.
            var zone = ZoneId.of(timezone);
            if (Duration.between(day.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone)).getSeconds() != 86400) throw new IllegalArgumentException();
            return t.toSecondOfDay();
        } catch (Exception e) { issues.add(new Problem("time-horizon", tour, "", "Uhrzeit ungültig oder Datum mit Zeitumstellung; erster Adapter unterstützt vollständige 24-Stunden-Tage")); return null; }
    }
    private static void checkPoint(Point p, Scenario.Region region, List<Problem> issues, String tour, String stop) {
        if (p == null || p.lon() == null || p.lat() == null || !Double.isFinite(p.lon()) || !Double.isFinite(p.lat())
                || Math.abs(p.lon()) > 180 || Math.abs(p.lat()) > 90 || p.provenance() == null || p.provenance().isBlank() || p.provenance().length() > 200) {
            issues.add(new Problem("location", tour, stop, "Bestätigte Koordinaten mit Herkunft erforderlich")); return;
        }
        if (region != null && (p.lon() < region.minLon || p.lon() > region.maxLon || p.lat() < region.minLat || p.lat() > region.maxLat))
            issues.add(new Problem("outside-region", tour, stop, "Halt/Depot liegt außerhalb der Modellregion; Region für die vollständige Tour erweitern oder Tour ausdrücklich abwählen"));
    }
    public static String key(String hash, String tour, int leg) { return "company:" + hash + ":" + tour + ":" + leg; }
    private static double distance(Point a, Point b) {
        return Math.hypot((a.lon() - b.lon()) * 111320 * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2)), (a.lat() - b.lat()) * 111320);
    }
}
