package de.synthesis.generation.pipeline;
import de.synthesis.vehicle.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnergyAccessRulesTest {
    static VehicleType type(String id) { return VehicleCatalog.standards().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(); }
    @Test void checksDimensionsAndKeepsConditionalRulesAndMissingMassUnknown() {
        var truck=type("standard.heavyTruck.diesel");
        assertTrue(EnergyAccessRules.check(Map.of("maxheight","2.0 m"),truck,false).stream().anyMatch(i->i.status().equals("violation")));
        assertTrue(EnergyAccessRules.check(Map.of("maxheight","2","maxheight:conditional","4 @ (Mo-Fr)"),truck,false).stream().allMatch(i->i.status().equals("unknown")));
        var car=type("standard.pkw.elektro");
        assertTrue(EnergyAccessRules.check(Map.of("maxweight","2 t"),car,false).stream().anyMatch(i->i.status().equals("unknown")));
        assertTrue(EnergyAccessRules.check(Map.of("barrier","gate"),car,false).stream().anyMatch(i->i.reason().equals("physical-control-unverified")));
    }
    @Test void modeAndDirectionSpecificRulesDoNotLeakAcrossProfiles() {
        var car=type("standard.pkw.elektro"); var truck=type("standard.heavyTruck.diesel");
        assertTrue(EnergyAccessRules.check(Map.of("hgv","no"),car,false).isEmpty());
        assertTrue(EnergyAccessRules.check(Map.of("hgv","no"),truck,false).stream().anyMatch(i->i.status().equals("violation")));
        assertTrue(EnergyAccessRules.check(Map.of("access:backward","no"),car,false).isEmpty());
        assertTrue(EnergyAccessRules.check(Map.of("access:backward","no"),car,true).stream().anyMatch(i->i.status().equals("violation")));
    }
}
