package de.synthesis.companytour;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TourSourceAnalysisTest {
    final ObjectMapper json=new ObjectMapper();
    final TourSourceReader.Source source=new TourSourceReader.Source(TourSourceReader.CONTRACT,"s","txt",List.of(),List.of(new TourSourceReader.Block(0,"line:1","T1 20261019 A1 Weg 1 01234 Teststadt 08:00 08:10"),new TourSourceReader.Block(1,"line:2","Interne Notiz")),List.of());
    JsonNode proposal()throws Exception{
        var row=json.createObjectNode();var values=Map.of("tour","T1","date","20261019","transaction","A1","street","Weg 1","postalCode","01234","city","Teststadt","arrival","08:00","departure","08:10");
        values.forEach((k,v)->row.set(k,json.createObjectNode().put("block",0).put("quote",v)));
        var root=json.createObjectNode();root.putArray("rows").add(row);root.putArray("ignoredBlocks").addObject().put("block",1).put("reason","Interne Notiz ohne Halt");return root;
    }
    @Test void materializesOnlyExactQuotesAndAccountsForIgnoredContent()throws Exception{
        var a=TourSourceAnalysis.materialize(source,proposal(),json);assertEquals(8,a.evidence().getFirst().fields().size());assertEquals(1,a.ignoredBlocks().size());
        var d=new CompanyTourMapper().apply(a.tables().getFirst(),a.mapping(),"s");assertEquals(1,d.mappedRows());assertEquals("needs-context",d.status());assertFalse(d.simulationReady());assertEquals("01234",d.tours().getFirst().stops().getFirst().location().postalCode());
    }
    @Test void rejectsInventedValuesOmissionsUnknownFieldsAndIntegerOverflow()throws Exception{
        var p=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)p.path("rows").get(0).path("street")).put("quote","Erfundene Straße");assertThrows(IllegalArgumentException.class,()->TourSourceAnalysis.materialize(source,p,json));
        var missing=proposal();((com.fasterxml.jackson.databind.node.ArrayNode)missing.path("ignoredBlocks")).removeAll();assertThrows(IllegalArgumentException.class,()->TourSourceAnalysis.materialize(source,missing,json));
        var unknown=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)unknown).put("execute","code");assertThrows(IllegalArgumentException.class,()->TourSourceAnalysis.materialize(source,unknown,json));
        var overflow=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)overflow.path("rows").get(0).path("tour")).put("block",4294967296L);assertThrows(IllegalArgumentException.class,()->TourSourceAnalysis.materialize(source,overflow,json));
    }
    @Test void tableAnalysisUsesExplicitLocalSourceModeAndRejectsCoercedIndices()throws Exception{
        var source=new TourSourceReader(json,new TourImportProperties()).read(CompanyTourImportTest.bytes(CompanyTourImportTest.CSV),"a.csv");
        var mapper=new CompanyTourMapper();var mapping=mapper.bestMapping(source.tables());
        var reply=new java.util.concurrent.atomic.AtomicReference<>(json.valueToTree(Map.of("mapping",mapping)).toString());
        var request=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{request.set(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] out=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",reply.get())))));e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();});server.start();
        try{
            var config=new TourImportProperties();config.setLlmEnabled(true);config.setSourceAnalysisEnabled(true);config.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");var planner=new TourMappingPlanner(json,config);
            var result=TourSourceAnalysis.analyze(source,planner,json,42);assertEquals(mapping,result.mapping());assertTrue(request.get().contains("Privater Name"));
            var bad=json.valueToTree(Map.of("mapping",mapping));((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("mapping").path("columns")).put("tour","0");reply.set(bad.toString());
            assertThrows(IllegalArgumentException.class,()->TourSourceAnalysis.analyze(source,planner,json,42));
        }finally{server.stop(0);}
    }
    @Test void sourceAnalysisRequiresBothFlags(){var config=new TourImportProperties();config.setLlmEnabled(true);assertThrows(IllegalStateException.class,()->TourSourceAnalysis.analyze(source,new TourMappingPlanner(json,config),json,42));}
}
