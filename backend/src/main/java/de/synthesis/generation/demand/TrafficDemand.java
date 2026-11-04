package de.synthesis.generation.demand;

import de.synthesis.companytour.CompanyTourExecution;
import de.synthesis.generation.pipeline.FreightDemandGenerator;
import de.synthesis.vehicle.VehicleOperationsConfiguration;
import de.synthesis.vehicle.VehicleConfiguration;
import java.time.LocalDate;
import java.util.*;

/** Target-independent union. A trip is a request, not proof of a routed or completed vehicle. */
public record TrafficDemand(String contract, String personDemandStatus, TravelDemand persons,
        int freightDay, List<FreightDemandGenerator.Trip> freight,
        List<ParcelTourAdapter.Leg> parcels, CompanyTourExecution.Batch companies,
        VehicleConfiguration vehicles, List<VehicleOperationsConfiguration.Binding> vehicleBindings,
        String parcelDemandStatus) {
    public static final String CONTRACT = "synthesis-traffic-demand-v1";
    public TrafficDemand {
        if (!CONTRACT.equals(contract)) throw new IllegalArgumentException("Unbekannter gemeinsamer Nachfragevertrag");
        if (!Set.of("not-generated", "available").contains(personDemandStatus)) throw new IllegalArgumentException("Unbekannter Personenstatus");
        freight = List.copyOf(freight); parcels = List.copyOf(parcels); vehicleBindings = List.copyOf(vehicleBindings);
        if (!Set.of("not-generated", "available").contains(parcelDemandStatus)) throw new IllegalArgumentException("Unbekannter Paketstatus");
        if (freightDay < 0 || freightDay > 6) throw new IllegalArgumentException("Bezugswochentag 0–6 erforderlich");
    }
    public TrafficDemand(String contract, String status, TravelDemand persons, int day,
            List<FreightDemandGenerator.Trip> freight, List<ParcelTourAdapter.Leg> parcels, CompanyTourExecution.Batch companies) {
        this(contract,status,persons,day,freight,parcels,companies,VehicleConfiguration.empty(),List.of(),status);
    }
    public record Movement(String sourceTripKey, String trafficCategory, String demandSource,
            String mode, String vehicleClass, String tourId, String vehicleId,
            double departureWeekSeconds, Double plannedArrivalWeekSeconds, int serviceSeconds,
            double fromLon, double fromLat, double toLon, double toLat,
            String originZoneId, String destinationZoneId, boolean returning) {}
    public record Tour(String tourId, String demandSource, String vehicleId, List<String> sourceTripKeys) {}
    public List<Movement> movements() {
        List<Movement> result = new ArrayList<>();
        for (var t : persons.trips()) result.add(new Movement(VehicleOperationsConfiguration.personKey(t.personOid(), t.tripId()),
                "private", "person-demand", t.mode().artifactName(), null, null, null,
                t.plannedDepartureMinute()*60, t.plannedArrivalMinute()*60, 0,
                t.fromLon(), t.fromLat(), t.toLon(), t.toLat(), t.originZoneId(), t.destinationZoneId(), false));
        for (var t : freight) result.add(new Movement(VehicleOperationsConfiguration.freightKey(t.id()),
                "logistics", "heavy-freight", "road", t.vehicleClass(), null, null,
                freightDay*86400. + t.departSec(), null, 0, t.fromLon(), t.fromLat(), t.toLon(), t.toLat(), null, null, false));
        for (var t : parcels) result.add(new Movement(t.sourceKey(), "logistics", "parcel-logistics", t.vehicleClass().equals("tram") ? "rail" : t.vehicleClass().equals("bicycle") ? "bike" : "road", t.vehicleClass(),
                "parcel:" + t.tourId() + ":" + t.day(), null, t.day()*86400. + t.departSec(), t.plannedArrivalSec() == null ? null : t.day()*86400. + t.plannedArrivalSec(), t.serviceSeconds(),
                Double.parseDouble(t.fromLon()), Double.parseDouble(t.fromLat()), Double.parseDouble(t.toLon()), Double.parseDouble(t.toLat()), null, null, t.returning()));
        for (var input : companies.imports()) {
            int day = LocalDate.parse(input.simulationDate()).getDayOfWeek().getValue()-1;
            for (var t : input.legs()) result.add(new Movement(t.sourceKey(), "logistics", "company-tours", "road", t.vehicleClass(),
                    "company:" + input.importId() + ":" + t.tourId(), t.vehicleId(), day*86400. + t.departSec(),
                    day*86400. + t.plannedArrivalSec(), t.serviceSeconds(), t.from().lon(), t.from().lat(), t.to().lon(), t.to().lat(), null, null, t.returning()));
        }
        result.sort(Comparator.comparingDouble(Movement::departureWeekSeconds).thenComparing(Movement::sourceTripKey));
        Set<String> ids = new HashSet<>();
        for (var m : result) if (!ids.add(m.sourceTripKey())) throw new IllegalArgumentException("Doppelte Nachfrageidentität: " + m.sourceTripKey());
        Map<String,String> bindings = new HashMap<>();
        for (var b : vehicleBindings) if (bindings.putIfAbsent(b.sourceTripKey(), b.vehicleId()) != null)
            throw new IllegalArgumentException("Doppelte Fahrzeugzuordnung: " + b.sourceTripKey());
        return result.stream().map(m -> m.vehicleId() != null || !bindings.containsKey(m.sourceTripKey()) ? m :
                new Movement(m.sourceTripKey(),m.trafficCategory(),m.demandSource(),m.mode(),m.vehicleClass(),m.tourId(),bindings.get(m.sourceTripKey()),
                        m.departureWeekSeconds(),m.plannedArrivalWeekSeconds(),m.serviceSeconds(),m.fromLon(),m.fromLat(),m.toLon(),m.toLat(),
                        m.originZoneId(),m.destinationZoneId(),m.returning())).toList();
    }
    public List<Tour> tours() {
        Map<String, List<Movement>> groups = new TreeMap<>();
        for (var m : movements()) if (m.tourId() != null) groups.computeIfAbsent(m.tourId(), ignored -> new ArrayList<>()).add(m);
        return groups.entrySet().stream().map(e -> new Tour(e.getKey(), e.getValue().getFirst().demandSource(),
                e.getValue().getFirst().vehicleId(), e.getValue().stream().map(Movement::sourceTripKey).toList())).toList();
    }
}
