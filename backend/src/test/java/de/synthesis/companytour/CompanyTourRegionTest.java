package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.generation.demand.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static de.synthesis.companytour.CompanyTourExecution.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyTourRegionTest {
    Point p(double x,double y){return new Point(x,y,"fixture");}
    Leg leg(String id,Point a,Point b,int depart,int arrive){return new Leg(id,"import","tour","stop","van","truck",depart,arrive,arrive+20,20,a,b,"report-only",List.of(new Window(0,1000)),false);}
    Batch batch(List<Leg> legs){return new Batch(List.of(new Prepared(CONTRACT,"import","mapping","execution","company","2026-10-19",1,List.of(),legs,new CompanyTourRegion.Bounds(0,0,10,10))));}
    @Test void incomingOutgoingThroughAndOutsideAreExplicitAndPreserveOriginals(){
        var incoming=leg("in",p(-10,5),p(5,5),0,120);var outgoing=leg("out",p(5,5),p(15,5),200,300);var through=leg("through",p(-5,5),p(15,5),400,600);var outside=leg("outside",p(-5,-5),p(15,-5),700,900);
        var original=batch(List.of(incoming,outgoing,through,outside));var regional=CompanyTourRegion.project(original);
        assertEquals(4,original.legs().size());assertEquals(-10.,original.legs().getFirst().from().lon());assertEquals(3,regional.batch().legs().size());
        var a=regional.batch().legs().get(0);assertEquals(80,a.departSec());assertEquals(20,a.serviceSeconds());assertEquals(0.,a.from().lon());
        var b=regional.batch().legs().get(1);assertEquals(250,b.plannedArrivalSec());assertEquals(0,b.serviceSeconds());assertTrue(b.windows().isEmpty());assertEquals(10.,b.to().lon());
        var c=regional.batch().legs().get(2);assertEquals(450,c.departSec());assertEquals(550,c.plannedArrivalSec());
        assertEquals(List.of("incoming","outgoing","through","outside"),regional.checks().stream().map(CompanyTourRegion.Check::scope).toList());assertEquals(Set.of("in","through"),regional.chainEntries());
        assertTrue(regional.batch().legs().stream().allMatch(l->l.vehicleId().equals("van")));assertEquals(regional,CompanyTourRegion.project(original));
    }
    @Test void networkPolylineCreatesEveryVisitAndNeverFallsBackToStraightLine(){
        var leg=leg("route",p(-5,5),p(-5,5),0,400);var source=batch(List.of(leg)).imports().getFirst();
        var requested=new Prepared(source.contract(),source.importId(),source.mappingHash(),source.executionHash(),source.companyId(),source.simulationDate(),source.sourceTours(),source.excludedTours(),source.legs(),source.regionalScope(),true);
        var input=new Batch(List.of(requested));assertEquals("pending-network",CompanyTourRegion.project(input).method());
        assertThrows(IllegalArgumentException.class,()->CompanyTourRegion.projectNetwork(input,Map.of()));
        var path=new CompanyTourRegion.NetworkPath(List.of(new CompanyTourRegion.PathPoint(p(-5,5),0),new CompanyTourRegion.PathPoint(p(5,5),10),new CompanyTourRegion.PathPoint(p(15,5),20),new CompanyTourRegion.PathPoint(p(5,5),30),new CompanyTourRegion.PathPoint(p(-5,5),40)),List.of("a","b","c"),"network-hash");
        var projected=CompanyTourRegion.projectNetwork(input,Map.of("route",path));assertEquals(2,projected.batch().legs().size());assertEquals(Set.of("route:regional:0","route:regional:1"),projected.chainEntries());
        assertEquals(50,projected.batch().legs().getFirst().departSec());assertEquals(350,projected.batch().legs().getLast().plannedArrivalSec());assertTrue(projected.batch().legs().stream().allMatch(l->l.serviceSeconds()==0));
    }
    @Test void tangentAndUnrelatedOutsideSegmentsDoNotInventVisits(){
        var result=CompanyTourRegion.project(batch(List.of(leg("touch",p(-1,1),p(1,-1),0,100),leg("miss",p(-2,-1),p(-1,-2),200,300))));
        assertTrue(result.batch().legs().isEmpty());assertTrue(result.checks().stream().allMatch(c->c.scope().equals("outside")));
    }
    @Test void externalCoordinatesNeedExplicitConfirmationAndSharedDemandKeepsThem()throws Exception{
        var draft=CompanyTourExecutionTest.draft();var scenario=CompanyTourExecutionTest.scenario();var base=CompanyTourExecutionTest.context(draft,"van-1");
        var end=CompanyTourExecutionTest.point(8.22);
        var t=base.tours().getFirst();var tour=new TourPlan(t.tourId(),true,t.vehicleId(),t.startTime(),t.returnTime(),end,end,t.stopOrder());
        var off=new Context(base.companyId(),base.simulationDate(),false,true,true,true,base.windowPolicy(),List.of(tour),base.locations());
        assertFalse(CompanyTourExecution.prepare(draft,off,scenario).readiness().ready());
        var on=new Context(off.companyId(),off.simulationDate(),false,true,true,true,off.windowPolicy(),off.tours(),off.locations(),true);
        var missing=new HashMap<>(on.locations());missing.remove(draft.tours().getFirst().stops().getFirst().stopId());
        var incomplete=new Context(on.companyId(),on.simulationDate(),false,true,true,true,on.windowPolicy(),on.tours(),missing,true);
        assertFalse(assertDoesNotThrow(()->CompanyTourExecution.prepare(draft,incomplete,scenario)).readiness().ready());
        var ready=CompanyTourExecution.prepare(draft,on,scenario);assertTrue(ready.readiness().ready(),ready.readiness().problems().toString());
        var input=new TrafficDemand(TrafficDemand.CONTRACT,"available",new TravelDemand(List.of(),0),0,List.of(),List.of(),new Batch(List.of(ready.prepared())));
        var json=new ObjectMapper().findAndRegisterModules();var roundtrip=json.readValue(json.writeValueAsBytes(input),TrafficDemand.class);
        assertEquals(8.22,roundtrip.companies().legs().getFirst().from().lon());assertEquals(3,roundtrip.movements().size());assertNotNull(roundtrip.companies().imports().getFirst().regionalScope());
        var hash=ready.readiness().executionHash();scenario.region.maxLon=8.215;assertNotEquals(hash,CompanyTourExecution.prepare(draft,on,scenario).readiness().executionHash());
    }
    @Test void internalDefaultKeepsLegacyHashAndAllLegsUnchanged()throws Exception{
        var draft=CompanyTourExecutionTest.draft();var scenario=CompanyTourExecutionTest.scenario();var c=CompanyTourExecutionTest.context(draft,"van-1");var result=CompanyTourExecution.prepare(draft,c,scenario);
        var json=de.synthesis.vehicle.VehicleJson.MAPPER;var old=json.valueToTree(c);((com.fasterxml.jackson.databind.node.ObjectNode)old).remove(List.of("regionalProjectionConfirmed","networkProjectionRequested"));
        String expected=de.synthesis.vehicle.VehicleJson.hash(Map.of("contract",CONTRACT,"source",draft.sourceHash(),"mapping",draft.mappingHash(),"context",old,"vehicles",scenario.vehicleConfiguration));
        assertEquals(expected,result.readiness().executionHash());var projection=CompanyTourRegion.project(new Batch(List.of(result.prepared())));assertEquals(result.prepared().legs(),projection.batch().legs());assertFalse(projection.approximate());
    }
}
