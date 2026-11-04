package de.synthesis.data;

import de.synthesis.data.importing.*;
import de.synthesis.data.spatial.*;
import de.synthesis.generation.pipeline.Zone;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import java.util.*;

/** Explicit uniform-density transfer of municipality totals using equal-area intersections. */
public final class MunicipalAreas {
    private MunicipalAreas() { }
    private record Area(int row,String ags,Geometry geometry) { }
    public static List<AspectImportAdapter.Issue> validateAreas(List<AspectImportAdapter.DataRow> rows){
        var issues=new ArrayList<AspectImportAdapter.Issue>();var areas=new ArrayList<Area>();var index=new STRtree();
        for(var row:rows){var area=new Area(row.sourceRow(),row.values().get("ags").toString(),SpatialGeometry.project(AreaGeometry.read(row.values().get("geometry"))));areas.add(area);index.insert(area.geometry().getEnvelopeInternal(),area);}
        index.build();
        for(var area:areas)for(Object value:index.query(area.geometry().getEnvelopeInternal())){
            var other=(Area)value;if(other.row()<=area.row())continue;
            double overlap=area.geometry().intersection(other.geometry()).getArea();
            if(overlap>Math.max(.01,Math.min(area.geometry().getArea(),other.geometry().getArea())*1e-10))
                issues.add(new AspectImportAdapter.Issue(other.row(),"geometry","overlap","Gemeindeflächen überlappen: "+area.ags()+" / "+other.ags()));
        }
        return issues;
    }
    public static void validateBinding(DataImportService.Revision totals,DataImportService.Revision areas){
        if(areas==null||!areas.data().schemaId().equals("municipality-areas-v1"))throw new IllegalArgumentException("Passende Gemeindegeometrierevision fehlt");
        var first=totals.data().metadata().geography();var second=areas.data().metadata().geography();
        if(second==null||!Objects.equals(first.codeSystem(),second.codeSystem())||!Objects.equals(first.edition(),second.edition())||!"uniform-area-v1".equals(second.allocationMethod()))
            throw new IllegalArgumentException("Schlüsselraum, Gebietsstand und Flächenmodell müssen übereinstimmen");
        Set<String> keys=new HashSet<>();for(var row:areas.data().rows())keys.add(row.values().get("ags").toString());
        for(var row:totals.data().rows())if(!keys.contains(row.values().get("ags").toString()))throw new IllegalArgumentException("Gemeindegeometrie fehlt für AGS "+row.values().get("ags"));
        for(var row:areas.data().rows())if(totals.data().rows().stream().anyMatch(total->total.values().get("ags").equals(row.values().get("ags")))){
            var envelope=AreaGeometry.read(row.values().get("geometry")).getEnvelopeInternal();var coverage=totals.data().metadata().coverage();
            if(!ImportAdapters.contains(coverage,envelope.getMinX(),envelope.getMinY())||!ImportAdapters.contains(coverage,envelope.getMaxX(),envelope.getMaxY()))throw new IllegalArgumentException("Gemeindegeometrie überschreitet das deklarierte Aggregatgebiet");
        }
    }
    public static Map<String,Object> apply(DataImportService.Revision totals,DataImportService.Revision shapes,SourcePlan.Policy policy,List<Zone> zones){
        validateBinding(totals,shapes);
        var geometries=new TreeMap<String,Area>();
        for(var row:shapes.data().rows())geometries.put(row.values().get("ags").toString(),new Area(row.sourceRow(),row.values().get("ags").toString(),SpatialGeometry.project(AreaGeometry.read(row.values().get("geometry")))));
        var used=totals.data().rows().stream().map(row->geometries.get(row.values().get("ags").toString()).geometry()).toList();
        // The existing inverse projection is accurate to <1 cm. Buffer containment, not area rounding,
        // tolerates that boundary error while preserving real holes wider than 2 cm.
        var fullCoverage=PreparedGeometryFactory.prepare(UnaryUnionOp.union(used).buffer(.01));
        var cells=new TreeMap<Integer,Geometry>();for(var zone:zones)if(!zone.external)cells.put(zone.index,SpatialGeometry.zone3035(zone));
        double[] values=new double[zones.size()],coverage=new double[zones.size()];
        var assignments=new ArrayList<Map<String,Object>>();var municipalityRows=new ArrayList<Map<String,Object>>();
        double input=0,represented=0;
        for(var row:totals.data().rows()){
            String ags=row.values().get("ags").toString();var area=geometries.get(ags);double jobs=((Number)row.values().get("value")).doubleValue();input+=jobs;double mapped=0;
            for(var zone:zones)if(!zone.external){
                var cell=cells.get(zone.index);if(!cell.getEnvelopeInternal().intersects(area.geometry().getEnvelopeInternal()))continue;
                double intersection=cell.intersection(area.geometry()).getArea();if(intersection<=0)continue;
                double fraction=intersection/area.geometry().getArea(),quantity=jobs*fraction;
                coverage[zone.index]+=intersection;values[zone.index]+=quantity;mapped+=quantity;
                var record=new TreeMap<String,Object>();record.put("ags",ags);record.put("aggregateId",row.values().get("id"));record.put("aggregateRow",row.sourceRow());record.put("geometryRow",area.row());
                record.put("zone",new SourceProvenance.ZoneRef(zone.id,zone.index));record.put("areaShare",fraction);record.put("intersectionSquareMeters",intersection);record.put("quantity",quantity);assignments.add(record);
            }
            represented+=mapped;municipalityRows.add(Map.of("ags",ags,"inputJobs",jobs,"representedJobs",mapped,"outsideJobs",Math.max(0,jobs-mapped),"municipalitySquareMeters",area.geometry().getArea()));
        }
        Set<Integer> full=new HashSet<>();var covered=new ArrayList<SourceProvenance.ZoneRef>();var fallback=new ArrayList<SourceProvenance.ZoneRef>();int partial=0;double applied=0;
        for(var zone:zones)if(!zone.external){
            if(fullCoverage.covers(cells.get(zone.index))){full.add(zone.index);covered.add(new SourceProvenance.ZoneRef(zone.id,zone.index));applied+=values[zone.index];}
            else{fallback.add(new SourceProvenance.ZoneRef(zone.id,zone.index));if(coverage[zone.index]>0)partial++;}
        }
        if(!fallback.isEmpty()&&policy.gaps.equals("none"))throw new IllegalArgumentException("Gemeindegeometrien decken nicht jede Modellzone vollständig ab; Lückenregel erforderlich");
        for(var record:assignments){var zone=(SourceProvenance.ZoneRef)record.get("zone");record.put("status",full.contains(zone.modelIndex())?"applied":"partial-zone-not-applied");}
        // Only fully covered zones change: partial municipality mass never combines with a whole legacy zone.
        for(var zone:zones)if(full.contains(zone.index))zone.attractivities.put("Work",values[zone.index]);
        var report=new TreeMap<String,Object>();report.put("contract","synthesis-municipal-area-allocation-v1");report.put("revision",totals.id());report.put("allocationRevision",shapes.id());report.put("geography",totals.data().metadata().geography());
        report.put("inputJobs",input);report.put("appliedJobs",applied);report.put("outsideJobs",Math.max(0,input-represented));report.put("unappliedPartialJobs",Math.max(0,represented-applied));report.put("unresolvedJobs",0);
        report.put("coveredZones",covered.size());report.put("fallbackZones",fallback.size());report.put("partialZones",partial);report.put("municipalities",municipalityRows);report.put("rowAssignments",assignments);
        report.put("coverageDenominator","internal model zones; polygon intersection area divided by true zone area in EPSG:3035");report.put("coverageToleranceMeters",.01);report.put("coverageRule","full containment after 1 cm numerical buffer; all quantities use unbuffered polygons");report.put("allocation","uniform-area-v1; uniform density assumption within each full municipality; no normalization to study region");
        report.put("provenance",MunicipalWorkplaces.provenance(totals,shapes,covered,fallback));return Collections.unmodifiableMap(report);
    }
}
