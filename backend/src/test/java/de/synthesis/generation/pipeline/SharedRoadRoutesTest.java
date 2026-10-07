package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static de.synthesis.generation.pipeline.RoutingEngine.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SharedRoadRoutesTest {
    @TempDir Path dir;

    @Test void preservesDirectionSplitEdgesAndPartialEndpoints() throws Exception {
        SumoRoadRouteMapper mapper = fixture("passenger delivery", true);
        var result = mapper.map(path(), "passenger");
        assertThat(result).isNotNull();
        assertThat(result.edges()).containsExactly("first", "second");
        assertThat(result.departPos()).isCloseTo(20, within(.01));
        assertThat(result.arrivalPos()).isCloseTo(80, within(.01));
        assertThat(result.edgeFreeFlowTimeSec()).isCloseTo(16, within(.01));
        assertThat(mapper.map(path(), "delivery")).isNotNull();
        assertThat(mapper.map(path(), "bicycle")).isNull();
        RoadPath reverse = new RoadPath("car", new Point(8.0018, 48), new Point(8.0002, 48), 160, 16,
                List.of(new WaySegment(42, List.of(new Point(8.0018, 48), new Point(8.0002, 48)))));
        assertThat(mapper.map(reverse, "passenger")).isNull();
    }

    @Test void refusesGeometryShortcutEvenWithMatchingOsmIdAndEndpoints() throws Exception {
        var mapper = fixture("passenger", true);
        RoadPath detour = new RoadPath("car", new Point(8.0002, 48), new Point(8.0018, 48), 500, 50,
                List.of(new WaySegment(42, List.of(new Point(8.0002, 48), new Point(8.001, 48.002), new Point(8.0018, 48)))));
        assertThat(mapper.map(detour, "passenger")).isNull();
    }

    @Test void refusesDisconnectedAndForbiddenLaneConnections() throws Exception {
        assertThat(fixture("passenger", false).map(path(), "passenger")).isNull();
        fixture("passenger", true);
        String net = Files.readString(dir.resolve("net.xml"));
        Files.writeString(dir.resolve("net.xml"), net.replace("<connection", "<edge id=\":junction\"><lane id=\":junction_0\" allow=\"bicycle\" length=\"1\" speed=\"10\"/></edge><connection")
                .replace("toLane=\"0\"", "toLane=\"0\" via=\":junction_0\""));
        var mapper = new SumoRoadRouteMapper(dir.resolve("net.xml"), dir.resolve("nodes.xml"), dir.resolve("edges.xml"));
        assertThat(mapper.map(path(), "passenger")).isNull();
    }

    @Test void detectsLostOrChangedExplicitRoutes() throws Exception {
        var json = new ObjectMapper();
        json.writeValue(dir.resolve("shared-road-routes-summary.json").toFile(), Map.of("mapped", 1));
        Files.writeString(dir.resolve("shared-road-routes.jsonl"), "{\"status\":\"shared\",\"vehicleId\":\"v0\",\"sumo\":{\"edges\":[\"first\",\"second\"]}}\n");
        Files.writeString(dir.resolve("routes.rou.xml"), "<routes><vehicle id=\"v0\"><route edges=\"first second\"/></vehicle></routes>");
        assertThatCode(() -> SharedRoadRoutes.verifyWritten(dir)).doesNotThrowAnyException();
        Files.writeString(dir.resolve("routes.rou.xml"), "<routes><vehicle id=\"v0\"><route edges=\"first other\"/></vehicle></routes>");
        assertThatThrownBy(() -> SharedRoadRoutes.verifyWritten(dir)).hasMessageContaining("verändert");
        Files.writeString(dir.resolve("routes.rou.xml"), "<routes/>");
        assertThatThrownBy(() -> SharedRoadRoutes.verifyWritten(dir)).hasMessageContaining("verworfen");
    }

    @Test void nativeRastattChainPreservesCarBikeAndDeliveryPaths() throws Exception {
        var config = new SynthesisProperties.Sumo(); config.setMaxTrips(10);
        var runner = new SumoRunner(config).useSharedRoadRoutes(true);
        Path osm = Path.of("../validation/rastatt-test.osm");
        assumeTrue(runner.isAvailable() && Files.isRegularFile(osm), "SUMO und lokales Rastatt-Netz erforderlich");
        Path demand = dir.resolve("demand.csv");
        Files.writeString(demand, """
                personOid;tripId;legId;tripMode;mainMode;legMode;distanceInKm;sourceZone;targetZone;fromX;fromY;toX;toY;tripBegin;tripEnd
                p-car;car;0;1;1;1;2;A;B;8.200;48.850;8.210;48.858;10;15
                p-bike;bike;0;0;0;0;2;A;B;8.200;48.850;8.210;48.858;20;30
                """);
        Path tours = dir.resolve("tours.csv");
        Files.writeString(tours, """
                tourId;vehicleType;fromX;fromY;toX;toY;plannedTimeSimMin;tripDur;deliveryDur
                tour1;0;8.200;48.850;8.210;48.858;45;5;1
                tour2;1;8.200;48.850;8.210;48.858;60;10;1
                """);
        var result = runner.run(dir, Files.readString(osm), demand, tours, message -> {});
        assertThat(result.carTrips()).isEqualTo(1);
        assertThat(result.bikeTrips()).isEqualTo(1);
        assertThat(result.deliveryTrips()).isGreaterThanOrEqualTo(2);
        var json = new ObjectMapper();
        var summary = json.readTree(dir.resolve("sumo/shared-road-routes-summary.json").toFile());
        assertThat(summary.path("mapped").asInt()).isEqualTo(2 + result.deliveryTrips());
        assertThat(summary.path("fallback").asInt()).isZero();
        assertThat(summary.path("verifiedRoutes").asInt()).isEqualTo(summary.path("mapped").asInt());
        Set<String> classes = new HashSet<>();
        for (String line : Files.readAllLines(dir.resolve("sumo/shared-road-routes.jsonl"))) {
            var row = json.readTree(line); classes.add(row.path("vehicleClass").asText());
            assertThat(row.path("path").path("segments").size()).isPositive();
            assertThat(row.path("osmSha256").asText()).hasSize(64);
        }
        assertThat(classes).contains("passenger", "bicycle", "delivery");
        // Recent SUMO separates bicycleTripStatistics from motor vehicleTripStatistics.
        // TripInfo, not the legacy aggregate field, proves that every emitted vehicle arrived.
        var times = Files.readAllLines(dir.resolve("sumo/shared-road-times.jsonl"));
        assertThat(times).hasSize(2 + result.deliveryTrips());
        for (String line : times) {
            var row = json.readTree(line);
            assertThat(row.path("completion").asText()).isEqualTo("completed");
            assertThat(row.path("sumoEdgeFreeFlowEstimateSec").asDouble()).isPositive();
            assertThat(row.path("sumoTripInfo").path("duration").asDouble()).isPositive();
            assertThat(row.path("sumoTripInfo").path("rerouteNo").asInt()).isZero();
        }
    }

    private RoadPath path() {
        return new RoadPath("car", new Point(8.0002, 48), new Point(8.0018, 48), 160, 16,
                List.of(new WaySegment(42, List.of(new Point(8.0002, 48), new Point(8.0018, 48)))));
    }
    private SumoRoadRouteMapper fixture(String allow, boolean connected) throws Exception {
        Files.writeString(dir.resolve("nodes.xml"), "<nodes><node id=\"a\" x=\"8\" y=\"48\"/><node id=\"b\" x=\"8.001\" y=\"48\"/><node id=\"c\" x=\"8.002\" y=\"48\"/></nodes>");
        Files.writeString(dir.resolve("edges.xml"), "<edges><edge id=\"first\" from=\"a\" to=\"b\"><param key=\"origId\" value=\"42\"/></edge><edge id=\"second\" from=\"b\" to=\"c\"><param key=\"origId\" value=\"42\"/></edge></edges>");
        Files.writeString(dir.resolve("net.xml"), "<net><edge id=\"first\"><lane allow=\""+allow+"\" length=\"100\" speed=\"10\"/></edge><edge id=\"second\"><lane allow=\""+allow+"\" length=\"100\" speed=\"10\"/></edge>"+
                (connected ? "<connection from=\"first\" to=\"second\" fromLane=\"0\" toLane=\"0\"/>" : "")+"</net>");
        return new SumoRoadRouteMapper(dir.resolve("net.xml"), dir.resolve("nodes.xml"), dir.resolve("edges.xml"));
    }
}
