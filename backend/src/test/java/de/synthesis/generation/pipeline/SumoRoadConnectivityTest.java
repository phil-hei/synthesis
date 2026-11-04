package de.synthesis.generation.pipeline;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SumoRoadConnectivityTest {
 @TempDir Path dir;
 @Test void legalDeadEndTurnWorksButExplicitOsmProhibitionRemainsEffective()throws Exception{
  var config=new SynthesisProperties.Sumo();Path bins=Path.of(config.getBinDir());
  assumeTrue(Files.isExecutable(bins.resolve("netconvert")) && Files.isExecutable(bins.resolve("duarouter")) && Files.isExecutable(bins.resolve("sumo")),"native SUMO missing");
  String source="<osm version='0.6'><node id='1' lon='8.2' lat='48.85'/><node id='2' lon='8.199' lat='48.85'/><node id='3' lon='8.2' lat='48.851'/><node id='4' lon='8.201' lat='48.85'/><way id='11'><nd ref='2'/><nd ref='1'/><tag k='highway' v='residential'/></way><way id='12'><nd ref='1'/><nd ref='3'/><tag k='highway' v='residential'/></way><way id='13'><nd ref='1'/><nd ref='4'/><tag k='highway' v='residential'/></way>%s</osm>";
  String restriction="<relation id='501'><member type='way' ref='12' role='from'/><member type='node' ref='3' role='via'/><member type='way' ref='12' role='to'/><tag k='type' v='restriction'/><tag k='restriction' v='no_u_turn'/></relation>";
  Files.writeString(dir.resolve("trips.xml"),"<routes><trip id='leave' depart='0' from='12' to='13'/></routes>");
  for(String variant:List.of("legal","restricted")){
   Files.writeString(dir.resolve(variant+".osm"),source.formatted(variant.equals("legal")?"":restriction));
   run(bins.resolve("netconvert"),"--osm-files",variant+".osm","-o",variant+".net.xml",SumoRunner.TURNAROUND_OPTION);
   run(bins.resolve("duarouter"),"-n",variant+".net.xml","-r","trips.xml","-o",variant+".rou.xml","--ignore-errors","--no-step-log");
   var ids=TrafficRoutes.vehicleIds(dir.resolve(variant+".rou.xml"));
   assertEquals(variant.equals("legal")?Set.of("leave"):Set.of(),ids);
   if(variant.equals("legal")){
    run(bins.resolve("sumo"),"-n","legal.net.xml","-r","legal.rou.xml","--end","100","--no-step-log","--tripinfo-output","tripinfo.xml");
    var infos=TrafficRoutes.elements(TrafficRoutes.read(dir.resolve("tripinfo.xml")),"tripinfo");
    assertEquals(1,infos.size());assertTrue(Double.parseDouble(infos.getFirst().getAttribute("arrival"))>=0);
   }
  }
 }
 void run(Path binary,String...args)throws Exception{
  var cmd=new ArrayList<String>();cmd.add(binary.toString());cmd.addAll(List.of(args));
  Path log=dir.resolve("native.log");var p=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
  try{assertTrue(p.waitFor(30,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),()->{try{return Files.readString(log);}catch(Exception e){return e.toString();}});}finally{if(p.isAlive())p.destroyForcibly();}
 }
}
