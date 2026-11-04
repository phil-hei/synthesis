package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.*;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class CompanyTourExecutionTest {
    @TempDir Path dir;
    public static final String CSV = "Tour;Liefertag;Vorgang;Strasse;PLZ;Ort;Ankunft;Abfahrt;Zeitfenster Kunde;Zeitfenster Kunde\n"
            + "T1;20261019;A1;Beispielweg 1;01234;Teststadt;00:02;00:03;00:01;01:00\n"
            + "T1;20261019;A2;Beispielweg 1;01234;Teststadt;00:02;00:03;00:01;01:00\n"
            + "T1;20261019;B1;Beispielweg 2;01234;Teststadt;00:05;00:06;00:04;01:00\n";
    public static byte[] bytes(String v) { return v.getBytes(StandardCharsets.UTF_8); }
    public static CompanyTourMapper.Draft draft() {
        var mapper = new CompanyTourMapper(); var table = new TourTableReader().read(bytes(CSV), "plan.csv").getFirst();
        return mapper.apply(table, mapper.suggest(mapper.profiles(List.of(table)).getFirst()), CompanyTourMapper.hash(bytes(CSV)));
    }
    public static CompanyTourExecution.Point point(double lon) { return new CompanyTourExecution.Point(lon, 48.85, "manual-confirmed"); }
    public static CompanyTourExecution.Context context(CompanyTourMapper.Draft draft, String vehicle) {
        var tour = draft.tours().getFirst(); var stops = tour.stops();
        return new CompanyTourExecution.Context("example-company", "2026-10-19", false, true, true, true, "report-only",
                List.of(new CompanyTourExecution.TourPlan(tour.tourId(), true, vehicle, "00:00", "00:10", point(8.201), point(8.201),
                        stops.stream().map(CompanyTourMapper.Stop::stopId).toList())),
                Map.of(stops.get(0).stopId(), point(8.203), stops.get(1).stopId(), point(8.205)));
    }
    public static Scenario scenario() throws Exception {
        var json = new ObjectMapper(); var resource = CompanyTourExecutionTest.class.getResourceAsStream("/vehicles/operations-freight-example.json");
        var bundle = json.readTree(resource); var s = new Scenario(); s.id = "company-test"; s.targetModels = new ArrayList<>(List.of("sumo"));
        s.region = new Scenario.Region(); s.region.minLon = 8.19; s.region.maxLon = 8.21; s.region.minLat = 48.84; s.region.maxLat = 48.86;
        s.vehicleConfiguration = VehicleJson.MAPPER.treeToValue(bundle.get("vehicleConfiguration"), VehicleConfiguration.class);
        s.vehicleOperations = VehicleJson.MAPPER.treeToValue(bundle.get("vehicleOperations"), VehicleOperationsConfiguration.class);
        return s;
    }
    @Test void completeContextCreatesExplicitLegsServiceAndReturnWithoutMutatingDemand() throws Exception {
        var d = draft(); var result = CompanyTourExecution.prepare(d, context(d, "van-1"), scenario());
        assertTrue(result.readiness().ready(), result.readiness().problems().toString());
        assertFalse(result.readiness().networkVerified()); assertEquals(3, result.prepared().legs().size());
        assertEquals(60, result.prepared().legs().getFirst().serviceSeconds()); assertTrue(result.prepared().legs().getLast().returning());
        assertEquals(point(8.201), result.prepared().legs().getLast().to());
        assertFalse(d.simulationReady(), "Source draft is immutable; execution preparation has its own contract");
    }
    @Test void missingOutsideAmbiguousDateAndReorderedTimeFailConservatively() throws Exception {
        var d = draft(); var s = scenario(); var good = context(d, "van-1");
        assertFalse(CompanyTourExecution.prepare(d, context(d, "missing"), s).readiness().ready());
        var points = new HashMap<>(good.locations()); points.remove(d.tours().getFirst().stops().getFirst().stopId());
        assertFalse(CompanyTourExecution.prepare(d, copy(good, good.simulationDate(), good.tours(), points, false), s).readiness().ready());
        points.put(d.tours().getFirst().stops().getFirst().stopId(), point(9));
        assertTrue(CompanyTourExecution.prepare(d, copy(good, good.simulationDate(), good.tours(), points, false), s).readiness().problems().stream().anyMatch(p -> p.code().equals("outside-region")));
        assertFalse(CompanyTourExecution.prepare(d, copy(good, "2026-10-25", good.tours(), good.locations(), true), s).readiness().ready());
        assertFalse(CompanyTourExecution.prepare(d, copy(good, "2026-10-20", good.tours(), good.locations(), false), s).readiness().ready());
        var p = good.tours().getFirst(); var reversed = new ArrayList<>(p.stopOrder()); Collections.reverse(reversed);
        var wrong = new CompanyTourExecution.TourPlan(p.tourId(), true, p.vehicleId(), p.startTime(), p.returnTime(), p.start(), p.end(), reversed);
        assertFalse(CompanyTourExecution.prepare(d, copy(good, good.simulationDate(), List.of(wrong), good.locations(), false), s).readiness().ready());
    }
    @Test void localAddressLookupPreservesAmbiguityAndDoesNotSendData() {
        var d = draft(); String header = "street;postalCode;city;lon;lat\n";
        var result = CompanyTourLocations.match(bytes(header + "Beispielweg 1;01234;Teststadt;8.203;48.85\n"
                + "Beispielweg 2;01234;Teststadt;8.205;48.85\n" + "Beispielweg 2;01234;Teststadt;8.206;48.85\n"), "places.csv", d);
        assertEquals(1, result.matched()); assertEquals(1, result.ambiguous()); assertEquals(0, result.missing());
        assertTrue(result.matches().getFirst().candidates().getFirst().provenance().contains(result.sourceHash()));
        assertThrows(IllegalArgumentException.class, () -> CompanyTourLocations.match(bytes(header + "Beispielweg 1;01234;Teststadt;NaN;48.85\n"), "places.csv", d));
    }
    @Test void addressSeparatorsRemainInSourceAndNearestNumberIsExplicitlyApproximate() {
        var mapper = new CompanyTourMapper();
        var table = new TourTableReader().read(bytes(CSV.replace("Beispielweg 1;", "Beispielweg 1/2;")), "plan.csv").getFirst();
        var draft = mapper.apply(table, mapper.suggest(mapper.profiles(List.of(table)).getFirst()), "synthetic");
        var lookup = CompanyTourLocations.match(bytes("street;postalCode;city;lon;lat\nBeispielweg 12;01234;Teststadt;8.203;48.85\n"), "places.csv", draft);
        assertEquals(2, lookup.matched()); assertEquals(0, lookup.missing());
        assertEquals("Beispielweg 1/2",draft.tours().getFirst().stops().getFirst().location().street());
        assertTrue(lookup.matches().stream().flatMap(m->m.candidates().stream())
                .allMatch(p->p.provenance().contains("approximate-nearest-house-number")),
                "House-number separators must never collapse into an unlabelled exact match");
    }
    @Test void confirmationActivationRevisionInvalidationAndFrozenBatchAreSafe() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules(); var props = new SynthesisProperties(); props.setDataDir(dir.toString());
        var repo = new ScenarioRepository(json, props); var s = scenario(); repo.save(s);
        var service = new CompanyTourImportService(repo, json, new TourMappingPlanner(json, new TourImportProperties()), props);
        var upload = service.upload(s.id, bytes(CSV), "plan.csv"); service.confirm(s.id, upload.importId(), upload.mapping());
        var contextual = service.context(s.id, upload.importId(), context(upload.draft(), "van-1"));
        assertTrue(contextual.execution().ready()); assertFalse(contextual.active());
        var active = service.activate(s.id, upload.importId(), true); assertTrue(active.active());
        var frozen = CompanyTourImportService.loadForGeneration(repo, json, repo.findById(s.id).orElseThrow());
        assertEquals(3, frozen.legs().size());
        service.context(s.id, upload.importId(), context(upload.draft(), "missing"));
        assertFalse(repo.findById(s.id).orElseThrow().companyTours.enabled);
        assertThrows(IllegalArgumentException.class, () -> service.activate(s.id, upload.importId(), true));
        assertEquals(3, frozen.legs().size());
    }
    @Test void depotAnswersPersistWhileCoordinatesRemainRequiredAndRevisionsStayExplicit() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules(); var props = new SynthesisProperties(); props.setDataDir(dir.toString());
        var repo = new ScenarioRepository(json, props); var scenario = scenario(); repo.save(scenario);
        var service = new CompanyTourImportService(repo, json, new TourMappingPlanner(json, new TourImportProperties()), props);
        var upload = service.upload(scenario.id, bytes(CSV), "plan.csv"); service.confirm(scenario.id, upload.importId(), upload.mapping());
        var c = context(upload.draft(), "van-1"); var tour = c.tours().getFirst();
        var addresses = Map.of(tour.tourId(),new CompanyTourExecution.DepotAddresses("Beispieldepot 21, 01234 Teststadt","Beispieldepot 21, 01234 Teststadt"));
        var unresolved = new CompanyTourExecution.Point(null,null,"not-located");
        var missing = new CompanyTourExecution.TourPlan(tour.tourId(),true,tour.vehicleId(),tour.startTime(),tour.returnTime(),unresolved,unresolved,tour.stopOrder());
        var answered = new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,false,c.windowPolicy(),List.of(missing),c.locations(),false,false,addresses);
        var saved = service.context(scenario.id,upload.importId(),answered);
        assertFalse(saved.execution().ready()); assertFalse(saved.active());
        assertEquals(addresses,service.list(scenario.id).getFirst().context().depotAddresses());
        assertNull(service.list(scenario.id).getFirst().context().tours().getFirst().start().lon());
        assertThrows(IllegalArgumentException.class,()->service.activate(scenario.id,upload.importId(),true));
        var complete = new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,true,c.windowPolicy(),c.tours(),c.locations(),false,false,addresses);
        assertTrue(CompanyTourExecution.prepare(upload.draft(),complete,scenario).readiness().ready());
        assertNotEquals(CompanyTourExecution.prepare(upload.draft(),c,scenario).readiness().executionHash(),CompanyTourExecution.prepare(upload.draft(),complete,scenario).readiness().executionHash());
        var empty = new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,true,c.windowPolicy(),c.tours(),c.locations(),false,false,Map.of(tour.tourId(),new CompanyTourExecution.DepotAddresses("", "")));
        assertEquals(CompanyTourExecution.prepare(upload.draft(),c,scenario).readiness().executionHash(),CompanyTourExecution.prepare(upload.draft(),empty,scenario).readiness().executionHash());
        var foreign = new CompanyTourExecution.Context(c.companyId(),c.simulationDate(),false,true,true,true,c.windowPolicy(),c.tours(),c.locations(),false,false,Map.of("another-import-tour",addresses.get(tour.tourId())));
        assertThrows(IllegalArgumentException.class,()->service.context(scenario.id,upload.importId(),foreign));
    }
    private static CompanyTourExecution.Context copy(CompanyTourExecution.Context c, String date, List<CompanyTourExecution.TourPlan> tours,
            Map<String, CompanyTourExecution.Point> locations, boolean shift) {
        return new CompanyTourExecution.Context(c.companyId(), date, shift, c.sequenceConfirmed(), c.windowsConfirmed(), c.locationsConfirmed(), c.windowPolicy(), tours, locations);
    }
}
