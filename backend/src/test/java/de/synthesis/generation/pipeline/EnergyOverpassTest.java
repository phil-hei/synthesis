package de.synthesis.generation.pipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class EnergyOverpassTest {
    @Test void queryFetchesAllOsmObjectTypesAndRecordsActualMirror()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<String> query=new AtomicReference<>();
        server.createContext("/unavailable",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        server.createContext("/mirror",exchange->{query.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),StandardCharsets.UTF_8));byte[] data="{\"elements\":[]}".getBytes();exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();
        try {
            String root="http://127.0.0.1:"+server.getAddress().getPort();var props=new SynthesisProperties();props.getOverpass().setEndpoint(root+"/unavailable");props.getOverpass().setMirrors(List.of(root+"/mirror"));var region=new Scenario.Region();region.minLon=7;region.maxLon=8;region.minLat=51;region.maxLat=52;
            var result=new OverpassClient(props,new ObjectMapper()).fetchEnergyStations(region);assertEquals(root+"/mirror",result.endpoint());assertTrue(result.data().path("elements").isArray());assertTrue(query.get().contains("nwr[\"amenity\""));assertTrue(query.get().contains("out meta center;"));assertTrue(query.get().contains("51.0,7.0,52.0,8.0"));
        }finally{server.stop(0);}
    }
}
