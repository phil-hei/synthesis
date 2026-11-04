package de.synthesis.settings;

import de.synthesis.SynthesisProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;

/** Deliberate allow-list of installation settings; scenario parameters stay in Scenario. */
public record ToolSettings(
        @NotNull @Valid Sumo sumo,
        @NotNull @Valid Llm llm,
        @NotNull @Valid Tool actitopp,
        @NotNull @Valid Tool simulation,
        @NotNull @Valid Transit transit,
        @NotNull @Valid MobileCity mobileCity) {
    public record Sumo(@NotNull Boolean enabled, @NotBlank @Size(max=2048) String binDir,
                       @NotBlank @Size(max=2048) String sumoHome,
                       @NotNull @Min(1) @Max(1440) Integer timeoutMinutes) {}
    public record Llm(@NotNull Boolean enabled, @NotBlank @Size(max=2048) String endpoint,
                      @NotBlank @Size(max=200) String model,
                      @NotNull @DecimalMin("0") @DecimalMax("2") Double temperature,
                      @NotNull @Min(1) @Max(3600) Integer timeoutSeconds,
                      @NotNull @Min(1) @Max(10000) Integer maxPersonas) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message="LLM-Endpunkt muss eine HTTP(S)-Basis-URL ohne Zugangsdaten, Query oder Fragment sein")
        public boolean isEndpointValid() {
            if (endpoint == null) return false;
            try {
                URI uri = URI.create(endpoint);
                return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null
                        && uri.getQuery() == null && uri.getFragment() == null
                        && !endpoint.endsWith("/");
            } catch (IllegalArgumentException ex) { return false; }
        }
    }
    public record Tool(@NotNull Boolean enabled, @NotBlank @Size(max=2048) String projectDir,
                       @NotNull @Min(1) @Max(1440) Integer timeoutMinutes) {}
    public record Transit(@NotNull Boolean enabled, @NotNull @Size(max=2048) String venvPython,
                          @NotBlank @Size(max=2048) String gtfs2pt,
                          @NotNull @Min(1) @Max(1440) Integer timeoutMinutes) {}
    public record MobileCity(@NotNull Boolean enabled, @NotBlank @Size(max=2048) String python,
                             @NotBlank @Size(max=2048) String script,
                             @NotBlank @Size(max=2048) String dotnet,
                             @NotBlank @Size(max=2048) String toolSource,
                             @NotNull @Size(max=2048) String nugetCache,
                             @NotNull @Min(1) @Max(1440) Integer timeoutMinutes) {}

    public static ToolSettings from(SynthesisProperties p) {
        var s = p.getSumo(); var l = p.getLlm(); var a = p.getActitopp();
        var r = p.getSimulation(); var t = p.getTransit(); var m = p.getMobileCityValidation();
        return new ToolSettings(new Sumo(s.isEnabled(), s.getBinDir(), s.getSumoHome(), s.getTimeoutMinutes()),
                new Llm(l.isEnabled(), l.getEndpoint(), l.getModel(), l.getTemperature(), l.getTimeoutSeconds(), l.getMaxPersonas()),
                new Tool(a.isEnabled(), a.getProjectDir(), a.getTimeoutMinutes()),
                new Tool(r.isEnabled(), r.getProjectDir(), r.getTimeoutMinutes()),
                new Transit(t.isEnabled(), t.getVenvPython(), t.getGtfs2pt(), t.getTimeoutMinutes()),
                new MobileCity(m.isEnabled(), m.getPython(), m.getScript(), m.getDotnet(), m.getToolSource(), m.getNugetCache(), m.getTimeoutMinutes()));
    }

    /** Called only during startup, before any consumer receives the configuration bean. */
    public void applyTo(SynthesisProperties p) {
        var s=p.getSumo(); s.setEnabled(sumo.enabled()); s.setBinDir(sumo.binDir());
        s.setSumoHome(sumo.sumoHome()); s.setTimeoutMinutes(sumo.timeoutMinutes());
        var l=p.getLlm(); l.setEnabled(llm.enabled()); l.setEndpoint(llm.endpoint()); l.setModel(llm.model());
        l.setTemperature(llm.temperature()); l.setTimeoutSeconds(llm.timeoutSeconds()); l.setMaxPersonas(llm.maxPersonas());
        var a=p.getActitopp(); a.setEnabled(actitopp.enabled()); a.setProjectDir(actitopp.projectDir()); a.setTimeoutMinutes(actitopp.timeoutMinutes());
        var r=p.getSimulation(); r.setEnabled(simulation.enabled()); r.setProjectDir(simulation.projectDir()); r.setTimeoutMinutes(simulation.timeoutMinutes());
        var t=p.getTransit(); t.setEnabled(transit.enabled()); t.setVenvPython(transit.venvPython()); t.setGtfs2pt(transit.gtfs2pt()); t.setTimeoutMinutes(transit.timeoutMinutes());
        var m=p.getMobileCityValidation(); m.setEnabled(mobileCity.enabled()); m.setPython(mobileCity.python());
        m.setScript(mobileCity.script()); m.setDotnet(mobileCity.dotnet()); m.setToolSource(mobileCity.toolSource());
        m.setNugetCache(mobileCity.nugetCache()); m.setTimeoutMinutes(mobileCity.timeoutMinutes());
    }
}
