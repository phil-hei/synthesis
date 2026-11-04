package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TourImportAssistantTest {
    @TempDir Path dir;
    @Test void documentAnalysisRequiresReviewAndProfilesReextractNewSources()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{
            calls.incrementAndGet();String request=new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String tour=request.contains("T2")?"T2":"T1";
            var row=json.createObjectNode();Map.of("tour",tour,"date","20261019","transaction","A1","street","Weg 1","postalCode","01234","city","Teststadt","arrival","08:00","departure","08:10").forEach((k,v)->row.set(k,json.createObjectNode().put("block",0).put("quote",v).put("occurrence",0)));
            var response=json.createObjectNode();response.putArray("rows").add(row);response.putArray("ignoredBlocks");
            byte[] out=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",response.toString())))));
            e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();
        });server.start();
        try{
            var config=new TourImportProperties();config.setLlmEnabled(true);config.setSourceAnalysisEnabled(true);config.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var props=new SynthesisProperties();props.setDataDir(dir.toString());var repo=new ScenarioRepository(json,props);var scenario=new Scenario();scenario.id="test";repo.save(scenario);
            var service=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,config));
            var uploaded=service.upload("test",CompanyTourImportTest.bytes("T1 20261019 A1 Weg 1 01234 Teststadt 08:00 08:10"),"a.txt");assertEquals(0,calls.get());
            var proposal=service.analyze("test",uploaded.importId(),"auto");assertEquals(1,calls.get());assertFalse(proposal.mappingConfirmed());assertFalse(service.list("test").getFirst().mappingConfirmed());
            assertThrows(IllegalArgumentException.class,()->service.confirm("test",uploaded.importId(),proposal.mapping()));
            var saved=service.confirm("test",uploaded.importId(),proposal.mapping(),true);assertTrue(saved.mappingConfirmed());assertFalse(saved.active());assertFalse(saved.draft().simulationReady());
            var profile=service.saveProfile("test",uploaded.importId(),"Firma A");assertEquals("document-evidence",profile.strategy());assertFalse(json.writeValueAsString(profile).contains("Weg 1"));
            var next=service.upload("test",CompanyTourImportTest.bytes("T2 20261019 A1 Weg 1 01234 Teststadt 08:00 08:10"),"b.txt");
            var reapplied=service.applyProfile("test",next.importId(),profile.id());assertEquals(2,calls.get());assertFalse(reapplied.mappingConfirmed());assertEquals("T2",reapplied.draft().tours().getFirst().sourceTourCode());
            assertTrue(service.profiles("test").stream().anyMatch(p->p.id().equals(profile.id())));
        }finally{server.stop(0);}
    }
    @Test void tableProfileRejectsChangedHeadersWithoutSavingPreview()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var props=new SynthesisProperties();props.setDataDir(dir.toString());var repo=new ScenarioRepository(json,props);var scenario=new Scenario();scenario.id="test";repo.save(scenario);
        var service=new CompanyTourImportService(repo,json,new TourMappingPlanner(json,new TourImportProperties()));
        var a=service.upload("test",CompanyTourImportTest.bytes(CompanyTourImportTest.CSV),"a.csv");service.confirm("test",a.importId(),a.mapping());var p=service.saveProfile("test",a.importId(),"CSV");
        var b=service.upload("test",CompanyTourImportTest.bytes(CompanyTourImportTest.CSV.replace("T1;","T2;")),"b.csv");assertFalse(service.applyProfile("test",b.importId(),p.id()).mappingConfirmed());
        var changed=service.upload("test",CompanyTourImportTest.bytes(CompanyTourImportTest.CSV.replace("Strasse;","Andere Spalte;")),"c.csv");assertThrows(IllegalArgumentException.class,()->service.applyProfile("test",changed.importId(),p.id()));
    }
}
