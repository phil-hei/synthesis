package de.synthesis.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario aggregate, persisted as JSON. Public fields keep the MVP lean;
 * field initializers double as the default values offered to the frontend.
 */
public class Scenario {

    public String id;
    public String name = "Neues Szenario";
    public Instant createdAt;
    /** Null until the user draws/sets a region on the map. */
    public Region region;
    public Zoning zoning = new Zoning();
    public Population population = new Population();
    public Logistics logistics = new Logistics();
    /** Versioned vehicle catalogue; only explicit sumoTraffic allocations affect traffic. */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = de.synthesis.vehicle.VehicleJson.Deserializer.class)
    public de.synthesis.vehicle.VehicleConfiguration vehicleConfiguration = de.synthesis.vehicle.VehicleConfiguration.empty();
    /** Common year for population and MobileCity contributions. */
    public int referenceYear = 2026;
    public MobileCity mobileCity = new MobileCity();

    public static class MobileCity {
        /** Experimental merge into a locally prepared, hash-pinned study-area template. */
        public boolean useTemplate = false;
        public String templateId = "tramigo-v2";
        /** Regional private BEV shares from the pinned KBA reference and BKG boundaries. */
        public boolean useRegionalFleet = false;
        /** Public charging points from the pinned BNetzA register snapshot. */
        public boolean useRegionalCharging = false;
        /** Explicit charging-capacity fallback; null means unknown, zero is valid. */
        public Double chargingPointsPer1000;
        /** Explicit carsharing fleet assumption distributed by district population. */
        public Double carsharingVehiclesPer1000;
        /** Explicit study-area share of people eligible to use carsharing. */
        public Double carsharingMemberShare;
        /** Resolve surrounding districts and their resident population from a census buffer. */
        public boolean autoSurrounding = false;
        /** Width of the census buffer outside the selected study-area rectangle, in kilometres. */
        public double surroundingBufferKm = 10.0;
        /** Optional resident population represented by the eight surrounding destination anchors. */
        public Integer surroundingPopulation;
        /** Share of education trips made by surrounding residents into the study area. */
        public Double surroundingEducationTripShare;
        /** Share of work trips made by surrounding residents into the study area. */
        public Double surroundingWorkTripShare;
        /** Share of shopping trips made by surrounding residents into the study area. */
        public Double surroundingShoppingTripShare;
        /** Share of leisure trips made by surrounding residents into the study area. */
        public Double surroundingLeisureTripShare;
        /** Complete regional baseline using an explicitly identified, uncalibrated model profile. */
        public boolean regionalExport = false;
        /** Run the exported complete package through the native MobileCity ConfigTool. */
        public boolean validateNative = false;
        public int maxDistricts = 100;
        /** Permit the disclosed matrix-based transit assumption when no GTFS feed resolves. */
        public boolean allowSyntheticTransitFallback = true;
        public double transitVehiclesPer1000 = 1.0;
        public double settlementAreaShare = 0.5;
        /** Explicit BEV fallback for uncovered zones; null means unknown, zero is valid. */
        public Double electricShare;
    }

    /**
     * Nachfragesimulation und Weiterverarbeitung (Mehrfachauswahl): {@code logitopp}
     * (Personen+Pakete, Standard), {@code mobilecity} (unabhängiger Takomat-Export) und
     * {@code sumo} (nachgelagerte Verkehrssimulation; impliziert logiTopp).
     * Leer/null = logitopp + mobilecity.
     */
    public List<String> targetModels = new ArrayList<>(List.of("logitopp", "mobilecity"));

    /** Selected target models with a sensible default when unset (backward compatibility). */
    public List<String> targets() {
        List<String> selected = targetModels == null || targetModels.isEmpty()
                ? new ArrayList<>(List.of("logitopp", "mobilecity"))
                : new ArrayList<>(targetModels);
        if (selected.contains("sumo") && !selected.contains("logitopp")) {
            selected.add(0, "logitopp");
        }
        return selected;
    }

    /** Persistiert harte Pipeline-Abhängigkeiten auch für API-Clients außerhalb des Studios. */
    public void normalizeTargetDependencies() {
        targetModels = new ArrayList<>(targets());
    }

    public static class Region {
        public double minLat;
        public double minLon;
        public double maxLat;
        public double maxLon;
    }

    public static class Zoning {
        /** Edge length of a square grid cell in meters. */
        public double cellSizeMeters = 100;
        /** Scenario-specific upper bound; downstream OD matrices grow quadratically. */
        public int maxZones = 600;
        /**
         * Zonierungs-Modus: {@code "grid"} = metrisches Bbox-Raster (global, Default) |
         * {@code "epsg3035"} = frei auflösbares, europaweit ausgerichtetes LAEA-Gitter |
         * {@code "inspire"} = EPSG:3035/Zensus-Gitter (europäisch, flächengleich, Zensus-aligned).
         */
        public String mode = "grid";
    }

    public static class Population {
        /** Total persons in the region; null = estimate via personsPerKm2. */
        public Integer totalPersons;
        public double personsPerKm2 = 1500;
        /** Fraction of the regional population synthesized and exported to downstream models. */
        public double samplingFraction = 1.0;
        public long seed = 42;
        /**
         * Legacy compatibility field. OSM POI/place data is now selected automatically
         * (scenario snapshot, live Overpass, synthetic fallback) and this value is ignored.
         */
        @Deprecated
        public boolean useOsmData = true;
        /** Build real travel-time/distance matrices via GraphHopper (OSM road network); falls back to beeline. */
        public boolean useRealRouting = true;
        /** Add external destination zones around the area so a share of trips leaves it (realistic trip lengths). */
        public boolean externalTrips = true;
        /**
         * Achse A – Verhaltensquelle für die Aktivitäten-/Verhaltensmodellierung:
         * {@code actitopp} (kalibriertes KIT-Modell, Standard), {@code gta} (LLM-Agenten,
         * experimentell – Fallback auf actiTopp), {@code template} (einfacher Generator).
         */
        public String behaviorSource = "actitopp";
        @JsonIgnore
        private boolean behaviorSourceConfigured;
        /**
         * GTA 3d: optionaler Was-wäre-wenn-Kontext (Policy/Technologie, Freitext), den die
         * LLM-Agenten bei der Tagesplanung berücksichtigen – z. B. „Die Innenstadt ist autofrei"
         * oder „ÖPNV ist kostenlos". Leer = normales Verhalten.
         */
        public String gtaWhatIf = "";
        /** @deprecated durch {@link #behaviorSource} ersetzt; nur für Rückwärtskompatibilität gelesen. */
        @Deprecated
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public boolean useActitopp = true;
        /** Draw households from a KIT-synthesized demographic sample (realistic compositions) instead of ad-hoc draws. */
        public boolean useKitPopulation = true;
        /** Experimental regional weighting of KIT households using census-2022 age/size margins. */
        public boolean useCensusStructure = false;
        /** Iterative ASC-Kalibrierung der Moduswahl gegen MiD-Modal-Split (teuer: mehrere Sim-Läufe). */
        public boolean calibrateModeChoice = false;
        /**
         * Distanz-Kalibrierung der Zielwahl (experimentell): passt b_intrazonal iterativ an,
         * bis der simulierte &lt;1-km-Wegeanteil den Zielwert trifft (mehrere logiTopp-Läufe).
         * Benötigt Ziel logiTopp. Kalibriert die Verteilungs-Form, nicht den absoluten Ø.
         */
        public boolean calibrateDistance = false;
        /**
         * Experimentelle Korrektur des Zielwahl-Distanz-Abklingens: addiert den über
         * Seeds 42/43/44 bestätigten Term +0,06 auf die fünf b_tt_*-Reisezeit-Betas.
         * Benötigt Ziel logiTopp; der Projekt-Standard wird nicht verändert.
         */
        public boolean correctDistanceDecay = false;
        /**
         * Gemeinsame Modus-Distanz-Kalibrierung (experimentell): regelt das globale
         * ÖV-Niveau über asc_put und die Fernweg-Schräge über b_tt_put getrennt gegen
         * MiD 2017. Mehrere logiTopp-Läufe; benötigt genügend Wege ab 20 km.
         */
        public boolean calibrateModeDistance = false;
        /**
         * Achse A (GTA 3c, experimentell): zusätzlich zur logiTopp-Moduswahl (MNL) eine
         * LLM-gestützte Moduswahl auf den simulierten Trips, geerdet in Reisezeiten je Modus –
         * liefert einen alternativen Modal-Split zum Vergleich mit MNL/MiD. Benötigt logiTopp + LLM.
         */
        public boolean llmModeChoice = false;
        /**
         * Netz-Feedback (experimentell): staubelastete SUMO-Reisezeiten fließen in die
         * Pkw-Matrix zurück und logiTopp entscheidet einmal neu (benötigt logiTopp + SUMO).
         */
        public boolean sumoFeedback = false;
        /**
         * @deprecated Kompatibilitätsfeld. Bei SUMO wird ÖV automatisch versucht, sobald ein
         * regionaler GTFS-Feed und die Transit-Werkzeuge verfügbar sind.
         */
        @Deprecated
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public boolean publicTransport = false;
        /** Optionaler hochgeladener GTFS-Feed (Pfad); Fallback, falls kein Auto-Fetch greift. */
        public String gtfsUploadPath;
        /** Ziel-Modal-Split der Kalibrierung (Anteile je Modus); Default = MiD 2017 bundesweit, editierbar/regional. */
        public Map<String, Double> calibrationTarget = defaultModalSplit();
        public double carsPerHousehold = 1.0;
        /** Explicit regional assumption for actiTopp; not inferred from census. */
        public String areaType = "PROVINCIAL";

        private static Map<String, Double> defaultModalSplit() {
            Map<String, Double> split = new LinkedHashMap<>();
            split.put("pedestrian", 0.22);
            split.put("bike", 0.11);
            split.put("car", 0.43);
            split.put("passenger", 0.14);
            split.put("publicTransport", 0.10);
            return split;
        }

        /** Effective behavior source: explicit field wins, else derived from the legacy flag. */
        public String behaviorSourceEffective() {
            if (behaviorSource != null && !behaviorSource.isBlank()
                    && (behaviorSourceConfigured || !"actitopp".equals(behaviorSource))) {
                return behaviorSource;
            }
            return useActitopp ? "actitopp" : "template";
        }

        /** Merkt, ob ein persistiertes Szenario bereits das neue Feld enthält. */
        @JsonSetter("behaviorSource")
        public void setBehaviorSource(String behaviorSource) {
            this.behaviorSource = behaviorSource;
            this.behaviorSourceConfigured = true;
        }
    }

    public static class Logistics {
        public double parcelsPerPersonWeek = 0.75;
        public double parcelStdDev = 0.5;
        public int maxParcelsPerPerson = 10;
        public double shareHome = 0.8;
        public double shareWork = 0.1;
        public double sharePackstation = 0.1;
        /** Experimentelle Kundenretouren als zusätzliche logiTopp-Abholnachfrage. */
        public ReturnDemand returns = new ReturnDemand();
        public List<Depot> depots = new ArrayList<>();
        public List<DeliveryService> services = defaultServices();

        private static List<DeliveryService> defaultServices() {
            List<DeliveryService> services = new ArrayList<>();
            services.add(new DeliveryService("DHL", 0.45));
            services.add(new DeliveryService("Hermes", 0.20));
            services.add(new DeliveryService("DPD", 0.15));
            services.add(new DeliveryService("GLS", 0.10));
            services.add(new DeliveryService("UPS", 0.10));
            return services;
        }
    }

    public static class ReturnDemand {
        /** Default aus: bestehende Szenarien erzeugen ausschließlich Zustellaufträge. */
        public boolean enabled = false;
        /** MVP-Modell: exogene Quote auf die unverändert erzeugten privaten Zustellaufträge. */
        public String model = "exogenous-share";
        /** Zusätzliche Pickup-Aufträge relativ zur Zahl der privaten Zustellaufträge. */
        public double shareOfPrivateOrders = 0.0;
        /** Abholorte im MVP; Packstationen sind bewusst noch kein Retourenkanal. */
        public PickupShares pickupShares = new PickupShares();
    }

    public static class PickupShares {
        public double home = 1.0;
        public double work = 0.0;
    }

    public static class Depot {
        public String name = "Depot";
        public double lat;
        public double lon;
        public int employees = 20;
        public double share = 1.0;
        /** TRUCK | CARGO_BIKE */
        public String vehicleType = "TRUCK";

        public Depot() {
        }

        public Depot(String name, double lat, double lon, int employees, double share, String vehicleType) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.employees = employees;
            this.share = share;
            this.vehicleType = vehicleType;
        }
    }

    public static class DeliveryService {
        public String name;
        public double share;

        public DeliveryService() {
        }

        public DeliveryService(String name, double share) {
            this.name = name;
            this.share = share;
        }
    }
}
