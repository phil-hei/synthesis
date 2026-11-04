package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficEndpointOriginsTest {
    @TempDir Path dir;
    private void write(String file,String text)throws Exception {
        Path path=dir.resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,text);
    }
    private Map<String,Object> endpoint(double lon,double lat) {
        Map<String,Object> e=new LinkedHashMap<>();e.put("coordinate",List.of(lon,lat));e.put("status","unmapped");return e;
    }
    private Map<String,Object> request(Map<String,Object> from,Map<String,Object> to) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("status","unmapped");row.put("from",from);row.put("to",to);return row;
    }
    @Test void preservesAmbiguousReferencesBoundsExamplesAndNeverFuzzilyAssignsNearbyCoordinates()throws Exception {
        StringBuilder homes=new StringBuilder("householdId;homeZone;homeLocation\n");
        for(int i=0;i<12;i++)homes.append(i).append(";3;\"(8.2,48.85: 0, 0.5)\"\n");
        write("legacy/demand-data/household.csv",homes.toString());
        write("legacy/demand-data/fixedDestination.csv","personOid;activityType;zoneId;location\n17;WORK;3;\"(8.2,48.85: 0, 0.5)\"\n");
        write("legacy/logistics/distribution-centers.csv","id;name;zone;loc_x;loc_y\nD;\"Depot; mit \"\"Name\"\"\";100004;8.3;48.86\n");
        var matched=endpoint(8.2,48.85);var near=endpoint(8.20000001,48.85);var depot=endpoint(8.3,48.86);
        var report=TrafficEndpointOrigins.annotate(dir,List.of(request(matched,near),request(depot,endpoint(10,50))));
        assertEquals(4,report.get("checkedEndpoints"));assertEquals(2,report.get("unmatchedEndpoints"));
        var origins=(Map<?,?>)matched.get("coordinateSources");assertEquals(13,origins.get("recordCount"));
        assertEquals(Set.of("household-home","fixed-destination"),origins.get("kinds"));
        assertEquals(8,((List<?>)origins.get("examples")).size());assertEquals(true,origins.get("examplesTruncated"));
        assertEquals(0,((Map<?,?>)near.get("coordinateSources")).get("recordCount"));
        var example=(Map<?,?>)((List<?>)((Map<?,?>)depot.get("coordinateSources")).get("examples")).getFirst();
        assertEquals("external",example.get("zoneIdSpace"));assertEquals("100004",example.get("zoneId"));assertEquals(2,example.get("line"));
        assertTrue(((List<?>)report.get("files")).stream().map(x->(Map<?,?>)x).anyMatch(x->"read".equals(x.get("status")) && x.containsKey("sha256")));
    }
    @Test void malformedSourceIsVisibleAndCannotLeavePartiallyAcceptedReferences()throws Exception {
        write("legacy/demand-data/household.csv","householdId;homeZone;homeLocation\n1;3;\"(8.2,48.85: 0, 0.5)\"\n2;3;\"unclosed\n");
        var from=endpoint(8.2,48.85);
        var report=TrafficEndpointOrigins.annotate(dir,List.of(request(from,endpoint(8.3,48.85))));
        assertEquals(0,((Map<?,?>)from.get("coordinateSources")).get("recordCount"));
        var source=(Map<?,?>)((List<?>)report.get("files")).getFirst();assertEquals("unreadable",source.get("status"));assertNotNull(source.get("error"));
        assertEquals(2,report.get("unmatchedEndpoints"));
    }
}
