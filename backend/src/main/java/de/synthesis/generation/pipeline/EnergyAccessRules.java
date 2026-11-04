package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleType;
import java.util.*;

/** Conservative static source-rule comparisons; missing/conditional data never becomes a clearance. */
final class EnergyAccessRules {
    record Issue(String status, String key, String value, String reason) { }
    static List<Issue> check(Map<String, String> tags, VehicleType type, boolean backward) {
        String clazz = type.routing().sumoVehicleClass(), dir = backward ? ":backward" : ":forward";
        boolean heavy = clazz.equals("truck") || clazz.equals("trailer"); List<Issue> issues = new ArrayList<>();
        List<String> access = new ArrayList<>();
        if (clazz.equals("trailer")) access.add("hgv_articulated");
        access.add(heavy ? "hgv" : clazz.equals("delivery") ? "goods" : clazz.equals("bicycle") ? "bicycle" : "motorcar");
        if (!clazz.equals("bicycle")) access.add("motor_vehicle"); access.addAll(List.of("vehicle", "access"));
        for (String base : access) if (conditional(tags, base, dir)) issues.add(new Issue("unknown", base + ":conditional", "", "conditional-access-unresolved"));
        for (String base : access) {
            String key = tags.containsKey(base + dir) ? base + dir : base;
            if (!tags.containsKey(key)) continue;
            String value = tags.get(key);
            if (!Set.of("yes", "designated", "permissive").contains(value)) issues.add(new Issue(
                    Set.of("no", "private").contains(value) && !conditional(tags, base, dir) ? "violation" : "unknown",
                    key, value, "access-or-purpose-restriction"));
            break;
        }
        for (String base : List.of("maxheight", "maxwidth", "maxlength", "maxweight", "maxweightrating", "maxaxleload")) {
            List<String> keys = new ArrayList<>();
            if (heavy) for (String suffix : clazz.equals("trailer") ? List.of(":hgv_articulated", ":hgv") : List.of(":hgv")) {
                keys.add(base + suffix + dir); keys.add(base + suffix);
            }
            keys.add(base + dir); keys.add(base);
            if (tags.keySet().stream().anyMatch(key -> key.startsWith(base + ":") && key.endsWith(":conditional"))) {
                issues.add(new Issue("unknown", base, "", "conditional-dimension-unresolved")); continue;
            }
            String key = keys.stream().filter(tags::containsKey).findFirst().orElse(null); if (key == null) continue;
            String value = tags.get(key); boolean mass = base.contains("weight") || base.equals("maxaxleload");
            Double limit = FreightProfileChecks.quantity(value, mass); var p = type.physical();
            Double actual = switch (base) {
                case "maxheight" -> p.heightM(); case "maxwidth" -> p.widthM(); case "maxlength" -> p.lengthM();
                case "maxweight", "maxweightrating" -> p.maxGrossMassKg(); default -> null;
            };
            if (limit == null || actual == null) issues.add(new Issue("unknown", key, value, "value-unit-or-vehicle-property-unknown"));
            else if (actual > limit + 1e-9) issues.add(new Issue("violation", key, value, "vehicle-envelope-exceeds-source-limit"));
        }
        if (tags.containsKey("barrier") && !tags.get("barrier").equals("no")) issues.add(new Issue("unknown", "barrier", tags.get("barrier"), "physical-control-unverified"));
        // Preserve unsupported access/dimension variants, such as per-lane limits or exemptions.
        for (var e : tags.entrySet()) if ((e.getKey().startsWith("maxheight:") || e.getKey().startsWith("maxwidth:") || e.getKey().startsWith("maxweight:")
                || e.getKey().startsWith("access:") || e.getKey().startsWith("hgv:")) && !e.getKey().matches("[^:]+(?::(?:hgv|hgv_articulated|forward|backward|conditional)){1,3}"))
            issues.add(new Issue("unknown", e.getKey(), e.getValue(), "unsupported-source-rule"));
        return List.copyOf(issues);
    }
    private static boolean conditional(Map<String, String> tags, String base, String dir) {
        return tags.containsKey(base + ":conditional") || tags.containsKey(base + dir + ":conditional");
    }
}
