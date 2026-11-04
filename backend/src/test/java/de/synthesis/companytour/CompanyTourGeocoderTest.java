package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompanyTourGeocoderTest {
    @TempDir Path dir;
    @Test void requiresExplicitLocalConfigurationAndNeverUsesPublicServices() {
        var config=new TourImportProperties();var coder=new CompanyTourGeocoder(new ObjectMapper(),config);
        assertFalse(coder.info().enabled());assertThrows(IllegalArgumentException.class,()->coder.lookup("hash",List.of(new CompanyTourGeocoder.Target("stop:s","Testweg 1")),dir));
        config.setGeocodingEnabled(true);
        for(String url:List.of("https://nominatim.openstreetmap.org","http://127.0.0.1@evil.example","http://127.0.0.1:1234/?secret=x")){
            config.setGeocodingEndpoint(url);assertFalse(coder.info().enabled());
        }
    }
    @Test void retainsAmbiguousCandidatesDeduplicatesCoordinatesAndCachesIdenticalDepotQueries() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",e->{calls.incrementAndGet();assertTrue(e.getRequestURI().getRawQuery().contains("format=jsonv2"));byte[] body="""
          [{"lon":"8.2","lat":"48.85","display_name":"Testweg 1 A","licence":"Fixture"},
           {"lon":"8.3","lat":"48.85","display_name":"Testweg 1 B","licence":"Fixture"},
           {"lon":"8.2","lat":"48.85","display_name":"Duplicate","licence":"Fixture"}]
          """.getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try {
            var config=new TourImportProperties();config.setGeocodingEnabled(true);config.setGeocodingEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
            var coder=new CompanyTourGeocoder(new ObjectMapper(),config);var result=coder.lookup("hash",List.of(new CompanyTourGeocoder.Target("start:t","Testweg 1"),new CompanyTourGeocoder.Target("end:t","Testweg 1")),dir);
            assertEquals(1,calls.get());assertEquals(1,result.cachedQueries());assertEquals(2,result.answers().getFirst().candidates().size());
            assertTrue(result.answers().getFirst().candidates().getFirst().point().provenance().startsWith("local-geocoder:"));
            assertEquals(result.answers(),coder.lookup("hash",List.of(new CompanyTourGeocoder.Target("start:t","Testweg 1"),new CompanyTourGeocoder.Target("end:t","Testweg 1")),dir).answers());assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }
    @Test void rejectsInvalidCoordinatesAndRedirectsWithoutSavingSuccessfulCache() throws Exception {
        var redirect=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",e->{if(redirect.get()>0){e.getResponseHeaders().add("Location","https://example.org/search");e.sendResponseHeaders(302,-1);}else{byte[] body="[{\"lon\":\"NaN\",\"lat\":\"48\",\"display_name\":\"Bad\"}]".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}e.close();});server.start();
        try {
            var config=new TourImportProperties();config.setGeocodingEnabled(true);config.setGeocodingEndpoint("http://127.0.0.1:"+server.getAddress().getPort());var coder=new CompanyTourGeocoder(new ObjectMapper(),config);
            assertThrows(java.io.IOException.class,()->coder.lookup("hash",List.of(new CompanyTourGeocoder.Target("s","Invalid")),dir));redirect.incrementAndGet();
            assertThrows(java.io.IOException.class,()->coder.lookup("hash",List.of(new CompanyTourGeocoder.Target("s","Redirect")),dir));
            try(var files=Files.list(dir)){assertEquals(0,files.count());}
        } finally {server.stop(0);}
    }
    @Test void explicitlyRefreshesEmptyResultsAfterLocalIndexUpdates() throws Exception {
        var available=new AtomicInteger();var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",e->{calls.incrementAndGet();byte[] body=(available.get()==0?"[]":"[{\"lon\":\"8.2\",\"lat\":\"48.85\",\"display_name\":\"New local entry\"}]").getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try {
            var config=new TourImportProperties();config.setGeocodingEnabled(true);config.setGeocodingEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
            var coder=new CompanyTourGeocoder(new ObjectMapper(),config);var target=new CompanyTourGeocoder.Target("start:t","Missing address");
            assertTrue(coder.lookup("hash",List.of(target),dir).answers().getFirst().candidates().isEmpty());available.incrementAndGet();
            assertTrue(coder.lookup("hash",List.of(target),dir).answers().getFirst().candidates().isEmpty());assertEquals(1,calls.get());
            var refreshed=coder.lookup("hash",List.of(target,new CompanyTourGeocoder.Target("end:t",target.query())),dir,true);
            assertEquals(1,refreshed.answers().getFirst().candidates().size());assertEquals(2,calls.get());assertEquals(1,refreshed.cachedQueries());
            assertEquals(1,coder.lookup("hash",List.of(target),dir).answers().getFirst().candidates().size());assertEquals(2,calls.get());
        } finally {server.stop(0);}
    }
    @Test void preflightKeepsOutsidePointsAndMissingOperatingInputsSeparate() throws Exception {
        var draft=CompanyTourExecutionTest.draft();var c=CompanyTourExecutionTest.context(draft,"missing");var scenario=CompanyTourExecutionTest.scenario();
        scenario.region.maxLon=8.202;var result=CompanyTourPreflight.inspect(draft,c,scenario);
        assertEquals(1,result.selectedTours());assertEquals(1,result.missingVehicles());assertEquals(2,result.externalPoints());assertEquals(0,result.unlocatedPoints());assertFalse(result.networkCoverageVerified());
    }
}
