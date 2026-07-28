package de.synthesis.reference;

import java.util.List;
import java.util.Map;

/**
 * Öffentliche MiD-2017-Referenzwerte, die von mehreren Pipeline-Komponenten
 * konsistent verwendet werden.
 */
public final class Mid2017 {

    public static final String DISTANCE_BANDS_SOURCE =
            "MiD 2017, Tabelle A W12, Gesamt; alle Wege inklusive imputierter Werte";
    public static final String DISTANCE_BANDS_SOURCE_URL =
            "https://www.mobilitaet-in-deutschland.de/archive/pdf/"
                    + "MiD2017_Tabellenband_Deutschland.pdf";
    public static final String MODE_DISTANCE_SOURCE =
            "MiD 2017, Tabelle A W10.5, Wegelänge; Zeilenprozente, "
                    + "<1 km und >=20 km gewichtet aggregiert und normalisiert";
    public static final List<DistanceBand> DISTANCE_BANDS = List.of(
            new DistanceBand("under1Km", 0.21),
            new DistanceBand("km1ToUnder2", 0.14),
            new DistanceBand("km2ToUnder5", 0.23),
            new DistanceBand("km5ToUnder10", 0.16),
            new DistanceBand("km10ToUnder20", 0.13),
            new DistanceBand("km20AndMore", 0.13)
    );
    public static final List<String> MODES =
            List.of("pedestrian", "bike", "passenger", "car", "publicTransport");
    public static final List<ModeDistanceBand> MODAL_SPLIT_BY_DISTANCE_BAND = List.of(
            modeDistanceBand("under1Km", 0.621643, 0.142232, 0.056594, 0.158942, 0.020589),
            modeDistanceBand("km1ToUnder2", 0.29, 0.20, 0.12, 0.34, 0.05),
            modeDistanceBand("km2ToUnder5", 0.14, 0.15, 0.16, 0.45, 0.10),
            modeDistanceBand("km5ToUnder10", 0.05, 0.08, 0.17, 0.55, 0.15),
            modeDistanceBand("km10ToUnder20", 0.019802, 0.039604, 0.178218, 0.613861, 0.148515),
            modeDistanceBand("km20AndMore", 0.0069, 0.0175, 0.1885, 0.6110, 0.1761)
    );
    public static final double NEAR_TRIP_SHARE = DISTANCE_BANDS.getFirst().share();

    private Mid2017() {
    }

    public record DistanceBand(String key, double share) {
    }

    public record ModeDistanceBand(String key, Map<String, Double> modalSplit) {
    }

    private static ModeDistanceBand modeDistanceBand(
            String key, double pedestrian, double bike, double passenger,
            double car, double publicTransport) {
        return new ModeDistanceBand(key, Map.of(
                "pedestrian", pedestrian,
                "bike", bike,
                "passenger", passenger,
                "car", car,
                "publicTransport", publicTransport));
    }
}
