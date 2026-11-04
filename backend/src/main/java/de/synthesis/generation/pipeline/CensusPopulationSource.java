package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Versionierte, hash-geprüfte Quelle für das amtliche Zensus-2022-Bevölkerungsraster.
 * Das deutschlandweite Archiv wird einmal zentral gecacht; pro Region werden nur die
 * passenden 100-m-Zellen in den Speicher geladen. Jeder Fehler degradiert sauber zur
 * nachfolgenden OSM-/Dichtequelle.
 */
public final class CensusPopulationSource {
    /** Integrity errors are fatal in an explicitly configured provider chain. */
    public static final class InvalidSourceException extends IOException {
        public InvalidSourceException(String message){super(message);}
    }

    public static final String LICENSE =
            "Datenlizenz Deutschland - Namensnennung - Version 2.0";
    public static final String ATTRIBUTION =
            "Statistisches Bundesamt (Destatis), Zensus 2022";

    public record Provenance(String source, String version, String referenceDate,
                             String url, String archiveSha256, String license,
                             String attribution) {
    }

    public record Resolved(PopulationGridParser.ParseResult grid, Provenance provenance) {
    }

    @FunctionalInterface
    interface Downloader {
        InputStream open(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    private final SynthesisProperties.Census config;
    private final Downloader downloader;

    public CensusPopulationSource(SynthesisProperties.Census config) {
        this(config, httpDownloader());
    }

    CensusPopulationSource(SynthesisProperties.Census config, Downloader downloader) {
        this.config = config;
        this.downloader = downloader;
    }

    /** Liefert für Deutschlandregionen ein regional zugeschnittenes Raster oder leer als Fallback-Signal. */
    public Optional<Resolved> resolve(Scenario.Region region, Path cacheRoot, Consumer<String> log) {
        if (!config.isEnabled() || region == null || !intersectsGermany(region)) {
            return Optional.empty();
        }
        try {
            return resolveInternal(region,cacheRoot,log,false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept("Zensus-2022-Abruf unterbrochen - nutze Fallback");
            return Optional.empty();
        } catch (Exception e) {
            log.accept("Zensus 2022 nicht verfügbar (" + e.getMessage() + ") - nutze Fallback");
            return Optional.empty();
        }
    }

    /** Same reader/cache, but explicit chains retain errors instead of treating corruption as absence. */
    public Optional<Resolved> resolveChecked(Scenario.Region region,Path cacheRoot,Consumer<String> log)
            throws IOException,InterruptedException {
        return resolveInternal(region,cacheRoot,log,true);
    }
    public String referenceDate(){return config.getReferenceDate();}
    private Optional<Resolved> resolveInternal(Scenario.Region region,Path cacheRoot,Consumer<String> log,boolean strict)
            throws IOException,InterruptedException {
        if(!config.isEnabled()||region==null||!intersectsGermany(region))return Optional.empty();
        Path csv=ensureCached(cacheRoot,log,strict);
        PopulationGridParser.ParseResult grid=strict?PopulationGridParser.parseStrict(csv,region):PopulationGridParser.parse(csv,region);
        if(grid.points().isEmpty()){
            log.accept("Zensus 2022 enthält keine Einwohnerzellen in der Region - nutze Fallback");
            return Optional.empty();
        }
        return Optional.of(new Resolved(grid,new Provenance("destatis-zensus-2022",config.getVersion(),
                config.getReferenceDate(),config.getSourceUrl(),config.getArchiveSha256(),LICENSE,ATTRIBUTION)));
    }

    private synchronized Path ensureCached(Path cacheRoot, Consumer<String> log,boolean strict)
            throws IOException, InterruptedException {
        validateConfig();
        String safeVersion = config.getVersion().replaceAll("[^A-Za-z0-9._-]", "_");
        Path versionDir = cacheRoot.resolve(safeVersion);
        Files.createDirectories(versionDir);
        Path archive = versionDir.resolve("Zensus2022_Bevoelkerungszahl.zip");
        Path csv = versionDir.resolve("Zensus2022_Bevoelkerungszahl_100m-Gitter.csv");
        Path marker = versionDir.resolve("source.sha256");
        Path gridHash=versionDir.resolve("grid.sha256");

        if (Files.isRegularFile(archive)
                && !config.getArchiveSha256().equalsIgnoreCase(sha256(archive))) {
            if(strict)throw new InvalidSourceException("SHA-256-Abweichung im gespeicherten Zensusarchiv");
            Files.delete(archive);
            Files.deleteIfExists(csv);
            Files.deleteIfExists(marker);
        }
        if (!Files.isRegularFile(archive)) {
            downloadArchive(archive, log);
        }
        String actualArchiveSha = sha256(archive);
        if (!config.getArchiveSha256().equalsIgnoreCase(actualArchiveSha)) {
            throw new InvalidSourceException("SHA-256-Abweichung: erwartet " + config.getArchiveSha256()
                    + ", erhalten " + actualArchiveSha);
        }

        String markedSha = Files.isRegularFile(marker) ? Files.readString(marker).trim() : "";
        if (!Files.isRegularFile(csv) || Files.size(csv) == 0
                || !config.getArchiveSha256().equalsIgnoreCase(markedSha)
                || (strict&&!Files.isRegularFile(gridHash))) {
            try{extractGrid(archive, csv);}catch(IOException invalid){
                if(strict)throw new InvalidSourceException("Zensusarchiv ist nicht lesbar: "+invalid.getMessage());
                throw invalid;
            }
            Files.writeString(marker, config.getArchiveSha256() + System.lineSeparator());
            if(strict)Files.writeString(gridHash,sha256(csv));
            log.accept("Zensus-2022-Raster " + config.getVersion() + " aus geprüftem Archiv extrahiert");
        }
        if(strict&&!sha256(csv).equals(Files.readString(gridHash).strip()))
            throw new InvalidSourceException("SHA-256-Abweichung im extrahierten Zensus-Raster");
        return csv;
    }

    private void downloadArchive(Path target, Consumer<String> log)
            throws IOException, InterruptedException {
        log.accept("Lade amtliches Zensus-2022-Bevölkerungsarchiv " + config.getVersion() + " ...");
        Path tmp = Files.createTempFile(target.getParent(), "zensus-", ".part");
        long maxBytes = config.getMaxArchiveMb() * 1024L * 1024L;
        try (InputStream input = downloader.open(URI.create(config.getSourceUrl()),
                Duration.ofSeconds(config.getTimeoutSeconds()));
             var output = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Archiv größer als Limit " + config.getMaxArchiveMb() + " MB");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String actualSha = sha256(tmp);
        if (!config.getArchiveSha256().equalsIgnoreCase(actualSha)) {
            Files.deleteIfExists(tmp);
            throw new InvalidSourceException("SHA-256-Abweichung: erwartet " + config.getArchiveSha256()
                    + ", erhalten " + actualSha);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private void extractGrid(Path archive, Path target) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "zensus-grid-", ".part");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(config.getGridEntry());
            if (entry == null || entry.isDirectory()) {
                throw new IOException("ZIP-Eintrag fehlt: " + config.getGridEntry());
            }
            try (InputStream input = zip.getInputStream(entry);
                 var output = Files.newOutputStream(tmp)) {
                input.transferTo(output);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private void validateConfig() {
        if (config.getArchiveSha256() == null
                || !config.getArchiveSha256().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("synthesis.census.archive-sha256 muss 64 Hex-Zeichen haben");
        }
        if (config.getSourceUrl() == null || config.getSourceUrl().isBlank()) {
            throw new IllegalArgumentException("synthesis.census.source-url fehlt");
        }
    }

    static boolean intersectsGermany(Scenario.Region region) {
        // Grobe Deutschland-Bbox nur als Download-Guard; die amtlichen Rasterzellen entscheiden
        // anschließend exakt, ob die konkrete (auch grenznahe) Region Daten enthält.
        return region.maxLat >= 47.2 && region.minLat <= 55.2
                && region.maxLon >= 5.5 && region.minLon <= 15.6;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist in der JVM nicht verfügbar", e);
        }
    }

    private static Downloader httpDownloader() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Download HTTP " + response.statusCode());
            }
            return response.body();
        };
    }
}
