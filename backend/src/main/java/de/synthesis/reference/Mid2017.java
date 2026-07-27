package de.synthesis.reference;

import java.util.List;

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
    public static final List<DistanceBand> DISTANCE_BANDS = List.of(
            new DistanceBand("under1Km", 0.21),
            new DistanceBand("km1ToUnder2", 0.14),
            new DistanceBand("km2ToUnder5", 0.23),
            new DistanceBand("km5ToUnder10", 0.16),
            new DistanceBand("km10ToUnder20", 0.13),
            new DistanceBand("km20AndMore", 0.13)
    );
    public static final double NEAR_TRIP_SHARE = DISTANCE_BANDS.getFirst().share();

    private Mid2017() {
    }

    public record DistanceBand(String key, double share) {
    }
}
