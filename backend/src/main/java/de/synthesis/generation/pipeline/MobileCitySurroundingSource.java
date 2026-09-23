package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Resolves an eight-sector census ring outside the selected MobileCity study area. */
public final class MobileCitySurroundingSource {
    static final List<String> DIRECTIONS = List.of("N", "NE", "E", "SE", "S", "SW", "W", "NW");
    private static final double[] ANGLES = {90, 45, 0, -45, -90, -135, 180, 135};
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    public record Sector(int directionIndex, String direction, Geometry geometry,
                         long population, double[] ageCounts) {
        public Sector {
            ageCounts = ageCounts.clone();
        }
        @Override public double[] ageCounts() { return ageCounts.clone(); }
    }

    public record Result(List<Sector> sectors, double bufferKm, long population,
                         boolean populationAvailable, boolean agesAvailable,
                         String unavailableReason, Map<String, Object> provenance) {
        public Result {
            sectors = List.copyOf(sectors);
            provenance = Map.copyOf(provenance);
        }
    }

    private record Ring(Scenario.Region bounds, List<Geometry> sectors) {}

    private final SynthesisProperties.Census config;

    public MobileCitySurroundingSource(SynthesisProperties.Census config) {
        this.config = config;
    }

    public Result resolve(Scenario.Region studyArea, double bufferKm, Path cache,
                          Consumer<String> log) {
        Ring ring = ring(studyArea, bufferKm);
        Optional<CensusPopulationSource.Resolved> population =
                new CensusPopulationSource(config).resolve(ring.bounds(), cache, log);
        long[] inhabitants = new long[ring.sectors().size()];
        if (population.isPresent()) {
            PreparedGeometry[] prepared = ring.sectors().stream()
                    .map(PreparedGeometryFactory::prepare).toArray(PreparedGeometry[]::new);
            for (PopulationGridParser.GridPoint point : population.get().grid().points()) {
                int sector = sector(prepared, point.lat(), point.lon());
                if (sector >= 0) inhabitants[sector] += Math.round(point.population());
            }
        }

        CensusStructureSource.AreaAges ages = new CensusStructureSource(config).resolveAgeAreas(
                ring.bounds(), ring.sectors(), cache.resolve("structure"), log);
        double[][] ageCounts = ages.available() ? ages.counts() : new double[ring.sectors().size()][3];
        List<Sector> sectors = new ArrayList<>();
        for (int i = 0; i < ring.sectors().size(); i++) {
            sectors.add(new Sector(i, DIRECTIONS.get(i), ring.sectors().get(i),
                    inhabitants[i], ageCounts[i]));
        }
        long total = Arrays.stream(inhabitants).sum();
        boolean populationAvailable = population.isPresent() && total > 0;
        List<String> reasons = new ArrayList<>();
        if (!populationAvailable) reasons.add("keine Zensus-Bevölkerung im Pufferring");
        if (!ages.available()) reasons.add("Altersstruktur: " + ages.unavailableReason());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("mode", "census-buffer");
        provenance.put("bufferKm", bufferKm);
        provenance.put("sectorOrder", DIRECTIONS);
        provenance.put("population", total);
        provenance.put("populationAvailable", populationAvailable);
        provenance.put("agesAvailable", ages.available());
        provenance.put("geometryCrs", "EPSG:4326; metric buffer constructed in EPSG:3035");
        provenance.put("populationBySector", sectors.stream().map(s -> Map.of(
                "direction", s.direction(), "population", s.population(),
                "ageCounts", s.ageCounts())).toList());
        population.ifPresent(resolved -> {
            var p = resolved.provenance();
            provenance.put("populationSource", p.source());
            provenance.put("populationVersion", p.version());
            provenance.put("populationReferenceDate", p.referenceDate());
            provenance.put("populationArchiveSha256", p.archiveSha256());
            provenance.put("license", p.license());
            provenance.put("attribution", p.attribution());
        });
        if (ages.available()) {
            provenance.put("ageSource", ages.dataset().id());
            provenance.put("ageArchiveSha256", ages.dataset().sha256());
            provenance.put("ageCells", ages.cells());
        }
        String reason = reasons.isEmpty() ? null : String.join("; ", reasons);
        log.accept(populationAvailable
                ? "MobileCity-Umland: " + total + " Zensus-Einwohner in " + bufferKm
                + " km Pufferring"
                : "MobileCity-Umland konnte nicht automatisch befüllt werden: " + reason);
        return new Result(sectors, bufferKm, total, populationAvailable, ages.available(), reason, provenance);
    }

    static List<Geometry> sectorGeometries(Scenario.Region studyArea, double bufferKm) {
        return ring(studyArea, bufferKm).sectors();
    }

    private static Ring ring(Scenario.Region studyArea, double bufferKm) {
        if (studyArea == null || !Double.isFinite(bufferKm) || bufferKm <= 0)
            throw new IllegalArgumentException("Umlandpuffer benötigt Region und positive Breite");
        Coordinate[] corners = {
                new Coordinate(studyArea.minLon, studyArea.minLat),
                new Coordinate(studyArea.minLon, studyArea.maxLat),
                new Coordinate(studyArea.maxLon, studyArea.maxLat),
                new Coordinate(studyArea.maxLon, studyArea.minLat),
                new Coordinate(studyArea.minLon, studyArea.minLat)
        };
        Geometry core = MobileCityGeometry.project(GEOMETRY.createPolygon(corners));
        Geometry ring = core.buffer(bufferKm * 1000, 16).difference(core);
        Coordinate center = core.getCentroid().getCoordinate();
        double radius = Math.max(ring.getEnvelopeInternal().getWidth(),
                ring.getEnvelopeInternal().getHeight()) * 4 + bufferKm * 1000;
        List<Geometry> sectors = new ArrayList<>(8);
        for (double angle : ANGLES) {
            Coordinate left = ray(center, angle + 22.5, radius);
            Coordinate right = ray(center, angle - 22.5, radius);
            Geometry wedge = GEOMETRY.createPolygon(new Coordinate[]{center, left, right, center});
            sectors.add(MobileCityGeometry.unproject(ring.intersection(wedge)));
        }
        Geometry outerWgs84 = MobileCityGeometry.unproject(core.buffer(bufferKm * 1000, 16));
        var envelope = outerWgs84.getEnvelopeInternal();
        Scenario.Region bounds = new Scenario.Region();
        bounds.minLat = envelope.getMinY(); bounds.maxLat = envelope.getMaxY();
        bounds.minLon = envelope.getMinX(); bounds.maxLon = envelope.getMaxX();
        return new Ring(bounds, sectors);
    }

    private static Coordinate ray(Coordinate origin, double degrees, double radius) {
        double radians = Math.toRadians(degrees);
        return new Coordinate(origin.x + Math.cos(radians) * radius,
                origin.y + Math.sin(radians) * radius);
    }

    private static int sector(PreparedGeometry[] sectors, double lat, double lon) {
        var point = GEOMETRY.createPoint(new Coordinate(lon, lat));
        for (int i = 0; i < sectors.length; i++) if (sectors[i].covers(point)) return i;
        return -1;
    }
}
