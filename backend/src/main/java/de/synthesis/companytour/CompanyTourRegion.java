package de.synthesis.companytour;

import de.synthesis.scenario.Scenario;
import java.util.*;
import static de.synthesis.companytour.CompanyTourExecution.*;

/** Explicit approximation of regional scope. Original demand and outside service are never rewritten. */
public final class CompanyTourRegion {
    public record Bounds(double minLon,double minLat,double maxLon,double maxLat) {
        public Bounds { if(!Double.isFinite(minLon)||!Double.isFinite(minLat)||!Double.isFinite(maxLon)||!Double.isFinite(maxLat)||minLon>=maxLon||minLat>=maxLat||minLon < -180||maxLon>180||minLat < -90||maxLat>90)throw new IllegalArgumentException("Gültige Modellregion für regionale Tourprojektion erforderlich"); }
        public static Bounds of(Scenario.Region r){if(r==null)throw new IllegalArgumentException("Modellregion fehlt");return new Bounds(r.minLon,r.minLat,r.maxLon,r.maxLat);}
        public boolean contains(Point p){return p.lon()>=minLon&&p.lon()<=maxLon&&p.lat()>=minLat&&p.lat()<=maxLat;}
    }
    public record Check(String sourceTripKey,String scope,Double entryFraction,Double exitFraction,Leg regionalLeg) {}
    public record Projection(Batch batch,Set<String> chainEntries,List<Check> checks,boolean approximate,String method) {
        public Projection(Batch batch,Set<String> entries,List<Check> checks,boolean approximate){this(batch,entries,checks,approximate,approximate?"confirmed-straight-line-plan-interpolation":"internal");}
    }
    public record PathPoint(Point location,double seconds) {}
    public record NetworkPath(List<PathPoint> points,List<String> edgeIds,String networkSha256) {}
    public static Projection project(Batch original) {
        List<Prepared> inputs=new ArrayList<>();Set<String> entries=new HashSet<>();List<Check> checks=new ArrayList<>();boolean approximate=false;
        for(var input:original.imports()) {
            List<Leg> regional=new ArrayList<>();Bounds b=input.regionalScope();approximate|=b!=null;
            for(var leg:input.legs()) {
                if(input.networkProjectionRequested()){checks.add(new Check(leg.sourceKey(),"pending-network",null,null,null));continue;}
                if(b==null||b.contains(leg.from())&&b.contains(leg.to())){regional.add(leg);checks.add(new Check(leg.sourceKey(),"internal",0.,1.,leg));continue;}
                if(Math.abs(leg.from().lon()-leg.to().lon())>180)throw new IllegalArgumentException("Datumsgrenzenüberschreitende Verbindung benötigt einen eigenen regionalen Routenvertrag");
                double[] range=clip(b,leg.from(),leg.to());
                if(range==null){checks.add(new Check(leg.sourceKey(),"outside",null,null,null));continue;}
                boolean incoming=!b.contains(leg.from()),outgoing=!b.contains(leg.to());
                int depart=time(leg,range[0]),arrival=time(leg,range[1]);
                var projected=new Leg(leg.sourceKey(),leg.importId(),leg.tourId(),outgoing?"boundary-exit:"+leg.stopId():leg.stopId(),leg.vehicleId(),leg.vehicleClass(),depart,arrival,outgoing?arrival:leg.plannedEndSec(),outgoing?0:leg.serviceSeconds(),
                        incoming?point(b,leg,range[0]):leg.from(),outgoing?point(b,leg,range[1]):leg.to(),leg.windowPolicy(),outgoing?List.of():leg.windows(),leg.returning());
                regional.add(projected);if(incoming)entries.add(leg.sourceKey());
                checks.add(new Check(leg.sourceKey(),incoming?outgoing?"through":"incoming":"outgoing",range[0],range[1],projected));
            }
            inputs.add(new Prepared(input.contract(),input.importId(),input.mappingHash(),input.executionHash(),input.companyId(),input.simulationDate(),input.sourceTours(),input.excludedTours(),List.copyOf(regional)));
        }
        return new Projection(new Batch(List.copyOf(inputs)),Set.copyOf(entries),List.copyOf(checks),approximate,original.imports().stream().anyMatch(Prepared::networkProjectionRequested)?"pending-network":approximate?"confirmed-straight-line-plan-interpolation":"internal");
    }
    /** Intersects actual directed route polylines, keeping every disjoint regional visit. */
    public static Projection projectNetwork(Batch original,Map<String,NetworkPath> routes) {
        List<Prepared> inputs=new ArrayList<>();List<Check> checks=new ArrayList<>();Set<String> entries=new HashSet<>();
        for(var input:original.imports()){
            if(!input.networkProjectionRequested()){var existing=project(new Batch(List.of(input)));inputs.addAll(existing.batch().imports());checks.addAll(existing.checks());entries.addAll(existing.chainEntries());continue;}
            if(input.regionalScope()==null)throw new IllegalArgumentException("Bestätigte Region fehlt für Netzprojektion");
            List<Leg> legs=new ArrayList<>();var bounds=input.regionalScope();
            for(var leg:input.legs()){
                var path=routes.get(leg.sourceKey());if(path==null||path.points().size()<2)throw new IllegalArgumentException("Vollständige Straßenroute fehlt: "+leg.sourceKey());
                var pts=path.points();double total=pts.getLast().seconds();
                if(total<=0){if(bounds.contains(leg.from())&&bounds.contains(leg.to())){legs.add(leg);checks.add(new Check(leg.sourceKey(),"internal",0.,1.,leg));}else checks.add(new Check(leg.sourceKey(),"outside",null,null,null));continue;}
                List<double[]> times=new ArrayList<>();List<Point[]> endpoints=new ArrayList<>();
                for(int i=1;i<pts.size();i++){
                    var a=pts.get(i-1);var b=pts.get(i);if(b.seconds()<a.seconds())throw new IllegalArgumentException("Ungültige Routenzeitfolge");
                    var cut=clip(bounds,a.location(),b.location());if(cut==null)continue;
                    double start=a.seconds()+(b.seconds()-a.seconds())*cut[0],end=a.seconds()+(b.seconds()-a.seconds())*cut[1];
                    if(end<=start)continue;
                    Point from=interpolate(a.location(),b.location(),cut[0]),to=interpolate(a.location(),b.location(),cut[1]);
                    if(!times.isEmpty()&&Math.abs(times.getLast()[1]-start)<1e-6){times.getLast()[1]=end;endpoints.getLast()[1]=to;}
                    else {times.add(new double[]{start,end});endpoints.add(new Point[]{from,to});}
                }
                if(times.isEmpty()){checks.add(new Check(leg.sourceKey(),"outside",null,null,null));continue;}
                for(int i=0;i<times.size();i++){
                    double start=times.get(i)[0]/total,end=times.get(i)[1]/total;
                    boolean incoming=start>1e-8||!bounds.contains(leg.from()),outgoing=end<1-1e-8||!bounds.contains(leg.to());
                    String key=times.size()==1?leg.sourceKey():leg.sourceKey()+":regional:"+i;
                    int arrival=time(leg,end);var regional=new Leg(key,leg.importId(),leg.tourId(),outgoing?"boundary-exit:"+leg.stopId():leg.stopId(),leg.vehicleId(),leg.vehicleClass(),time(leg,start),arrival,outgoing?arrival:leg.plannedEndSec(),outgoing?0:leg.serviceSeconds(),incoming?endpoints.get(i)[0]:leg.from(),outgoing?endpoints.get(i)[1]:leg.to(),leg.windowPolicy(),outgoing?List.of():leg.windows(),leg.returning());
                    legs.add(regional);if(incoming)entries.add(key);checks.add(new Check(leg.sourceKey(),incoming?outgoing?"through":"incoming":"outgoing",start,end,regional));
                }
            }
            inputs.add(new Prepared(input.contract(),input.importId(),input.mappingHash(),input.executionHash(),input.companyId(),input.simulationDate(),input.sourceTours(),input.excludedTours(),List.copyOf(legs)));
        }
        return new Projection(new Batch(List.copyOf(inputs)),Set.copyOf(entries),List.copyOf(checks),true,"directed-network-edge-free-flow-plan-time-scaling");
    }
    private static Point interpolate(Point a,Point b,double f){return new Point(a.lon()+(b.lon()-a.lon())*f,a.lat()+(b.lat()-a.lat())*f,"directed-road-network-boundary");}
    public static void writeReport(java.nio.file.Path artifacts,Batch original,Projection projection)throws java.io.IOException {
        de.synthesis.vehicle.VehicleArtifacts.write(artifacts.resolve("company-tours/regional-projection.json"),Map.of("contract","synthesis-company-tour-regional-v1","method",projection.method(),"outsideExecutionSimulated",false,"originalLegs",original.legs().size(),"regionalLegs",projection.batch().legs().size(),"chainEntries",projection.chainEntries(),"checks",projection.checks()));
    }
    private static int time(Leg l,double t){return (int)Math.round(l.departSec()+(l.plannedArrivalSec()-l.departSec())*t);}
    private static Point point(Bounds b,Leg l,double t){return new Point(Math.clamp(l.from().lon()+(l.to().lon()-l.from().lon())*t,b.minLon(),b.maxLon()),Math.clamp(l.from().lat()+(l.to().lat()-l.from().lat())*t,b.minLat(),b.maxLat()),"regional-straight-line-plan-interpolation");}
    private static double[] clip(Bounds b,Point a,Point z){
        double lo=0,hi=1;double[] start={a.lon(),a.lat()},delta={z.lon()-a.lon(),z.lat()-a.lat()},min={b.minLon(),b.minLat()},max={b.maxLon(),b.maxLat()};
        for(int i=0;i<2;i++){if(delta[i]==0){if(start[i]<min[i]||start[i]>max[i])return null;}else{double x=(min[i]-start[i])/delta[i],y=(max[i]-start[i])/delta[i];lo=Math.max(lo,Math.min(x,y));hi=Math.min(hi,Math.max(x,y));if(hi<=lo)return null;}}
        return new double[]{lo,hi};
    }
}
