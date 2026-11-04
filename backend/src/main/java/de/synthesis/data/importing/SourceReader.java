package de.synthesis.data.importing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Common extraction envelope. Document content is data, never executable instructions. */
public final class SourceReader {
    public static final String CONTRACT="synthesis-source-v1";
    public record Block(int id,String reference,String text) {}
    public record Evidence(String tableId,int row,String reference) {}
    public record Source(String contract,String sourceHash,String format,List<TabularReader.Table> tables,
                         List<Block> blocks,List<Evidence> evidence) {}
    public record ExtractionSettings(String documentPython, String documentExtractor) {
        public String getDocumentPython(){return documentPython;}
        public String getDocumentExtractor(){return documentExtractor;}
        public static ExtractionSettings defaults(){return new ExtractionSettings("python3","../scripts/extract-tour-document.py");}
    }
    private final ObjectMapper json;
    private final ExtractionSettings config;
    public SourceReader(ObjectMapper json,ExtractionSettings config){this.json=json;this.config=config;}
    public Source read(byte[] bytes,String filename) {
        if(bytes.length==0||bytes.length>TabularReader.MAX_BYTES)throw new IllegalArgumentException("Datei fehlt oder überschreitet 5 MB");
        String ext=filename==null?"":filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        try {
            String hash=ImportSupport.hash(bytes);
            if(Set.of("csv","tsv","xlsx").contains(ext))return new Source(CONTRACT,hash,ext,new TabularReader().read(bytes,filename),List.of(),List.of());
            if(Set.of("json","xml").contains(ext)) {
                JsonNode root=ext.equals("json")?json.readTree(bytes):xmlJson(xml(bytes).getDocumentElement(),0);
                if(root==null)throw new IllegalArgumentException("Leeres Quelldokument");
                var rows=new LinkedHashMap<String,List<Map<String,String>>>();var refs=new HashMap<String,List<String>>();
                visit(root,"",new LinkedHashMap<>(),rows,refs,0);
                List<TabularReader.Table> tables=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();int n=0;
                for(var e:rows.entrySet()) {
                    if(e.getValue().isEmpty())continue;
                    Set<String> headers=new LinkedHashSet<>();e.getValue().forEach(r->headers.addAll(r.keySet()));
                    if(headers.size()>200||e.getValue().size()>10_000)throw new IllegalArgumentException("Strukturierte Quelle überschreitet Tabellenlimit");
                    String id=ext+"-"+n++;List<String> names=List.copyOf(headers);List<TabularReader.Row> data=new ArrayList<>();data.add(new TabularReader.Row(1,names));
                    for(int i=0;i<e.getValue().size();i++) {var row=e.getValue().get(i);data.add(new TabularReader.Row(i+2,names.stream().map(h->row.getOrDefault(h,"")).toList()));evidence.add(new Evidence(id,i+2,refs.get(e.getKey()).get(i)));}
                    tables.add(new TabularReader.Table(id,e.getKey().isBlank()?"root":e.getKey(),List.copyOf(data)));
                }
                if(tables.isEmpty()&&root.isObject()){var values=new LinkedHashMap<String,String>();flatten(root,"",values,0);if(!values.isEmpty()){tables.add(new TabularReader.Table(ext+"-0","root",List.of(new TabularReader.Row(1,List.copyOf(values.keySet())),new TabularReader.Row(2,List.copyOf(values.values())))));evidence.add(new Evidence(ext+"-0",2,"/"));}}
                if(tables.isEmpty())throw new IllegalArgumentException("Keine Datensätze in strukturierter Quelle gefunden");
                return new Source(CONTRACT,hash,ext,List.copyOf(tables),List.of(),List.copyOf(evidence));
            }
            List<Block> blocks=new ArrayList<>();List<TabularReader.Table> tables=new ArrayList<>();
            if(ext.equals("txt"))lines(blocks,"text",utf8(bytes));
            else if(ext.equals("docx")) {
                byte[] doc=null;int total=0,entries=0;
                try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {ZipEntry e;while((e=zip.getNextEntry())!=null){if(++entries>2000)throw new IllegalArgumentException("DOCX enthält zu viele Einträge");byte[] data=zip.readNBytes(30_000_001-total);total+=data.length;if(total>30_000_000)throw new IllegalArgumentException("Entpackte DOCX überschreitet 30 MB");if(e.getName().equals("word/document.xml"))doc=data;}}
                if(doc==null)throw new IllegalArgumentException("DOCX-Hauptdokument fehlt");
                var document=xml(doc);var paragraphs=document.getElementsByTagNameNS("*","p");
                for(int i=0;i<paragraphs.getLength();i++)lines(blocks,"paragraph:"+(i+1),text(paragraphs.item(i)));
                var all=document.getElementsByTagNameNS("*","tbl");if(all.getLength()>100)throw new IllegalArgumentException("Zu viele DOCX-Tabellen");
                for(int i=0;i<all.getLength();i++){var table=(Element)all.item(i);var trs=table.getElementsByTagNameNS("*","tr");List<TabularReader.Row> data=new ArrayList<>();if(trs.getLength()>10_001)throw new IllegalArgumentException("Zu viele DOCX-Zeilen");
                    for(int j=0;j<trs.getLength();j++){var cells=((Element)trs.item(j)).getElementsByTagNameNS("*","tc");List<String> values=new ArrayList<>();if(cells.getLength()>200)throw new IllegalArgumentException("Zu viele DOCX-Spalten");for(int k=0;k<cells.getLength();k++)values.add(text(cells.item(k)));data.add(new TabularReader.Row(j+1,List.copyOf(values)));}
                    if(!data.isEmpty()&&data.stream().anyMatch(r->r.cells().stream().anyMatch(v->!v.isBlank())))tables.add(new TabularReader.Table("docx-"+i,"Tabelle "+(i+1),List.copyOf(data)));
                }
            } else if(ext.equals("pdf")) {
                for(var page:pdf(bytes).path("pages"))lines(blocks,"page:"+page.path("page").asInt(),page.path("text").asText());
            } else throw new IllegalArgumentException("Unterstützt: CSV, TSV, XLSX, JSON, XML, TXT, DOCX und PDF mit Text. Andere Formate benötigen einen Extraktionsadapter");
            if(blocks.isEmpty()&&tables.isEmpty())throw new IllegalArgumentException("Keine lesbaren Inhalte gefunden; Scans benötigen OCR");
            return new Source(CONTRACT,hash,ext,List.copyOf(tables),List.copyOf(blocks),List.of());
        } catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Quelldatei konnte nicht sicher gelesen werden: "+e.getMessage(),e);}
    }
    private JsonNode pdf(byte[] bytes)throws Exception {
        Path script=Path.of(config.getDocumentExtractor()).toAbsolutePath();
        if(!Files.isRegularFile(script))throw new IllegalArgumentException("PDF-Extraktor fehlt; synthesis.company-tour-import.document-extractor konfigurieren");
        Path dir=Files.createTempDirectory("synthesis-tour-pdf-");
        try {
            Path input=dir.resolve("source.pdf"),output=dir.resolve("text.json"),log=dir.resolve("extract.log");Files.write(input,bytes);
            Process process=new ProcessBuilder(config.getDocumentPython(),script.toString(),input.toString()).redirectOutput(output.toFile()).redirectError(log.toFile()).start();
            try { if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new IllegalArgumentException("PDF-Extraktion nach 30 Sekunden abgebrochen");} } catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();throw e;}
            if(process.exitValue()!=0){String message=Files.readString(log);throw new IllegalArgumentException("Lokale PDF-Extraktion fehlgeschlagen: "+message.substring(0,Math.min(message.length(),800)));}
            if(Files.size(output)>1_000_000)throw new IllegalArgumentException("PDF-Text zu groß");return json.readTree(output.toFile());
        } finally {try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static String utf8(byte[] b)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString().replace("\uFEFF","");}
    private static void lines(List<Block> out,String ref,String text){int line=0;for(String s:text.split("\\R")){line++;if(!s.isBlank())out.add(new Block(out.size(),ref+":line:"+line,s));}if(out.size()>10_000)throw new IllegalArgumentException("Zu viele Textblöcke");}
    private static String text(Node node){var texts=((Element)node).getElementsByTagNameNS("*","t");StringBuilder out=new StringBuilder();for(int i=0;i<texts.getLength();i++)out.append(texts.item(i).getTextContent());return out.toString();}
    private static Document xml(byte[] b)throws Exception{var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));}
    private JsonNode xmlJson(Element e,int depth){if(depth>20)throw new IllegalArgumentException("Quellstruktur tiefer als 20 Ebenen");ObjectNode o=json.createObjectNode();var attrs=e.getAttributes();for(int i=0;i<attrs.getLength();i++)o.put(attrs.item(i).getNodeName(),attrs.item(i).getNodeValue());
        Map<String,List<Element>> groups=new LinkedHashMap<>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element c)groups.computeIfAbsent(c.getTagName(),k->new ArrayList<>()).add(c);
        if(groups.isEmpty()&&attrs.getLength()==0)return new TextNode(e.getTextContent().strip());
        groups.forEach((key,list)->{if(list.size()==1)o.set(key,xmlJson(list.getFirst(),depth+1));else{var a=o.putArray(key);list.forEach(c->a.add(xmlJson(c,depth+1)));}});return o;}
    private void visit(JsonNode node,String path,Map<String,String> inherited,Map<String,List<Map<String,String>>> rows,Map<String,List<String>> refs,int depth){
        if(depth>20)throw new IllegalArgumentException("Quellstruktur tiefer als 20 Ebenen");
        if(node.isArray()){int i=0;for(var item:node){if(item.isObject()){var values=new LinkedHashMap<>(inherited);flatten(item,"",values,0);String shape=path.replaceAll("/\\d+(?=/|$)","/*");rows.computeIfAbsent(shape,k->new ArrayList<>()).add(values);refs.computeIfAbsent(shape,k->new ArrayList<>()).add(path+"/"+i);if(rows.size()>100||rows.values().stream().mapToInt(List::size).sum()>10_000)throw new IllegalArgumentException("Zu viele Datensätze");}visit(item,path+"/"+i++,inherited,rows,refs,depth+1);} }
        else if(node.isObject()){var next=new LinkedHashMap<>(inherited);flatten(node,"",next,0);var fields=node.fields();while(fields.hasNext()){var e=fields.next();if(e.getValue().isContainerNode())visit(e.getValue(),path+"/"+e.getKey().replace("~","~0").replace("/","~1"),next,rows,refs,depth+1);}}
    }
    private void flatten(JsonNode node,String prefix,Map<String,String> row,int depth){if(depth>20)throw new IllegalArgumentException("Quellstruktur zu tief");node.fields().forEachRemaining(e->{String key=prefix+e.getKey();if(e.getValue().isObject())flatten(e.getValue(),key+".",row,depth+1);else if(e.getValue().isValueNode())row.put(key,e.getValue().isNull()?"":e.getValue().asText());});}
}
