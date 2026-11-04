package de.synthesis.generation.pipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TrafficEndpointMapperTest {
 @TempDir Path dir;
 TrafficRoutes network()throws Exception{
  Files.writeString(dir.resolve("net.xml"),"<net><edge id='car'><lane allow='passenger delivery'/></edge><edge id='walk'><lane allow='pedestrian bicycle'/></edge></net>");
  Files.writeString(dir.resolve("nodes.xml"),"<nodes><node id='a' x='8.2' y='48.85' type='dead_end'/><node id='b' x='8.202' y='48.85'/><node id='c' x='8.2' y='48.8501'/><node id='d' x='8.202' y='48.8501'/></nodes>");
  Files.writeString(dir.resolve("edges.xml"),"<edges><edge id='car' from='a' to='b'/><edge id='walk' from='c' to='d'/></edges>");
  return new TrafficRoutes(dir.resolve("net.xml"),dir.resolve("nodes.xml"),dir.resolve("edges.xml"));
 }
 @Test void matchesActualClassPreservesExplicitDirectionAndReportsUncoveredCoordinates()throws Exception{
  var routes=network();assertEquals("walk",routes.nearest(8.201,48.85,"pedestrian"));assertEquals("car",routes.nearest(8.201,48.8501,"passenger"));
  assertEquals("walk",routes.nearest(8.201,48.85,"bicycle"));
  var mapper=new TrafficEndpointMapper(routes);
  assertEquals(Map.of("from","car","to","car"),mapper.map("a","person-demand","passenger","8.201","48.85","8.201","48.85"));
  assertNull(mapper.map("bad","person-demand","passenger","9","48","8.201","48.85"));
  Path osm=dir.resolve("osm.xml");Files.writeString(osm,"<osm/>");
  assertThrows(IOException.class,()->mapper.write(dir,osm,true));
  var x=new ObjectMapper().readTree(dir.resolve("network-coverage.json").toFile());
  assertEquals(1,x.path("unmappedEndpoints").asInt());assertFalse(x.path("completeTopologyVerified").asBoolean());
  assertTrue(x.path("requests").get(0).path("from").path("match").path("fromDeadEnd").asBoolean());
 }
 @Test void indexedNearestMatchesExhaustiveGeometryAcrossTileBoundaries()throws Exception{
  var routes=network();var random=new Random(42);
  for(int i=0;i<200;i++)for(String clazz:List.of("passenger","pedestrian","bicycle","delivery")){
   double lon=8.198+random.nextDouble()*.006,lat=48.848+random.nextDouble()*.004;String best=null;double distance=300;
   for(var edge:routes.allEdges())if(edge.allowed().contains(clazz))for(int j=1;j<edge.shape().size();j++){
    double candidate=TrafficRoutes.distance(lon,lat,edge.shape().get(j-1),edge.shape().get(j));if(candidate<distance){distance=candidate;best=edge.id();}
   }
   if(best==null)assertThrows(IOException.class,()->routes.nearest(lon,lat,clazz));else assertEquals(best,routes.nearest(lon,lat,clazz));
  }
 }
 @Test void choosesReachableOppositeDirectionOfSameStreetButKeepsExplicitPins()throws Exception{
  Files.writeString(dir.resolve("net.xml"),"<net><edge id='-road'><lane allow='passenger'/></edge><edge id='road'><lane allow='passenger'/></edge><edge id='target'><lane allow='pedestrian'/><lane allow='passenger'/></edge><connection from='road' to='target' fromLane='0' toLane='1'/></net>");
  Files.writeString(dir.resolve("nodes.xml"),"<nodes><node id='a' x='8.2' y='48.85'/><node id='b' x='8.202' y='48.85'/><node id='c' x='8.204' y='48.85'/></nodes>");
  Files.writeString(dir.resolve("edges.xml"),"<edges><edge id='-road' from='b' to='a'/><edge id='road' from='a' to='b'/><edge id='target' from='b' to='c'/></edges>");
  var routes=new TrafficRoutes(dir.resolve("net.xml"),dir.resolve("nodes.xml"),dir.resolve("edges.xml"));
  assertEquals("-road",routes.nearest(8.201,48.85,"passenger"));
  var pair=routes.matchPair(8.201,48.85,8.203,48.85,"passenger",null,null);
  assertEquals("road",pair.from().edgeId());assertTrue(pair.directionAdjusted());assertEquals(Boolean.TRUE,pair.possibleDirectedConnection());
  var explicit=routes.matchPair(8.201,48.85,8.203,48.85,"passenger","-road","target");
  assertEquals("-road",explicit.from().edgeId());assertEquals(Boolean.FALSE,explicit.possibleDirectedConnection());
  assertNull(routes.possibleConnection("road","target","pedestrian"),"pedestrian topology is a separate native check");
 }
 @Test void aPedestrianOnlyLaneConnectionCannotProveVehicleReachability()throws Exception{
  Files.writeString(dir.resolve("net.xml"),"<net><edge id='road'><lane allow='pedestrian'/><lane allow='passenger'/></edge><edge id='target'><lane allow='pedestrian'/><lane allow='passenger'/></edge><connection from='road' to='target' fromLane='0' toLane='0'/></net>");
  Files.writeString(dir.resolve("nodes.xml"),"<nodes><node id='a' x='8.2' y='48.85'/><node id='b' x='8.202' y='48.85'/><node id='c' x='8.204' y='48.85'/></nodes>");
  Files.writeString(dir.resolve("edges.xml"),"<edges><edge id='road' from='a' to='b'/><edge id='target' from='b' to='c'/></edges>");
  var routes=new TrafficRoutes(dir.resolve("net.xml"),dir.resolve("nodes.xml"),dir.resolve("edges.xml"));
  assertEquals(Boolean.FALSE,routes.possibleConnection("road","target","passenger"));
 }

 @Test void explicitlyApproximateCompanyLocationCanLeaveDisconnectedNearestStreet()throws Exception{
  Files.writeString(dir.resolve("net.xml"),"<net><edge id='island'><lane allow='truck'/></edge><edge id='access'><lane allow='truck'/></edge><edge id='target'><lane allow='truck'/></edge><connection from='access' to='target' fromLane='0' toLane='0'/></net>");
  Files.writeString(dir.resolve("nodes.xml"),"<nodes><node id='a' x='8.2' y='48.85'/><node id='b' x='8.202' y='48.85'/><node id='c' x='8.2' y='48.851'/><node id='d' x='8.202' y='48.851'/><node id='e' x='8.204' y='48.851'/></nodes>");
  Files.writeString(dir.resolve("edges.xml"),"<edges><edge id='island' from='a' to='b'/><edge id='access' from='c' to='d'/><edge id='target' from='d' to='e'/></edges>");
  var routes=new TrafficRoutes(dir.resolve("net.xml"),dir.resolve("nodes.xml"),dir.resolve("edges.xml"));
  assertEquals(Boolean.FALSE,routes.matchPair(8.201,48.85,8.2035,48.851,"truck",null,null).possibleDirectedConnection());
  var approximate=routes.matchApproximatePair(8.201,48.85,8.2035,48.851,"truck");
  assertEquals("access",approximate.from().edgeId());assertEquals("target",approximate.to().edgeId());
  assertTrue(approximate.from().distanceMetres()>100);assertTrue(approximate.possibleDirectedConnection());
  assertEquals(Map.of("from","access","to","target"),new TrafficEndpointMapper(routes)
      .map("company","company-tours","truck","8.201","48.85","8.2035","48.851"));
 }

}
