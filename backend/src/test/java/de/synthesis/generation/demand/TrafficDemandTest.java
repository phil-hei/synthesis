package de.synthesis.generation.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.companytour.*;
import de.synthesis.generation.pipeline.FreightDemandGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficDemandTest {
    @TempDir Path dir;
    @Test void combinesFourSourcesWithoutChangingPersonsAndPreservesPlansAndUnknownRoutes() throws Exception {
        var s=CompanyTourExecutionTest.scenario(); var d=CompanyTourExecutionTest.draft();
        var prepared=CompanyTourExecution.prepare(d,CompanyTourExecutionTest.context(d,"van-1"),s).prepared();
        s.freight.enabled=true;s.freight.day=0;s.freight.relations=List.of(new FreightDemandGenerator.Relation("f","truck","internal",8.201,48.85,8.205,48.85,2,6,8,"test","2026-10-19"));
        var trip=new TravelDemand.PersonTrip("p","1",TravelDemand.Mode.CAR,"1","1",10,11,1,"100001","100002",8.201,48.85,8.205,48.85,
                List.of(new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",1,"100001","100002")));
        var persons=new TravelDemand(List.of(trip),1);
        Path file=dir.resolve("parcels.csv");Files.writeString(file,"tourId;isReturning;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur\n"
                +"parcel;false;2;8.201;48.85;8.205;48.85;100;2;0.5\n");
        var parcels=ParcelTourAdapter.readAll(file);assertEquals(2,parcels.size());assertTrue(parcels.getLast().returning());
        assertEquals(30,parcels.getFirst().serviceSeconds());assertEquals(6000.,parcels.getFirst().plannedArrivalSec());
        var combined=new TrafficDemand(TrafficDemand.CONTRACT,"available",persons,0,FreightDemandGenerator.generate(s),parcels,new CompanyTourExecution.Batch(List.of(prepared)));
        assertEquals(8,combined.movements().size());assertEquals(2,combined.tours().size());
        assertEquals(persons,combined.persons());assertEquals(7,combined.movements().stream().filter(m->m.trafficCategory().equals("logistics")).count());
        var json=new ObjectMapper();TrafficDemandArtifacts.write(dir,combined,json);
        var roundtrip=TrafficDemandArtifacts.read(dir,json);assertEquals(combined,roundtrip);
        assertEquals(combined.movements(),roundtrip.movements());assertEquals(combined.tours(),roundtrip.tours());
        var summary=json.readTree(dir.resolve(TrafficDemandArtifacts.SUMMARY).toFile());assertEquals(4,summary.path("byDemandSource").size());
        var geo=json.readTree(dir.resolve("demand/planned-connections.geojson").toFile());assertEquals(8,geo.path("features").size());
        for(var f:geo.path("features"))assertFalse(f.path("properties").path("routed").asBoolean());
        assertTrue(roundtrip.freight().stream().allMatch(f->roundtrip.movements().stream().anyMatch(m->m.sourceTripKey().endsWith(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(f.id().getBytes())))));
    }
    @Test void invalidParcelInputCannotMasqueradeAsAnEmptySharedSource() throws Exception {
        Path file=dir.resolve("invalid.csv");Files.writeString(file,"unexpected;header\n");
        assertThrows(java.io.IOException.class,()->ParcelTourAdapter.readAll(file));
        Files.writeString(file,"tourId;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin\np;2;8.201\n");
        assertThrows(java.io.IOException.class,()->ParcelTourAdapter.readAll(file));
    }
    @Test void doesNotApplySumoRoadOnlyFilterToTheSharedParcelPlan() throws Exception {
        Path file=dir.resolve("parcels.csv");Files.writeString(file,"tourId;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin\nrail;3;8.201;48.85;8.205;48.85;100\n");
        assertEquals("tram",ParcelTourAdapter.readAll(file).getFirst().vehicleClass());
    }
}
