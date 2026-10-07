import com.graphhopper.*;
import com.graphhopper.config.*;
import com.graphhopper.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
public class GraphExample {
 public static void main(String[] args) throws Exception {
  GraphHopper h=new GraphHopper(); h.setOSMFile(args[0]);h.setGraphHopperLocation(args[1]);
  h.setProfiles(new Profile("car").setVehicle("car")); h.importOrLoad();
  List<Object> edges=new ArrayList<>(); var iter=h.getBaseGraph().getAllEdges();
  while(iter.next()) {var ps=iter.fetchWayGeometry(FetchMode.ALL); List<Object> pts=new ArrayList<>(); for(int i=0;i<ps.size();i++)pts.add(List.of(ps.getLon(i),ps.getLat(i))); edges.add(Map.of("id",iter.getEdge(),"points",pts));}
  double[][] places={{51.515,7.565},{51.515,7.475},{51.505,7.57},{51.526,7.66},{51.492,7.64}};
  List<Object> routes=new ArrayList<>(); int[][] pairs={{0,1},{1,2},{2,0},{3,0},{0,4},{4,3}};
  for(var p:pairs){GHResponse r=h.route(new GHRequest(places[p[0]][0],places[p[0]][1],places[p[1]][0],places[p[1]][1]).setProfile("car")); if(r.hasErrors())throw new Exception(r.getErrors().toString()); var b=r.getBest();List<Object> pts=new ArrayList<>();for(int i=0;i<b.getPoints().size();i++)pts.add(List.of(b.getPoints().getLon(i),b.getPoints().getLat(i)));routes.add(Map.of("from",p[0],"to",p[1],"points",pts,"distanceM",b.getDistance(),"timeMin",b.getTime()/60000.0));}
  new ObjectMapper().writeValue(Path.of(args[2]).toFile(),Map.of("edges",edges,"routes",routes,"places",places));
  System.out.println("Graph edges: "+edges.size()+" routes: "+routes.size());h.close();
 }
}
