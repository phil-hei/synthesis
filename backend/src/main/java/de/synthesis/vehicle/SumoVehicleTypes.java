package de.synthesis.vehicle;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.xml.stream.*;

/** Native SUMO vTypes and seed-stable assignment of EXISTING trip segments. */
public final class SumoVehicleTypes {
    private final VehicleConfiguration config;
    private final long seed;
    private boolean explicitPhysical;
    private final Map<String, VehicleType> types = new TreeMap<>();
    private final Map<String, String> names = new TreeMap<>();
    private final Map<String, Integer> exported = new TreeMap<>();
    private static final Map<String, String> ROLES = Map.of(
            "pkw", "car", "fahrrad", "bicycle", "lkw", "deliveryVan", "lastenrad", "cargoBike",
            "heavyTruck", "heavyTruck", "articulatedTruck", "articulatedTruck");

    /** Receives the already validated, resolved snapshot (no live catalogue lookup). */
    public SumoVehicleTypes(VehicleConfiguration resolved, long seed) {
        this.config = resolved;
        this.seed = seed;
        if (resolved.appliesToSumo()) {
            for (var a : resolved.trafficAssignments()) {
                VehicleType type = resolved.vehicleTypes().stream().filter(t -> t.key().equals(a.typeRef().key())).findFirst().orElseThrow();
                String id = adapterId(type.key());
                types.put(id, type);
                names.put(id, type.key());
            }
        }
    }
    /** Explicit operation opt-in; initialVehicles alone remains storage-only. */
    public void includePhysicalVehicles() {
        if (!active()) return;
        for (var instance : config.initialVehicles()) {
            VehicleType type = config.vehicleTypes().stream().filter(t -> t.key().equals(instance.typeRef().key())).findFirst().orElseThrow();
            String id = adapterId(type.key()); types.put(id, type); names.put(id, type.key());
        }
    }
    /** Explicit imported tours use their declared physical types even when global trip allocation is off. */
    public void includeCompanyPhysicalVehicles() { explicitPhysical = true; includePhysicalVehicles(); }
    public boolean active() { return config.appliesToSumo() || explicitPhysical; }
    /** Routing checks use the actual selected vType, including catalogue overrides. */
    public String vehicleClass(String id) {
        if (types.containsKey(id)) return types.get(id).routing().sumoVehicleClass();
        return switch (id) {
            case "fahrrad", "lastenrad" -> "bicycle";
            case "lkw" -> "delivery";
            case "heavyTruck" -> "truck";
            case "articulatedTruck" -> "trailer";
            default -> "passenger";
        };
    }
    public String resultName(String id) { return names.getOrDefault(id, id); }
    public void beginExport() { exported.clear(); }
    public String select(String legacyType, String stableTripKey) { return select(legacyType, stableTripKey, null); }
    public String select(String legacyType, String stableTripKey, VehicleType.TypeRef physicalType) {
        if (!active()) return legacyType;
        String role = ROLES.get(legacyType);
        var candidates = (config.appliesToSumo() ? config.trafficAssignments() : List.<VehicleConfiguration.TrafficAssignment>of()).stream().filter(a -> Objects.equals(a.role(), role))
                .sorted(Comparator.comparing(a -> a.typeRef().key())).toList();
        String result = legacyType;
        if (!candidates.isEmpty()) {
            long bits = ByteBuffer.wrap(digest(seed + ":vehicle-types:" + role + ":" + stableTripKey)).getLong();
            double draw = (bits >>> 11) * 0x1.0p-53;
            double sum = 0;
            result = adapterId(candidates.getLast().typeRef().key());
            for (var a : candidates) {
                sum += a.share();
                if (draw < sum) { result = adapterId(a.typeRef().key()); break; }
            }
        }
        if (physicalType != null) {
            String fixed = adapterId(physicalType.key());
            if (!types.containsKey(fixed)) throw new IllegalArgumentException("Physischer Fahrzeugtyp nicht exportiert: " + physicalType.key());
            if (!vehicleClass(fixed).equals(vehicleClass(legacyType))) throw new IllegalArgumentException("Physisches Fahrzeug passt nicht zur Verkehrsrolle " + legacyType);
            result = fixed;
        }
        exported.merge(resultName(result), 1, Integer::sum);
        return result;
    }
    public void writeDefinitions(Writer writer) throws IOException {
        try {
            var factory = XMLOutputFactory.newFactory();
            for (var entry : types.entrySet()) {
                // Each definition is an XML fragment in the caller's <routes>. Strict providers reject
                // multiple roots on one fragment writer (the application can use Woodstox).
                XMLStreamWriter xml = factory.createXMLStreamWriter(writer);
                VehicleType t = entry.getValue(); var p = t.physical(); var d = t.driving();
                xml.writeEmptyElement("vType");
                attribute(xml, "id", entry.getKey());
                attribute(xml, "vClass", t.routing().sumoVehicleClass());
                attribute(xml, "emissionClass", t.routing().sumoEmissionClass());
                attribute(xml, "length", p.lengthM()); attribute(xml, "width", p.widthM());
                attribute(xml, "height", p.heightM()); attribute(xml, "mass", p.curbMassKg());
                attribute(xml, "maxSpeed", p.maxSpeedKmh() / 3.6);
                // Bikes have an additional desiredMaxSpeed default; use the explicit configured cap.
                attribute(xml, "desiredMaxSpeed", p.maxSpeedKmh() / 3.6);
                attribute(xml, "accel", d.accelerationMps2()); attribute(xml, "decel", d.decelerationMps2());
                attribute(xml, "emergencyDecel", d.emergencyDecelerationMps2());
                attribute(xml, "minGap", d.minGapM()); attribute(xml, "tau", d.timeHeadwayS());
                attribute(xml, "carFollowModel", d.carFollowModel());
                xml.writeCharacters("\n");
                xml.flush();
            }
        } catch (XMLStreamException e) { throw new IOException("SUMO-Fahrzeugtypen können nicht exportiert werden", e); }
    }
    public void writeUsage(Path file) throws IOException {
        if (active()) VehicleArtifacts.write(file, Map.of("contract", VehicleConfiguration.CONTRACT,
                "allocationBasis", "existing-trip-segments", "adapterTypeIds", names,
                "exportedTripsByType", exported, "fleetStateSimulated", false));
    }
    public static String adapterId(String key) { return "vt_" + HexFormat.of().formatHex(digest(key)); }
    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void attribute(XMLStreamWriter writer, String name, Object value) throws XMLStreamException {
        writer.writeAttribute(name, String.valueOf(value));
    }
}
