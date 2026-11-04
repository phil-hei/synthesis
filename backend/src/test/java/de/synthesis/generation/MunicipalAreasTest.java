package de.synthesis.generation;

import com.fasterxml.jackson.databind.*;
import de.synthesis.data.*;
import de.synthesis.data.importing.*;
import de.synthesis.data.spatial.*;
import de.synthesis.generation.pipeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

class MunicipalAreasTest {
    @TempDir Path directory;
    MunicipalSourcesTest base;ObjectMapper json;
    static final double X=4200000,Y=2900000;
    @BeforeEach void setup(){base=new MunicipalSourcesTest();base.directory=directory;base.setup();json=base.json;}
    Object ring(double x,double y,double width,double height){
        var points=new ArrayList<List<Double>>();
        for(double[] p:new double[][]{{x,y},{x+width,y},{x+width,y+height},{x,y+height},{x,y}}){var ll=Epsg3035.toLatLon(p[0],p[1]);points.add(List.of(ll[1],ll[0]));}return points;
    }
    Object polygon(double x,double y,double width,double height){return Map.of("type","Polygon","coordinates",List.of(ring(x,y,width,height)));}
    byte[] geo(Object... geometries)throws Exception{
        var features=new ArrayList<Object>();int i=0;for(var geometry:geometries)features.add(Map.of("type","Feature","properties",Map.of("id","shape-"+i,"AGS",i++==0?"01001000":"02000000"),"geometry",geometry));
        return json.writeValueAsBytes(Map.of("type","FeatureCollection","features",features));
    }
    Metadata metadata(String schema,String ref){return new Metadata("Künstliche Geometriequelle","Künstliche Flächenprüfung","2026-01-01",schema.equals("municipal-workplaces-v1")?"jobs":"area-boundary",DataSourcesTest.box(5,45,16,56),"unknown","Testfixture",
            schema.equals("municipal-workplaces-v1")?new CoverageDeclaration("municipality-aggregate","complete","Vollständige künstliche Gemeindesumme für den enthaltenen AGS"):null,
            new Geography("de:AGS","2025-01-01",ref,"uniform-area-v1"));}
    DataImportService.RevisionInfo publishAreas(byte[] raw)throws Exception{
        var draft=base.imports.upload(base.scenario.id,"municipality-areas-v1",raw,"areas.geojson");
        return base.imports.publish(base.scenario.id,draft.id(),new DataImportService.Request(base.imports.suggest(draft),metadata("municipality-areas-v1",null)));
    }
    SourceResolver.Inputs inputs(String shape)throws Exception{
        var totals=base.publish("municipal-workplaces-v1","id;ags;value\nt;01001000;100\n",metadata("municipal-workplaces-v1",shape));
        base.scenario.region=DataSourcesTest.box(8,49,10,51);base.scenario.dataPlan.enabled=true;var policy=new SourcePlan.Policy();policy.source="dataset";policy.revision=totals.id();base.scenario.dataPlan.aspects.put("opportunities.work",policy);
        return SourceResolver.prepare(base.scenario,base.repo,json);
    }
    Zone zone(int index){var z=DataSourcesTest.workZone(index,900001+index,8,9);z.laeaE=(long)(X+index*1000);z.laeaN=(long)Y;z.laeaRes=1000;return z;}
    @Test void geometryRemainsTypedWithFeatureEvidenceAndRevisionHashRoundtrip()throws Exception{
        var raw=geo(polygon(X-10,Y-10,2020,1020));var draft=base.imports.upload(base.scenario.id,"municipality-areas-v1",raw,"areas.geojson");
        assertEquals("/features/0",draft.source().evidence().getFirst().reference());
        var revision=base.imports.publish(base.scenario.id,draft.id(),new DataImportService.Request(base.imports.suggest(draft),metadata("municipality-areas-v1",null)));
        var retained=DataImportService.readRevision(base.repo,json,base.scenario.id,revision.id());
        assertEquals("01001000",retained.data().rows().getFirst().values().get("ags"));assertTrue(retained.data().rows().getFirst().values().get("geometry") instanceof Map);
        assertEquals(5,AreaGeometry.read(retained.data().rows().getFirst().values().get("geometry")).getNumPoints());
        assertEquals(ImportSupport.hash(raw),retained.data().sourceHash());
    }
    @Test void trueProjectedCellUsesFullMunicipalityAreaWithoutRenormalization()throws Exception{
        var reference=publishAreas(geo(polygon(X-10,Y-10,2020,1020)));var input=inputs(reference.id());var cell=zone(0);
        var report=SourceResolver.applyWorkplaces(input,base.scenario.dataPlan,List.of(cell));double expected=100*1000000.0/(2020*1020);
        assertEquals(expected,(Double)report.get("appliedJobs"),.0001);assertEquals(100-expected,(Double)report.get("outsideJobs"),.0001);
        assertEquals(0,(double)(Double)report.get("unappliedPartialJobs"),.0001);assertEquals(1,report.get("coveredZones"));
        assertEquals(expected,cell.attractivities.get("Work"),.0001);
        var provenance=(SourceProvenance)report.get("provenance");assertEquals("uniform-area-v1",provenance.nodes().get(2).operation());assertEquals("assumed",provenance.nodes().get(2).evidence());
    }
    @Test void coincidentProjectedCellAndBoundaryHaveNumericallyCompleteCoverage()throws Exception{
        var input=inputs(publishAreas(geo(polygon(X,Y,1000,1000))).id());var report=SourceResolver.applyWorkplaces(input,base.scenario.dataPlan,List.of(zone(0)));
        assertEquals(1,report.get("coveredZones"));assertEquals(100,(Double)report.get("appliedJobs"),.001);
    }
    @Test void geoJsonPackUsesTheSameTypedReaderAndStableRevisionHash()throws Exception{
        byte[] raw=geo(polygon(X-10,Y-10,2020,1020));var source=base.imports.readSource("municipality-areas-v1",raw,"areas.geojson");
        var draft=new DataImportService.Draft("draft","areas.geojson","municipality-areas-v1",source);
        var entry=new DataPacks.Entry("areas","municipality-areas-v1","areas.geojson",ImportSupport.hash(raw),base.imports.suggest(draft),metadata("municipality-areas-v1",null));
        var manifest=new DataPacks.Manifest(DataPacks.CONTRACT,"geo-example","1","Künstliche Flächen",List.of(entry));
        byte[] archive=SourceProfilesAndPacksTest.zip(Map.of("areas.geojson",raw,"manifest.json",json.writeValueAsBytes(manifest)));
        var packs=new DataPacks(base.repo,json,ImportRegistry.builtins());var installed=packs.install(base.scenario.id,archive);
        assertEquals(installed,packs.read(base.scenario.id,installed.id()));assertEquals(ImportSupport.hash(raw),packs.resolve(base.scenario.id,installed.id(),"areas").data().sourceHash());assertFalse(base.repo.findById(base.scenario.id).orElseThrow().dataPlan.enabled);
    }
    @Test void holesCreatePartialCoverageAndDoNotMixPartialMassWithWholeLegacyZone()throws Exception{
        Object shape=Map.of("type","Polygon","coordinates",List.of(ring(X-10,Y-10,2020,1020),ring(X+250,Y+250,250,250)));
        var input=inputs(publishAreas(geo(shape)).id());var first=zone(0);var second=zone(1);
        var report=SourceResolver.applyWorkplaces(input,base.scenario.dataPlan,List.of(first,second));
        assertEquals(60.0,first.attractivities.get("Work"));assertTrue(second.attractivities.get("Work")>0);assertEquals(1,report.get("partialZones"));
        double sum=(Double)report.get("appliedJobs")+(Double)report.get("outsideJobs")+(Double)report.get("unappliedPartialJobs");assertEquals(100,sum,1e-8);
        assertTrue((Double)report.get("unappliedPartialJobs")>0);base.scenario.dataPlan.policy("opportunities.work").gaps="none";
        var untouched=List.of(zone(0),zone(1));assertThrows(IllegalArgumentException.class,()->SourceResolver.applyWorkplaces(input,base.scenario.dataPlan,untouched));
        assertEquals(List.of(60.0,60.0),untouched.stream().map(z->z.attractivities.get("Work")).toList());
    }
    @Test void multipolygonRetainsDisjointPartsAndItsGapIsNotZeroCoverage()throws Exception{
        Object shape=Map.of("type","MultiPolygon","coordinates",List.of(List.of(ring(X-10,Y-10,1020,1020)),List.of(ring(X+1990,Y-10,1020,1020))));
        var input=inputs(publishAreas(geo(shape)).id());var cells=List.of(zone(0),zone(1),zone(2));var report=SourceResolver.applyWorkplaces(input,base.scenario.dataPlan,cells);
        assertEquals(60.0,cells.get(1).attractivities.get("Work"));assertEquals(2,report.get("coveredZones"));assertEquals(1,report.get("fallbackZones"));
    }
    @Test void overlappingMunicipalitiesInvalidRingsAndWrongCrsCannotPublish()throws Exception{
        var shape=polygon(X,Y,2000,1000);var draft=base.imports.upload(base.scenario.id,"municipality-areas-v1",geo(shape,shape),"overlap.geojson");
        var request=new DataImportService.Request(base.imports.suggest(draft),metadata("municipality-areas-v1",null));var preview=base.imports.preview(base.scenario.id,draft.id(),request);
        assertEquals("invalid",preview.status());assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("overlap")));assertThrows(IllegalArgumentException.class,()->base.imports.publish(base.scenario.id,draft.id(),request));
        assertThrows(IllegalArgumentException.class,()->base.imports.upload(base.scenario.id,"municipality-areas-v1",geo(Map.of("type","Point","coordinates",List.of(8,49))),"point.geojson"));
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(geo(shape));node.put("crs","EPSG:3035");
        assertThrows(IllegalArgumentException.class,()->base.imports.upload(base.scenario.id,"municipality-areas-v1",json.writeValueAsBytes(node),"crs.geojson"));
        Object unclosed=Map.of("type","Polygon","coordinates",List.of(List.of(List.of(8,49),List.of(9,49),List.of(9,50),List.of(8,50))));
        assertThrows(IllegalArgumentException.class,()->base.imports.upload(base.scenario.id,"municipality-areas-v1",geo(unclosed),"unclosed.geojson"));
    }
}
