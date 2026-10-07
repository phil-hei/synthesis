package edu.kit.ifv.mobitopp.simulation;

import edu.kit.ifv.mobitopp.simulation.parcels.IParcel;
import edu.kit.ifv.mobitopp.simulation.parcels.PrivateParcelBuilder;
import edu.kit.ifv.mobitopp.simulation.person.PickUpParcelPerson;
import edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenter;

/** Own extension: register returns as pickup requests without modifying KIT ParcelBuilder. */
public final class SynthesisPrivateParcelBuilder extends PrivateParcelBuilder {
    private IParcel built;
    public SynthesisPrivateParcelBuilder(PickUpParcelPerson person, DeliveryResults results) {
        super(person, results);
    }
    @Override public IParcel get() {
        if (built == null) {
            built = doBuild();
            if (built.isPickUp() && built.getProducer() instanceof DistributionCenter center) {
                center.requestPickup(built);
            } else {
                built.getProducer().addParcel(built);
            }
        }
        return built;
    }
}
