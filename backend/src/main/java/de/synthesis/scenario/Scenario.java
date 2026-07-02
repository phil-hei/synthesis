package de.synthesis.scenario;

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

    /**
     * Achse B – Zielmodelle, welche die erzeugte Nachfrage verwerten (Mehrfachauswahl):
     * {@code logitopp} (Personen+Pakete, Standard), {@code mobilecity} (Takomat-Export),
     * {@code sumo} (mikroskopischer Verkehr, geplant). Leer/null = logitopp + mobilecity.
     */
    public List<String> targetModels = new ArrayList<>(List.of("logitopp", "mobilecity"));

    /** Selected target models with a sensible default when unset (backward compatibility). */
    public List<String> targets() {
        return targetModels == null || targetModels.isEmpty()
                ? List.of("logitopp", "mobilecity") : targetModels;
    }

    public static class Region {
        public double minLat;
        public double minLon;
        public double maxLat;
        public double maxLon;
    }

    public static class Zoning {
        /** Edge length of a square grid cell in meters. */
        public double cellSizeMeters = 1000;
    }

    public static class Population {
        /** Total persons in the region; null = estimate via personsPerKm2. */
        public Integer totalPersons;
        public double personsPerKm2 = 1500;
        /** Fraction of the population that is synthesized (mobiTopp fractionOfPopulation). */
        public double samplingFraction = 1.0;
        public long seed = 42;
        /** Fetch POIs from OSM (Overpass) for attractivities; falls back to synthetic on failure. */
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
        /** @deprecated durch {@link #behaviorSource} ersetzt; nur für Rückwärtskompatibilität gelesen. */
        @Deprecated
        public boolean useActitopp = true;
        /** Draw households from a KIT-synthesized demographic sample (realistic compositions) instead of ad-hoc draws. */
        public boolean useKitPopulation = true;
        /** Iterative ASC-Kalibrierung der Moduswahl gegen MiD-Modal-Split (teuer: mehrere Sim-Läufe). */
        public boolean calibrateModeChoice = false;
        /**
         * Achse A (GTA 3c, experimentell): zusätzlich zur logiTopp-Moduswahl (MNL) eine
         * LLM-gestützte Moduswahl auf den simulierten Trips, geerdet in Reisezeiten je Modus –
         * liefert einen alternativen Modal-Split zum Vergleich mit MNL/MiD. Benötigt logiTopp + LLM.
         */
        public boolean llmModeChoice = false;
        /** Ziel-Modal-Split der Kalibrierung (Anteile je Modus); Default = MiD 2017 bundesweit, editierbar/regional. */
        public Map<String, Double> calibrationTarget = defaultModalSplit();
        public double carsPerHousehold = 1.0;

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
            if (behaviorSource != null && !behaviorSource.isBlank()) {
                return behaviorSource;
            }
            return useActitopp ? "actitopp" : "template";
        }
    }

    public static class Logistics {
        public double parcelsPerPersonWeek = 0.75;
        public double parcelStdDev = 0.5;
        public int maxParcelsPerPerson = 10;
        public double shareHome = 0.8;
        public double shareWork = 0.1;
        public double sharePackstation = 0.1;
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
