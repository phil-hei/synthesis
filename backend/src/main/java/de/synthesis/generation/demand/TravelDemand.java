package de.synthesis.generation.demand;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kanonische, simulatorunabhängige Sicht auf die in SyntheSis simulierte
 * Personenverkehrsnachfrage.
 *
 * <p>Ein {@link PersonTrip} ist ein vollständiger Weg. Seine {@link Leg}-Liste bleibt
 * mit Modus, Zonen und Distanz erhalten, damit intermodale Wege nicht auf eine einzelne
 * Request- oder Fahrzeugfahrt verengt werden. Der Legacy-Export enthält keine belastbare
 * Leg-Geometrie; Koordinaten und Zeitrahmen werden daher ausschließlich am Gesamtweg geführt.
 * Zeitangaben sind geplante Minuten seit Montag 00:00.
 */
public record TravelDemand(List<PersonTrip> trips, int sourceRows) {

    public static final String CONTRACT = "synthesis-travel-demand-v1";
    public static final int HORIZON_MINUTES = 7 * 24 * 60;
    /** Ein am Sonntag begonnener Weg darf höchstens bis Montag 24:00 reichen. */
    public static final int MAX_PLANNED_ARRIVAL_MINUTES = HORIZON_MINUTES + 24 * 60;

    private static final Comparator<PersonTrip> TRIP_ORDER =
            Comparator.comparingDouble(PersonTrip::plannedDepartureMinute)
                    .thenComparing(PersonTrip::personOid)
                    .thenComparing(PersonTrip::tripId);

    public TravelDemand {
        trips = trips.stream().sorted(TRIP_ORDER).toList();
        if (sourceRows < 0) {
            throw new IllegalArgumentException("sourceRows darf nicht negativ sein");
        }
        long legs = trips.stream().mapToLong(trip -> trip.legs().size()).sum();
        if (legs != sourceRows) {
            throw new IllegalArgumentException(
                    "Leg-Bilanz verletzt: " + legs + " Legs bei " + sourceRows + " Quellzeilen");
        }
    }

    public enum Mode {
        BIKE("bike"),
        CAR("car"),
        PASSENGER("passenger"),
        PEDESTRIAN("pedestrian"),
        PUBLIC_TRANSPORT("publicTransport"),
        OTHER("other");

        private final String artifactName;

        Mode(String artifactName) {
            this.artifactName = artifactName;
        }

        public String artifactName() {
            return artifactName;
        }

        public static Mode fromLegacyCode(String code) {
            return switch (code) {
                case "0" -> BIKE;
                case "1" -> CAR;
                case "2" -> PASSENGER;
                case "3" -> PEDESTRIAN;
                case "4" -> PUBLIC_TRANSPORT;
                default -> OTHER;
            };
        }
    }

    public record Leg(
            int legId,
            Mode mode,
            String modeCode,
            double distanceKm,
            String originZoneId,
            String destinationZoneId) {
    }

    public record PersonTrip(
            String personOid,
            String tripId,
            Mode mode,
            String modeCode,
            String rawTripModeCode,
            double plannedDepartureMinute,
            double plannedArrivalMinute,
            double distanceKm,
            String originZoneId,
            String destinationZoneId,
            double fromLon,
            double fromLat,
            double toLon,
            double toLat,
            List<Leg> legs) {

        public PersonTrip {
            legs = List.copyOf(legs);
            if (legs.isEmpty()) {
                throw new IllegalArgumentException("Ein Weg muss mindestens ein Leg enthalten");
            }
        }

        public String key() {
            return personOid + ":" + tripId;
        }
    }

    public long tripCount() {
        return trips.size();
    }

    public long personCount() {
        Set<String> persons = new HashSet<>();
        for (PersonTrip trip : trips) {
            persons.add(trip.personOid());
        }
        return persons.size();
    }

    public long legCount() {
        return trips.stream().mapToLong(trip -> trip.legs().size()).sum();
    }

    public double totalDistanceKm() {
        return trips.stream().mapToDouble(PersonTrip::distanceKm).sum();
    }

    public double nearTripShare() {
        if (trips.isEmpty()) {
            return Double.NaN;
        }
        return trips.stream().filter(trip -> trip.distanceKm() < 1.0).count()
                / (double) trips.size();
    }
}
