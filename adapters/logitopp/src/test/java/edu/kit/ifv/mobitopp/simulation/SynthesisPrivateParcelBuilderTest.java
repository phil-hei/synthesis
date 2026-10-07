package edu.kit.ifv.mobitopp.simulation;
import edu.kit.ifv.mobitopp.simulation.parcels.IParcel;
import edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenter;
import edu.kit.ifv.mobitopp.simulation.person.PickUpParcelPerson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SynthesisPrivateParcelBuilderTest {
    @Test void registersPickupOnlyOnceWithoutAddingOutboundDelivery() {
        verifyRegistration(true);
    }
    @Test void registersDeliveryOnlyOnceWithoutPickupRequest() {
        verifyRegistration(false);
    }
    private void verifyRegistration(boolean pickup) {
        var parcel = mock(IParcel.class);
        var depot = mock(DistributionCenter.class);
        when(parcel.isPickUp()).thenReturn(pickup);
        when(parcel.getProducer()).thenReturn(depot);
        var builder = spy(new SynthesisPrivateParcelBuilder(mock(PickUpParcelPerson.class), null));
        doReturn(parcel).when(builder).doBuild();
        assertSame(parcel, builder.get());
        assertSame(parcel, builder.get());
        verify(builder, times(1)).doBuild();
        if (pickup) {
            verify(depot, times(1)).requestPickup(parcel);
            verify(depot, never()).addParcel(parcel);
        } else {
            verify(depot, times(1)).addParcel(parcel);
            verify(depot, never()).requestPickup(parcel);
        }
    }
}
