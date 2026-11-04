package de.synthesis.data.importing;

import com.fasterxml.jackson.databind.*;
import de.synthesis.data.spatial.AreaGeometry;
import java.util.*;

/** Geometry remains a typed JSON object with feature evidence; coordinate arrays are never flattened. */
public final class GeometrySourceReader {
    private final ObjectMapper json;
    public GeometrySourceReader(ObjectMapper json){this.json=json;}
    public SourceReader.Source read(byte[] bytes){
        if(bytes.length==0||bytes.length>TabularReader.MAX_BYTES)throw new IllegalArgumentException("GeoJSON fehlt oder überschreitet 5 MB");
        try{
            var root=json.readTree(bytes);
            if(root==null||!"FeatureCollection".equals(root.path("type").asText())||!root.path("features").isArray()||root.path("features").isEmpty()||root.path("features").size()>500||root.has("crs"))
                throw new IllegalArgumentException("GeoJSON FeatureCollection mit 1–500 Features, WGS84 lon/lat und ohne abweichendes CRS erforderlich");
            var records=new ArrayList<Map<String,String>>();var evidence=new ArrayList<SourceReader.Evidence>();
            var headers=new LinkedHashSet<String>();int index=0,totalPoints=0;
            for(var feature:root.path("features")){
                if(!"Feature".equals(feature.path("type").asText())||!feature.path("properties").isObject())throw new IllegalArgumentException("Jedes GeoJSON Feature benötigt Eigenschaften");
                var geometry=feature.path("geometry");totalPoints+=AreaGeometry.read(geometry).getNumPoints();
                if(totalPoints>50000)throw new IllegalArgumentException("GeoJSON überschreitet insgesamt 50.000 Koordinaten");
                var record=new LinkedHashMap<String,String>();feature.path("properties").properties().forEach(property->{if(property.getValue().isValueNode())record.put(property.getKey(),property.getValue().isNull()?"":property.getValue().asText());});
                record.putIfAbsent("id",feature.path("id").asText("feature-"+index));record.put("geometry",json.writeValueAsString(geometry));
                records.add(record);headers.addAll(record.keySet());if(headers.size()>200)throw new IllegalArgumentException("Zu viele GeoJSON-Eigenschaften");
                evidence.add(new SourceReader.Evidence("geojson",index+2,"/features/"+index));index++;
            }
            var names=List.copyOf(headers);var rows=new ArrayList<TabularReader.Row>();rows.add(new TabularReader.Row(1,names));
            for(int i=0;i<records.size();i++){var record=records.get(i);rows.add(new TabularReader.Row(i+2,names.stream().map(name->record.getOrDefault(name,"")).toList()));}
            return new SourceReader.Source(SourceReader.CONTRACT,ImportSupport.hash(bytes),"geojson",List.of(new TabularReader.Table("geojson","Gemeindegeometrien",rows)),List.of(),evidence);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("GeoJSON konnte nicht gelesen werden: "+e.getMessage(),e);}
    }
}
