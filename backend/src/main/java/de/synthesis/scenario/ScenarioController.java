package de.synthesis.scenario;

import de.synthesis.SynthesisProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private static final Set<String> BEHAVIOR_SOURCES = Set.of("actitopp", "gta", "template");
    private static final Set<String> TARGET_MODELS = Set.of("logitopp", "sumo", "mobilecity");

    private final ScenarioRepository repository;
    private final SynthesisProperties properties;

    public ScenarioController(ScenarioRepository repository, SynthesisProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @GetMapping
    public List<Scenario> list() {
        return repository.findAll();
    }

    @GetMapping("/defaults")
    public Scenario defaults() {
        Scenario scenario = new Scenario();
        scenario.zoning.maxZones = properties.getMaxZones();
        scenario.zoning.mode = "epsg3035";
        scenario.freight.day = properties.getSumo().getDay();
        return scenario;
    }

    @GetMapping("/{id}")
    public Scenario get(@PathVariable String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Szenario nicht gefunden: " + id));
    }

    @PostMapping
    public ResponseEntity<Scenario> create(@RequestBody Scenario scenario) {
        scenario.id = UUID.randomUUID().toString().substring(0, 8);
        scenario.createdAt = Instant.now();
        scenario.createdBy = de.synthesis.auth.AuthContext.username().orElse(null);
        scenario.normalizeTargetDependencies();
        validate(scenario);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(scenario));
    }

    @PutMapping("/{id}")
    public Scenario update(@PathVariable String id, @RequestBody Scenario scenario) {
        Scenario existing = get(id);
        scenario.id = existing.id;
        scenario.createdAt = existing.createdAt;
        scenario.createdBy = existing.createdBy;
        scenario.normalizeTargetDependencies();
        validate(scenario);
        return repository.save(scenario);
    }

    /** Löschen dürfen Ersteller und Administratoren; alle Angemeldeten teilen sich die Szenarien. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        repository.findById(id).ifPresent(existing -> {
            if (!de.synthesis.auth.AuthContext.mayManage(existing.createdBy)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Nur der Ersteller oder ein Administrator darf dieses Szenario löschen");
            }
        });
        repository.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Speichert einen bewusst ausgewählten GTFS-Feed für das Szenario und verankert den Pfad in
     * {@code population.gtfsUploadPath}. Ein solcher Upload hat bei SUMO und MobileCity Vorrang
     * vor dem automatischen Regionsfeed.
     */
    @PostMapping("/{id}/gtfs")
    public Scenario uploadGtfs(@PathVariable String id,
                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Scenario scenario = get(id);
        if (file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte eine GTFS-ZIP-Datei hochladen");
        }
        try {
            java.nio.file.Path uploads = repository.uploadsDir(id);
            java.nio.file.Files.createDirectories(uploads);
            java.nio.file.Path dest = uploads.resolve("gtfs.zip");
            file.transferTo(dest.toAbsolutePath());
            scenario.population.gtfsUploadPath = dest.toString();
            return repository.save(scenario);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "GTFS-Upload fehlgeschlagen: " + e.getMessage());
        }
    }

    private void validate(Scenario scenario) {
        try { de.synthesis.data.SourcePlan.validate(scenario.dataPlan); }
        catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
        if (scenario.energyInfrastructure == null) scenario.energyInfrastructure = new Scenario.EnergyInfrastructure();
        if (scenario.energyInfrastructure.enabled && !scenario.energyInfrastructure.useBnetza && !scenario.energyInfrastructure.useOsm)
            throw badRequest("Öffentliche Lade-/Tankstationen benötigen mindestens eine Datenquelle");
        if (scenario.energyInfrastructure.verifyNetworkAccess && (!scenario.energyInfrastructure.enabled || !scenario.targets().contains("sumo")))
            throw badRequest("Stations-Netzprüfung benötigt aktiven Stationsbestand und SUMO");
        if (!Double.isFinite(scenario.energyInfrastructure.maxSnapDistanceM) || scenario.energyInfrastructure.maxSnapDistanceM < 1 || scenario.energyInfrastructure.maxSnapDistanceM > 300)
            throw badRequest("Stations-Suchradius muss zwischen 1 und 300 m liegen");
        if (scenario.vehicleOperations == null) scenario.vehicleOperations = de.synthesis.vehicle.VehicleOperationsConfiguration.empty();
        if (scenario.companyTours == null) scenario.companyTours = new Scenario.CompanyTours();
        if (scenario.companyTours.imports == null || scenario.companyTours.imports.size() > 100)
            throw badRequest("Unternehmenstouren benötigen eine Liste mit höchstens 100 Importrevisionen");
        if (scenario.companyTours.enabled) {
            try { de.synthesis.companytour.CompanyTourImportService.requireScenarioDemand(scenario); }
            catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
        }
        var operationErrors = de.synthesis.vehicle.VehicleOperationsConfiguration.validate(scenario);
        if (!operationErrors.isEmpty()) throw badRequest(operationErrors.getFirst().path() + ": " + operationErrors.getFirst().message());
        de.synthesis.vehicle.VehicleCatalog.Report vehicles = de.synthesis.vehicle.VehicleCatalog.validate(scenario.vehicleConfiguration);
        if (!vehicles.valid()) {
            var error = vehicles.errors().getFirst();
            throw badRequest("Fahrzeugkonfiguration: " + error.path() + ": " + error.message());
        }
        if (scenario.referenceYear < 2000 || scenario.referenceYear > 2050) {
            throw badRequest("Bezugsjahr muss zwischen 2000 und 2050 liegen");
        }
        if (scenario.mobileCity == null || scenario.mobileCity.templateId == null
                || !scenario.mobileCity.templateId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) {
            throw badRequest("Ungültige MobileCity-Vorlage");
        }
        if (scenario.mobileCity.validateNative
                && (!scenario.targets().contains("mobilecity")
                || (!scenario.mobileCity.regionalExport && !scenario.mobileCity.useTemplate))) {
            throw badRequest("Native MobileCity-Prüfung benötigt MobileCity und ein vollständiges Regional- oder Vorlagenpaket");
        }
        if (scenario.zoning == null) {
            throw badRequest("Zonierungsparameter fehlen");
        }
        if (scenario.population == null) {
            throw badRequest("Bevölkerungsparameter fehlen");
        }
        if (scenario.mobileCity != null && scenario.mobileCity.regionalExport) {
            if (scenario.referenceYear < 2018 || scenario.referenceYear > 2048) throw badRequest("MobileCity-Regionalpaket unterstützt Bezugsjahre 2018 bis 2048");
            if (scenario.mobileCity.useTemplate) throw badRequest("Regionalpaket und Vorlagen-Ergänzung sind alternative Exportmodi");
            if (!scenario.mobileCity.useRegionalFleet && scenario.mobileCity.electricShare == null)
                throw badRequest("MobileCity-Regionalpaket benötigt regionale KBA-Daten oder eine explizite BEV-Annahme; für einen rudimentären Export ist 0 zulässig");
            if (scenario.mobileCity.maxDistricts < 1 || scenario.mobileCity.maxDistricts > 100)
                throw badRequest("Regionalpaket unterstützt 1 bis 100 Districts");
            if (scenario.mobileCity.allowSyntheticTransitFallback
                    && (!Double.isFinite(scenario.mobileCity.transitVehiclesPer1000)
                    || scenario.mobileCity.transitVehiclesPer1000 <= 0
                    || scenario.mobileCity.transitVehiclesPer1000 > 100))
                throw badRequest("Modellierte ÖV-Flotte muss größer als 0 und höchstens 100 Fahrzeuge je 1000 Einwohner sein");
            if (!Double.isFinite(scenario.mobileCity.settlementAreaShare) || scenario.mobileCity.settlementAreaShare <= 0 || scenario.mobileCity.settlementAreaShare > 1)
                throw badRequest("Siedlungsflächenanteil muss größer als 0 und höchstens 1 sein");
        }
        if (scenario.mobileCity != null && scenario.mobileCity.electricShare != null
                && (!Double.isFinite(scenario.mobileCity.electricShare) || scenario.mobileCity.electricShare < 0 || scenario.mobileCity.electricShare > 1))
            throw badRequest("MobileCity-BEV-Anteil muss zwischen 0 und 1 liegen");
        if (scenario.mobileCity != null && scenario.mobileCity.chargingPointsPer1000 != null
                && (!Double.isFinite(scenario.mobileCity.chargingPointsPer1000)
                || scenario.mobileCity.chargingPointsPer1000 < 0
                || scenario.mobileCity.chargingPointsPer1000 > 100))
            throw badRequest("MobileCity-Ladepunkte müssen zwischen 0 und 100 je 1000 Einwohner liegen");
        if (scenario.mobileCity != null && scenario.mobileCity.carsharingVehiclesPer1000 != null
                && (!Double.isFinite(scenario.mobileCity.carsharingVehiclesPer1000)
                || scenario.mobileCity.carsharingVehiclesPer1000 < 0
                || scenario.mobileCity.carsharingVehiclesPer1000 > 100))
            throw badRequest("MobileCity-Carsharing-Flotte muss zwischen 0 und 100 Fahrzeugen je 1000 Einwohner liegen");
        if (scenario.mobileCity != null && scenario.mobileCity.carsharingMemberShare != null
                && (!Double.isFinite(scenario.mobileCity.carsharingMemberShare)
                || scenario.mobileCity.carsharingMemberShare < 0
                || scenario.mobileCity.carsharingMemberShare > 0.4))
            throw badRequest("MobileCity-Carsharing-Mitgliederanteil muss zwischen 0 und 0,4 liegen");
        if (scenario.mobileCity != null) {
            Integer surroundingPopulation = scenario.mobileCity.surroundingPopulation;
            boolean surroundingRequested = scenario.mobileCity.autoSurrounding
                    || (surroundingPopulation != null && surroundingPopulation > 0);
            boolean hasSurroundingShares = scenario.mobileCity.surroundingEducationTripShare != null
                    || scenario.mobileCity.surroundingWorkTripShare != null
                    || scenario.mobileCity.surroundingShoppingTripShare != null
                    || scenario.mobileCity.surroundingLeisureTripShare != null;
            if (scenario.mobileCity.autoSurrounding
                    && (!Double.isFinite(scenario.mobileCity.surroundingBufferKm)
                    || scenario.mobileCity.surroundingBufferKm < 1
                    || scenario.mobileCity.surroundingBufferKm > 100))
                throw badRequest("MobileCity-Umlandpuffer muss zwischen 1 und 100 km liegen");
            if (surroundingPopulation != null && (surroundingPopulation < 0
                    || surroundingPopulation > 100_000_000))
                throw badRequest("MobileCity-Umlandbevölkerung muss zwischen 0 und 100 Millionen liegen");
            if (scenario.mobileCity.autoSurrounding && surroundingPopulation != null
                    && surroundingPopulation == 0)
                throw badRequest("MobileCity-Umlandüberschreibung muss positiv sein oder leer bleiben");
            if (hasSurroundingShares && !surroundingRequested)
                throw badRequest("MobileCity-Umlandanteile benötigen eine positive Umlandbevölkerung");
            if (surroundingRequested) {
                if (!scenario.population.externalTrips)
                    throw badRequest("MobileCity-Umlandbevölkerung benötigt aktivierte Außenbeziehungen");
                validateShare(scenario.mobileCity.surroundingEducationTripShare, "Bildungswege");
                validateShare(scenario.mobileCity.surroundingWorkTripShare, "Arbeitswege");
                validateShare(scenario.mobileCity.surroundingShoppingTripShare, "Einkaufswege");
                validateShare(scenario.mobileCity.surroundingLeisureTripShare, "Freizeitwege");
            }
        }
        if (scenario.population.useCensusStructure && !scenario.population.useKitPopulation) {
            throw badRequest("Zensus-Struktur benötigt das KIT-Demografie-Sample");
        }
        if (scenario.logistics == null) {
            throw badRequest("Logistikparameter fehlen");
        }
        Scenario.Region r = scenario.region;
        if (r == null) {
            throw badRequest("Bitte zuerst eine Region auf der Karte festlegen");
        }
        if (!Double.isFinite(r.minLat) || !Double.isFinite(r.minLon)
                || !Double.isFinite(r.maxLat) || !Double.isFinite(r.maxLon)
                || r.minLat < -90 || r.maxLat > 90 || r.minLon < -180 || r.maxLon > 180) {
            throw badRequest("Region enthält ungültige Koordinaten");
        }
        if (r.minLat >= r.maxLat || r.minLon >= r.maxLon) {
            throw badRequest("Ungültige Region (minLat/minLon müssen kleiner als maxLat/maxLon sein)");
        }
        if (!Double.isFinite(scenario.zoning.cellSizeMeters) || scenario.zoning.cellSizeMeters < 100) {
            throw badRequest("Zellgröße muss mindestens 100 m betragen");
        }
        if (scenario.zoning.maxZones < 100
                || scenario.zoning.maxZones > properties.getMaxZonesHardLimit()) {
            throw badRequest("Maximale Zonenanzahl muss zwischen 100 und "
                    + properties.getMaxZonesHardLimit() + " liegen");
        }
        if (!"grid".equals(scenario.zoning.mode)
                && !"epsg3035".equals(scenario.zoning.mode)
                && !"inspire".equals(scenario.zoning.mode)) {
            throw badRequest("Unbekannter Zonierungsmodus");
        }
        String behaviorSource = scenario.population.behaviorSourceEffective();
        if (!BEHAVIOR_SOURCES.contains(behaviorSource)) {
            throw badRequest("Unbekannte Verhaltensquelle");
        }
        if (scenario.targetModels != null
                && scenario.targetModels.stream()
                .anyMatch(target -> target == null || !TARGET_MODELS.contains(target))) {
            throw badRequest("Unbekanntes Zielmodell");
        }
        if (scenario.population.sharedRoadRoutes && !scenario.population.useRealRouting)
            throw badRequest("Gemeinsame Straßenrouten benötigen reales Routing");
        if (scenario.population.roadLocatedActivities && !scenario.population.useRealRouting)
            throw badRequest("Straßennahe Wohn- und Aktivitätsorte benötigen reales Routing");
        validateReturns(scenario);
        if (!java.util.Set.of("RURAL", "PROVINCIAL", "CITYOUTSKIRT", "METROPOLITAN", "CONURBATION")
                .contains(scenario.population.areaType == null ? "" : scenario.population.areaType)) {
            throw badRequest("Ungültiger Raumtyp für die Aktivitätenplanung");
        }
        double samplingFraction = scenario.population.samplingFraction;
        if (!Double.isFinite(samplingFraction) || samplingFraction <= 0 || samplingFraction > 1) {
            throw badRequest("Stichprobenanteil muss größer 0 und höchstens 1 sein");
        }
        try { de.synthesis.generation.pipeline.FreightDemandGenerator.validateDemand(scenario); }
        catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
    }

    private static void validateShare(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1)
            throw badRequest("MobileCity-Umlandanteil für " + label + " muss zwischen 0 und 1 liegen");
    }

    private void validateReturns(Scenario scenario) {
        Scenario.ReturnDemand returns = scenario.logistics.returns;
        if (returns == null) {
            throw badRequest("Retourenparameter fehlen");
        }
        if (!"exogenous-share".equals(returns.model)) {
            throw badRequest("Unbekanntes Retourenmodell");
        }
        double share = returns.shareOfPrivateOrders;
        if (!Double.isFinite(share) || share < 0 || share > 1) {
            throw badRequest("Retourenquote muss zwischen 0 und 1 liegen");
        }
        if (returns.enabled && share <= 0) {
            throw badRequest("Aktivierte Retouren benötigen eine Quote größer 0");
        }
        Scenario.PickupShares pickupShares = returns.pickupShares;
        if (pickupShares == null) {
            throw badRequest("Retouren-Abholortanteile fehlen");
        }
        if (!Double.isFinite(pickupShares.home) || !Double.isFinite(pickupShares.work)
                || pickupShares.home < 0 || pickupShares.work < 0) {
            throw badRequest("Retouren-Abholortanteile müssen endlich und nicht negativ sein");
        }
        if (Math.abs(pickupShares.home + pickupShares.work - 1.0) > 1e-6) {
            throw badRequest("Retouren-Abholortanteile müssen zusammen 1 ergeben");
        }
        if (returns.enabled && !scenario.targets().contains("logitopp")) {
            throw badRequest("Aktivierte Retouren benötigen das Zielmodell logiTopp");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /** Normalized OD CSV; coordinates must already identify sites or directed network boundary connectors. */
    @PostMapping("/{id}/freight")
    public Scenario.Freight uploadFreight(@PathVariable String id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Scenario scenario = get(id);
        if (file.isEmpty() || file.getSize() > 5_000_000) throw badRequest("Güterverkehrs-CSV fehlt oder überschreitet 5 MB");
        try {
            var relations = de.synthesis.generation.pipeline.FreightDemandGenerator.parseCsv(file.getBytes());
            if (relations.isEmpty()) throw badRequest("Güterverkehrs-CSV enthält keine Beziehungen");
            de.synthesis.generation.pipeline.FreightDemandGenerator.validateRelations(relations, scenario.region);
            scenario.freight.relations = relations;
            repository.save(scenario);
            return scenario.freight;
        } catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
        catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Güterverkehrs-Upload fehlgeschlagen", e); }
    }
    @PostMapping("/{id}/freight/sevas")
    public Scenario.Freight uploadSevas(@PathVariable String id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Scenario scenario = get(id);
        if (file.isEmpty() || file.getSize() > 5_000_000) throw badRequest("SEVAS-GeoJSON fehlt oder überschreitet 5 MB");
        try {
            var snapshot = de.synthesis.generation.pipeline.SevasFreightPermissions.parse(file.getBytes());
            scenario.freight.sevasSnapshot = snapshot;
            repository.save(scenario);
            return scenario.freight;
        } catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
        catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "SEVAS-Upload fehlgeschlagen", e); }
    }

}
