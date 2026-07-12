package de.synthesis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synthesis")
public class SynthesisProperties {

    private String dataDir = "data";
    private int maxZones = 600;
    private Overpass overpass = new Overpass();
    private Simulation simulation = new Simulation();
    private Actitopp actitopp = new Actitopp();
    private Sumo sumo = new Sumo();
    private Transit transit = new Transit();
    private Llm llm = new Llm();

    /**
     * Local LLM behaviour source (Achse A, GTA-Stil): generative activity agents via an
     * OpenAI-compatible endpoint (e.g. Ollama). Experimental; falls back to actiTopp/Template.
     */
    public static class Llm {
        private boolean enabled = true;
        /** OpenAI-kompatibler Basis-Endpunkt (Ollama: http://localhost:11434/v1). */
        private String endpoint = "http://localhost:11434/v1";
        private String model = "qwen2.5:7b-instruct";
        private double temperature = 0.7;
        private int timeoutSeconds = 120;
        /** Obergrenze distinkter Personas (LLM-Calls); je Persona ~30–40 s auf einem M1. */
        private int maxPersonas = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxPersonas() { return maxPersonas; }
        public void setMaxPersonas(int maxPersonas) { this.maxPersonas = maxPersonas; }
    }

    /** SUMO microscopic traffic simulation (Zielmodell Achse B). */
    public static class Sumo {
        private static final String MAC_FRAMEWORK =
                "/Library/Frameworks/EclipseSUMO.framework/Versions/1.26.0/EclipseSUMO";

        private boolean enabled = true;
        /** Verzeichnis mit sumo/netconvert/duarouter; Default aus $SUMO_HOME abgeleitet. */
        private String binDir = defaultBinDir();
        /** SUMO_HOME (Daten/Tools), i. d. R. .../share/sumo; Default aus $SUMO_HOME. */
        private String sumoHome = defaultSumoHome();

        private static String defaultSumoHome() {
            String env = System.getenv("SUMO_HOME");
            return env != null && !env.isBlank() ? env : MAC_FRAMEWORK + "/share/sumo";
        }

        /** $SUMO_HOME/bin (Standard-Layout) oder $SUMO_HOME/../../bin (macOS-Framework). */
        private static String defaultBinDir() {
            String env = System.getenv("SUMO_HOME");
            if (env != null && !env.isBlank()) {
                java.nio.file.Path home = java.nio.file.Path.of(env);
                java.nio.file.Path direct = home.resolve("bin");
                if (java.nio.file.Files.exists(direct.resolve("sumo"))) {
                    return direct.toString();
                }
                java.nio.file.Path parent = home.getParent() != null ? home.getParent().getParent() : null;
                if (parent != null && java.nio.file.Files.exists(parent.resolve("bin").resolve("sumo"))) {
                    return parent.resolve("bin").toString();
                }
                return direct.toString();
            }
            return MAC_FRAMEWORK + "/bin";
        }
        /** Simulierter Tag (0 = Montag) und Obergrenze der Fahrzeug-Trips (Performance). */
        private int day = 0;
        private int maxTrips = 4000;
        private int timeoutMinutes = 15;
        /** Zonen-Obergrenze fürs Netz-Feedback (OD-Routing ist n²). */
        private int feedbackMaxZones = 150;
        /** Max. Gleichgewichts-Iterationen (je Iteration ein logiTopp- + SUMO-Lauf). */
        private int feedbackIterations = 3;
        /** Konvergenz: Abbruch, wenn die mittlere Faktor-Änderung darunter liegt. */
        private double feedbackTolerance = 0.01;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBinDir() { return binDir; }
        public void setBinDir(String binDir) { this.binDir = binDir; }
        public String getSumoHome() { return sumoHome; }
        public void setSumoHome(String sumoHome) { this.sumoHome = sumoHome; }
        public int getDay() { return day; }
        public void setDay(int day) { this.day = day; }
        public int getMaxTrips() { return maxTrips; }
        public void setMaxTrips(int maxTrips) { this.maxTrips = maxTrips; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getFeedbackMaxZones() { return feedbackMaxZones; }
        public void setFeedbackMaxZones(int feedbackMaxZones) { this.feedbackMaxZones = feedbackMaxZones; }
        public int getFeedbackIterations() { return feedbackIterations; }
        public void setFeedbackIterations(int feedbackIterations) { this.feedbackIterations = feedbackIterations; }
        public double getFeedbackTolerance() { return feedbackTolerance; }
        public void setFeedbackTolerance(double feedbackTolerance) { this.feedbackTolerance = feedbackTolerance; }
    }

