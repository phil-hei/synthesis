package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.scenario.Scenario;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Beschafft den GTFS-Feed eines Szenarios und schneidet ihn auf Region + Zeitfenster zu.
 *
 * <p>Reihenfolge nach Nutzer-Vorgabe: <b>Auto-Fetch</b> aus der Feed-Registry (Verbund-GTFS,
 * dessen Abdeckungs-Bbox das Regions-Zentrum enthält) – bei fehlendem Regionsfeed oder zu
 * großem Download ({@code maxFeedMb}) greift der <b>Upload-Fallback</b>. Steht schon ein
 * hochgeladener Feed bereit, wird dieser bevorzugt. Ohne verwertbare Quelle: {@code null}
 * (die ÖV-Stage wird dann übersprungen).
 */
public class GtfsSource {

    public record Resolved(Path croppedFeed, String feedName, String source, GtfsCropper.Result crop) {
    }

    private final SynthesisProperties.Transit config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GtfsSource(SynthesisProperties.Transit config) {
        this.config = config;
    }

    /**
     * @param region       Szenario-Region (Bbox)
     * @param uploadedFeed  hochgeladener GTFS-Feed oder {@code null}
     * @param rawCacheDir   Cache für heruntergeladene Verbund-Feeds (wiederverwendet)
     * @param croppedOut    Zieldatei des zugeschnittenen Feeds
     */
    public Resolved resolve(Scenario.Region region, Path uploadedFeed, Path rawCacheDir,
                            Path croppedOut, Consumer<String> log)
            throws IOException, InterruptedException {
        GtfsCropper cropper = new GtfsCropper(region.minLat, region.minLon, region.maxLat,
                region.maxLon, config.getWindowStartHour() * 3600, config.getWindowEndHour() * 3600);

        // Auto-Fetch primär
        double lat = (region.minLat + region.maxLat) / 2;
        double lon = (region.minLon + region.maxLon) / 2;
        SynthesisProperties.Transit.Feed feed = config.getFeeds().stream()
                .filter(f -> f.covers(lat, lon)).findFirst().orElse(null);
        if (feed != null) {
            Path raw = fetch(feed, rawCacheDir, log);
            if (raw != null) {
                GtfsCropper.Result result = cropper.crop(raw, croppedOut);
                log.accept("Auto-Fetch " + feed.getName() + ": Feed auf Region+Fenster zugeschnitten ("
                        + result.trips() + " Fahrten, " + result.stops() + " Halte)");
                return new Resolved(croppedOut, feed.getName(), "fetch", result);
            }
            log.accept("Auto-Fetch für " + feed.getName() + " nicht möglich – Upload-Fallback prüfen");
        } else {
            log.accept("Kein Auto-Fetch-Feed deckt die Region ab – Upload-Fallback prüfen");
        }

        // Upload-Fallback
        if (uploadedFeed != null && Files.exists(uploadedFeed)) {
            GtfsCropper.Result result = cropper.crop(uploadedFeed, croppedOut);
            log.accept("Hochgeladener GTFS-Feed auf Region+Fenster zugeschnitten ("
                    + result.trips() + " Fahrten, " + result.stops() + " Halte)");
            return new Resolved(croppedOut, "upload", "upload", result);
        }

        log.accept("Kein GTFS-Feed verfügbar (weder Auto-Fetch noch Upload) – ÖV übersprungen");
        return null;
    }

    /** Lädt den Verbund-Feed (einmalig gecacht); überspringt zu große Downloads. */
    private Path fetch(SynthesisProperties.Transit.Feed feed, Path rawCacheDir, Consumer<String> log)
            throws IOException, InterruptedException {
        Files.createDirectories(rawCacheDir);
        Path cached = rawCacheDir.resolve(feed.getName() + ".zip");
        if (Files.exists(cached) && Files.size(cached) > 0) {
            return cached;
        }
        long sizeMb = headContentLengthMb(feed.getUrl());
        if (sizeMb > config.getMaxFeedMb()) {
            log.accept("Feed " + feed.getName() + " ist " + sizeMb + " MB (> "
                    + config.getMaxFeedMb() + " MB) – Auto-Fetch übersprungen (Upload empfohlen)");
            return null;
        }
        log.accept("Lade Verbund-Feed " + feed.getName() + (sizeMb > 0 ? " (~" + sizeMb + " MB)" : "")
                + "…");
        Path tmp = Files.createTempFile(rawCacheDir, feed.getName() + "-", ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.getUrl()))
                .timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() != 200 || Files.size(tmp) == 0) {
            Files.deleteIfExists(tmp);
            log.accept("Download von " + feed.getName() + " fehlgeschlagen (HTTP "
                    + response.statusCode() + ")");
            return null;
        }
        Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return cached;
    }

    /** Content-Length per HEAD in MB (0 = unbekannt). */
    private long headContentLengthMb(String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = http.send(head, HttpResponse.BodyHandlers.discarding());
            return response.headers().firstValueAsLong("content-length").orElse(0L) / (1024 * 1024);
        } catch (Exception e) {
            return 0;
        }
    }
}
