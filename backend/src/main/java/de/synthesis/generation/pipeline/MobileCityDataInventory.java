package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Machine-readable account of resolved, assumed and missing Regionalexport inputs. */
final class MobileCityDataInventory {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MobileCityDataInventory() {}

    static Map<String, Object> regional(Scenario scenario, List<Zone> zones,
                                        MobileCityPopulation population,
                                        MobileCityFleetSource.Result fleet,
                                        int districtCount, int surroundingDistrictCount, Path artifacts,
                                        Map<String, Object> runSources,
                                        String profileSha256,
                                        MobileCityGtfsAdapter.Feed transitFeed,
                                        MobileCityGtfsAdapter.Result transit,
                                        MobileCityChargingSource.Result charging,
                                        MobileCitySurroundingSource.Result surrounding,
                                        long surroundingPopulation,
                                        boolean surroundingAgesFromCensus) throws IOException {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("mode", "regional");
        inventory.put("referenceYear", scenario.referenceYear);
        inventory.put("inputSnapshot", inputSnapshot(scenario));
        inventory.put("ready", true);
        inventory.put("blockingIssues", List.of());

        List<Map<String, Object>> items = new ArrayList<>();
        inventory.put("items", items);
        long people = 0, cars = 0;
        for (Zone zone : zones) if (!zone.external) {
            people += Arrays.stream(population.ages[zone.index]).sum();
            cars += population.cars[zone.index];
        }
        items.add(item("geography", "Gebiet und Districts", "derived", "automatic",
                "SyntheSis-Zonierung", true, null, 1d, districtCount, "Districts",
                "Alle internen Zonen und ihre tatsächlichen Polygonflächen sind zugeordnet."));

        String populationSource = text(runSources.get("populationSource"),
                scenario.population.totalPersons == null ? "automatic-source-chain" : "user-defined");
        String populationResolution = switch (populationSource) {
            case "user-defined", "upload-grid" -> "manual";
            case "census-2022" -> "automatic";
            default -> "fallback";
        };
        String populationStatus = "census-2022".equals(populationSource) || "upload-grid".equals(populationSource)
                ? "observed" : "user-defined".equals(populationSource) ? "assumption" : "derived";
        items.add(item("population-total", "Einwohnerzahl", populationStatus, populationResolution,
                text(runSources.get("populationSourceLabel"), populationSource), true,
                nullableText(runSources.get("populationReferenceDate")), 1d, people, "Personen",
                nullableText(runSources.get("populationSourceSha256"))));

        Path censusReport = artifacts.resolve("population/census-structure.json");
        if (Files.isRegularFile(censusReport)) {
            JsonNode report = JSON.readTree(censusReport.toFile());
            long weighted = report.path("weightedZones").asLong();
            long fallback = report.path("fitFallbackZones").asLong();
            double coverage = weighted + fallback == 0 ? 0 : weighted / (double) (weighted + fallback);
            String resolution = fallback == 0 ? "automatic" : weighted == 0 ? "fallback" : "fallback";
            String status = weighted == 0 ? "assumption" : "derived";
            items.add(item("population-structure", "Alters- und Haushaltsstruktur", status, resolution,
                    "Destatis Zensus 2022 + KIT-Haushaltspool", true,
                    report.path("referenceDate").asText(null), coverage, weighted, "gewichtete Zonen",
                    fallback + " Zonen verwenden den dokumentierten Fallback."));
        } else {
            items.add(item("population-structure", "Alters- und Haushaltsstruktur", "assumption", "profile",
                    scenario.population.useKitPopulation ? "KIT-Haushaltspool" : "SyntheSis-Ziehung", true,
                    null, 0d, null, null, "Keine regionale Zensus-Struktur verwendet."));
        }

        items.add(item("private-cars", "Private Pkw", "derived", "automatic",
                "Synthetische Haushalte", true, null, 1d, cars, "Pkw",
                "Bestand aus den erzeugten Haushalten; kein beobachteter KBA-Gesamtbestand."));
        addFleet(items, zones, population, fleet, cars);

        items.add(item("networks", "Auto-, Rad- und Fußverbindungen", "derived", "automatic",
                text(runSources.get("matrixSource"), "SyntheSis-OD-Matrizen"), true, null, 1d,
                districtCount, "Districts", "Aggregierte Verbindungen, kein physisches MobileCity-Straßennetz."));
        if (transit != null) {
            String resolution = "upload".equals(transitFeed.source()) ? "manual" : "automatic";
            items.add(item("transit", "ÖV-Angebot", "observed", resolution,
                    "GTFS " + transitFeed.name(), true, transit.serviceDate().toString(), 1d,
                    transit.scheduledTrips(), "Fahrten/Werktag",
                    transit.serviceStops() + " Haltestellen; Angebotsdatum und Nachfragejahr werden getrennt ausgewiesen."));
        } else {
            items.add(item("transit", "ÖV-Angebot", "assumption", "fallback",
                    "Modellierter Fallback", true, null, 0d, scenario.mobileCity.transitVehiclesPer1000,
                    "Fahrzeuge/1.000 Einwohner", "Kein regionaler oder hochgeladener GTFS-Feed aufgelöst."));
        }
        items.add(item("chargers", "Öffentliche Ladepunkte", charging.status(), charging.resolution(),
                charging.source(), false,
                charging.referenceDate() == null ? null : charging.referenceDate().toString(),
                charging.coverage(), charging.effectivePoints(), "Ladepunkte", charging.note()));
        double sharingVehicles = scenario.mobileCity.carsharingVehiclesPer1000 == null
                ? 0 : scenario.mobileCity.carsharingVehiclesPer1000;
        double sharingMembers = scenario.mobileCity.carsharingMemberShare == null
                ? 0 : scenario.mobileCity.carsharingMemberShare;
        boolean manualSharing = scenario.mobileCity.carsharingVehiclesPer1000 != null
                || scenario.mobileCity.carsharingMemberShare != null;
        long sharingFleet = 0;
        for (Zone zone : zones) if (!zone.external) {
            sharingFleet += Math.round(Arrays.stream(population.ages[zone.index]).sum()
                    * sharingVehicles / 1000d);
        }
        items.add(item("sharing", "Carsharing-Angebot", "assumption",
                manualSharing ? "manual" : "profile",
                manualSharing ? "Nutzervorgabe" : "Regionalexport-Baseline", false, null,
                manualSharing ? 1d : 0d, sharingFleet, "Fahrzeuge",
                "Konstant: " + sharingVehicles + " Fahrzeuge je 1.000 Einwohner und "
                        + Math.round(people * sharingMembers) + " Mitglieder (Anteil "
                        + sharingMembers + ")."));
        items.add(item("model-profile", "Modellparameter", "assumption", "profile",
                scenario.mobileCity.templateId, true, null, 0d, profileSha256, "SHA-256",
                "Unkalibriertes, hashgeprüftes MobileCity-Modellprofil."));
        boolean hasSurrounding = surroundingDistrictCount > 0;
        boolean automaticSurrounding = hasSurrounding && scenario.mobileCity.autoSurrounding;
        boolean surroundingOverride = automaticSurrounding
                && scenario.mobileCity.surroundingPopulation != null
                && scenario.mobileCity.surroundingPopulation > 0;
        items.add(item("surrounding", "Umlandbevölkerung",
                hasSurrounding ? automaticSurrounding
                        ? surroundingOverride || !surroundingAgesFromCensus ? "mixed" : "observed"
                        : "assumption" : "missing",
                hasSurrounding ? automaticSurrounding
                        ? surroundingOverride || !surroundingAgesFromCensus ? "fallback" : "automatic"
                        : "manual" : "missing",
                hasSurrounding ? automaticSurrounding
                        ? "Destatis Zensus 2022, metrischer Pufferring" : "Nutzervorgabe"
                        : "Noch nicht modelliert", false,
                automaticSurrounding ? "2022-05-15" : null,
                hasSurrounding ? 1d : 0d,
                hasSurrounding ? surroundingPopulation : null, "Personen",
                hasSurrounding
                        ? surroundingDistrictCount + " Umland-Districts; eingehende Wegeanteile Bildung/Arbeit/Einkauf/Freizeit: "
                        + scenario.mobileCity.surroundingEducationTripShare + "/"
                        + scenario.mobileCity.surroundingWorkTripShare + "/"
                        + scenario.mobileCity.surroundingShoppingTripShare + "/"
                        + scenario.mobileCity.surroundingLeisureTripShare + ". "
                        + (automaticSurrounding
                        ? "Geometrie und räumliche Bevölkerungsverteilung automatisch; Altersstruktur "
                        + (surroundingAgesFromCensus ? "aus Zensus." : "aus dem Untersuchungsgebiet extrapoliert.")
                        + (surroundingOverride ? " Manuelle Gesamtsumme skaliert die beobachteten Sektorgewichte." : "")
                        : "Alters-, Pkw- und BEV-Struktur aus dem Untersuchungsgebiet extrapoliert.")
                        : "Externe Zielanker sind ohne explizite Bevölkerung keine Umland-Districts."));
        items.add(item("logistics", "Logistik", "missing", "missing",
                "Kein bestätigter MobileCity-Vertrag", false, null, 0d, null, null,
                "Paketmengen, Depots, Fahrzeuge und Touren bleiben außerhalb dieses Exports."));

        List<Map<String, Object>> snapshots = new ArrayList<>();
        inventory.put("sourceSnapshots", snapshots);
        Object kba = fleet.provenance().get("kba");
        if (kba instanceof JsonNode metadata) {
            Map<String,Object> kbaSnapshot=snapshot("kba-private-fleet", "bundled-resource",
                    metadata.path("normalizedSha256").asText());
            kbaSnapshot.put("sourceSha256",metadata.path("sourceSha256").asText());
            kbaSnapshot.put("referenceDate",metadata.path("referenceDate").asText());
            snapshots.add(kbaSnapshot);
        }
        if (fleet.boundarySnapshot() != null) snapshots.add(snapshot("bkg-vg250-boundaries",
                "fleet-boundaries.geojson", fleet.provenance().get("boundarySha256")));
        if (Files.isRegularFile(censusReport)) snapshots.add(snapshot("census-structure",
                "census-structure.json", CensusStructureSource.sha256(censusReport)));
        if (automaticSurrounding && surrounding != null)
            snapshots.add(snapshot("census-surrounding", "surrounding-provenance.json",
                    surrounding.provenance().getOrDefault("populationArchiveSha256",
                            surrounding.provenance().get("ageArchiveSha256"))));
        if (transit != null) snapshots.add(snapshot("gtfs-transit", "gtfs-feed.zip", transit.feedSha256()));
        if (charging.regionalSnapshot() != null) snapshots.add(snapshot("bnetza-public-charging",
                "charging-points.csv", charging.provenance().get("regionalSnapshotSha256")));
        inventory.put("sourceContext", new TreeMap<>(runSources));
        return inventory;
    }

