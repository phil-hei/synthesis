package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.synthesis.scenario.Scenario;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Public charging-point capacity from a pinned BNetzA register snapshot. */
final class MobileCityChargingSource {
    static final String RESOURCE = "/reference/bnetza-public-charging-2026-09-01";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    record District(String id, Geometry geometry, long inhabitants) {}
    record ChargingPoint(String facilityId, LocalDate commissionedOn, double lat, double lon,
                         int chargingPoints, double nominalPowerKw, String facilityType) {}
    record Dataset(List<ChargingPoint> points, Map<String, Object> metadata) {}
    record Result(Map<String, SortedMap<Integer, Integer>> values, String status, String resolution,
                  String source, LocalDate referenceDate, double coverage, Integer effectivePoints,
                  String note, Map<String, Object> provenance, byte[] regionalSnapshot) {
        Result {
            Map<String, SortedMap<Integer, Integer>> copy = new LinkedHashMap<>();
            values.forEach((id, years) -> copy.put(id,
                    Collections.unmodifiableSortedMap(new TreeMap<>(years))));
            values = Collections.unmodifiableMap(copy);
            provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
            regionalSnapshot = regionalSnapshot == null ? null : regionalSnapshot.clone();
        }
        @Override public byte[] regionalSnapshot() {
            return regionalSnapshot == null ? null : regionalSnapshot.clone();
        }
        Integer value(String districtId, int year) {
            return values.getOrDefault(districtId, Collections.emptySortedMap()).get(year);
        }
    }

    @FunctionalInterface interface Loader { Dataset load() throws IOException; }
    private final Loader loader;
    MobileCityChargingSource() { this(MobileCityChargingSource::loadBundled); }
    MobileCityChargingSource(Loader loader) { this.loader = loader; }

    Result resolve(Scenario scenario, List<District> districts, List<Integer> years) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("schemaVersion", 1);
        provenance.put("scenarioReferenceYear", scenario.referenceYear);
        provenance.put("targetUnit", "public charging points; sum of BNetzA 'Anzahl Ladepunkte', not facilities or plug types");
        provenance.put("futurePolicy", "Values after the last observed year remain empty so MobileCity can apply its endogenous charger model");
        provenance.put("manualPolicy", "Optional charging points per 1,000 inhabitants are rounded independently per district and applied through the scenario reference year");

