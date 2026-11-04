package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TourMappingPlannerTest {
    @Test void localHttpSuggestionIsTypedAndNeverSendsCustomerCellsOrUnknownHeaders() throws Exception {
        var request = new AtomicReference<String>(); var response = new AtomicReference<>("{\"columns\":{\"tour\":0}}");
        var slowBody = new AtomicBoolean(false);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", response.get())))));
            exchange.sendResponseHeaders(200, out.length);
            if (slowBody.get()) {
                exchange.getResponseBody().write(out, 0, 1); exchange.getResponseBody().flush();
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try { exchange.getResponseBody().write(out); } finally { exchange.close(); }
        }); server.start();
        try {
            var config = new TourImportProperties(); config.setLlmEnabled(true); config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var table = new TourTableReader().read(CompanyTourImportTest.bytes(CompanyTourImportTest.CSV.replace("Name;", "private@example.test;")), "a.csv").getFirst();
            var mapper = new CompanyTourMapper(); var profile = mapper.profiles(List.of(table)).getFirst(); var plan = mapper.suggest(profile);
            var planner = new TourMappingPlanner(json, config); var mapped = planner.propose(profile, plan, 42);
            assertEquals(Map.of("tour", 0), mapped.columns()); assertFalse(request.get().contains("Privater Name"));
            assertFalse(request.get().contains("private@example.test")); assertFalse(request.get().contains("Beispielweg"));
            assertEquals(42, json.readTree(request.get()).get("seed").asInt()); assertEquals(0, json.readTree(request.get()).get("temperature").asDouble());
            response.set("{\"columns\":{\"tour\":0},\"execute\":\"malicious\"}");
            assertThrows(IllegalStateException.class, () -> planner.propose(profile, plan, 42));
            response.set("{\"columns\":{\"tour\":0,\"date\":0}}");
            assertThrows(IllegalStateException.class, () -> planner.propose(profile, plan, 42));
            config.setEndpoint("https://example.test/v1");
            assertThrows(IllegalStateException.class, () -> planner.propose(profile, plan, 42));
            config.setLlmEnabled(false); assertThrows(IllegalStateException.class, () -> planner.propose(profile, plan, 42));
            config.setLlmEnabled(true); config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); config.setTimeoutSeconds(1);
            slowBody.set(true);
            assertTimeoutPreemptively(java.time.Duration.ofMillis(1700), () ->
                    assertThrows(IllegalStateException.class, () -> planner.propose(profile, plan, 42)));
        } finally { server.stop(0); }
    }
}
