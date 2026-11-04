package de.synthesis.data.importing;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.synthesis.scenario.Scenario;
import java.util.*;

/** SPI: additional import domains register a Spring bean, without modifying the orchestrator. */
public interface AspectImportAdapter {
    record Field(String id,String label,String type,boolean required,List<String> aliases) { }
    record Schema(String id,String label,String aspect,String unit,List<Field> fields) { }
    /** A source owner's declaration, not an independently verified coverage measurement. */
    record CoverageDeclaration(String kind,String completeness,String statement) { }
    /** Key space/edition survive normalization; allocation is an explicit model input. */
    record Geography(String codeSystem,String edition,String allocationRevision,String allocationMethod) { }
    record Dependency(String role,String revision,String schema) { }
    record Metadata(String label,String source,String referenceDate,String unit,Scenario.Region coverage,String licence,String attribution,
                    @JsonInclude(JsonInclude.Include.NON_NULL) CoverageDeclaration coverageDeclaration,
                    @JsonInclude(JsonInclude.Include.NON_NULL) Geography geography) {
        public Metadata {
            licence=licence==null||licence.isBlank()?"unknown":licence.strip();
            attribution=attribution==null?"":attribution.strip();
        }
        public Metadata(String label,String source,String referenceDate,String unit,Scenario.Region coverage,String licence,String attribution,CoverageDeclaration declaration){this(label,source,referenceDate,unit,coverage,licence,attribution,declaration,null);}
        public Metadata(String label,String source,String referenceDate,String unit,Scenario.Region coverage,String licence,String attribution){this(label,source,referenceDate,unit,coverage,licence,attribution,null,null);}
        public Metadata(String label,String source,String referenceDate,String unit,Scenario.Region coverage){this(label,source,referenceDate,unit,coverage,"unknown",source,null,null);}
    }
    record Mapping(String tableId,int headerRow,Map<String,Integer> columns,String decimalSeparator) { }
    record DataRow(int sourceRow,Map<String,Object> values) { }
    record Issue(int sourceRow,String field,String code,String message) { }
    Schema schema();
    /** Domain normalization preserves source markers separately from numeric quantities. */
    default DataRow normalize(DataRow row){return row;}
    /** Checks normalized domain values, including spatial and temporal semantics. */
    List<Issue> validate(DataRow row,Metadata metadata);
    default List<Issue> validateDataset(List<DataRow> rows,Metadata metadata){return List.of();}
    default List<Dependency> dependencies(Metadata metadata){return List.of();}
}
