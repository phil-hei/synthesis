package de.synthesis.generation.pipeline;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.OSMParsers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Real routing via an embedded GraphHopper graph built from an OSM extract
 * (fetched through Overpass). Provides door-to-door distance and travel time for
 * car, bike and foot between zone centroids; unroutable pairs return {@code null}
 * so the matrix generator can fall back to a beeline estimate.
 *
 * <p>Implements {@link MatrixGenerator.Impedance}. The graph is built in a temp
 * directory that is removed on {@link #close()}.
 */
public class RoutingEngine implements MatrixGenerator.Impedance, AutoCloseable {

    private final GraphHopper hopper;
    private final Path workDir;
    private final Map<Integer, Long> osmWays;

    private RoutingEngine(GraphHopper hopper, Path workDir, Map<Integer, Long> osmWays) {
        this.hopper = hopper;
        this.workDir = workDir;
        this.osmWays = osmWays;
    }

    /**
     * Builds a routing engine from raw OSM XML (Overpass output). The import runs
     * with contraction hierarchies for car/bike/foot so the subsequent thousands of
     * zone-pair queries stay cheap.
     */
    public static RoutingEngine fromOsmXml(String osmXml) throws IOException {
        Path workDir = Files.createTempDirectory("synthesis-routing-");
        Path osmFile = workDir.resolve("network.osm");
        Files.writeString(osmFile, osmXml, StandardCharsets.UTF_8);

        Map<Integer, Long> osmWays = new HashMap<>();
        GraphHopper hopper = new GraphHopper() {
            @Override
            protected OSMParsers buildOSMParsers(Map<String, String> vehicles, List<String> values,
                                                  List<String> ignored, String dateRange) {
                return super.buildOSMParsers(vehicles, values, ignored, dateRange)
                        .addWayTagParser((edge, access, way, flags) -> osmWays.put(edge, way.getId()));
            }
        };
        hopper.setOSMFile(osmFile.toString());
        hopper.setGraphHopperLocation(workDir.resolve("graph").toString());
        hopper.setProfiles(
                new Profile("car").setVehicle("car"),
                new Profile("bike").setVehicle("bike"),
                new Profile("foot").setVehicle("foot"));
        hopper.getCHPreparationHandler().setCHProfiles(
                new CHProfile("car"), new CHProfile("bike"), new CHProfile("foot"));
        try {
            hopper.importOrLoad();
        } catch (RuntimeException e) {
            cleanup(workDir);
            throw new IOException("GraphHopper-Import fehlgeschlagen: " + e.getMessage(), e);
        }
        return new RoutingEngine(hopper, workDir, osmWays);
    }

    private static String profileOf(MatrixGenerator.TravelMode mode) {
        return switch (mode) {
            case CAR -> "car";
            case BIKE -> "bike";
            case FOOT -> "foot";
        };
    }

    @Override
    public double[] impedance(Zone from, Zone to, MatrixGenerator.TravelMode mode) {
        if (from.index == to.index) {
            return null; // intrazonal: keep the generator's local-distance heuristic
        }
        GHRequest request = new GHRequest(
                from.centroidLat, from.centroidLon, to.centroidLat, to.centroidLon)
                .setProfile(profileOf(mode));
        GHResponse response = hopper.route(request);
        if (response.hasErrors()) {
            return null;
        }
        ResponsePath best = response.getBest();
        double meters = best.getDistance();
        double minutes = best.getTime() / 60000.0; // ms -> min
        return new double[]{meters, minutes};
    }

    public record Point(double lon, double lat) {}
    public record WaySegment(long osmWayId, List<Point> points) {}
    public record RoadPath(String profile, Point requestedFrom, Point requestedTo,
                           double distanceM, double timeSec, List<WaySegment> segments) {}

    /** Concrete directed path; edge IDs stay internal, persisted references use OSM way IDs. */
    public RoadPath roadPath(double fromLat, double fromLon, double toLat, double toLon,
                             MatrixGenerator.TravelMode mode) throws IOException {
        GHRequest request = new GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(profileOf(mode)).setPathDetails(List.of("edge_id"));
        request.getHints().putObject("way_point_max_distance", 0);
        GHResponse response = hopper.route(request);
        if (response.hasErrors()) return null;
        ResponsePath best = response.getBest();
        List<WaySegment> segments = new ArrayList<>();
        for (var detail : best.getPathDetails().getOrDefault("edge_id", List.of())) {
            Long way = osmWays.get(((Number) detail.getValue()).intValue());
            if (way == null) throw new IOException("OSM-Bezug fehlt für GraphHopper-Kante");
            List<Point> points = new ArrayList<>();
            for (int i = detail.getFirst(); i <= detail.getLast(); i++)
                points.add(new Point(best.getPoints().getLon(i), best.getPoints().getLat(i)));
            if (!segments.isEmpty() && segments.getLast().osmWayId() == way) {
                var previous = segments.removeLast();
                var joined = new ArrayList<>(previous.points());
                joined.addAll(points.subList(1, points.size()));
                segments.add(new WaySegment(way, List.copyOf(joined)));
            } else segments.add(new WaySegment(way, List.copyOf(points)));
        }
        return new RoadPath(profileOf(mode), new Point(fromLon, fromLat), new Point(toLon, toLat),
                best.getDistance(), best.getTime() / 1000.0, List.copyOf(segments));
    }

    @Override
    public void close() {
        hopper.close();
        cleanup(workDir);
    }

    private static void cleanup(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: temp dir is cleaned by the OS eventually
                }
            });
        } catch (IOException ignored) {
            // nothing to clean up
        }
    }
}