    static Map<String, Object> inputSnapshot(Scenario scenario) {
        Map<String, Object> region = new TreeMap<>();
        if (scenario.region != null) {
            region.put("maxLat", scenario.region.maxLat); region.put("maxLon", scenario.region.maxLon);
            region.put("minLat", scenario.region.minLat); region.put("minLon", scenario.region.minLon);
        }
        Map<String, Object> zoning = new TreeMap<>();
        zoning.put("cellSizeMeters", scenario.zoning.cellSizeMeters);
        zoning.put("maxZones", scenario.zoning.maxZones);
        zoning.put("mode", scenario.zoning.mode);
        Map<String, Object> result = new TreeMap<>();
        result.put("carsPerHousehold", scenario.population.carsPerHousehold);
        result.put("carsharingMemberShare", scenario.mobileCity.carsharingMemberShare);
        result.put("carsharingVehiclesPer1000", scenario.mobileCity.carsharingVehiclesPer1000);
        result.put("chargingPointsPer1000", scenario.mobileCity.chargingPointsPer1000);
        result.put("electricShare", scenario.mobileCity.electricShare);
        result.put("externalTrips", scenario.population.externalTrips);
        result.put("maxDistricts", scenario.mobileCity.maxDistricts);
        result.put("populationTotal", scenario.population.totalPersons);
        result.put("referenceYear", scenario.referenceYear);
        result.put("region", region);
        result.put("samplingFraction", scenario.population.samplingFraction);
        result.put("settlementAreaShare", scenario.mobileCity.settlementAreaShare);
        result.put("autoSurrounding", scenario.mobileCity.autoSurrounding);
        result.put("surroundingBufferKm", scenario.mobileCity.surroundingBufferKm);
        result.put("surroundingEducationTripShare", scenario.mobileCity.surroundingEducationTripShare);
        result.put("surroundingLeisureTripShare", scenario.mobileCity.surroundingLeisureTripShare);
        result.put("surroundingPopulation", scenario.mobileCity.surroundingPopulation);
        result.put("surroundingShoppingTripShare", scenario.mobileCity.surroundingShoppingTripShare);
        result.put("surroundingWorkTripShare", scenario.mobileCity.surroundingWorkTripShare);
        result.put("templateId", scenario.mobileCity.templateId);
        result.put("transitVehiclesPer1000", scenario.mobileCity.transitVehiclesPer1000);
        result.put("allowSyntheticTransitFallback", scenario.mobileCity.allowSyntheticTransitFallback);
        result.put("gtfsUploadPath", scenario.population.gtfsUploadPath);
        result.put("useCensusStructure", scenario.population.useCensusStructure);
        result.put("useKitPopulation", scenario.population.useKitPopulation);
        result.put("useRegionalCharging", scenario.mobileCity.useRegionalCharging);
        result.put("useRegionalFleet", scenario.mobileCity.useRegionalFleet);
        result.put("zoning", zoning);
        return result;
    }

