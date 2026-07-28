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
        /**
         * Zonierungs-Modus: {@code "grid"} = metrisches Bbox-Raster (global, Default) |
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
        /** Iterative ASC-Kalibrierung der Moduswahl gegen MiD-Modal-Split (teuer: mehrere Sim-Läufe). */
        public boolean calibrateModeChoice = false;
        /**
         * Distanz-Kalibrierung der Zielwahl (experimentell): passt b_intrazonal iterativ an,
         * bis der simulierte &lt;1-km-Wegeanteil den Zielwert trifft (mehrere logiTopp-Läufe).
         * Benötigt Ziel logiTopp. Kalibriert die Verteilungs-Form, nicht den absoluten Ø.
         */
        public boolean calibrateDistance = false;
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
         * ÖV in SUMO (experimentell, Phase C): GTFS → gtfs2pt.py → Bus-Fahrplan; Nachfrage-
         * Personen fahren intermodal mit. Braucht Ziel SUMO + ein Python-venv; GTFS per
         * Auto-Fetch (nach Region) mit Upload-Fallback. gtfs2pt-Mapping ist langsam (gecacht).
         */
        public boolean publicTransport = false;
        /** Optionaler hochgeladener GTFS-Feed (Pfad); Fallback, falls kein Auto-Fetch greift. */
        public String gtfsUploadPath;
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
