package de.synthesis.data.importing;

import de.synthesis.scenario.Scenario;
import org.springframework.context.annotation.*;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;

@Configuration
public class ImportAdapters {
    private static Field field(String id,String label,String type,String... aliases){return new Field(id,label,type,true,List.of(aliases));}
    @Bean AspectImportAdapter workplacesImport(){return points("workplaces-v1","Arbeitsplätze","opportunities.work","jobs","Arbeitsplatzanzahl",List.of("arbeitsplaetze","jobs","workplaces"));}
    @Bean AspectImportAdapter populationImport(){return points("population-grid-v1","Bevölkerungsverteilung","population.distribution","persons","Einwohner",List.of("population","einwohner","persons"));}
    @Bean AspectImportAdapter workplaceCellsImport(){return new CellImportAdapter("workplace-cells-v1","Arbeitsplatzraster mit Wertstatus (EPSG:3035)","opportunities.work","jobs");}
    @Bean AspectImportAdapter populationCellsImport(){return new CellImportAdapter("population-cells-v1","Bevölkerungsraster mit Wertstatus (EPSG:3035)","population.distribution","persons");}
    @Bean AspectImportAdapter municipalWorkplacesImport(){return new AspectImportAdapter(){
        public Schema schema(){return new Schema("municipal-workplaces-v1","Arbeitsplätze je Gemeinde","opportunities.work","jobs",List.of(
                field("id","Quell-ID","text","id","kennung"),field("ags","Gemeindeschlüssel (AGS)","text","ags","gemeindeschluessel"),field("value","Arbeitsplatzanzahl","number","arbeitsplaetze","jobs")));}
        public List<Issue> validate(DataRow row,Metadata meta){var issues=new ArrayList<Issue>();ags(row,issues);if(number(row,"value")<0)issues.add(new Issue(row.sourceRow(),"value","negative","Arbeitsplatzanzahl darf nicht negativ sein"));return issues;}
        public List<Issue> validateDataset(List<DataRow> rows,Metadata meta){return uniqueAgs(rows);}
        public List<Dependency> dependencies(Metadata meta){return List.of(new Dependency("municipality-allocation",meta.geography().allocationRevision(),meta.geography().allocationMethod().equals("uniform-area-v1")?"municipality-areas-v1":"municipality-allocation-v1"));}
    };}
    @Bean AspectImportAdapter municipalityAllocationImport(){return new AspectImportAdapter(){
        public Schema schema(){return new Schema("municipality-allocation-v1","Räumliche Gemeindegewichte","support.municipalityAllocation","relative-weight",List.of(
                field("id","Zuordnungs-ID","text","id","kennung"),field("ags","Gemeindeschlüssel (AGS)","text","ags","gemeindeschluessel"),
                field("lon","Längengrad","number","lon","longitude","laenge"),field("lat","Breitengrad","number","lat","latitude","breite"),field("weight","Verteilungsgewicht","number","weight","gewicht")));}
        public List<Issue> validate(DataRow row,Metadata meta){var issues=new ArrayList<Issue>();ags(row,issues);point(row,"lon","lat",meta.coverage(),issues);if(number(row,"weight")<=0)issues.add(new Issue(row.sourceRow(),"weight","weight","Verteilungsgewicht muss positiv sein"));return issues;}
    };}
    @Bean AspectImportAdapter municipalityAreasImport(){return new AspectImportAdapter(){
        public Schema schema(){return new Schema("municipality-areas-v1","Gemeindegeometrien (GeoJSON)","support.municipalityAllocation","area-boundary",List.of(
                field("id","Feature-ID","text","id"),field("ags","Gemeindeschlüssel (AGS)","text","ags"),field("geometry","Polygon-/MultiPolygon-Geometrie","geometry","geometry")));}
        public List<Issue> validate(DataRow row,Metadata meta){
            var issues=new ArrayList<Issue>();ags(row,issues);
            try{var geometry=de.synthesis.data.spatial.AreaGeometry.read(row.values().get("geometry"));var box=geometry.getEnvelopeInternal();
                if(!contains(meta.coverage(),box.getMinX(),box.getMinY())||!contains(meta.coverage(),box.getMaxX(),box.getMaxY()))issues.add(new Issue(row.sourceRow(),"geometry","coverage","Geometrie überschreitet die deklarierte Datenbox"));
            }catch(IllegalArgumentException e){issues.add(new Issue(row.sourceRow(),"geometry","geometry",e.getMessage()));}
            return issues;
        }
        public List<Issue> validateDataset(List<DataRow> rows,Metadata meta){var issues=new ArrayList<>(uniqueAgs(rows));issues.addAll(de.synthesis.data.MunicipalAreas.validateAreas(rows));return issues;}
    };}
    private static void ags(DataRow row,List<Issue> issues){if(!row.values().get("ags").toString().matches("[0-9]{8}"))issues.add(new Issue(row.sourceRow(),"ags","key","AGS als achtstelligen Text mit führenden Nullen erhalten"));}
    private static List<Issue> uniqueAgs(List<DataRow> rows){var seen=new HashSet<String>();var issues=new ArrayList<Issue>();for(var row:rows)if(!seen.add(row.values().get("ags").toString()))issues.add(new Issue(row.sourceRow(),"ags","duplicate-key","Je AGS ist genau ein Gemeindeaggregat zulässig"));return issues;}
    private static AspectImportAdapter points(String id,String label,String aspect,String unit,String valueLabel,List<String> aliases){
        var fields=List.of(field("id","Quell-ID","text","kennung","id","sourceId"),field("lon","Längengrad","number","lon","longitude","laenge"),
                field("lat","Breitengrad","number","lat","latitude","breite"),new Field("value",valueLabel,"number",true,aliases));
        return new AspectImportAdapter(){
            public Schema schema(){return new Schema(id,label,aspect,unit,fields);}
            public List<Issue> validate(DataRow row,Metadata meta){
                var errors=new ArrayList<Issue>();point(row,"lon","lat",meta.coverage(),errors);
                if(number(row,"value")<0)errors.add(new Issue(row.sourceRow(),"value","negative","Menge darf nicht negativ sein"));return errors;
            }
        };
    }
    @Bean AspectImportAdapter freightImport(){return new AspectImportAdapter(){
        public Schema schema(){return new Schema("vehicle-od-v1","Schwerlastbeziehungen","freight.vehicleOd","vehicle-trips/day",List.of(
            field("id","Relations-ID","text","id","kennung"),field("fromLon","Start: Längengrad","number","fromLon","von_lon"),field("fromLat","Start: Breitengrad","number","fromLat","von_lat"),
            field("toLon","Ziel: Längengrad","number","toLon","nach_lon"),field("toLat","Ziel: Breitengrad","number","toLat","nach_lat"),
            field("trips","Fahrzeugfahrten pro Tag","integer","trips","fahrten"),field("vehicleClass","Fahrzeugklasse","text","vehicleClass","klasse"),
            field("movement","Binnen-/Außenbeziehung","text","movement","beziehung"),field("beginHour","Fensterbeginn (Stunde)","number","beginHour","von_stunde"),field("endHour","Fensterende (Stunde)","number","endHour","bis_stunde")));}
        public List<Issue> validate(DataRow row,Metadata meta){
            var errors=new ArrayList<Issue>();point(row,"fromLon","fromLat",meta.coverage(),errors);point(row,"toLon","toLat",meta.coverage(),errors);
            var v=row.values();
            if(!v.get("id").toString().matches("[A-Za-z0-9_-]{1,80}"))errors.add(new Issue(row.sourceRow(),"id","id","Relations-ID: 1–80 Buchstaben, Ziffern, Unterstrich oder Bindestrich"));
            if(!Set.of("truck","trailer").contains(v.get("vehicleClass")))errors.add(new Issue(row.sourceRow(),"vehicleClass","class","Erlaubt: truck oder trailer"));
            if(!Set.of("internal","inbound","outbound","through").contains(v.get("movement")))errors.add(new Issue(row.sourceRow(),"movement","movement","Erlaubt: internal, inbound, outbound, through"));
            if(number(row,"trips")<=0||number(row,"trips")>200000)errors.add(new Issue(row.sourceRow(),"trips","range","Positive Tagesmenge bis 200.000 erforderlich"));
            if(number(row,"beginHour")<0||number(row,"endHour")>24||number(row,"beginHour")>=number(row,"endHour"))errors.add(new Issue(row.sourceRow(),"beginHour","window","Stundenfenster muss innerhalb [0,24] liegen"));
            if(number(row,"fromLon")==number(row,"toLon")&&number(row,"fromLat")==number(row,"toLat"))errors.add(new Issue(row.sourceRow(),"toLon","same-point","Start und Ziel müssen verschieden sein"));
            return errors;
        }
    };}
    public static double number(DataRow row,String key){return ((Number)row.values().get(key)).doubleValue();}
    public static boolean contains(Scenario.Region r,double lon,double lat){return r!=null&&lon>=r.minLon&&lon<=r.maxLon&&lat>=r.minLat&&lat<=r.maxLat;}
    private static void point(DataRow row,String lonKey,String latKey,Scenario.Region r,List<Issue> out){
        double lon=number(row,lonKey),lat=number(row,latKey);
        if(Math.abs(lon)>180||Math.abs(lat)>90||!contains(r,lon,lat))out.add(new Issue(row.sourceRow(),lonKey,"outside-coverage","Koordinate liegt außerhalb der deklarierten WGS84-Abdeckung"));
    }
}
