package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlRecordsTest {
    @TempDir Path temp;

    @Test void preservesNamespacesEscapesCommentsAndPermissionSemantics() throws Exception {
        Path file = temp.resolve("net.xml");
        Files.writeString(file, """
            <?xml version="1.0"?>
            <net xmlns:x="urn:test" x:version="a&amp;b"><!--keep-->
              <edge id="a" from="n" to="m" x:value="&quot;&amp;">
                <lane allow="passenger truck trailer"/><param key="a" value="&lt;"/>
              </edge><junction id="m" x:value="unchanged"/>
            </net>
            """);
        XmlRecords.rewrite(file, Set.of("edge"), edge -> {
            assertEquals("\"&", edge.getAttributeNS("urn:test", "value"));
            FreightProfileChecks.deny(TrafficRoutes.elements(edge,"lane").getFirst(),"truck");
        });
        var doc = TrafficRoutes.read(file);
        assertEquals("a&b", doc.getDocumentElement().getAttribute("x:version"));
        assertEquals("passenger trailer", TrafficRoutes.elements(doc,"lane").getFirst().getAttribute("allow"));
        assertEquals("<", TrafficRoutes.elements(doc,"param").getFirst().getAttribute("value"));
        assertTrue(Files.readString(file).contains("<!--keep-->"));
        XmlRecords.read(file, Set.of("junction"), j -> assertEquals("unchanged", j.getAttributeNS("urn:test","value")));
    }

    @Test void malformedInputAndCallbackFailureLeaveOriginalAndNoTemporaryFile() throws Exception {
        Path file = temp.resolve("net.xml");
        String malformed = "<net><edge id='a'/><unfinished>";
        Files.writeString(file, malformed);
        assertThrows(IOException.class, () -> XmlRecords.rewrite(file, Set.of("edge"), e -> e.setAttribute("id","b")));
        assertEquals(malformed, Files.readString(file));
        String valid = "<net><edge id='a'/></net>";
        Files.writeString(file, valid);
        assertThrows(IOException.class, () -> XmlRecords.rewrite(file, Set.of("edge"), e -> { throw new IOException("stop"); }));
        assertEquals(valid, Files.readString(file));
        try (var files = Files.list(temp)) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void rejectsDoctypeWithoutReplacingInput() throws Exception {
        Path file = temp.resolve("net.xml");
        String source = "<!DOCTYPE net [<!ENTITY x SYSTEM 'file:///should-not-be-read'>]><net><edge id='&x;'/></net>";
        Files.writeString(file,source);
        assertThrows(IOException.class, () -> XmlRecords.read(file, Set.of("edge"), e -> fail("DTD accepted")));
        assertThrows(IOException.class, () -> XmlRecords.rewrite(file, Set.of("edge"), e -> fail("DTD accepted")));
        assertEquals(source, Files.readString(file));
    }

    @Test void largeNetworkFitsInSmallForkedHeap() throws Exception {
        Path input = temp.resolve("large.net.xml");
        try (var out = Files.newBufferedWriter(input)) {
            out.write("<net>\n");
            for (int i = 0; i < 150_000; i++) out.write("<edge id='"+i+"' from='a' to='b'><lane allow='truck trailer passenger' shape='1,2 3,4'/></edge>\n<junction id='j"+i+"' shape='1,2 3,4'/><connection from='a' to='b'/>\n");
            out.write("</net>");
        }
        Path log = temp.resolve("fork.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx64m", "-cp", System.getProperty("java.class.path"), HeapProbe.class.getName(), input.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,process.exitValue(), () -> { try { return Files.readString(log); } catch(IOException e) {return e.toString();} });
    }

    public static class HeapProbe {
        public static void main(String[] args) throws Exception {
            Path input = Path.of(args[0]);
            XmlRecords.rewrite(input, Set.of("edge"), e -> FreightProfileChecks.deny(TrafficRoutes.elements(e,"lane").getFirst(),"truck"));
            int[] count = {0};
            XmlRecords.read(input,Set.of("edge"), e -> {
                if (!"passenger trailer".equals(TrafficRoutes.elements(e,"lane").getFirst().getAttribute("allow"))) throw new IOException("permissions changed");
                count[0]++;
            });
            if (count[0] != 150_000) throw new IOException("records lost: " + count[0]);
        }
    }
}
