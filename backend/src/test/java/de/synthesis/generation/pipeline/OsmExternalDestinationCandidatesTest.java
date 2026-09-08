package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsmExternalDestinationCandidatesTest {

    @Test
    void groupsOnlyExternalSettlementsAndWeightsThemByPopulation() {
        Scenario.Region region = region();
        List<OverpassClient.Place> places = List.of(
                new OverpassClient.Place("Inside", "town", 48.86, 8.20, 100_000),
                new OverpassClient.Place("North small", "village", 49.00, 8.20, 10_000),
                new OverpassClient.Place("North large", "town", 49.10, 8.20, 30_000),
                new OverpassClient.Place("East", "town", 48.86, 8.55, 40_000));

        OsmExternalDestinationCandidates.Result result =
                OsmExternalDestinationCandidates.build(region, places, null);

        assertEquals(3, result.eligiblePlaces());
        assertEquals(80_000, result.eligiblePopulation());
        assertEquals(2, result.candidates().size());
        assertEquals(1.0, result.candidates().stream().mapToDouble(
                OsmExternalDestinationCandidates.Candidate::share).sum(), 1e-9);
        OsmExternalDestinationCandidates.Candidate north = result.candidates().stream()
                .filter(candidate -> candidate.direction().equals("N")).findFirst().orElseThrow();
        assertEquals("North large", north.representativePlace());
        assertEquals(0.5, north.share(), 1e-9);
        assertEquals((49.00 * 10_000 + 49.10 * 30_000) / 40_000, north.lat(), 1e-9);
    }

    @Test
    void evaluatesCandidateAgainstOfficialExternalOd() {
        Scenario.Region region = region();
        List<OverpassClient.Place> places = List.of(
                new OverpassClient.Place("North", "town", 49.10, 8.20, 30_000),
                new OverpassClient.Place("East", "town", 48.86, 8.60, 10_000));
        CommuterMatrix.ParseResult official = new CommuterMatrix.ParseResult(List.of(
                new CommuterMatrix.Flow(48.86, 8.20, 49.10, 8.20, 75,
                        CommuterMatrix.HomeScope.EXTERNAL),
                new CommuterMatrix.Flow(48.86, 8.20, 48.86, 8.60, 25,
                        CommuterMatrix.HomeScope.EXTERNAL)), 100);

        OsmExternalDestinationCandidates.Result result =
                OsmExternalDestinationCandidates.build(region, places, official);

        assertNotNull(result.evaluation());
        assertEquals(0, result.evaluation().directionShareMae(), 1e-9);
        assertEquals(result.evaluation().officialMeanDistanceKm(),
                result.evaluation().candidateMeanDistanceKm(), 1e-9);
        assertEquals(0, result.evaluation().meanDistanceRelativeError(), 1e-9);
        assertEquals(0, result.evaluation().shareBeyond20KmDifference(), 1e-9);
    }

    @Test
    void writesExplicitDiagnosticStatusAndEvaluation(@TempDir Path tempDir) throws Exception {
        OsmExternalDestinationCandidates.Result result =
                OsmExternalDestinationCandidates.build(region(), List.of(
                        new OverpassClient.Place("North", "town", 49.10, 8.20, 30_000)),
                        null);
        Path file = tempDir.resolve(OsmExternalDestinationCandidates.NAME);

        String sha = OsmExternalDestinationCandidates.write(file, result, new ObjectMapper());

        String json = Files.readString(file);
        assertTrue(json.contains("diagnostic-only"));
        assertTrue(json.contains("North"));
        assertEquals(64, sha.length());
    }

    @Test
    void ignoresUnrelatedPointOriginsButKeepsRegionWideReferences() {
        var places = List.of(new OverpassClient.Place("North", "town", 49.10, 8.20, 30_000));
        var unrelated = new CommuterMatrix.Flow(47, 7, 48.86, 8.60, 1000);
        var regional = new CommuterMatrix.Flow(47, 7, 49.10, 8.20, 100,
                CommuterMatrix.HomeScope.EXTERNAL);
        var result = OsmExternalDestinationCandidates.build(region(), places,
                new CommuterMatrix.ParseResult(List.of(unrelated, regional), 1100));
        assertNotNull(result.evaluation());
        assertEquals(0, result.evaluation().directionShareMae(), 1e-9);
        assertEquals(0, result.evaluation().meanDistanceRelativeError(), 1e-9);
    }

    private static Scenario.Region region() {
        Scenario.Region region = new Scenario.Region();
        region.minLat = 48.84;
        region.minLon = 8.17;
        region.maxLat = 48.88;
        region.maxLon = 8.23;
        return region;
    }
}