        String failure = null;
        if (scenario.mobileCity.useRegionalCharging) {
            try {
                if (scenario.region == null || !CensusPopulationSource.intersectsGermany(scenario.region)) {
                    throw new IOException("Keine deutsche BNetzA-Abdeckung");
                }
                Dataset data = loader.load();
                return automatic(scenario, districts, years, data, provenance);
            } catch (IOException | IllegalArgumentException e) {
                failure = e.getMessage();
            }
        }
        if (scenario.mobileCity.chargingPointsPer1000 != null) {
            return manual(scenario, districts, years, provenance, failure);
        }
        provenance.put("sourceFailure", failure);
        provenance.put("resolution", "missing");
        return new Result(empty(districts), "missing", "missing", "Keine Quelle oder Annahme",
                null, 0, null,
                failure == null ? "Keine öffentliche Ladepunktquelle gewählt." : failure,
                provenance, null);
    }

    private static Result automatic(Scenario scenario, List<District> districts, List<Integer> years,
                                    Dataset data, Map<String, Object> provenance) throws IOException {
        JsonNode metadata = JSON.valueToTree(data.metadata());
        LocalDate referenceDate = LocalDate.parse(metadata.path("referenceDate").asText());
        int observedThrough = Math.min(scenario.referenceYear, referenceDate.getYear());
        List<Integer> orderedYears=years.stream().sorted().toList();
        int firstModelYear=orderedYears.getFirst();
        boolean carriedForward=firstModelYear>referenceDate.getYear();
        int appliedThrough=carriedForward?firstModelYear:observedThrough;
        List<District> ordered = districts.stream().sorted(Comparator.comparing(District::id)).toList();
        STRtree index = new STRtree();
        ordered.forEach(d -> index.insert(d.geometry().getEnvelopeInternal(), d));
        index.build();

        Map<String, SortedMap<Integer, Integer>> additions = empty(ordered);
        List<Matched> matched = new ArrayList<>();
        int boundaryTies = 0, normal = 0, fast = 0;
        for (ChargingPoint point : data.points()) {
            var geometry = MobileCityGeometry.FACTORY.createPoint(new Coordinate(point.lon(), point.lat()));
            @SuppressWarnings("unchecked")
            List<District> candidates = ((List<District>) index.query(geometry.getEnvelopeInternal())).stream()
                    .filter(d -> d.geometry().covers(geometry)).sorted(Comparator.comparing(District::id)).toList();
            if (candidates.isEmpty()) continue;
            if (candidates.size() > 1) boundaryTies++;
            District district = candidates.getFirst();
            additions.get(district.id()).merge(point.commissionedOn().getYear(), point.chargingPoints(), Integer::sum);
            matched.add(new Matched(point, district.id()));
            if (point.facilityType().contains("Schnell")) fast += point.chargingPoints();
            else normal += point.chargingPoints();
        }

        Map<String, SortedMap<Integer, Integer>> cumulative = empty(ordered);
        for (District district : ordered) {
            int total = 0; Iterator<Map.Entry<Integer,Integer>> events=
                    additions.get(district.id()).entrySet().iterator();
            Map.Entry<Integer,Integer> next=events.hasNext()?events.next():null;
            for (int year : orderedYears) {
                while(next!=null && next.getKey()<=year){total+=next.getValue();next=events.hasNext()?events.next():null;}
                if (year <= observedThrough || (carriedForward && year==firstModelYear))
                    cumulative.get(district.id()).put(year, total);
            }
        }
        int effective = cumulative.values().stream()
                .mapToInt(row -> row.getOrDefault(appliedThrough, 0)).sum();
        byte[] snapshot = regionalSnapshot(matched);
        provenance.put("resolution", "automatic");
        provenance.put("source", data.metadata());
        provenance.put("observedThroughYear", observedThrough);
        provenance.put("valueAppliedThroughYear",appliedThrough);
        provenance.put("carryForwardYears",carriedForward?firstModelYear-referenceDate.getYear():0);
        provenance.put("temporalPolicy", "Cumulative points by commissioning year among facilities still present in the 2026-09-01 register; historical values are a surviving-stock lower bound. If the model starts after the snapshot, the snapshot stock is held to the first model year only.");
        provenance.put("regionalFacilities", matched.size());
        provenance.put("regionalChargingPointsAtSnapshot", normal + fast);
        provenance.put("regionalNormalChargingPoints", normal);
        provenance.put("regionalFastChargingPoints", fast);
        provenance.put("effectiveChargingPoints", effective);
        provenance.put("districtBoundaryTies", boundaryTies);
        provenance.put("regionalSnapshot", "charging-points.csv");
        provenance.put("regionalSnapshotSha256", MobileCityFleetSource.hash(snapshot));
        return new Result(cumulative, carriedForward?"derived":"observed", "automatic",
                "BNetzA Ladesäulenregister", referenceDate, 1, effective,
                carriedForward
                        ? "Öffentliche Ladepunkte; Snapshot-Bestand bis zum ersten Modelljahr konstant gehalten, danach endogene Entwicklung."
                        : "Öffentliche Ladepunkte; historische Werte enthalten nur Einrichtungen, die im Snapshot noch geführt werden.",
                provenance, snapshot);
    }

    private static Result manual(Scenario scenario, List<District> districts, List<Integer> years,
                                 Map<String, Object> provenance, String failure) {
        double rate = scenario.mobileCity.chargingPointsPer1000;
        Map<String, SortedMap<Integer, Integer>> values = empty(districts);
        int total = 0;
        for (District district : districts) {
            int points = (int) Math.round(district.inhabitants() / 1000d * rate);
            total += points;
            for (int year : years) if (year <= scenario.referenceYear) values.get(district.id()).put(year, points);
        }
        provenance.put("resolution", failure == null ? "manual" : "fallback");
        provenance.put("sourceFailure", failure);
        provenance.put("chargingPointsPer1000", rate);
        provenance.put("effectiveChargingPoints", total);
        return new Result(values, "assumption", failure == null ? "manual" : "fallback",
                "Nutzervorgabe", null, 0, total,
                failure == null ? "Nach Einwohnerzahl verteilte Modellannahme."
                        : "BNetzA-Quelle nicht nutzbar; nach Einwohnerzahl verteilte Modellannahme.",
                provenance, null);
    }

    private static Map<String, SortedMap<Integer, Integer>> empty(List<District> districts) {
        Map<String, SortedMap<Integer, Integer>> result = new LinkedHashMap<>();
        districts.stream().sorted(Comparator.comparing(District::id))
                .forEach(d -> result.put(d.id(), new TreeMap<>()));
        return result;
    }

    private record Matched(ChargingPoint point, String districtId) {}
    private static byte[] regionalSnapshot(List<Matched> matched) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("facilityId;commissionedOn;latitude;longitude;chargingPoints;nominalPowerKw;facilityType;districtID\n"
                .getBytes(StandardCharsets.UTF_8));
        for (Matched value : matched.stream().sorted(Comparator
                .comparing((Matched m) -> m.point().commissionedOn())
                .thenComparing(m -> m.point().facilityId())).toList()) {
            ChargingPoint p = value.point();
            String row = String.join(";", p.facilityId(), p.commissionedOn().toString(),
                    Double.toString(p.lat()), Double.toString(p.lon()),
                    Integer.toString(p.chargingPoints()), Double.toString(p.nominalPowerKw()),
                    p.facilityType(), value.districtId()) + "\n";
            out.write(row.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    static Dataset loadBundled() throws IOException {
        byte[] normalized;
        try (InputStream raw = MobileCityChargingSource.class.getResourceAsStream(RESOURCE + ".csv.gz")) {
            if (raw == null) throw new IOException("BNetzA-Referenz fehlt");
            try (GZIPInputStream gzip = new GZIPInputStream(raw)) { normalized = gzip.readAllBytes(); }
        }
        JsonNode metadata;
        try (InputStream input = MobileCityChargingSource.class.getResourceAsStream(RESOURCE + ".json")) {
            if (input == null) throw new IOException("BNetzA-Metadaten fehlen");
            metadata = JSON.readTree(input);
        }
        if (!MobileCityFleetSource.hash(normalized).equals(metadata.path("normalizedContentSha256").asText())) {
            throw new IOException("BNetzA-Referenzhash falsch");
        }
        List<String> lines = new String(normalized, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(
                "facilityId;commissionedOn;latitude;longitude;chargingPoints;nominalPowerKw;facilityType")) {
            throw new IOException("BNetzA-Referenzschema falsch");
        }
        List<ChargingPoint> points = new ArrayList<>(lines.size() - 1);
        try {
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split(";", -1);
                if (c.length != 7) throw new IllegalArgumentException();
                points.add(new ChargingPoint(c[0], LocalDate.parse(c[1]), Double.parseDouble(c[2]),
                        Double.parseDouble(c[3]), Integer.parseInt(c[4]), Double.parseDouble(c[5]), c[6]));
            }
        } catch (RuntimeException e) {
            throw new IOException("BNetzA-Referenzwerte ungültig", e);
        }
        if (points.size() != metadata.path("rows").asInt()) throw new IOException("BNetzA-Zeilenzahl falsch");
        Map<String, Object> metadataMap = JSON.convertValue(metadata, new TypeReference<>() {});
        return new Dataset(List.copyOf(points), metadataMap);
    }
}