    /** KIT actiTopp (actitoppNG) activity scheduling, run as a Gradle task. */
    public static class Actitopp {
        private boolean enabled = true;
        /** Pfad zum actitoppNG-Projekt (gradlew + runSynthesis). */
        private String projectDir = "../validation/actitoppNG";
        private int timeoutMinutes = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProjectDir() { return projectDir; }
        public void setProjectDir(String projectDir) { this.projectDir = projectDir; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }

    /** logiTopp-Lauf (Pipeline-Stufe 10) im Legacy-Projekt mobitopp-rastatt. */
    public static class Simulation {
        private boolean enabled = true;
        /** Pfad zum mobitopp-rastatt-Projekt (gradlew + runDeliverySynthesis). */
        private String projectDir = "../validation/mobitopp-rastatt";
        private int timeoutMinutes = 30;
        /** Max. Iterationen der Modal-Split-Kalibrierung (je Iteration ein Sim-Lauf). */
        private int calibrationIterations = 8;
        /** Abbruch-Toleranz (RMSE der Modus-Anteile). */
        private double calibrationTolerance = 0.02;
        /**
         * Ziel-Anteil der Wege &lt; 1 km für die Distanz-Kalibrierung (Zielwahl, b_intrazonal).
         * NÄHERUNGSWERT/tunbar — kein amtlicher MiD-Wert; ersetzt die Nahdistanz-Überkonzentration
         * (Rastatt roh ~0,25). Kalibriert die Verteilungs-FORM, nicht den absoluten Ø (docs/06 „Phase 1b").
         */
        private double distanceCalibrationTargetNearShare = 0.14;
        /** Abbruch-Toleranz (|Ist − Ziel| des &lt;1-km-Anteils). */
        private double distanceCalibrationTolerance = 0.02;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProjectDir() { return projectDir; }
        public void setProjectDir(String projectDir) { this.projectDir = projectDir; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getCalibrationIterations() { return calibrationIterations; }
        public void setCalibrationIterations(int n) { this.calibrationIterations = n; }
        public double getCalibrationTolerance() { return calibrationTolerance; }
        public void setCalibrationTolerance(double t) { this.calibrationTolerance = t; }
        public double getDistanceCalibrationTargetNearShare() { return distanceCalibrationTargetNearShare; }
        public void setDistanceCalibrationTargetNearShare(double s) { this.distanceCalibrationTargetNearShare = s; }
        public double getDistanceCalibrationTolerance() { return distanceCalibrationTolerance; }
        public void setDistanceCalibrationTolerance(double t) { this.distanceCalibrationTolerance = t; }
    }

    public static class Overpass {
        private String endpoint = "https://overpass-api.de/api/interpreter";
        /** Ausweich-Endpunkte, falls der primäre Server rate-limited/überlastet ist (504/429). */
        private java.util.List<String> mirrors = new java.util.ArrayList<>(java.util.List.of(
                "https://overpass.kumi.systems/api/interpreter",
                "https://overpass.private.coffee/api/interpreter"));
        private int timeoutSeconds = 30;
        /**
         * Eigenes, großzügiges Zeitbudget für den schweren Straßennetz-Abruf (GraphHopper-Routing):
         * die {@code way["highway"]}+recurse-Extraktion überschreitet das allgemeine 30-s-Budget
         * unter Overpass-Last regelmäßig → Timeout auf allen Endpunkten → Luftlinien-Fallback.
         */
        private int roadNetworkTimeoutSeconds = 180;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public java.util.List<String> getMirrors() { return mirrors; }
        public void setMirrors(java.util.List<String> mirrors) { this.mirrors = mirrors; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getRoadNetworkTimeoutSeconds() { return roadNetworkTimeoutSeconds; }
        public void setRoadNetworkTimeoutSeconds(int s) { this.roadNetworkTimeoutSeconds = s; }

        /** Primärer Endpunkt zuerst, dann die Mirrors (deduped). */
        public java.util.List<String> allEndpoints() {
            java.util.List<String> all = new java.util.ArrayList<>();
            all.add(endpoint);
            if (mirrors != null) {
                for (String m : mirrors) {
                    if (m != null && !m.isBlank() && !all.contains(m)) {
                        all.add(m);
                    }
                }
            }
            return all;
        }
    }

