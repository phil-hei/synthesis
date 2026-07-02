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
    }

    public static class Overpass {
        private String endpoint = "https://overpass-api.de/api/interpreter";
        /** Ausweich-Endpunkte, falls der primäre Server rate-limited/überlastet ist (504/429). */
        private java.util.List<String> mirrors = new java.util.ArrayList<>(java.util.List.of(
                "https://overpass.kumi.systems/api/interpreter",
                "https://overpass.private.coffee/api/interpreter"));
        private int timeoutSeconds = 30;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public java.util.List<String> getMirrors() { return mirrors; }
        public void setMirrors(java.util.List<String> mirrors) { this.mirrors = mirrors; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

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
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
}
