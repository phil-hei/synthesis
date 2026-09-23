package de.synthesis.vehicle;

import java.util.List;
import de.synthesis.vehicle.VehicleType.TypeRef;

/** Versioned catalogue plus explicit downstream traffic allocation. Default: storage only. */
public record VehicleConfiguration(String schemaVersion, String application,
                                   boolean allowBehaviorApproximation,
                                   List<String> requiredCapabilities,
                                   List<VehicleType> vehicleTypes, List<TypeRef> catalogRefs,
                                   List<TrafficAssignment> trafficAssignments,
                                   List<FleetAssignment> fleetAssignments,
                                   List<VehicleInstance> initialVehicles) {
    public static final String CONTRACT = "synthesis-vehicle-configuration-v1";
    public static VehicleConfiguration empty() {
        return new VehicleConfiguration(CONTRACT, "catalogOnly", false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
    public boolean appliesToSumo() { return "sumoTraffic".equals(application); }
    /** Shares of existing trips, NOT fleet counts or mode-choice shares. */
    public record TrafficAssignment(String role, TypeRef typeRef, Double share) { }
    public record FleetAssignment(String id, TypeRef typeRef, Integer count, String locationRef) { }
    public record VehicleInstance(String vehicleId, TypeRef typeRef, String fleetId,
                                  String locationRef, VehicleState state) { }
    public record VehicleState(Double cargoWeightKg, Double cargoVolumeM3,
                               Integer passengers, Integer operators,
                               Double batteryEnergyKwh, Double fuelLitres) { }
}
