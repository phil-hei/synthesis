package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import de.synthesis.companytour.*;
import de.synthesis.generation.demand.*;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native mixture acceptance at two geographic locations and three scenario seeds. */
class RegionalBackgroundIntegrationTest {
 @TempDir Path dir;
 @Test void additionalBackgroundRetainsPrivateAndCompanyDemandAcrossRegionsAndSeeds()throws Exception{
  var cfg=new SynthesisProperties.Sumo();cfg.setDay(0);cfg.setMaxTrips(-1);
  assumeTrue(new SumoRunner(cfg).isAvailable(),"Native SUMO required");
  var json=new ObjectMapper();
  for(int region=0;region<2;region++)for(long seed:List.of(7L,42L,777L)){
   var scenario=NativeVehicleChainsIntegrationTest.scenario("standard.heavyTruck.diesel",List.of());
   scenario.vehicleOperations=VehicleOperationsConfiguration.empty();scenario.population.seed=seed;scenario.freight.enabled=true;
   double lon=region==0?8.2:7.4,lat=region==0?48.85:51.5;
   scenario.region.minLon=lon-.01;scenario.region.maxLon=lon+.02;scenario.region.minLat=lat-.01;scenario.region.maxLat=lat+.01;
   scenario.freight.relations=new ArrayList<>();
   for(String movement:List.of("internal","inbound","outbound","through"))scenario.freight.relations.add(new FreightDemandGenerator.Relation("bg_"+movement,"truck",movement,lon+.0002,lat,lon+.0058,lat,2,0,.1,"Synthetic regional regression","2026-10-19","10","40"));
   var freight=FreightDemandGenerator.generate(scenario);
   var p=new TravelDemand.PersonTrip("private","1",TravelDemand.Mode.CAR,"1","1",1,4,.4,"1","2",lon+.0002,lat,lon+.0058,lat,List.of(new TravelDemand.Leg(0,TravelDemand.Mode.CAR,"1",.4,"1","2")));
   var a=new CompanyTourExecution.Point(lon+.0002,lat,"fixture");var b=new CompanyTourExecution.Point(lon+.0058,lat,"fixture");
   var leg=new CompanyTourExecution.Leg("company:fixture:1","fixture","tour1","stop1","delivery-1","truck",600,700,760,60,a,b,"report-only",List.of(),false);
   var prepared=new CompanyTourExecution.Prepared("synthesis-company-execution-v1","fixture","mapping","execution","example","2026-10-19",1,List.of(),List.of(leg));
   var shared=new TrafficDemand(TrafficDemand.CONTRACT,"available",new TravelDemand(List.of(p),1),0,freight,List.of(),new CompanyTourExecution.Batch(List.of(prepared)),VehicleCatalog.requireValid(scenario.vehicleConfiguration),List.of(),"available");
   assertEquals(10,shared.movements().size());
   Path out=dir.resolve("region-"+region+"-seed-"+seed);TrafficDemandArtifacts.write(out,shared,json);
   String osm=SumoFreightIntegrationTest.OSM;
   if(region==1)osm=osm.replace("48.852","51.502").replace("48.851","51.501").replace("48.848","51.498").replace("48.85","51.5").replace("8.2","7.4");
   new SumoRunner(cfg,shared.vehicles(),seed).useFreight(scenario).useEnergyAndOperations(scenario).run(out,osm,shared,null,ignored->{});
   var execution=json.readTree(out.resolve("sumo/traffic-execution.json").toFile());
   assertEquals("complete",execution.path("status").asText());assertEquals(10,execution.path("vehicles").path("completed").asInt());assertEquals(0,execution.path("nativeVehicleTeleports").asInt());
   assertTrue(Files.readString(out.resolve("sumo/statistics.xml")).contains("<seed value=\""+seed+"\""));
   assertEquals(shared.movements(),TrafficDemandArtifacts.read(out,json).movements());
  }
 }
}
