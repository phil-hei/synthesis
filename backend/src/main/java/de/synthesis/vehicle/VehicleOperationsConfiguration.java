package de.synthesis.vehicle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit physical identities for existing demand; no implicit fleet creation or dispatch. */
public record VehicleOperationsConfiguration(String schemaVersion, boolean enabled,
        Double reserveFraction, List<Binding> bindings, boolean chainTrips) {
    public static final String CONTRACT = "synthesis-vehicle-operations-input-v1";
    public record Binding(String sourceTripKey, String vehicleId) { }
    public VehicleOperationsConfiguration(String schemaVersion, boolean enabled, Double reserveFraction, List<Binding> bindings) {
        this(schemaVersion, enabled, reserveFraction, bindings, false);
    }
    public static VehicleOperationsConfiguration empty() {
        return new VehicleOperationsConfiguration(CONTRACT, false, .1, List.of());
    }
    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String personKey(String person, String trip) { return "person:" + encoded(person) + ":" + encoded(trip); }
    public static String parcelKey(String tour, String leg) { return "parcel:" + encoded(tour) + ":" + leg; }
    public static String freightKey(String trip) { return "freight:" + encoded(trip); }

    public static List<VehicleCatalog.Issue> validate(Scenario scenario) {
        var config = scenario.vehicleOperations;
        List<VehicleCatalog.Issue> errors = new ArrayList<>();
        if (config == null) { errors.add(new VehicleCatalog.Issue("vehicleOperations", "Konfiguration fehlt")); return errors; }
        if (!CONTRACT.equals(config.schemaVersion)) errors.add(new VehicleCatalog.Issue("vehicleOperations.schemaVersion", "Unbekannte Vertragsversion"));
        if (config.chainTrips && !config.enabled) errors.add(new VehicleCatalog.Issue("vehicleOperations.chainTrips", "Fahrzeugzuordnungen müssen aktiviert sein"));
        if (config.reserveFraction == null || !Double.isFinite(config.reserveFraction) || config.reserveFraction < 0 || config.reserveFraction > 1)
            errors.add(new VehicleCatalog.Issue("vehicleOperations.reserveFraction", "Reserveanteil muss zwischen 0 und 1 liegen"));
        if (config.bindings == null || config.bindings.size() > 100_000) { errors.add(new VehicleCatalog.Issue("vehicleOperations.bindings", "Liste mit höchstens 100.000 Fahrtzuordnungen erforderlich")); return errors; }
        Set<String> ids = new HashSet<>(), trips = new HashSet<>();
        var vehicles = scenario.vehicleConfiguration;
        if (vehicles != null && vehicles.initialVehicles() != null) for (var v : vehicles.initialVehicles()) if (v != null) ids.add(v.vehicleId());
        for (int i = 0; i < config.bindings.size(); i++) {
            var b = config.bindings.get(i); String p = "vehicleOperations.bindings[" + i + "]";
            if (b == null || b.sourceTripKey == null || b.sourceTripKey.length() > 2000
                    || !b.sourceTripKey.matches("(?:person|parcel|freight|company):[A-Za-z0-9_:-]+")) {
                errors.add(new VehicleCatalog.Issue(p, "Quell-Fahrtschlüssel aus vehicles/trip-identities.json erforderlich")); continue;
            }
            if (!trips.add(b.sourceTripKey)) errors.add(new VehicleCatalog.Issue(p, "Fahrt ist mehrfach einem Fahrzeug zugeordnet"));
            if (!ids.contains(b.vehicleId)) errors.add(new VehicleCatalog.Issue(p, "Fahrzeug-ID fehlt in initialVehicles"));
        }
        return List.copyOf(errors);
    }
    public static class Deserializer extends JsonDeserializer<VehicleOperationsConfiguration> {
        @Override public VehicleOperationsConfiguration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return VehicleJson.MAPPER.readValue(parser.readValueAsTree().toString(), VehicleOperationsConfiguration.class);
        }
    }
}
