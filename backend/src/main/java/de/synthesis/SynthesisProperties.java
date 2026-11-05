package de.synthesis;

import de.synthesis.reference.Mid2017;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synthesis")
public class SynthesisProperties {

    private String dataDir = "data";
    /** Default for new scenarios; individual scenarios may choose a lower or higher limit. */
    private int maxZones = 600;
    /** Safety ceiling because several downstream matrices grow quadratically with the zone count. */
    private int maxZonesHardLimit = 1200;
    private Overpass overpass = new Overpass();
    private Census census = new Census();
    private Simulation simulation = new Simulation();
    private Actitopp actitopp = new Actitopp();
    private Sumo sumo = new Sumo();
    private Transit transit = new Transit();
    private MobileCityValidation mobileCityValidation = new MobileCityValidation();
    private Llm llm = new Llm();
    private Auth auth = new Auth();

    /**
     * Anmeldung der Plattform: Cookie-Session, Benutzerkonten unter {@code <data-dir>/users.json}.
     * Beim ersten Start ohne Konten wird ein Administrator mit Startpasswort angelegt.
     */
    public static class Auth {
        private boolean enabled = true;
        /** Erlaubte Browser-Herkünfte für Cross-Origin-Aufrufe (Dev-Server); gleiche Herkunft braucht nichts. */
        private java.util.List<String> allowedOrigins = new java.util.ArrayList<>(java.util.List.of("http://localhost:4300"));
        private String bootstrapAdmin = "admin";
        /** Mindestlänge neuer Passwörter. */
        private int minPasswordLength = 8;
        /** Fehlversuche je Benutzername und Herkunft, bevor der Login zeitweise gesperrt wird. */
        private int maxLoginFailures = 10;
        /** Dauer der Sperre nach zu vielen Fehlversuchen. */
        private int lockoutMinutes = 15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(java.util.List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public String getBootstrapAdmin() { return bootstrapAdmin; }
        public void setBootstrapAdmin(String bootstrapAdmin) { this.bootstrapAdmin = bootstrapAdmin; }
        public int getMinPasswordLength() { return minPasswordLength; }
        public void setMinPasswordLength(int minPasswordLength) { this.minPasswordLength = minPasswordLength; }
        public int getMaxLoginFailures() { return maxLoginFailures; }
        public void setMaxLoginFailures(int maxLoginFailures) { this.maxLoginFailures = maxLoginFailures; }
        public int getLockoutMinutes() { return lockoutMinutes; }
        public void setLockoutMinutes(int lockoutMinutes) { this.lockoutMinutes = lockoutMinutes; }
    }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    private Frontend frontend = new Frontend();

    /** Auslieferung des Angular-Builds durch das Backend (Plattform-Betrieb ohne eigenen Webserver). */
    public static class Frontend {
        /** Verzeichnis mit index.html des Builds; leer = {@code classpath:/static/} (im Jar gebündelt). */
        private String dir = "";

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public Frontend getFrontend() { return frontend; }
    public void setFrontend(Frontend frontend) { this.frontend = frontend; }

    /** Optional native validation of a complete MobileCity import package. */
    public static class MobileCityValidation {
        private boolean enabled = true;
        private String python = "python3";
        private String script = "../scripts/validate-mobilecity.py";
        private String dotnet = "../validation/tools/dotnet-8.0.425/dotnet";
        private String toolSource = "../validation/mobilecity/ConfigTool";
        private String nugetCache = "../.runtime/nuget";
        private int timeoutMinutes = 20;
        /** Entire eleven-year horizon emitted by the regional baseline. */
        private int years = 11;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPython() { return python; }
        public void setPython(String python) { this.python = python; }
        public String getScript() { return script; }
        public void setScript(String script) { this.script = script; }
        public String getDotnet() { return dotnet; }
        public void setDotnet(String dotnet) { this.dotnet = dotnet; }
        public String getToolSource() { return toolSource; }
        public void setToolSource(String toolSource) { this.toolSource = toolSource; }
        public String getNugetCache() { return nugetCache; }
        public void setNugetCache(String nugetCache) { this.nugetCache = nugetCache; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getYears() { return years; }
        public void setYears(int years) { this.years = years; }
    }

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
        /**
         * macOS-Framework über die versionsunabhängigen Symlinks des Installers
         * (EclipseSUMO -> Versions/Current/EclipseSUMO). Bewusst ohne Versionsnummer,
         * damit ein SUMO-Update den Pfad nicht bricht.
         */
        private static final String MAC_FRAMEWORK =
                "/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO";

