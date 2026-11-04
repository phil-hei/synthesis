package de.synthesis.generation.pipeline;

import de.synthesis.vehicle.VehicleArtifacts;
import org.w3c.dom.Element;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/** Transfers explicit unconditional OSM HGV prohibitions; conditional/weight rules remain unsupported. */
final class OsmFreightPermissions {
    static boolean hgvNo(Map<String,String> tags,String clazz,boolean backward) {
        String dir = backward ? ":backward" : ":forward";
        List<String> bases = clazz.equals("trailer") ? List.of("hgv_articulated","hgv") : List.of("hgv");
        for (String base : bases) {
            // An unresolved conditional can lift a static prohibition; do not assert it unconditionally.
            if (tags.entrySet().stream().anyMatch(e -> e.getKey().startsWith(base+":") && e.getKey().endsWith(":conditional")
                    && !(backward && e.getKey().contains(":forward")) && !(!backward && e.getKey().contains(":backward"))
                    && !e.getValue().matches("no\\s*@\\s*\\([^;]+\\)"))) return false;
            String key = tags.containsKey(base+dir) ? base+dir : base;
            if (tags.containsKey(key)) return tags.get(key).equals("no");
        }
        return false;
    }
    static void apply(Path osm, Path network, Path report) throws IOException {
        Map<String, Map<String,String>> sourceTags = new HashMap<>();
        List<String> unsupported = new ArrayList<>();
        XmlRecords.read(osm, Set.of("way"), way -> {
            Map<String,String> tags = new HashMap<>();
            for (Element tag : TrafficRoutes.elements(way,"tag")) tags.put(tag.getAttribute("k"),tag.getAttribute("v"));
            sourceTags.put(way.getAttribute("id"),tags);
            for (var tag : tags.entrySet())
                if (tag.getKey().startsWith("hgv") && (!Set.of("hgv", "hgv:forward", "hgv:backward").contains(tag.getKey())
                        || !Set.of("no", "yes", "designated").contains(tag.getValue())))
                    unsupported.add(way.getAttribute("id") + ":" + tag.getKey() + "=" + tag.getValue());
        });

        List<String> changed = new ArrayList<>();
        XmlRecords.rewrite(network, Set.of("edge"), edge -> {
            if (!edge.hasAttribute("from")) return; // internal junction edges
            String id = edge.getAttribute("id");
            boolean backward = id.startsWith("-");
            String wayId = (backward ? id.substring(1) : id).split("#",2)[0];
            var tags = sourceTags.get(wayId);
            if (tags == null) return;
            Set<String> classes = new TreeSet<>();
            for (String clazz : List.of("truck","trailer")) if (hgvNo(tags,clazz,backward)) classes.add(clazz);
            if (classes.isEmpty()) return;
            for (Element lane : TrafficRoutes.elements(edge,"lane")) for (String clazz : classes)
                FreightProfileChecks.deny(lane,clazz);
            changed.add(id);
        });
        VehicleArtifacts.write(report,Map.of("contract","synthesis-osm-hgv-permissions-v1",
                "restrictedEdges",changed.stream().sorted().toList(),"unsupportedHgvTags",unsupported.stream().sorted().toList(),
                "weightHeightAndConditionalRulesSupported",false));
    }
}
