package de.synthesis.vehicle;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** Simulator-independent type. Runtime load and charge belong to VehicleInstance. */
public record VehicleType(String id, String version, String name, String category,
                          Provenance provenance, PhysicalProperties physical,
                          TransportCapacity capacity, Powertrain powertrain,
                          DrivingProfile driving, RoutingProfile routing,
                          OperationProfile operation, EmissionReference emissions,
                          NoiseReference noise) {
    public String key() { return id + "@" + version; }
    public record TypeRef(String id, String version) {
        public String key() { return id + "@" + version; }
    }
    public record Provenance(String source, String quality, String notes) { }
    /** Curb mass excludes occupants and payload; gross mass includes both. */
    public record PhysicalProperties(Double curbMassKg, Double maxGrossMassKg,
                                     Double lengthM, Double widthM, Double heightM,
                                     Double maxSpeedKmh, Double maxAccelerationMps2) { }
    public record TransportCapacity(Integer passengerSeats, Integer operatorSeats,
                                    Double payloadKg, Double cargoVolumeM3) { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CombustionPowertrain.class, name = "combustion"),
        @JsonSubTypes.Type(value = ElectricPowertrain.class, name = "electric"),
        @JsonSubTypes.Type(value = HumanPowered.class, name = "human")
    })
    public sealed interface Powertrain permits CombustionPowertrain, ElectricPowertrain, HumanPowered { }
    public record CombustionPowertrain(String fuel, Double tankCapacityL,
                                      Double litresPerKm) implements Powertrain { }
    public record ElectricPowertrain(Double usableBatteryCapacityKwh, Double maxChargePowerKw,
                                    Double chargingEfficiency, Double recuperationEfficiency,
                                    Double kwhPerKm, String assistanceMode,
                                    Double assistanceCutoffKmh, List<String> connectors) implements Powertrain { }
    public record HumanPowered() implements Powertrain { }
    public record DrivingProfile(String controlMode, String carFollowModel,
                                 Double accelerationMps2, Double decelerationMps2,
                                 Double emergencyDecelerationMps2, Double minGapM,
                                 Double timeHeadwayS) { }
    /** vClass is an explicit adapter mapping, not inferred from the type's name. */
    public record RoutingProfile(String sumoVehicleClass, String sumoEmissionClass,
                                 List<String> allowedNetworks) { }
    public record OperationProfile(String deliveryMode, Double loadingTimeS,
                                   Double unloadingTimeS, String serviceTimeBasis,
                                   String parkingBehavior) { }
    public record EmissionReference(Double gramsPerKm, String substance, String boundary,
                                    String source) { }
    public record NoiseReference(Double decibels, String weighting, Double distanceM,
                                Double speedKmh, String conditions) { }
}
