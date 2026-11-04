package de.synthesis.vehicle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeavyVehicleCatalogTest {
    @Test void standardHeavyTypesResolveAndRetainTheirRoutingClass() {
        for (String role : List.of("heavyTruck", "articulatedTruck")) {
            VehicleType type = VehicleCatalog.standards().stream()
                    .filter(t -> role.equals(t.category())).findFirst().orElseThrow();
            var ref = new VehicleType.TypeRef(type.id(), type.version());
            var config = new VehicleConfiguration(VehicleConfiguration.CONTRACT, "sumoTraffic", false,
                    List.of(), List.of(), List.of(ref),
                    List.of(new VehicleConfiguration.TrafficAssignment(role, ref, 1.0)), List.of(), List.of());
            var report = VehicleCatalog.validate(config);
            assertTrue(report.valid(), () -> report.errors().toString());
            var adapter = new SumoVehicleTypes(report.resolved(), 42);
            assertEquals(type.routing().sumoVehicleClass(), adapter.vehicleClass(adapter.select(role, "trip-1")));
        }
    }
}
