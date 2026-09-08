package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a diagnostic, non-behavioural candidate distribution for destinations
 * outside the study region from population-tagged OSM settlements. The result is
 * deliberately not wired into destination choice until the multi-region promotion
 * gates are met.
 */
public final class OsmExternalDestinationCandidates {

    public static final String NAME = "external-destination-candidates.json";
    public static final String SOURCE = "osm-settlements-experiment";
    private static final int SECTORS = 8;
    static final double MAX_DISTANCE_KM = 80;

    public record Candidate(int sector, String direction, String representativePlace,
                            double lat, double lon, long population,
                            double share, double meanDistanceKm, double shareBeyond20Km) {
    }

    public record Evaluation(double directionShareMae, double officialMeanDistanceKm,
                             double candidateMeanDistanceKm, double meanDistanceRelativeError,
                             double officialShareBeyond20Km, double candidateShareBeyond20Km,
                             double shareBeyond20KmDifference) {
    }

    public record Result(List<Candidate> candidates, Evaluation evaluation,
                         int eligiblePlaces, long eligiblePopulation) {
    }

    private record WeightedPlace(OverpassClient.Place place, int sector, double distanceKm) {
    }

    private OsmExternalDestinationCandidates() {
    }

    public static Result build(Scenario.Region region, List<OverpassClient.Place> places,
                               CommuterMatrix.ParseResult official) {
        double centreLat = (region.minLat + region.maxLat) / 2;
        double centreLon = (region.minLon + region.maxLon) / 2;
        List<WeightedPlace> eligible = places.stream()
                .filter(place -> !contains(region, place.lat(), place.lon()))
                .map(place -> new WeightedPlace(place,
                        sector(centreLat, centreLon, place.lat(), place.lon()),
                        distanceKm(centreLat, centreLon, place.lat(), place.lon())))
                .filter(place -> place.distanceKm() <= MAX_DISTANCE_KM)
                .toList();
        double totalPopulation = eligible.stream().mapToDouble(p -> p.place().population()).sum();
        if (totalPopulation <= 0) {
            return new Result(List.of(), null, 0, 0);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int sector = 0; sector < SECTORS; sector++) {
            final int selectedSector = sector;
            List<WeightedPlace> group = eligible.stream()
                    .filter(place -> place.sector() == selectedSector).toList();
            if (group.isEmpty()) {
                continue;
            }
            double population = group.stream().mapToDouble(p -> p.place().population()).sum();
            double lat = group.stream().mapToDouble(p -> p.place().lat() * p.place().population()).sum()
                    / population;
            double lon = group.stream().mapToDouble(p -> p.place().lon() * p.place().population()).sum()
                    / population;
            double meanDistance = group.stream()
                    .mapToDouble(p -> p.distanceKm() * p.place().population()).sum() / population;
            double beyond20 = group.stream().filter(p -> p.distanceKm() >= 20)
                    .mapToDouble(p -> p.place().population()).sum() / population;
            String representative = group.stream()
                    .max(Comparator.comparingLong(p -> p.place().population()))
                    .orElseThrow().place().name();
            candidates.add(new Candidate(sector, direction(sector), representative, lat, lon,
                    Math.round(population), population / totalPopulation, meanDistance, beyond20));
        }

        Evaluation evaluation = official == null ? null
                : evaluate(region, candidates, official, centreLat, centreLon);
        return new Result(List.copyOf(candidates), evaluation, eligible.size(),
                Math.round(totalPopulation));
    }

    public static String write(Path file, Result result, ObjectMapper mapper) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("source", SOURCE);
        root.put("status", "diagnostic-only");
        root.put("maxDistanceKm", MAX_DISTANCE_KM);
        root.put("method", "population-weighted OSM settlements outside the region, grouped into eight compass sectors");
        root.put("eligiblePlaces", result.eligiblePlaces());
        root.put("eligiblePopulation", result.eligiblePopulation());
        ArrayNode candidates = root.putArray("candidates");
        for (Candidate candidate : result.candidates()) {
            ObjectNode node = candidates.addObject();
            node.put("sector", candidate.sector());
            node.put("direction", candidate.direction());
            node.put("representativePlace", candidate.representativePlace());
            node.put("lat", candidate.lat());
            node.put("lon", candidate.lon());
            node.put("population", candidate.population());
            node.put("share", candidate.share());
            node.put("meanDistanceKm", candidate.meanDistanceKm());
            node.put("shareBeyond20Km", candidate.shareBeyond20Km());
        }
        if (result.evaluation() != null) {
            root.set("officialOdEvaluation", mapper.valueToTree(result.evaluation()));
        }
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return OsmDataSnapshot.sha256(file);
    }

    private static Evaluation evaluate(Scenario.Region region, List<Candidate> candidates,
                                       CommuterMatrix.ParseResult official,
                                       double centreLat, double centreLon) {
        double[] candidateShares = new double[SECTORS];
        double candidateMeanDistance = 0;
        double candidateBeyond20 = 0;
        for (Candidate candidate : candidates) {
            candidateShares[candidate.sector()] = candidate.share();
            candidateMeanDistance += candidate.share() * candidate.meanDistanceKm();
            candidateBeyond20 += candidate.share() * candidate.shareBeyond20Km();
        }

        double[] officialWeights = new double[SECTORS];
        double officialTotal = 0;
        double officialDistance = 0;
        double officialBeyond20 = 0;
        for (CommuterMatrix.Flow flow : official.flows()) {
            if (flow.homeScope() == CommuterMatrix.HomeScope.POINT
                    && !contains(region, flow.homeLat(), flow.homeLon())) {
                continue;
            }
            if (contains(region, flow.workLat(), flow.workLon())) {
                continue;
            }
            double distance = distanceKm(centreLat, centreLon, flow.workLat(), flow.workLon());
            int sector = sector(centreLat, centreLon, flow.workLat(), flow.workLon());
            officialWeights[sector] += flow.commuters();
            officialTotal += flow.commuters();
            officialDistance += flow.commuters() * distance;
            if (distance >= 20) {
                officialBeyond20 += flow.commuters();
            }
        }
        if (officialTotal <= 0) {
            return null;
        }
        double mae = 0;
        for (int sector = 0; sector < SECTORS; sector++) {
            mae += Math.abs(candidateShares[sector] - officialWeights[sector] / officialTotal);
        }
        mae /= SECTORS;
        double officialMean = officialDistance / officialTotal;
        double officialBeyondShare = officialBeyond20 / officialTotal;
        return new Evaluation(mae, officialMean, candidateMeanDistance,
                officialMean == 0 ? 0 : Math.abs(candidateMeanDistance - officialMean) / officialMean,
                officialBeyondShare, candidateBeyond20,
                Math.abs(candidateBeyond20 - officialBeyondShare));
    }

    private static boolean contains(Scenario.Region region, double lat, double lon) {
        return lat >= region.minLat && lat <= region.maxLat
                && lon >= region.minLon && lon <= region.maxLon;
    }

    private static int sector(double fromLat, double fromLon, double toLat, double toLon) {
        double north = toLat - fromLat;
        double east = (toLon - fromLon) * Math.cos(Math.toRadians(fromLat));
        double clockwiseFromNorth = Math.atan2(east, north);
        if (clockwiseFromNorth < 0) {
            clockwiseFromNorth += Math.PI * 2;
        }
        return ((int) Math.floor((clockwiseFromNorth + Math.PI / 8) / (Math.PI / 4))) % SECTORS;
    }

    private static String direction(int sector) {
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[sector];
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
