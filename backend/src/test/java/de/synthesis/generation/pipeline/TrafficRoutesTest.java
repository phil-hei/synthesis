package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class TrafficRoutesTest {
    @TempDir Path temp;
    @Test void directedEdgesOverrideOppositeNearestAndRejectInvalidAccess() throws Exception {
        Path net=temp.resolve("net.xml"), nodes=temp.resolve("nodes.xml"), edges=temp.resolve("edges.xml");
        Files.writeString(net,"""
            <net><edge id="-road"><lane allow="truck trailer"/></edge>
            <edge id="road"><lane allow="truck trailer"/></edge>
            <edge id="shortcut"><lane allow="passenger"/></edge></net>
            """);
        Files.writeString(nodes,"<nodes><node id='a' x='8.2' y='48.85'/><node id='b' x='8.202' y='48.85'/></nodes>");
        Files.writeString(edges,"<edges><edge id='-road' from='b' to='a'/><edge id='road' from='a' to='b'/><edge id='shortcut' from='a' to='b'/></edges>");
        var routes=new TrafficRoutes(net,nodes,edges);
        assertEquals("-road",routes.nearest(8.201,48.85,"truck"));
        assertEquals("road",routes.resolve("road",8.201,48.85,"truck"));
        assertThrows(IOException.class,()->routes.resolve("missing",8.201,48.85,"truck"));
        assertThrows(IOException.class,()->routes.resolve("shortcut",8.201,48.85,"truck"));
        assertThrows(IOException.class,()->routes.resolve("road",9.2,48.85,"truck"));
        routes.writeNetwork(temp.resolve("network.geojson"));
        assertEquals(2,new ObjectMapper().readTree(temp.resolve("network.geojson").toFile()).path("features").size());
    }
}
