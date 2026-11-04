package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompanyTourImportTest {
    @TempDir Path dir;
    final TourTableReader reader = new TourTableReader();
    final CompanyTourMapper mapper = new CompanyTourMapper();
    static final String HEADER = "Tour;Liefertag;Vorgang;Auftragsnummer;Strasse;PLZ;Ort;geplante Ankunft PASS;geplante Abfahrt PASS;Zeitfenster Kunde;Zeitfenster Kunde;Arbeitswerte;Name;\n";
    static final String CSV = HEADER
            + "T1;20260929;v1;order1;Beispielweg 1;01234;Teststadt;09:00:00;10:15:00;08:00;18:00;15;Privater Name;unknown\n"
            + "T1;20260929;v2;order1;Beispielweg 1;01234;Teststadt;09:00:00;10:15:00;08:00;18:00;60;Privater Name;unknown\n"
            + "T1;20260929;v3;order2;Testweg 2;01234;Teststadt;11:00;11:30;08:00;18:00;30;Anderer Name;unknown\n";

    @Test void groupsOrdersAndPreservesUnknownSourceWithoutCustomerNamesInCanonicalDraft() throws Exception {
        var table = reader.read(bytes(CSV), "plan.csv").getFirst();
        var plan = mapper.suggest(mapper.profiles(List.of(table)).getFirst());
        var draft = mapper.apply(table, plan, "source");
        assertEquals(3, draft.mappedRows()); assertEquals(0, draft.rejectedRows());
        assertEquals(1, draft.tours().size()); assertEquals(2, draft.tours().getFirst().stops().size());
        var stop = draft.tours().getFirst().stops().getFirst();
        assertEquals(4500, stop.plannedDwellSec()); assertEquals(2, stop.orders().size());
        assertEquals("01234", stop.location().postalCode()); assertEquals(List.of(2, 3), stop.sourceRows());
        assertEquals(9, plan.columns().get("windowStart")); assertEquals(10, plan.columns().get("windowEnd"));
        assertFalse(draft.simulationReady()); assertEquals("needs-context", draft.status());
        String json = new ObjectMapper().writeValueAsString(draft);
        assertFalse(json.contains("Privater Name")); assertFalse(json.contains("\"transactionId\":\"v1\""));
        assertEquals(List.of(12, 13), draft.unmappedColumns());
        assertTrue(table.rows().get(1).cells().contains("Privater Name"));
    }
    @Test void supportsQuotedNewlinesEscapedQuotesAndBom() {
        var table = reader.read(bytes("\uFEFFa,b,c\r\n\"x,y\",\"a\"\"b\",\"line1\nline2\"\r\nz,q,p\r\n"), "a.csv").getFirst();
        assertEquals(List.of("x,y", "a\"b", "line1\nline2"), table.rows().get(1).cells());
        assertEquals(4, table.rows().get(2).sourceRow());
        assertThrows(IllegalArgumentException.class, () -> reader.read(bytes("a;b\n\"unclosed;b"), "bad.csv"));
    }
    @Test void rejectsBadMappingAndInvalidRowsWithoutSilentSuccess() {
        var table = reader.read(bytes(CSV), "a.csv").getFirst(); var plan = mapper.suggest(mapper.profiles(List.of(table)).getFirst());
        var cols = new HashMap<>(plan.columns()); cols.put("execute", 0);
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(table, mapping(plan, cols), "s"));
        cols.remove("execute"); cols.put("arrival", cols.get("departure"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(table, mapping(plan, cols), "s"));
        var bad = reader.read(bytes(CSV.replace("20260929", "20260230")), "bad.csv").getFirst();
        var draft = mapper.apply(bad, plan, "s");
        assertEquals("invalid", draft.status()); assertEquals(3, draft.rejectedRows()); assertEquals(0, draft.mappedRows());
        assertTrue(draft.issues().stream().anyMatch(i -> i.code().equals("invalid-date")));
    }
    @Test void detectsDuplicateTransactionsOverlapsAndAmbiguousDst() {
        var duplicate = reader.read(bytes(CSV.replace(";v2;", ";v1;")), "a.csv").getFirst();
        var plan = mapper.suggest(mapper.profiles(List.of(duplicate)).getFirst());
        assertEquals("invalid", mapper.apply(duplicate, plan, "s").status());
        var overlap = reader.read(bytes(CSV.replace("11:00;11:30", "10:00;10:30")), "a.csv").getFirst();
        assertTrue(mapper.apply(overlap, plan, "s").issues().stream().anyMatch(i -> i.code().equals("overlapping-stops")));
        var dst = reader.read(bytes(CSV.replace("20260929", "20261025").replace("09:00:00", "02:30:00")), "a.csv").getFirst();
        assertTrue(mapper.apply(dst, plan, "s").issues().stream().anyMatch(i -> i.code().equals("ambiguous-or-invalid-local-time")));
    }
    @Test void frozenMappingHashDoesNotDependOnJsonObjectFieldOrder() {
        var table = reader.read(bytes(CSV), "a.csv").getFirst(); var plan = mapper.suggest(mapper.profiles(List.of(table)).getFirst());
        var reversed = new LinkedHashMap<String, Integer>(); var keys = new ArrayList<>(plan.columns().keySet());
        Collections.reverse(keys); keys.forEach(k -> reversed.put(k, plan.columns().get(k)));
        assertEquals(mapper.apply(table, plan, "s").mappingHash(), mapper.apply(table, mapping(plan, reversed), "s").mappingHash());
        assertEquals(mapper.apply(table, plan, "s").tours(), mapper.apply(table, plan, "s").tours());
    }
    @Test void preservesDistinctOrderWindowsAtOneStopAndNamespacesRepeatedTransactionsByDate() {
        var raw = CSV.replace("10:15:00;08:00;18:00;60", "10:15:00;09:00;18:00;60");
        var table = reader.read(bytes(raw), "a.csv").getFirst(); var plan = mapper.suggest(mapper.profiles(List.of(table)).getFirst());
        var draft = mapper.apply(table, plan, "source"); var stop = draft.tours().getFirst().stops().getFirst();
        assertEquals("needs-context", draft.status()); assertEquals("", stop.windowStart());
        assertEquals("08:00", stop.orders().get(0).windowStart()); assertEquals("09:00", stop.orders().get(1).windowStart());
        assertTrue(draft.issues().stream().anyMatch(i -> i.code().equals("conflicting-window")));
        var repeated = reader.read(bytes(CSV + CSV.substring(HEADER.length()).replace("20260929", "20260930")), "days.csv").getFirst();
        var days = mapper.apply(repeated, plan, "source");
        assertEquals(2, days.tours().size()); assertEquals(6, days.mappedRows());
        assertNotEquals(days.tours().get(0).stops().get(0).orders().get(0).transactionId(), days.tours().get(1).stops().get(0).orders().get(0).transactionId());
    }
    @Test void readsExcelRelationshipsSharedAndInlineStringsSparseCellsAndTypedDates() throws Exception {
        byte[] xlsx = workbook(false, false);
        var tables = reader.read(xlsx, "plan.xlsx");
        assertEquals(2, tables.size()); assertEquals("Touren", tables.getFirst().name());
        assertEquals("2026-09-29", tables.getFirst().rows().get(1).cells().get(1));
        assertEquals("09:00:00", tables.getFirst().rows().get(1).cells().get(7));
        assertEquals("01234", tables.getFirst().rows().get(1).cells().get(5));
        var plan = mapper.suggest(mapper.profiles(tables).getFirst());
        assertEquals(1, mapper.apply(tables.getFirst(), plan, "s").mappedRows());
    }
    @Test void rejectsExcelFormulaMergedCellsAndExternalEntities() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> reader.read(workbook(true, false), "f.xlsx"));
        assertThrows(IllegalArgumentException.class, () -> reader.read(workbook(false, true), "m.xlsx"));
        var zip = new LinkedHashMap<String, String>();
        zip.put("xl/workbook.xml", "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>");
        zip.put("xl/_rels/workbook.xml.rels", "<Relationships/>");
        assertThrows(IllegalArgumentException.class, () -> reader.read(zip(zip), "xxe.xlsx"));
    }
    @Test void persistenceUploadIdempotencyConfirmationAndDeletionWorkThroughHttp() throws Exception {
        var service = service(); var mvc = MockMvcBuilders.standaloneSetup(new CompanyTourController(service)).build();
        mvc.perform(multipart("/api/scenarios/test/company-tours").file(new MockMultipartFile("file", "plan.csv", "text/csv", bytes(CSV))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mappingConfirmed").value(false));
        var uploaded = service.upload("test", bytes(CSV), "plan.csv");
        var nullDate = new CompanyTourMapper.Mapping(uploaded.mapping().tableId(), uploaded.mapping().headerRow(), uploaded.mapping().columns(), null, "Europe/Berlin", true);
        mvc.perform(post("/api/scenarios/test/company-tours/" + uploaded.importId() + "/preview")
                .contentType("application/json").content(new ObjectMapper().writeValueAsBytes(new CompanyTourImportService.MappingRequest(nullDate))))
                .andExpect(status().isBadRequest());
        assertEquals(1, service.list("test").size()); assertFalse(uploaded.mappingConfirmed());
        mvc.perform(get("/api/scenarios/test/company-tours/" + uploaded.importId() + "/export")).andExpect(status().isBadRequest());
        var confirmed = service.confirm("test", uploaded.importId(), uploaded.mapping()); assertTrue(confirmed.mappingConfirmed());
        assertTrue(service.upload("test", bytes(CSV), "again.csv").mappingConfirmed());
        mvc.perform(get("/api/scenarios/test/company-tours/" + uploaded.importId() + "/export"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.simulationReady").value(false));
        var cols = new HashMap<>(uploaded.mapping().columns()); cols.remove("tour");
        assertThrows(IllegalArgumentException.class, () -> service.confirm("test", uploaded.importId(), mapping(uploaded.mapping(), cols)));
        assertTrue(service.list("test").getFirst().mappingConfirmed());
        assertThrows(IllegalArgumentException.class, () -> service.export("test", "../escape"));
        mvc.perform(delete("/api/scenarios/test/company-tours/" + uploaded.importId())).andExpect(status().isNoContent());
        assertTrue(service.list("test").isEmpty());
    }
    private CompanyTourImportService service() {
        var props = new SynthesisProperties(); props.setDataDir(dir.toString()); var json = new ObjectMapper().findAndRegisterModules();
        var scenarios = new ScenarioRepository(json, props); var scenario = new Scenario(); scenario.id = "test"; scenarios.save(scenario);
        return new CompanyTourImportService(scenarios, json, new TourMappingPlanner(json, new TourImportProperties()));
    }
    static CompanyTourMapper.Mapping mapping(CompanyTourMapper.Mapping p, Map<String, Integer> c) {
        return new CompanyTourMapper.Mapping(p.tableId(), p.headerRow(), c, p.dateFormat(), p.timezone(), p.groupStops());
    }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    // Independent OOXML fixture writer, no dependency on the production reader.
    static byte[] workbook(boolean formula, boolean merged) throws IOException {
        var parts = new LinkedHashMap<String, String>();
        parts.put("xl/workbook.xml", "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Touren' sheetId='1' r:id='r1'/><sheet name='Hinweise' sheetId='2' r:id='r2'/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id='r1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/tour.xml'/><Relationship Id='r2' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/info.xml'/></Relationships>");
        parts.put("xl/sharedStrings.xml", "<sst><si><t>Tour</t></si><si><t>Teststadt</t></si></sst>");
        parts.put("xl/styles.xml", "<styleSheet><numFmts><numFmt numFmtId='164' formatCode='00000'/></numFmts><cellXfs><xf numFmtId='0'/><xf numFmtId='14'/><xf numFmtId='21'/><xf numFmtId='164'/></cellXfs></styleSheet>");
        String[] headers = HEADER.strip().split(";", -1); StringBuilder head = new StringBuilder(), row = new StringBuilder();
        for (int i = 0; i < headers.length; i++) head.append(cell(i, 1, headers[i]));
        row.append(cell(0, 2, "T1")).append("<c r='B2' s='1'><v>46294</v></c>").append(cell(2, 2, "v1")).append(cell(3, 2, "o1"))
                .append(cell(4, 2, "Beispielweg 1")).append("<c r='F2' s='3'><v>1234</v></c><c r='G2' t='s'><v>1</v></c>")
                .append("<c r='H2' s='2'><v>0.375</v></c>").append(cell(8, 2, "10:15:00")).append(cell(9, 2, "08:00")).append(cell(10, 2, "18:00"));
        if (formula) row.append("<c r='L2'><f>1+1</f><v>2</v></c>");
        parts.put("xl/worksheets/tour.xml", "<worksheet><sheetData><row r='1'>" + head + "</row><row r='2'>" + row + "</row></sheetData>"
                + (merged ? "<mergeCells><mergeCell ref='A1:B1'/></mergeCells>" : "") + "</worksheet>");
        parts.put("xl/worksheets/info.xml", "<worksheet><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>Hinweis</t></is></c></row></sheetData></worksheet>");
        return zip(parts);
    }
    static String cell(int col, int row, String value) { return "<c r='" + (char) ('A' + col) + row + "' t='inlineStr'><is><t>" + value + "</t></is></c>"; }
    static byte[] zip(Map<String, String> parts) throws IOException {
        var bytes = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(bytes)) {
            for (var p : parts.entrySet()) { zip.putNextEntry(new ZipEntry(p.getKey())); zip.write(bytes(p.getValue())); zip.closeEntry(); }
        } return bytes.toByteArray();
    }
}
