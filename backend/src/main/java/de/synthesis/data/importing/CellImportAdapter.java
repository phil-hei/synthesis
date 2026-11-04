package de.synthesis.data.importing;

import de.synthesis.data.MaskedCells;
import de.synthesis.generation.pipeline.Epsg3035;
import java.util.*;
import static de.synthesis.data.importing.AspectImportAdapter.*;

/** Explicit cell extent and discriminated quantity: zero is a value, never a missing marker. */
public final class CellImportAdapter implements AspectImportAdapter {
    private final Schema schema;
    private static Field f(String id,String label,String type,String... aliases){return new Field(id,label,type,true,List.of(aliases));}
    public CellImportAdapter(String id,String label,String aspect,String unit){schema=new Schema(id,label,aspect,unit,List.of(
        f("id","Zell-ID","text","id","cellId","kennung"),f("easting","Westkante EPSG:3035 (m)","integer","easting","x","west"),
        f("northing","Südkante EPSG:3035 (m)","integer","northing","y","south"),f("resolution","Zellkante (m)","integer","resolution","res","cellSize"),
        f("state","Wertstatus (value / missing / not-collected / suppressed)","text","state","status","wertstatus"),
        new Field("value","Menge oder expliziter NoData-Marker","masked-number",false,List.of("value","population","persons","jobs","einwohner","arbeitsplaetze"))));}
    public Schema schema(){return schema;}
    public DataRow normalize(DataRow row){
        var v=new TreeMap<>(row.values());String state=Objects.toString(v.get("state"),"");Object value=v.get("value");
        if(Set.of("missing","not-collected","suppressed").contains(state)&&(value==null||value instanceof String||value instanceof Number n&&n.doubleValue()<0)){
            v.remove("value");v.put("rawValue",value==null?"":value);
        }
        return new DataRow(row.sourceRow(),Collections.unmodifiableMap(v));
    }
    public List<Issue> validate(DataRow row,Metadata metadata){
        var issues=new ArrayList<Issue>();var v=row.values();String state=Objects.toString(v.get("state"),"");
        if(!Set.of("value","missing","not-collected","suppressed").contains(state))issues.add(new Issue(row.sourceRow(),"state","state","Status muss value, missing, not-collected oder suppressed sein"));
        if(state.equals("value")&&(!(v.get("value") instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0))issues.add(new Issue(row.sourceRow(),"value","quantity","Für value ist eine nichtnegative Zahl erforderlich; 0 bleibt ein echter Wert"));
        if(!state.equals("value")&&v.containsKey("value"))issues.add(new Issue(row.sourceRow(),"value","contradiction","Fehlend/unterdrückt darf keinen nichtnegativen Zahlenwert verdecken"));
        double x=((Number)v.get("easting")).doubleValue(),y=((Number)v.get("northing")).doubleValue(),r=((Number)v.get("resolution")).doubleValue();
        if(r<100||r>10000||r%100!=0||x%100!=0||y%100!=0)issues.add(new Issue(row.sourceRow(),"resolution","grid","100-m-ausgerichtete Zellen mit 100–10.000 m Kantenlänge erforderlich"));
        for(double[] p:new double[][]{{x,y},{x+r,y},{x,y+r},{x+r,y+r}}){
            var ll=Epsg3035.toLatLon(p[0],p[1]);if(!Double.isFinite(ll[0])||!Double.isFinite(ll[1])||!Epsg3035.covers(ll[0],ll[1],ll[0],ll[1])||!ImportAdapters.contains(metadata.coverage(),ll[1],ll[0])){
                issues.add(new Issue(row.sourceRow(),"easting","coverage","Vollständige Rasterzelle muss im EPSG:3035-Gültigkeitsbereich und in der deklarierten Quellenbox liegen"));break;
            }
        }
        return issues;
    }
    public List<Issue> validateDataset(List<DataRow> rows,Metadata metadata){return MaskedCells.validateCells(rows);}
}
