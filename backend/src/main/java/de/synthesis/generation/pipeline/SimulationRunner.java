package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pipeline-Stufe 10: orchestriert die logiTopp-Wochensimulation im Legacy-Projekt
 * (validation/mobitopp-rastatt). Schreibt eine per-Szenario-Run-Config mit absoluten
 * Pfaden auf den Legacy-Export, ruft `sh gradlew runDeliverySynthesis -PconfigFile=…`
 * auf und legt Log + Ergebnisse unter artifacts/simulation/ ab.
 */
public class SimulationRunner {

    public record Result(Path resultsDir, long durationSeconds) {
    }

    private final Path projectDir;
    private final int timeoutMinutes;

    public SimulationRunner(SynthesisProperties.Simulation config) {
        this.projectDir = Path.of(config.getProjectDir()).toAbsolutePath().normalize();
        this.timeoutMinutes = config.getTimeoutMinutes();
    }

    /** Verfügbar, wenn das Legacy-Projekt mit Gradle-Wrapper und Delivery-Task existiert. */
    public boolean isAvailable() {
        return Files.exists(projectDir.resolve("gradlew"))
                && Files.exists(projectDir.resolve("build.gradle"));
    }

    public Path projectDir() {
        return projectDir;
    }

    public Result run(Scenario scenario, Path artifacts, Consumer<String> log)
            throws IOException, InterruptedException {
        return run(scenario, artifacts, log, null, null);
    }

    public Result run(Scenario scenario, Path artifacts, Consumer<String> log, Path modeChoiceMnl)
            throws IOException, InterruptedException {
        return run(scenario, artifacts, log, modeChoiceMnl, null);
    }

    /**
     * @param modeChoiceMnl        optionaler Override für die MNL-Mode-Choice-Parameterdatei
     *                             (Modal-Split-Kalibrierung); {@code null} = Standarddatei des Projekts
     * @param destinationChoiceDir optionales Verzeichnis mit gepatchten Zielwahl-Parameterdateien
     *                             (gleiche Dateinamen wie {@code config/shared/parameters/destination_choice_*});
     *                             {@code null} = Standarddateien des Projekts. Für die
     *                             Distanz-Sensitivitäts-Kalibrierung (Skill {@code synthesis-distanz-kampagne}).
     */
    public Result run(Scenario scenario, Path artifacts, Consumer<String> log, Path modeChoiceMnl,
                      Path destinationChoiceDir) throws IOException, InterruptedException {
        Path simulationDir = artifacts.resolve("simulation");
        Path resultsDir = simulationDir.resolve("results");
        Files.createDirectories(resultsDir);

        Path config = writeRunConfig(scenario, artifacts, simulationDir, resultsDir, modeChoiceMnl,
                destinationChoiceDir);
        log.accept("Run-Config geschrieben: " + artifacts.relativize(config));

        Path logFile = simulationDir.resolve("run.log");
        ProcessBuilder builder = new ProcessBuilder("sh", "gradlew", "runDeliverySynthesis",
                "-PconfigFile=" + config.toAbsolutePath());
        builder.directory(projectDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        long start = System.currentTimeMillis();
        Process process = builder.start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Simulation nach " + timeoutMinutes + " min abgebrochen (Timeout)");
        }
        long duration = (System.currentTimeMillis() - start) / 1000;
        if (process.exitValue() != 0) {
            throw new IOException("Simulation fehlgeschlagen (Exit " + process.exitValue()
                    + "): " + lastLines(logFile, 5));
        }
        truncateLog(logFile, 400);
        return new Result(resultsDir, duration);
    }