        private boolean enabled = true;
        /** Verzeichnis mit sumo/netconvert/duarouter; Default aus $SUMO_HOME abgeleitet. */
        private String binDir = defaultBinDir();
        /** SUMO_HOME (Daten/Tools), i. d. R. .../share/sumo; Default aus $SUMO_HOME. */
        private String sumoHome = defaultSumoHome();

        private static String defaultSumoHome() {
            String env = System.getenv("SUMO_HOME");
            return env != null && !env.isBlank()
                    ? env
                    : java.nio.file.Path.of(sumoRoot(), "share", "sumo").toString();
        }

        /**
         * SUMO-Wurzelverzeichnis (enthält bin/ und share/sumo/), ohne feste Version:
         * erst ein sumo auf dem PATH — das deckt Linux-Paketinstallationen und Homebrew
         * mit ab —, sonst das macOS-Framework. Nur relevant, wenn $SUMO_HOME fehlt.
         */
        private static String sumoRoot() {
            return rootOf(binaryOnPath(System.getenv("PATH"), "sumo"));
        }

        /** {@code <root>/bin/sumo} -> {@code <root>}; ohne brauchbaren Treffer das macOS-Framework. */
        static String rootOf(java.nio.file.Path sumoBinary) {
            if (sumoBinary != null && sumoBinary.getParent() != null
                    && sumoBinary.getParent().getParent() != null) {
                return sumoBinary.getParent().getParent().toString();
            }
            return MAC_FRAMEWORK;
        }

        /** Erster ausführbarer Treffer für {@code name} in {@code pathEnv}, Symlinks aufgelöst; sonst null. */
        static java.nio.file.Path binaryOnPath(String pathEnv, String name) {
            if (pathEnv == null || pathEnv.isBlank()) {
                return null;
            }
            for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                java.nio.file.Path candidate = java.nio.file.Path.of(entry, name);
                if (java.nio.file.Files.isExecutable(candidate)) {
                    try {
                        return candidate.toRealPath();
                    } catch (java.io.IOException e) {
                        return candidate.toAbsolutePath();
                    }
                }
            }
            return null;
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
            return java.nio.file.Path.of(sumoRoot(), "bin").toString();
        }
        /** Simulierter Tag (0 = Montag) und Obergrenze der Fahrzeug-Trips (Performance). */
        private int day = 0;
        /** Negative Werte: unbegrenzt; 0: keine Personenwege; positiv: Testlimit je Modus. */
        private int maxTrips = -1;
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

    /** KIT actiTopp (actitoppNG) activity scheduling via a separate SyntheSis adapter. */
    public static class Actitopp {
        private boolean enabled = true;
        /** Pfad zur installDist-Ausgabe des separaten SyntheSis-Adapters. */
        private String projectDir = "../adapters/actitopp/build/install/synthesis-actitopp-adapter";
        private int timeoutMinutes = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProjectDir() { return projectDir; }
        public void setProjectDir(String projectDir) { this.projectDir = projectDir; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }

    /** logiTopp-Lauf (Pipeline-Stufe 12) im separaten SyntheSis-Adapter. */
    public static class Simulation {
        private boolean enabled = true;
        /** Pfad zur vorgebauten logiTopp-Adapter-Distribution. */
        private String projectDir = "../adapters/logitopp/build/install/synthesis-logitopp-adapter";
        private int timeoutMinutes = 30;
        /** Max. Iterationen der Modal-Split-Kalibrierung (je Iteration ein Sim-Lauf). */
        private int calibrationIterations = 8;
        /** Abbruch-Toleranz (RMSE der Modus-Anteile). */
        private double calibrationTolerance = 0.02;
        /**
         * Ziel-Anteil der Wege &lt; 1 km für die Distanz-Kalibrierung (Zielwahl, b_intrazonal).
         * MiD 2017, Tabelle A W12: 10 % unter 0,5 km + 11 % von 0,5 bis unter 1 km.
         * Kalibriert nur dieses Nahband, nicht den absoluten Ø (docs/06 „Phase 1b").
         */
        private double distanceCalibrationTargetNearShare = Mid2017.NEAR_TRIP_SHARE;
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
        /** Live POI/place queries; scenario snapshots remain usable when this is disabled. */
        private boolean liveDataEnabled = true;
        private String endpoint = "https://overpass-api.de/api/interpreter";
        /**
         * Optionale lokale OSM-XML-Datei für den Straßennetz-Import. Ist sie gesetzt,
         * wird nur der schwere Routing-Abruf ersetzt; POI-/Place-Abfragen bleiben
         * unverändert. Das ermöglicht reproduzierbare Offline-Läufe ohne Overpass-Fallback.
         */
        private String roadNetworkFile = "";
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

