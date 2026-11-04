package de.synthesis.vehicle;
import de.synthesis.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleOperationsContractTest {
    @Test void nativeChainsAreAnExplicitOptInAndTheFreightExampleIsValid() throws Exception {
        var controller = new VehicleController(null);
        assertFalse(VehicleOperationsConfiguration.empty().chainTrips());
        var preview = controller.validateOperations(controller.freightOperationsExample().toString());
        assertTrue(preview.valid(), () -> preview.errors().toString()); assertTrue(preview.vehicleOperations().chainTrips());
        var scenario = new Scenario(); scenario.targetModels = List.of("sumo"); scenario.vehicleConfiguration = preview.vehicleConfiguration();
        scenario.vehicleOperations = new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT, false, .1, List.of(), true);
        assertTrue(VehicleOperationsConfiguration.validate(scenario).stream().anyMatch(i -> i.path().equals("vehicleOperations.chainTrips")));
    }
    @Test void sharedVehicleBindingsAreValidWithoutSelectingSumo() throws Exception {
        var controller=new VehicleController(null);var example=controller.operationsExample();
        var preview=controller.validateOperations(example.toString());
        var scenario=new Scenario();scenario.targetModels=List.of("logitopp");scenario.vehicleConfiguration=preview.vehicleConfiguration();
        scenario.vehicleOperations=new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT,true,.1,
                List.of(new VehicleOperationsConfiguration.Binding(VehicleOperationsConfiguration.personKey("p","a"),"ev-1")));
        assertTrue(VehicleOperationsConfiguration.validate(scenario).isEmpty());
    }
    @Test void validatesExampleAndRejectsDuplicateUnknownAndUnversionedBindings() throws Exception {
        var controller=new VehicleController(null); var example=controller.operationsExample();
        var result=controller.validateOperations(example.toString()); assertTrue(result.valid(),()->result.errors().toString());
        assertNotNull(result.vehicleConfigurationSha256());
        var scenario=new Scenario();scenario.targetModels=List.of("sumo");scenario.vehicleConfiguration=result.vehicleConfiguration();
        String key=VehicleOperationsConfiguration.personKey("p","a"); var binding=new VehicleOperationsConfiguration.Binding(key,"ev-1");
        scenario.vehicleOperations=new VehicleOperationsConfiguration(VehicleOperationsConfiguration.CONTRACT,true,.1,List.of(binding,binding));
        assertTrue(VehicleOperationsConfiguration.validate(scenario).stream().anyMatch(i->i.message().contains("mehrfach")));
        scenario.vehicleOperations=new VehicleOperationsConfiguration("unknown",true,.1,List.of(new VehicleOperationsConfiguration.Binding(key,"absent")));
        assertEquals(2,VehicleOperationsConfiguration.validate(scenario).size());
        var altered=example.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)altered.path("vehicleOperations")).put("unrecognizedField",true);
        assertThrows(java.io.IOException.class,()->controller.validateOperations(altered.toString()));
    }
}
