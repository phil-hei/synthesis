package de.synthesis.data;

import java.util.*;

/** Versioned, scoped parts of a resolved contribution. IDs refer only to earlier nodes. */
public record SourceProvenance(String contract, String aspect, String root, List<Node> nodes) {
    public static final String CONTRACT = "synthesis-source-provenance-v1";

    public record ZoneRef(long externalId, int modelIndex) { }
    public record Scope(String purpose, List<ZoneRef> zones) {
        public Scope { zones = List.copyOf(zones); }
    }
    public record Reference(String kind, String revision, String sourceHash, String label,
                            String origin, String referenceDate, String licence, String attribution) { }
    public record Node(String id, String operation, String evidence, String selectionRole,
                       Reference source, List<String> inputs, Scope scope, Map<String, Object> parameters) {
        public Node {
            inputs = List.copyOf(inputs);
            parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
        }
    }
    public record RowAssignment(int sourceRow, String sourceId, ZoneRef zone, String status, double quantity) { }

    public SourceProvenance {
        if (!CONTRACT.equals(contract)) throw new IllegalArgumentException("Unbekannter Herkunftsvertrag");
        nodes = List.copyOf(nodes);
        Set<String> known = new HashSet<>();
        for (var node : nodes) {
            if (!Set.of("observed", "derived", "assumed", "unknown").contains(node.evidence()))
                throw new IllegalArgumentException("Herkunft muss Beobachtung, Ableitung, Annahme oder unbekannt angeben");
            if (!known.containsAll(node.inputs()) || !known.add(node.id()))
                throw new IllegalArgumentException("Herkunftsreferenz fehlt, ist zyklisch oder doppelt");
        }
        if (!known.contains(root)) throw new IllegalArgumentException("Wurzel der Teileherkunft fehlt");
    }
}
