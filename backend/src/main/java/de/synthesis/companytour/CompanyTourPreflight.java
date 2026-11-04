package de.synthesis.companytour;

import de.synthesis.scenario.Scenario;
import java.util.*;

/** Target-independent checks of the saved operating context and model region. */
public final class CompanyTourPreflight {
    public record Summary(CompanyTourRegion.Bounds region, int selectedTours, int missingVehicles,
                          int missingDepotTimes, int locatedPoints, int externalPoints, int unlocatedPoints,
                          int pendingAddresses, boolean networkCoverageVerified) {}
    public static boolean located(CompanyTourExecution.Point p) {
        return p!=null && p.lon()!=null && p.lat()!=null && Double.isFinite(p.lon()) && Double.isFinite(p.lat()) && Math.abs(p.lon())<=180 && Math.abs(p.lat())<=90;
    }
    public static List<CompanyTourGeocoder.Target> targets(CompanyTourMapper.Draft draft, CompanyTourExecution.Context context) {
        List<CompanyTourGeocoder.Target> targets=new ArrayList<>(); if(context==null) return targets;
        Set<String> selected=new HashSet<>();
        for(var tour:context.tours()) if(tour.included()) {
            selected.add(tour.tourId()); var addresses=context.depotAddresses()==null?null:context.depotAddresses().get(tour.tourId());
            if(addresses!=null) {
                if(!located(tour.start())&&!addresses.start().isBlank())targets.add(new CompanyTourGeocoder.Target("start:"+tour.tourId(),addresses.start()));
                if(!located(tour.end())&&!addresses.end().isBlank())targets.add(new CompanyTourGeocoder.Target("end:"+tour.tourId(),addresses.end()));
            }
        }
        for(var tour:draft.tours()) if(selected.contains(tour.tourId())) for(var stop:tour.stops()) if(!located(context.locations().get(stop.stopId()))) {
            var l=stop.location(); targets.add(new CompanyTourGeocoder.Target("stop:"+stop.stopId(),l.street()+", "+l.postalCode()+" "+l.city()));
        }
        return List.copyOf(targets);
    }
    public static Summary inspect(CompanyTourMapper.Draft draft, CompanyTourExecution.Context context, Scenario scenario) {
        var region=scenario.region==null?null:CompanyTourRegion.Bounds.of(scenario.region);
        if(context==null)return new Summary(region,0,0,0,0,0,0,0,false);
        Set<String> fleet=new HashSet<>();CompanyTourExecution.vehicles(scenario).forEach(v->fleet.add(v.vehicleId()));
        int selected=0,vehicles=0,times=0,located=0,external=0,missing=0;List<CompanyTourExecution.Point> points=new ArrayList<>();Set<String> stops=new HashSet<>();
        for(var tour:context.tours()) if(tour.included()) {
            selected++;if(!fleet.contains(tour.vehicleId()))vehicles++;
            if(tour.startTime()==null||tour.startTime().isBlank()||tour.returnTime()==null||tour.returnTime().isBlank())times++;
            points.add(tour.start());points.add(tour.end());stops.addAll(tour.stopOrder());
        }
        stops.forEach(id->points.add(context.locations().get(id)));
        for(var p:points)if(located(p)){located++;if(region!=null&&!region.contains(p))external++;}else missing++;
        return new Summary(region,selected,vehicles,times,located,external,missing,targets(draft,context).size(),false);
    }
}
