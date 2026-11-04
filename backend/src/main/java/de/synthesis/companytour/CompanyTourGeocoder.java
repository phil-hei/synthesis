package de.synthesis.companytour;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local Nominatim-compatible search. Results are proposals, never execution context. */
public final class CompanyTourGeocoder {
    public record Info(boolean enabled, String endpoint, int maxBatch, String message) {}
    public record Target(String id, String query) {}
    public record Candidate(String label, CompanyTourExecution.Point point, String reference, String attribution) {}
    public record Answer(String targetId, String query, List<Candidate> candidates) {}
    public record Lookup(String importId, String endpoint, int cachedQueries, List<Answer> answers) {}
    private record Cached(String endpoint, String query, List<Candidate> candidates) {}
    private final ObjectMapper json;
    private final TourImportProperties config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CompanyTourGeocoder(ObjectMapper json, TourImportProperties config) { this.json=json; this.config=config; }
    private URI endpoint() {
        try {
            URI uri=URI.create(config.getGeocodingEndpoint());
            if (!Set.of("http","https").contains(uri.getScheme()) ||
                    !Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost()) ||
                    uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
            return URI.create(uri.toString().replaceAll("/+$","")+"/search");
        } catch (RuntimeException e) { throw new IllegalArgumentException("Geokodierung benötigt einen lokalen Nominatim-kompatiblen Endpunkt ohne Zugangsdaten oder URL-Parameter"); }
    }
    public Info info() {
        int batch=Math.clamp(config.getGeocodingMaxBatch(),1,10);
        if (!config.isGeocodingEnabled()) return new Info(false,"",batch,"Lokale Geokodierung ist deaktiviert. Lokale Ortstabelle oder manuelle Koordinaten bleiben verfügbar.");
        try { return new Info(true,endpoint().toString(),batch,"Adressen werden ausschließlich an den konfigurierten lokalen Dienst gesendet."); }
        catch (IllegalArgumentException e) { return new Info(false,"",batch,e.getMessage()); }
    }
    public Lookup lookup(String importId, List<Target> targets, Path cache) throws IOException {
        return lookup(importId, targets, cache, false);
    }
    public Lookup lookup(String importId, List<Target> targets, Path cache, boolean refresh) throws IOException {
        Info info=info(); if (!info.enabled()) throw new IllegalArgumentException(info.message());
        if (targets.isEmpty() || targets.size()>info.maxBatch()) throw new IllegalArgumentException("Zwischen 1 und "+info.maxBatch()+" Adressziele auswählen");
        List<Answer> answers=new ArrayList<>(); int cached=0; Map<String,Cached> refreshed=new HashMap<>();
        for (var target:targets) {
            if (target.query()==null || target.query().isBlank() || target.query().length()>1000) throw new IllegalArgumentException("Vollständige Adresse für die Ortszuordnung erforderlich");
            String query=target.query().strip(); String key=CompanyTourMapper.hash((info.endpoint()+"\n"+query).getBytes(StandardCharsets.UTF_8));
            Path file=cache.resolve(key+".json"); Cached result;
            if (refresh && refreshed.containsKey(key)) {
                result=refreshed.get(key);
                cached++;
            } else if (!refresh && Files.isRegularFile(file)) {
                result=json.readValue(file.toFile(),Cached.class);
                if (!result.endpoint().equals(info.endpoint()) || !result.query().equals(query)) throw new IOException("Geokodierungs-Cache passt nicht zur Anfrage");
                cached++;
            } else {
                var candidates=search(query); result=new Cached(info.endpoint(),query,candidates); Files.createDirectories(cache);
                Path temporary=Files.createTempFile(cache,".geocoding-",".json");
                try { json.writeValue(temporary.toFile(),result); Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temporary); }
                refreshed.put(key,result);
            }
            answers.add(new Answer(target.id(),query,result.candidates()));
        }
        return new Lookup(importId,info.endpoint(),cached,List.copyOf(answers));
    }
    private List<Candidate> search(String query) throws IOException {
        int timeout=Math.clamp(config.getGeocodingTimeoutSeconds(),1,10);
        URI uri=URI.create(endpoint()+"?format=jsonv2&addressdetails=1&limit=5&q="+URLEncoder.encode(query,StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout)).header("Accept","application/json")
                .header("User-Agent","SyntheSis-local-tour-import/1").GET().build();
        var pending=client.sendAsync(request,ignored->new LimitedBody());
        try {
            var response=pending.get(timeout,TimeUnit.SECONDS);
            if (response.statusCode()!=200) throw new IOException("Lokaler Geokodierungsdienst: HTTP "+response.statusCode());
            var root=json.readTree(response.body());
            if (root==null || !root.isArray() || root.size()>5) throw new IOException("Geokodierungsantwort benötigt höchstens fünf Kandidaten");
            String source=CompanyTourMapper.hash(response.body()); List<Candidate> candidates=new ArrayList<>(); Set<String> positions=new HashSet<>();
            int i=0;
            for (var row:root) {
                double lon=Double.parseDouble(row.path("lon").asText()),lat=Double.parseDouble(row.path("lat").asText());
                if (!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90) throw new IOException("Ungültige Koordinate in Geokodierungsantwort");
                if (!positions.add(lon+"|"+lat)) continue;
                String label=row.path("display_name").asText(""); if (label.isBlank()||label.length()>2000) throw new IOException("Adressbezeichnung in Geokodierungsantwort fehlt oder ist zu lang");
                candidates.add(new Candidate(label,new CompanyTourExecution.Point(lon,lat,"local-geocoder:"+source+":"+i++),
                        row.path("osm_type").asText("")+":"+row.path("osm_id").asText(""),row.path("licence").asText("Konfigurierter lokaler Geokodierungsdienst")));
            }
            return List.copyOf(candidates);
        } catch (InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw new IOException("Geokodierung unterbrochen",e); }
        catch (IOException e) { throw e; }
        catch (Exception e) { pending.cancel(true); throw new IOException("Lokale Geokodierung fehlgeschlagen; vorhandene Orte bleiben erhalten",e); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> future=new CompletableFuture<>(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream(); Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return future;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(bytes.size()+b.remaining()>128_000){subscription.cancel();future.completeExceptionally(new IOException("Geokodierungsantwort überschreitet 128 KB"));return;}byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public void onError(Throwable error){future.completeExceptionally(error);}
        public void onComplete(){future.complete(bytes.toByteArray());}
    }
}
