package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.*;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyTourRevisionsTest {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    ScenarioRepository repo;
    CompanyTourImportService service;
    Scenario scenario;

    void setup() throws Exception {
        var props = new SynthesisProperties(); props.setDataDir(dir.toString());
        repo = new ScenarioRepository(json, props); scenario = CompanyTourExecutionTest.scenario();
        scenario.targetModels = new ArrayList<>(List.of("mobilecity")); // Guard belongs before export selection.
        var config = VehicleJson.MAPPER.valueToTree(scenario.vehicleConfiguration);
        var vehicles = (com.fasterxml.jackson.databind.node.ArrayNode) config.path("initialVehicles");
        var second = vehicles.get(0).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) second).put("vehicleId", "van-2"); vehicles.add(second);
        var third = vehicles.get(0).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) third).put("vehicleId", "van-3"); vehicles.add(third);
        scenario.vehicleConfiguration = VehicleJson.MAPPER.treeToValue(config, VehicleConfiguration.class); repo.save(scenario);
        service = new CompanyTourImportService(repo, json, new TourMappingPlanner(json, new TourImportProperties()));
    }
    CompanyTourImportService.ImportView prepare(String csv, String vehicle, String company, boolean shift) throws Exception {
        var upload = service.upload(scenario.id, CompanyTourExecutionTest.bytes(csv), "plan.csv");
        service.confirm(scenario.id, upload.importId(), upload.mapping());
        var base = CompanyTourExecutionTest.context(upload.draft(), vehicle);
        var context = new CompanyTourExecution.Context(company, base.simulationDate(), shift, true, true, true,
                base.windowPolicy(), base.tours(), base.locations());
        var view = service.context(scenario.id, upload.importId(), context);
        assertTrue(view.execution().ready(), view.execution().problems().toString()); return view;
    }
    CompanyTourExecution.Batch selected() throws Exception {
        return CompanyTourImportService.loadForGeneration(repo, json, repo.findById(scenario.id).orElseThrow());
    }
    @Test void changedFileAndVehicleCannotDuplicateSameBusinessTourAndFailurePreservesSelection() throws Exception {
        setup(); var original = prepare(CompanyTourExecutionTest.CSV, "van-1", "company-a", false);
        service.activate(scenario.id, original.importId(), true); var frozen = selected();
        var revised = prepare(CompanyTourExecutionTest.CSV.replace("00:03", "00:04"), "van-2", "company-a", false);
        byte[] before = Files.readAllBytes(repo.scenarioDir(scenario.id).resolve("scenario.json"));
        var error = assertThrows(IllegalArgumentException.class, () -> service.activate(scenario.id, revised.importId(), true));
        assertTrue(error.getMessage().contains("Planrevision")); assertTrue(error.getMessage().contains("T1"));
        assertTrue(error.getMessage().contains(original.importId().substring(0, 12)));
        assertArrayEquals(before, Files.readAllBytes(repo.scenarioDir(scenario.id).resolve("scenario.json")));
        assertEquals(1, selected().imports().size()); assertEquals(3, frozen.legs().size());
        // Deliberate replacement through existing controls keeps both source drafts.
        service.activate(scenario.id, original.importId(), false); service.activate(scenario.id, revised.importId(), true);
        assertEquals(revised.importId(), selected().imports().getFirst().importId()); assertEquals(2, service.list(scenario.id).size());
        assertEquals(original.importId(), frozen.imports().getFirst().importId());
    }
    @Test void directSelectionAndDateShiftCannotBypassRevisionGuard() throws Exception {
        setup(); var original = prepare(CompanyTourExecutionTest.CSV, "van-1", "company-a", false);
        service.activate(scenario.id, original.importId(), true);
        var shifted = prepare(CompanyTourExecutionTest.CSV.replace("20261019", "20261020"), "van-2", "company-a", true);
        var direct = repo.findById(scenario.id).orElseThrow();
        direct.companyTours.imports.add(new CompanyTourExecution.Selection(shifted.importId(), shifted.execution().executionHash()));
        assertThrows(IllegalArgumentException.class, () -> CompanyTourImportService.loadForGeneration(repo, json, direct));
        assertEquals(1, selected().imports().size());
    }
    @Test void differentCompaniesAndTourCodesRemainAdditive() throws Exception {
        setup(); var a = prepare(CompanyTourExecutionTest.CSV, "van-1", "company-a", false);
        service.activate(scenario.id, a.importId(), true);
        var b = prepare(CompanyTourExecutionTest.CSV.replace("T1;", "T2;"), "van-2", "company-a", false);
        service.activate(scenario.id, b.importId(), true);
        var c = prepare(CompanyTourExecutionTest.CSV.replace("00:03", "00:04"), "van-3", "company-b", false);
        service.activate(scenario.id, c.importId(), true);
        assertEquals(3, selected().imports().size()); assertEquals(9, selected().legs().size());
    }
    @Test void excludedTourDoesNotCreateARevisionConflict() throws Exception {
        setup(); var a = prepare(CompanyTourExecutionTest.CSV, "van-1", "company-a", false);
        service.activate(scenario.id, a.importId(), true);
        var extra = CompanyTourExecutionTest.CSV.substring(CompanyTourExecutionTest.CSV.indexOf('\n') + 1).replace("T1;", "T2;").replace(";A1;", ";C1;").replace(";A2;", ";C2;").replace(";B1;", ";D1;");
        var upload = service.upload(scenario.id, CompanyTourExecutionTest.bytes(CompanyTourExecutionTest.CSV + extra), "plan.csv");
        service.confirm(scenario.id, upload.importId(), upload.mapping());
        var t1 = upload.draft().tours().stream().filter(t -> t.sourceTourCode().equals("T1")).findFirst().orElseThrow();
        var t2 = upload.draft().tours().stream().filter(t -> t.sourceTourCode().equals("T2")).findFirst().orElseThrow();
        var base = CompanyTourExecutionTest.context(upload.draft(), "van-2");
        var plan = base.tours().getFirst();
        var include = new CompanyTourExecution.TourPlan(t2.tourId(), true, "van-2", plan.startTime(), plan.returnTime(), plan.start(), plan.end(), t2.stops().stream().map(CompanyTourMapper.Stop::stopId).toList());
        var exclude = new CompanyTourExecution.TourPlan(t1.tourId(), false, "van-2", plan.startTime(), plan.returnTime(), plan.start(), plan.end(), List.of());
        Map<String, CompanyTourExecution.Point> points = new HashMap<>();
        t2.stops().forEach(stop -> points.put(stop.stopId(), CompanyTourExecutionTest.point(8.203)));
        var context = new CompanyTourExecution.Context("company-a", base.simulationDate(), false, true, true, true, base.windowPolicy(), List.of(exclude, include), points);
        assertTrue(service.context(scenario.id, upload.importId(), context).execution().ready());
        service.activate(scenario.id, upload.importId(), true); assertEquals(6, selected().legs().size());
    }
}
