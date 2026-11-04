package de.synthesis.data.importing;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class ImportRegistry {
    private final Map<String,AspectImportAdapter> adapters=new TreeMap<>();
    public ImportRegistry(List<AspectImportAdapter> values){for(var a:values)if(adapters.putIfAbsent(a.schema().id(),a)!=null)throw new IllegalArgumentException("Doppelter Importvertrag");}
    public AspectImportAdapter require(String id){var a=adapters.get(id);if(a==null)throw new IllegalArgumentException("Unbekannter Importvertrag: "+id);return a;}
    public List<AspectImportAdapter.Schema> schemas(){return adapters.values().stream().map(AspectImportAdapter::schema).toList();}
    public static ImportRegistry builtins(){var c=new ImportAdapters();return new ImportRegistry(List.of(c.workplacesImport(),c.populationImport(),c.freightImport(),c.municipalWorkplacesImport(),c.municipalityAllocationImport(),c.municipalityAreasImport(),c.workplaceCellsImport(),c.populationCellsImport()));}
}
