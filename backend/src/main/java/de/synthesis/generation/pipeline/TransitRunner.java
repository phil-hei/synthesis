package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ÖV-Zielmodell (Achse B, Phase C): baut aus einem (zugeschnittenen) GTFS-Feed und dem
 * SUMO-Netz die Fahrplan-Infrastruktur via {@code gtfs2pt.py} – Haltestellen
 * (pt_stops.add.xml) und fahrplangebundene ÖV-Fahrzeuge (pt_vehicles.add.xml), die als
 * additional-files in die Simulation eingebunden werden.
 *
 * <p>Bewusst gekapselt über ein Python-venv (pandas/rtree/pyproj) – die einzige Stelle mit
 * Python im Projekt. Fehlt das venv oder gtfs2pt.py, ist die Stage schlicht nicht verfügbar
 * ({@link #isAvailable()} = false, Stage wird übersprungen).
 *
 * <p>gtfs2pt.py fällt ohne kompilierte Mapping-Bibliothek auf den langsamen
 * {@code tracemapper} zurück; deshalb wird das Ergebnis gecacht (existieren die Ausgabe-
 * dateien bereits, wird gtfs2pt übersprungen) und der Zuschnitt zeitlich begrenzt.
 */
public class TransitRunner {

    public record PtInfrastructure(Path vtypes, Path stops, Path vehicles, int busStops,
                                   int ptVehicles) {
    }

    private final String venvPython;
    private final String gtfs2pt;
    private final String sumoHome;
    private final String modes;
    private final int timeoutMinutes;

    public TransitRunner(SynthesisProperties.Transit config, String sumoHome) {
        this.venvPython = config.getVenvPython();
        this.gtfs2pt = config.getGtfs2pt();
        this.sumoHome = sumoHome;
        this.modes = config.getModes();
        this.timeoutMinutes = config.getTimeoutMinutes();
    }

    /** venv-Python und gtfs2pt.py müssen vorhanden sein. */
    public boolean isAvailable() {
        return venvPython != null && !venvPython.isBlank()
                && Files.exists(Path.of(venvPython)) && Files.exists(Path.of(gtfs2pt));
    }

    /**
     * Erzeugt (oder liest aus dem Cache) die ÖV-Infrastruktur unter {@code outDir}.
     *
     * @param net         SUMO-Netz (net.net.xml)
     * @param croppedGtfs auf Region/Zeitfenster zugeschnittener GTFS-Feed
     * @param date        Betriebstag als YYYYMMDD (muss im Feed-Kalender liegen)
     * @param outDir      Cache-/Ausgabeverzeichnis (persistiert pt_stops/pt_vehicles)
     */
    public PtInfrastructure build(Path net, Path croppedGtfs, String date, Path outDir,
                                  Consumer<String> log) throws IOException, InterruptedException {
        Files.createDirectories(outDir);
        Path vtypes = writeVtypes(outDir);
        Path stops = outDir.resolve("pt_stops.add.xml");
        Path vehicles = outDir.resolve("pt_vehicles.add.xml");

        if (Files.exists(stops) && Files.exists(vehicles) && Files.size(vehicles) > 0) {
            log.accept("ÖV-Infrastruktur aus Cache übernommen (" + outDir.getFileName() + ")");
            return new PtInfrastructure(vtypes, stops, vehicles, count(stops, "<busStop"),
                    count(vehicles, "<vehicle "));
        }

        log.accept("gtfs2pt.py: Fahrplan-Mapping (tracemapper, kann einige Minuten dauern)…");
        exec(outDir, log, venvPython, gtfs2pt,
                "-n", net.toAbsolutePath().toString(),
                "--gtfs", croppedGtfs.toAbsolutePath().toString(),
                "--date", date, "--modes", modes,
                "--route-output", vehicles.toAbsolutePath().toString(),
                "--additional-output", stops.toAbsolutePath().toString());

        int busStops = count(stops, "<busStop");
        int ptVehicles = count(vehicles, "<vehicle ");
        log.accept("ÖV-Infrastruktur: " + busStops + " Haltestellen, " + ptVehicles + " Fahrten");
        return new PtInfrastructure(vtypes, stops, vehicles, busStops, ptVehicles);
    }

    /**
     * gtfs2pt.py referenziert die ÖV-Fahrzeuge über {@code type="bus"} usw., schreibt die
     * vType-Definitionen aber nicht in die Fahrzeug-Datei. Sie werden hier für die
     * konfigurierten Modi bereitgestellt (id = vClass = Modus, z. B. bus) und in der
     * Simulation VOR den Fahrzeugen geladen.
     */
    private Path writeVtypes(Path outDir) throws IOException {
        Path vtypes = outDir.resolve("pt_vtypes.add.xml");
        StringBuilder sb = new StringBuilder("<additional>\n");
        java.util.Set<String> written = new java.util.LinkedHashSet<>();
        for (String mode : modes.split(",")) {
            // gtfs2pt referenziert die Fahrzeuge über die SUMO-vClass (train→rail), nicht den OSM-Namen
            String vClass = vClassOf(mode.trim());
            if (!vClass.isEmpty() && written.add(vClass)) {
                sb.append(String.format("  <vType id=\"%s\" vClass=\"%s\" emissionClass=\"%s\"/>%n",
                        vClass, vClass, emissionClass(vClass)));
            }
        }
        sb.append("</additional>\n");
        Files.writeString(vtypes, sb.toString());
        return vtypes;
    }

    /** OSM-Modus (--modes) → SUMO-vClass, wie gtfs2pt.py sie in die Fahrzeug-Datei schreibt. */
    private static String vClassOf(String osmMode) {
        return switch (osmMode) {
            case "bus" -> "bus";
            case "train" -> "rail";
            case "tram" -> "tram";
            case "light_rail", "monorail" -> "rail_urban";
            case "subway" -> "subway";
            default -> osmMode;
        };
    }

    /** HBEFA3-Emissionsklasse je vClass: Bus dieselbetrieben, Schiene/Tram elektrisch (Zero). */
    private static String emissionClass(String vClass) {
        return "bus".equals(vClass) ? "HBEFA3/Bus" : "Zero";
    }

    private static int count(Path file, String needle) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int idx = 0;
            while ((idx = line.indexOf(needle, idx)) >= 0) {
                n++;
                idx += needle.length();
            }
        }
        return n;
    }

    private void exec(Path workDir, Consumer<String> log, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.environment().put("SUMO_HOME", sumoHome);
        // gtfs2pt importiert sumolib/gtfs2fcd/… aus den SUMO-Tools
        builder.environment().merge("PYTHONPATH", Path.of(sumoHome, "tools").toString(),
                (a, b) -> b + java.io.File.pathSeparator + a);
        builder.redirectErrorStream(true);
        Path logFile = workDir.resolve("gtfs2pt.log");
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("gtfs2pt.py nach " + timeoutMinutes + " min abgebrochen (Timeout)");
        }
        if (process.exitValue() != 0) {
            throw new IOException("gtfs2pt.py fehlgeschlagen (Exit " + process.exitValue() + "): "
                    + lastLines(logFile));
        }
    }

    private static String lastLines(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 4), lines.size()));
        } catch (IOException e) {
            return "(Log nicht lesbar)";
        }
    }
}