    private static void addFleet(List<Map<String, Object>> items, List<Zone> zones,
                                 MobileCityPopulation population, MobileCityFleetSource.Result fleet,
                                 long totalCars) {
        long automatic = 0, manual = 0; double bevCars = 0;
        for (Zone zone : zones) if (!zone.external && population.cars[zone.index] > 0) {
            var share = fleet.shares().get(zone.index);
            if (share == null) continue;
            if (share.source().startsWith("kba-")) automatic += population.cars[zone.index];
            else manual += population.cars[zone.index];
            bevCars += population.cars[zone.index] * share.bev();
        }
        String resolution = automatic > 0 && manual > 0 ? "fallback" : automatic > 0 ? "automatic" : "manual";
        String status = manual == 0 ? "observed" : automatic == 0 ? "assumption" : "mixed";
        String source = automatic > 0 && manual > 0 ? "KBA 01.07.2026 + manuelle Lückenannahme"
                : automatic > 0 ? "KBA FZ 27.17, 01.07.2026" : "Nutzervorgabe";
        double observedCoverage = totalCars == 0 ? 0 : automatic / (double) totalCars;
        double share = totalCars == 0 ? 0 : bevCars / totalCars;
        items.add(item("private-bev", "Privater BEV-Anteil", status, resolution, source, true,
                automatic > 0 ? "2026-07-01" : null, observedCoverage, share, "Anteil",
                "PHEV bleibt separat im Herkunftsbericht und zählt im binären Zielmodell nicht als BEV."));
    }

    private static Map<String, Object> item(String id, String label, String status, String resolution,
                                            String source, boolean required, String referenceDate,
                                            double coverage, Object value, String unit, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id); item.put("label", label); item.put("status", status);
        item.put("resolution", resolution); item.put("source", source); item.put("required", required);
        item.put("referenceDate", referenceDate); item.put("coverage", coverage);
        item.put("value", value); item.put("unit", unit); item.put("note", note);
        return item;
    }

    private static Map<String, Object> snapshot(String id, String path, Object sha256) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", id); snapshot.put("path", path); snapshot.put("sha256", sha256);
        return snapshot;
    }

    private static String text(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static String nullableText(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
