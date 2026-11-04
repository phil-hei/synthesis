package de.synthesis.generation.pipeline;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.Set;

/** Streams XML while materializing only the selected record, never its owner file. */
final class XmlRecords {
    @FunctionalInterface interface Visitor { void accept(Element record) throws IOException; }
    @FunctionalInterface interface Filter { boolean keep(Element record) throws IOException; }

    static void read(Path file, Set<String> names, Visitor visitor) throws IOException {
        process(file, null, names, record -> { visitor.accept(record); return true; });
    }

    /** Replace only after the entire input has been parsed and the output has closed successfully. */
    static void rewrite(Path file, Set<String> names, Visitor visitor) throws IOException {
        filter(file, names, record -> { visitor.accept(record); return true; });
    }

    static void filter(Path file, Set<String> names, Filter filter) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".xml-records-", ".part");
        try {
            process(file, temporary, names, filter);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void process(Path file, Path output, Set<String> names, Filter filter) throws IOException {
        try (var input = new BufferedInputStream(Files.newInputStream(file));
             var out = output == null ? OutputStream.nullOutputStream() : new BufferedOutputStream(Files.newOutputStream(output))) {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External XML forbidden"); });
            var builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setNamespaceAware(true);
            var builder = builderFactory.newDocumentBuilder();
            var reader = factory.createXMLStreamReader(input);
            var writer = output == null ? null : XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE forbidden");
                    if (event == XMLStreamConstants.START_ELEMENT && names.contains(reader.getLocalName())) {
                        Element record = record(reader, builder.newDocument());
                        boolean keep = filter.keep(record);
                        if (writer != null && keep) write(record, writer);
                    } else if (writer != null) copy(reader, writer, event);
                }
                if (writer != null) writer.flush();
            } finally {
                try { reader.close(); } finally { if (writer != null) writer.close(); }
            }
        } catch (XMLStreamException | ParserConfigurationException e) {
            throw new IOException("Ungültiges XML: " + file.getFileName(), e);
        }
    }

    private static Element record(XMLStreamReader reader, Document document) throws XMLStreamException {
        String prefix = reader.getPrefix();
        Element element = document.createElementNS(reader.getNamespaceURI(),
                prefix == null || prefix.isEmpty() ? reader.getLocalName() : prefix + ":" + reader.getLocalName());
        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String p = reader.getNamespacePrefix(i);
            element.setAttributeNS("http://www.w3.org/2000/xmlns/", p == null ? "xmlns" : "xmlns:" + p,
                    reader.getNamespaceURI(i) == null ? "" : reader.getNamespaceURI(i));
        }
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String p = reader.getAttributePrefix(i);
            element.setAttributeNS(reader.getAttributeNamespace(i), p == null || p.isEmpty() ? reader.getAttributeLocalName(i)
                    : p + ":" + reader.getAttributeLocalName(i), reader.getAttributeValue(i));
        }
        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> element.appendChild(record(reader, document));
                case XMLStreamConstants.END_ELEMENT -> { return element; }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> element.appendChild(document.createTextNode(reader.getText()));
                case XMLStreamConstants.CDATA -> element.appendChild(document.createCDATASection(reader.getText()));
                case XMLStreamConstants.COMMENT -> element.appendChild(document.createComment(reader.getText()));
                case XMLStreamConstants.PROCESSING_INSTRUCTION -> element.appendChild(document.createProcessingInstruction(reader.getPITarget(), reader.getPIData()));
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> throw new XMLStreamException("DTD/entities forbidden");
                default -> { }
            }
        }
        throw new XMLStreamException("Incomplete XML record");
    }

    static void write(Node node, XMLStreamWriter writer) throws XMLStreamException {
        if (node instanceof Element element) {
            String namespace = element.getNamespaceURI(), prefix = element.getPrefix();
            writer.writeStartElement(prefix == null ? "" : prefix, element.getLocalName(), namespace == null ? "" : namespace);
            var attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                var a = (Attr) attrs.item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI())) {
                    if (a.getName().equals("xmlns")) writer.writeDefaultNamespace(a.getValue());
                    else writer.writeNamespace(a.getLocalName(), a.getValue());
                } else if (a.getNamespaceURI() == null) writer.writeAttribute(a.getName(), a.getValue());
                else writer.writeAttribute(a.getPrefix() == null ? "" : a.getPrefix(), a.getNamespaceURI(), a.getLocalName(), a.getValue());
            }
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) write(child, writer);
            writer.writeEndElement();
        } else if (node instanceof CDATASection) writer.writeCData(node.getNodeValue());
        else if (node instanceof Text) writer.writeCharacters(node.getNodeValue());
        else if (node instanceof Comment) writer.writeComment(node.getNodeValue());
        else if (node instanceof ProcessingInstruction p) writer.writeProcessingInstruction(p.getTarget(), p.getData());
    }

    private static void copy(XMLStreamReader reader, XMLStreamWriter writer, int event) throws XMLStreamException {
        switch (event) {
            case XMLStreamConstants.START_ELEMENT -> {
                writer.writeStartElement(reader.getPrefix() == null ? "" : reader.getPrefix(), reader.getLocalName(),
                        reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI());
                for (int i = 0; i < reader.getNamespaceCount(); i++) {
                    if (reader.getNamespacePrefix(i) == null) writer.writeDefaultNamespace(reader.getNamespaceURI(i) == null ? "" : reader.getNamespaceURI(i));
                    else writer.writeNamespace(reader.getNamespacePrefix(i), reader.getNamespaceURI(i) == null ? "" : reader.getNamespaceURI(i));
                }
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    if (reader.getAttributeNamespace(i) == null) writer.writeAttribute(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                    else writer.writeAttribute(reader.getAttributePrefix(i), reader.getAttributeNamespace(i), reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                }
            }
            case XMLStreamConstants.END_ELEMENT -> writer.writeEndElement();
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> writer.writeCharacters(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
            case XMLStreamConstants.CDATA -> writer.writeCData(reader.getText());
            case XMLStreamConstants.COMMENT -> writer.writeComment(reader.getText());
            case XMLStreamConstants.PROCESSING_INSTRUCTION -> writer.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
            case XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument();
            case XMLStreamConstants.ENTITY_REFERENCE -> throw new XMLStreamException("Entities forbidden");
            default -> { }
        }
    }
}
