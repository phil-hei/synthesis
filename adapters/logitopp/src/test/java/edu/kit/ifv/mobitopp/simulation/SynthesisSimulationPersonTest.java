package edu.kit.ifv.mobitopp.simulation;
import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.person.SimulationPersonPassenger;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisSimulationPersonTest {
    private static <T extends SimulationPersonPassenger> T actor(Class<T> type)throws Exception {
        T actor=mock(type,CALLS_REAL_METHODS);
        var random=SimulationPersonPassenger.class.getDeclaredField("random");random.setAccessible(true);random.set(actor,new Random(42));
        return actor;
    }
    @Test void actualKitSelectorLosesTypedEducationPointButAdapterRetainsIt()throws Exception {
        var person=mock(Person.class);var activity=mock(ActivityIfc.class);var zone=mock(Zone.class);
        var fixed=mock(Location.class);var centroid=mock(Location.class);when(zone.centroidLocation()).thenReturn(centroid);
        var original=actor(SimulationPersonPassenger.class);var corrected=actor(SynthesisSimulationPerson.class);
        var select=SimulationPersonPassenger.class.getDeclaredMethod("selectLocation",Person.class,ActivityIfc.class,Zone.class);select.setAccessible(true);
        for(var type:new ActivityType[]{ActivityType.EDUCATION_PRIMARY,ActivityType.EDUCATION_SECONDARY,ActivityType.EDUCATION_TERTIARY}) {
            when(activity.activityType()).thenReturn(type);when(person.hasFixedZoneFor(type)).thenReturn(true);
            when(person.fixedZoneFor(type)).thenReturn(zone);when(person.fixedDestinationFor(type)).thenReturn(fixed);
            assertSame(centroid,select.invoke(original,person,activity,zone));
            assertSame(fixed,corrected.selectLocation(person,activity,zone));
            var random=SimulationPersonPassenger.class.getDeclaredField("random");random.setAccessible(true);
            assertEquals(((Random)random.get(original)).nextLong(),((Random)random.get(corrected)).nextLong());
        }
    }
    @Test void differentDestinationZoneAndUnfixedEducationKeepKitSelection()throws Exception {
        var actor=actor(SynthesisSimulationPerson.class);var person=mock(Person.class);var activity=mock(ActivityIfc.class);var zone=mock(Zone.class);var centroid=mock(Location.class);
        when(zone.centroidLocation()).thenReturn(centroid);when(activity.activityType()).thenReturn(ActivityType.EDUCATION_TERTIARY);
        assertSame(centroid,actor.selectLocation(person,activity,zone));
        when(person.hasFixedZoneFor(ActivityType.EDUCATION_TERTIARY)).thenReturn(true);when(person.fixedZoneFor(ActivityType.EDUCATION_TERTIARY)).thenReturn(mock(Zone.class));
        assertSame(centroid,actor.selectLocation(person,activity,zone));verify(person,never()).fixedDestinationFor(any());
        when(activity.activityType()).thenReturn(ActivityType.SERVICE);
        assertSame(centroid,actor.selectLocation(person,activity,zone));
    }
}
