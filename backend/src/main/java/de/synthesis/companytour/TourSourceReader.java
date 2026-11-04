package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.data.importing.SourceReader;
import java.util.List;

/** Legacy tour envelope over the common format readers. Wire contract stays unchanged. */
public final class TourSourceReader {
    public static final String CONTRACT="synthesis-tour-source-v1";
    public record Block(int id,String reference,String text) { }
    public record Evidence(String tableId,int row,String reference) { }
    public record Source(String contract,String sourceHash,String format,List<TourTableReader.Table> tables,
                         List<Block> blocks,List<Evidence> evidence) { }
    private final SourceReader reader;
    public TourSourceReader(ObjectMapper json,TourImportProperties config){
        reader=new SourceReader(json,new SourceReader.ExtractionSettings(config.getDocumentPython(),config.getDocumentExtractor()));
    }
    public Source read(byte[] bytes,String filename){
        var source=reader.read(bytes,filename);
        return new Source(CONTRACT,source.sourceHash(),source.format(),source.tables(),
                source.blocks().stream().map(b->new Block(b.id(),b.reference(),b.text())).toList(),
                source.evidence().stream().map(e->new Evidence(e.tableId(),e.row(),e.reference())).toList());
    }
}