    /**
     * ÖV-Zielmodell (Achse B, Phase C): GTFS → gtfs2pt.py → SUMO-Fahrplan-Infrastruktur.
     * Braucht ein Python-venv mit pandas/rtree/pyproj (gekapselt, Stage SKIPPED wenn fehlt).
     * GTFS-Quelle: Auto-Fetch aus der Feed-Registry (nach Region) mit Upload-Fallback.
     */
    public static class Transit {
        private boolean enabled = true;
        /** Python-Interpreter eines venv mit pandas/rtree/pyproj; leer = ÖV-Stage nicht verfügbar. */
        private String venvPython = "";
        /** gtfs2pt.py; Default aus $SUMO_HOME/tools abgeleitet. */
        private String gtfs2pt = defaultGtfs2pt();
        /** Auto-Fetch-Feeds (Verbund-GTFS mit Abdeckungs-Bbox). */
        private java.util.List<Feed> feeds = new java.util.ArrayList<>(java.util.List.of(Feed.kvv()));
        /** Obergrenze für den Auto-Fetch-Download (MB); größere Feeds → Upload-Empfehlung. */
        private int maxFeedMb = 200;
        /** Zeitfenster (Stunden) für den Fahrplan-Zuschnitt – begrenzt die gtfs2pt-Laufzeit. */
        private int windowStartHour = 6;
        private int windowEndHour = 10;
        /** gtfs2pt-Map-Matching (tracemapper) ist langsam → großzügiger Timeout, Ergebnis wird gecacht. */
        private int timeoutMinutes = 25;
        /** gtfs2pt-Modi (OSM-Namen). Aktuell Bus (Netz ist road-only; Rail/Tram s. docs). */
        private String modes = "bus";

        private static String defaultGtfs2pt() {
            String env = System.getenv("SUMO_HOME");
            String home = env != null && !env.isBlank()
                    ? env : Sumo.MAC_FRAMEWORK + "/share/sumo";
            return java.nio.file.Path.of(home, "tools", "import", "gtfs", "gtfs2pt.py").toString();
        }

        /** Ein Verbund-GTFS-Feed mit der Region, die er abdeckt. */
        public static class Feed {
            private String name;
            private String url;
            private double minLat;
            private double minLon;
            private double maxLat;
            private double maxLon;

            public Feed() {
            }

            public Feed(String name, String url, double minLat, double minLon,
                        double maxLat, double maxLon) {
                this.name = name;
                this.url = url;
                this.minLat = minLat;
                this.minLon = minLon;
                this.maxLat = maxLat;
                this.maxLon = maxLon;
            }

            /** Karlsruher Verkehrsverbund (deckt u. a. Rastatt) – NVBW/MobiData-BW, ODbL. */
            static Feed kvv() {
                return new Feed("kvv",
                        "https://www.nvbw.de/fileadmin/user_upload/service/open_data/"
                                + "fahrplandaten_mit_liniennetz/kvv.zip",
                        48.55, 7.95, 49.25, 8.95);
            }

            /** Enthält die Feed-Bbox das Regions-Zentrum? */
            public boolean covers(double lat, double lon) {
                return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
            }

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public double getMinLat() { return minLat; }
            public void setMinLat(double v) { this.minLat = v; }
            public double getMinLon() { return minLon; }
            public void setMinLon(double v) { this.minLon = v; }
            public double getMaxLat() { return maxLat; }
            public void setMaxLat(double v) { this.maxLat = v; }
            public double getMaxLon() { return maxLon; }
            public void setMaxLon(double v) { this.maxLon = v; }
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getVenvPython() { return venvPython; }
        public void setVenvPython(String venvPython) { this.venvPython = venvPython; }
        public String getGtfs2pt() { return gtfs2pt; }
        public void setGtfs2pt(String gtfs2pt) { this.gtfs2pt = gtfs2pt; }
        public java.util.List<Feed> getFeeds() { return feeds; }
        public void setFeeds(java.util.List<Feed> feeds) { this.feeds = feeds; }
        public int getMaxFeedMb() { return maxFeedMb; }
        public void setMaxFeedMb(int maxFeedMb) { this.maxFeedMb = maxFeedMb; }
        public int getWindowStartHour() { return windowStartHour; }
        public void setWindowStartHour(int h) { this.windowStartHour = h; }
        public int getWindowEndHour() { return windowEndHour; }
        public void setWindowEndHour(int h) { this.windowEndHour = h; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public String getModes() { return modes; }
        public void setModes(String modes) { this.modes = modes; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxZones() { return maxZones; }
    public void setMaxZones(int maxZones) { this.maxZones = maxZones; }
    public Overpass getOverpass() { return overpass; }
    public void setOverpass(Overpass overpass) { this.overpass = overpass; }
    public Simulation getSimulation() { return simulation; }
    public void setSimulation(Simulation simulation) { this.simulation = simulation; }
    public Actitopp getActitopp() { return actitopp; }
    public void setActitopp(Actitopp actitopp) { this.actitopp = actitopp; }
    public Sumo getSumo() { return sumo; }
    public void setSumo(Sumo sumo) { this.sumo = sumo; }
    public Transit getTransit() { return transit; }
    public void setTransit(Transit transit) { this.transit = transit; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
}
