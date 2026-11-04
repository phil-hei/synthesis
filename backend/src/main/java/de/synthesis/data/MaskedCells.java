package de.synthesis.data;

import de.synthesis.data.importing.*;
import de.synthesis.data.spatial.SpatialGeometry;
import de.synthesis.generation.pipeline.Zone;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;

/** EPSG:3035 cell quantities and explicit masks. Only wholly known zones receive source weights. */
public final class MaskedCells {
    public static final String WORK="workplace-cells-v1",POP="population-cells-v1";
    public static final double EDGE_METERS=.01,AREA_EPS=.01;
    public record Allocation(double[] counts,Set<Integer> covered,List<SourceProvenance.ZoneRef> gaps,Map<String,Object> report) { }
    private record Cell(DataRow row,Geometry geometry,boolean known) { }
    private MaskedCells(){ }
    private static double n(DataRow row,String key){return ((Number)row.values().get(key)).doubleValue();}
    private static Geometry shape(DataRow row){double x=n(row,"easting"),y=n(row,"northing"),r=n(row,"resolution");return SpatialGeometry.FACTORY.createPolygon(new Coordinate[]{new Coordinate(x,y),new Coordinate(x+r,y),new Coordinate(x+r,y+r),new Coordinate(x,y+r),new Coordinate(x,y)});}
    public static List<Issue> validateCells(List<DataRow> rows){
        var tree=new STRtree();for(int i=0;i<rows.size();i++)tree.insert(shape(rows.get(i)).getEnvelopeInternal(),i);tree.build();
        var issues=new ArrayList<Issue>();var ids=new HashSet<String>();
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);if(!ids.add(row.values().get("id").toString()))issues.add(new Issue(row.sourceRow(),"id","duplicate","Doppelte Zell-ID"));
            var box=shape(row).getEnvelopeInternal();for(Object hit:tree.query(box)){int j=(Integer)hit;if(j>=i)continue;var other=shape(rows.get(j)).getEnvelopeInternal();
                if(Math.min(box.getMaxX(),other.getMaxX())>Math.max(box.getMinX(),other.getMinX())&&Math.min(box.getMaxY(),other.getMaxY())>Math.max(box.getMinY(),other.getMinY())){issues.add(new Issue(row.sourceRow(),"easting","overlap","Rasterzellen dürfen sich nicht überlappen, auch nicht bei NoData"));break;}
            }
        }
        return issues;
    }
    public static Allocation allocate(DataImportService.Revision revision,List<Zone> zones){
        var cells=revision.data().rows().stream().map(r->new Cell(r,shape(r),r.values().get("state").equals("value"))).toList();
        var tree=new STRtree();var knownShapes=new ArrayList<Geometry>();double input=0;
        for(var c:cells){tree.insert(c.geometry().getEnvelopeInternal(),c);if(c.known()){knownShapes.add(c.geometry());input+=n(c.row(),"value");}}tree.build();
        Geometry union=knownShapes.isEmpty()?SpatialGeometry.FACTORY.createPolygon():UnaryUnionOp.union(knownShapes);
        var safeUnion=union.buffer(EDGE_METERS);double[] counts=new double[zones.size()];var covered=new LinkedHashSet<Integer>();var gaps=new ArrayList<SourceProvenance.ZoneRef>();
        var masks=new ArrayList<Map<String,Object>>();double represented=0,applied=0;int missing=0,suppressed=0,notCollected=0;
        for(var c:cells){if(c.row().values().get("state").equals("missing"))missing++;if(c.row().values().get("state").equals("suppressed"))suppressed++;if(c.row().values().get("state").equals("not-collected"))notCollected++;}
        for(var zone:zones)if(!zone.external){
            var geometry=SpatialGeometry.zone3035(zone);double area=geometry.getArea();if(!Double.isFinite(area)||area<=0)throw new IllegalArgumentException("Modellzone ohne gültige Fläche");
            double knownArea=0,unknownArea=0;var reasons=new LinkedHashSet<String>();
            for(Object hit:tree.query(geometry.getEnvelopeInternal())){var cell=(Cell)hit;double overlap=cell.geometry().intersection(geometry).getArea();if(overlap<=0)continue;
                if(cell.known()){knownArea+=overlap;counts[zone.index]+=n(cell.row(),"value")*overlap/cell.geometry().getArea();}
                else{unknownArea+=overlap;if(overlap>AREA_EPS)reasons.add(cell.row().values().get("state").toString());}
            }
            represented+=counts[zone.index];boolean complete=!union.isEmpty()&&safeUnion.covers(geometry)&&unknownArea<=AREA_EPS;
            if(complete){covered.add(zone.index);applied+=counts[zone.index];}
            else{gaps.add(new SourceProvenance.ZoneRef(zone.id,zone.index));if(area-knownArea-unknownArea>AREA_EPS)reasons.add("outside-source-cells");if(knownArea>AREA_EPS)reasons.add("partial-known-coverage");}
            masks.add(Map.of("zone",new SourceProvenance.ZoneRef(zone.id,zone.index),"state",complete?"value":"unresolved","reasons",List.copyOf(reasons),"knownAreaM2",knownArea,"maskedAreaM2",unknownArea,"zoneAreaM2",area));
        }
        if(!Double.isFinite(input)||!Double.isFinite(represented)||represented>input+Math.max(1,input)*1e-7)throw new IllegalArgumentException("Rastermengen-/Flächenbilanz ungültig");
        var rows=new ArrayList<Map<String,Object>>();for(var c:cells){var row=new TreeMap<String,Object>();row.put("sourceRow",c.row().sourceRow());row.putAll(c.row().values());rows.add(Collections.unmodifiableMap(row));}
        var report=new TreeMap<String,Object>();report.put("contract","synthesis-masked-cell-allocation-v1");report.put("revision",revision.id());report.put("crs","EPSG:3035");report.put("quantityBasis","known-source-cells-only");
        report.put("knownInput",input);report.put("knownApplied",applied);report.put("knownOutside",Math.max(0,input-represented));report.put("knownInPartialZonesNotApplied",Math.max(0,represented-applied));report.put("missingCells",missing);report.put("suppressedCells",suppressed);report.put("notCollectedCells",notCollected);report.put("cells",rows);report.put("zoneMasks",masks);
        report.put("coveredZones",covered.size());report.put("fallbackZones",gaps.size());report.put("coverageToleranceMeters",EDGE_METERS);report.put("maskIntersectionToleranceM2",AREA_EPS);report.put("allocation","uniform within each full source cell; unbuffered intersection quantities; no renormalization to region");
        return new Allocation(counts,Collections.unmodifiableSet(covered),List.copyOf(gaps),Collections.unmodifiableMap(report));
    }
    public static Map<String,Object> workplaces(DataImportService.Revision revision,SourcePlan.Policy policy,List<Zone> zones){
        var allocation=allocate(revision,zones);if(!allocation.gaps().isEmpty()&&policy.gaps.equals("none"))throw new IllegalArgumentException("Arbeitsplatzraster enthält unbekannte oder nicht vollständig abgedeckte Zonen; explizite Lückenregel erforderlich");
        var report=new TreeMap<String,Object>(allocation.report());report.put("inputJobs",report.get("knownInput"));report.put("appliedJobs",report.get("knownApplied"));report.put("outsideJobs",report.get("knownOutside"));report.put("unappliedPartialJobs",report.get("knownInPartialZonesNotApplied"));
        var covered=zones.stream().filter(z->allocation.covered().contains(z.index)).map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList();
        var scope=new SourceProvenance.Scope("Work",covered);var nodes=new ArrayList<SourceProvenance.Node>();nodes.add(source(revision,scope));
        nodes.add(new SourceProvenance.Node("cell-allocation","uniform-source-cell-area","assumed","primary",null,List.of("cells"),scope,Map.of("crs","EPSG:3035","unit","jobs","missingCells",report.get("missingCells"),"suppressedCells",report.get("suppressedCells"))));
        var inputs=new ArrayList<>(List.of("cell-allocation"));if(!allocation.gaps().isEmpty()){
            nodes.add(new SourceProvenance.Node("legacy-work","legacy-delegation","unknown","fallback",new SourceProvenance.Reference("legacy",null,null,"Bisherige Arbeitsgewichte","legacy-delegation",null,"unknown",""),List.of(),new SourceProvenance.Scope("Work",allocation.gaps()),Map.of("unit","model-weight")));inputs.add("legacy-work");
        }
        var all=zones.stream().filter(z->!z.external).map(z->new SourceProvenance.ZoneRef(z.id,z.index)).toList();nodes.add(new SourceProvenance.Node("work-weights",allocation.gaps().isEmpty()?"choose":"fill-gaps","derived","composition",null,inputs,new SourceProvenance.Scope("Work",all),Map.of("quantitiesComparable",allocation.gaps().isEmpty(),"quantityBasis","known-source-cells-only")));
        report.put("provenance",new SourceProvenance(SourceProvenance.CONTRACT,"opportunities.work","work-weights",nodes));
        for(var z:zones)if(allocation.covered().contains(z.index))z.attractivities.put("Work",allocation.counts()[z.index]);return Collections.unmodifiableMap(report);
    }
    static SourceProvenance.Node source(DataImportService.Revision revision,SourceProvenance.Scope scope){var d=revision.data();var m=d.metadata();return new SourceProvenance.Node("cells","source","unknown","primary",new SourceProvenance.Reference("dataset",revision.id(),d.sourceHash(),m.label(),m.source(),m.referenceDate(),m.licence(),m.attribution()),List.of(),scope,Map.of("schema",d.schemaId(),"crs","EPSG:3035","mapping",d.mapping()));}
}