    /**
     * Per-Szenario-Config nach dem Muster von config/synthesis/short-term-module.yaml:
     * Szenario-Daten (Legacy-Export, Depots, Ergebnisse) absolut, Modellparameter
     * (Wahlmodelle, Visum-Netz) relativ zum Legacy-Projekt — der Prozess läuft dort.
     */
    // package-private für SimulationRunnerTest (verifiziert die Override-Verdrahtung ohne Gradle-Lauf)
    Path writeRunConfig(Scenario scenario, Path artifacts, Path simulationDir,
                        Path resultsDir, Path modeChoiceMnl, Path destinationChoiceDir)
            throws IOException {
        Path legacy = artifacts.resolve("legacy").toAbsolutePath();
        String mnlFile = modeChoiceMnl != null
                ? modeChoiceMnl.toAbsolutePath().toString()
                : "config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt";
        String dcBase = destinationChoicePath(destinationChoiceDir,
                "destination_choice_utility_calculation_parameters.txt");
        String dcBusiness = destinationChoicePath(destinationChoiceDir,
                "destination_choice_parameters_BUSINESS.txt");
        String dcLeisure = destinationChoicePath(destinationChoiceDir,
                "destination_choice_parameters_LEISURE.txt");
        String dcService = destinationChoicePath(destinationChoiceDir,
                "destination_choice_parameters_SERVICE.txt");
        String dcShopping = destinationChoicePath(destinationChoiceDir,
                "destination_choice_parameters_SHOPPING.txt");
        String yaml = """
                dataSource: !file
                  demandDataFolder: %s/demand-data
                  matrixConfigurationFile: %s/matrices/matrix-configuration.yaml
                  attractivityDataFile: %s/zoneproperties/Attractivities.csv
                  zonePropertiesDataFile: %s/zoneproperties/zoneproperties.csv
                  parkingFacilitiesDataFile: %s/zoneproperties/parkingFacilities.csv
                  bikeSharingPropertiesDataFile: %s/zoneproperties/bikesharing-properties.csv
                  carSharingStationsDataFile: %s/zoneproperties/carsharing-stations.csv
                  carSharingFreeFloatingDataFile: %s/zoneproperties/carsharing-free-floating.csv
                  carSharingPropertiesDataFile: %s/zoneproperties/carsharing-properties.csv
                  defaultChargingPower: config/shared/parameters/ChargingPower.txt
                  zoneRepositoryFolder: %s/zone-repository/
                days: 7
                destinationChoice:
                  base: %s
                  business: %s
                  leisure: %s
                  service: %s
                  shopping: %s
                modeChoice:
                  model: MNL
                  MNL: %s
                  main: config/shared/parameters/mode_choice_main_parameters.txt
                  modePreferencesSeed: %d
                  travelTimeSensitivitySeed: %d
                  preferencesFile: %s/preferences.csv
                  travelTimeSensitivitiesFile: %s/travel-time-sensitivities.csv
                fractionOfPopulation: %s
                resultFolder: %s
                seed: %d
                visumFile: data/rastatt/rastatt.net.bz2
                visumToMobitopp:
                  carTransportSystemCode: CAR
                  ptTransportSystemCode: PUTW
                  individualWalkTransportSystemCode: FUSS
                  ptWalkTransportSystemCode: F
                experimental:
                  distributionCenters: %s/logistics/distribution-centers.csv
                  cepspShares: %s/logistics/cepsp-shares.csv
                  writeMatrices: false
                  dayMatrices: false
                  hourMatrices: false
                  logModeChoice: false
                  logModeChoiceN: 0
                """.formatted(
                legacy, legacy, legacy, legacy, legacy, legacy, legacy, legacy, legacy, legacy,
                dcBase, dcBusiness, dcLeisure, dcService, dcShopping,
                mnlFile,
                scenario.population.seed, scenario.population.seed,
                simulationDir.toAbsolutePath(), simulationDir.toAbsolutePath(),
                // PopulationGenerator hat samplingFraction bereits auf die exportierten
                // Personen angewandt. logiTopp muss diesen Bestand vollständig verarbeiten;
                // andernfalls würde derselbe Anteil ein zweites Mal gezogen.
                "1.0",
                resultsDir.toAbsolutePath(),
                scenario.population.seed,
                legacy, legacy);
        Path config = simulationDir.resolve("run-config.yaml");
        Files.writeString(config, yaml);
        return config;
    }

    /** Zielwahl-Parameterpfad: gepatchte Kopie im Override-Verzeichnis (absolut) oder Projekt-Standard. */
    private static String destinationChoicePath(Path destinationChoiceDir, String filename) {
        return destinationChoiceDir != null
                ? destinationChoiceDir.resolve(filename).toAbsolutePath().toString()
                : "config/shared/parameters/" + filename;
    }

    /** Volles Gradle-Log wird zweistellig groß (MB); im Erfolgsfall reicht das Ende. */
    private static void truncateLog(Path logFile, int keepLines) throws IOException {
        var lines = Files.readAllLines(logFile);
        if (lines.size() <= keepLines) {
            return;
        }
        var tail = lines.subList(lines.size() - keepLines, lines.size());
        Files.write(logFile, java.util.stream.Stream.concat(
                java.util.stream.Stream.of("… (" + (lines.size() - keepLines)
                        + " Zeilen gekürzt, voller Lauf erfolgreich, Exit 0)"),
                tail.stream()).toList());
    }

    private static String lastLines(Path logFile, int n) {
        try {
            var lines = Files.readAllLines(logFile);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
        } catch (IOException e) {
            return "(Log nicht lesbar)";
        }
    }
}