        public boolean isLiveDataEnabled() { return liveDataEnabled; }
        public void setLiveDataEnabled(boolean liveDataEnabled) { this.liveDataEnabled = liveDataEnabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRoadNetworkFile() { return roadNetworkFile; }
        public void setRoadNetworkFile(String roadNetworkFile) { this.roadNetworkFile = roadNetworkFile; }
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

    /** Amtliches Zensus-2022-Bevölkerungsraster für Deutschland. */
    public static class Census {
        private boolean enabled = true;
        private String version = "zensus-2022-2024-10-08";
        private String referenceDate = "2022-05-15";
        private String sourceUrl =
                "https://www.destatis.de/static/DE/zensus/gitterdaten/Zensus2022_Bevoelkerungszahl.zip";
        private String archiveSha256 =
                "1a8428d31eab264c8717bfdcbf40fbd66c217edb2e2a98d0e67741bd8a1a941a";
        private String gridEntry = "Zensus2022_Bevoelkerungszahl_100m-Gitter.csv";
        private int timeoutSeconds = 180;
        private int maxArchiveMb = 64;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getReferenceDate() { return referenceDate; }
        public void setReferenceDate(String referenceDate) { this.referenceDate = referenceDate; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        public String getArchiveSha256() { return archiveSha256; }
        public void setArchiveSha256(String archiveSha256) { this.archiveSha256 = archiveSha256; }
        public String getGridEntry() { return gridEntry; }
        public void setGridEntry(String gridEntry) { this.gridEntry = gridEntry; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxArchiveMb() { return maxArchiveMb; }
        public void setMaxArchiveMb(int maxArchiveMb) { this.maxArchiveMb = maxArchiveMb; }
    }

    /**
     * Automatische ÖV-Ergänzung für SUMO: GTFS → gtfs2pt.py → Fahrplan-Infrastruktur.
     * Braucht ein Python-venv mit pandas/rtree/pyproj (gekapselt, Stage SKIPPED wenn fehlt).
     * GTFS-Quelle: Auto-Fetch aus der Feed-Registry (nach Region) mit Upload-Fallback.
     */
    public static class Transit {
        private boolean enabled = true;
        /** Python-Interpreter eines venv mit pandas/rtree/pyproj; leer = ÖV-Stage nicht verfügbar. */
        private String venvPython = "";
        /** gtfs2pt.py; Default aus $SUMO_HOME/tools abgeleitet. */
        private String gtfs2pt = defaultGtfs2pt();
        /** Auto-Fetch-Feeds (Verbund-GTFS mit Abdeckungs-Bbox; Reihenfolge = Priorität, spezifisch vor DE-weit). */
        private java.util.List<Feed> feeds = new java.util.ArrayList<>(
                java.util.List.of(Feed.kvv(), Feed.gtfsDeNahverkehr()));
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
                    ? env
                    : java.nio.file.Path.of(Sumo.sumoRoot(), "share", "sumo").toString();
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

            /**
             * Deutschlandweiter ÖPNV-Gesamtfeed aus dem DELFI-Datensatz (gtfs.de, CC BY 4.0,
             * täglich regeneriert). ~223 MB (Stand 2026-07) und damit bewusst über dem
             * maxFeedMb-Default: ohne erhöhtes max-feed-mb liefert der HEAD-Check die
             * "Upload empfohlen"-Meldung statt "kein Feed". Steht NACH den Verbund-Feeds,
             * damit findFirst spezifische Feeds bevorzugt.
             */
            static Feed gtfsDeNahverkehr() {
                return new Feed("gtfs-de-nahverkehr",
                        "https://download.gtfs.de/germany/nv_free/latest.zip",
                        47.2, 5.8, 55.1, 15.1);
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
    public int getMaxZonesHardLimit() { return maxZonesHardLimit; }
    public void setMaxZonesHardLimit(int maxZonesHardLimit) { this.maxZonesHardLimit = maxZonesHardLimit; }
    public Overpass getOverpass() { return overpass; }
    public void setOverpass(Overpass overpass) { this.overpass = overpass; }
    public Census getCensus() { return census; }
    public void setCensus(Census census) { this.census = census; }
    public Simulation getSimulation() { return simulation; }
    public void setSimulation(Simulation simulation) { this.simulation = simulation; }
    public Actitopp getActitopp() { return actitopp; }
    public void setActitopp(Actitopp actitopp) { this.actitopp = actitopp; }
    public Sumo getSumo() { return sumo; }
    public void setSumo(Sumo sumo) { this.sumo = sumo; }
    public Transit getTransit() { return transit; }
    public void setTransit(Transit transit) { this.transit = transit; }
    public MobileCityValidation getMobileCityValidation() { return mobileCityValidation; }
    public void setMobileCityValidation(MobileCityValidation mobileCityValidation) {
        this.mobileCityValidation = mobileCityValidation;
    }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
}
