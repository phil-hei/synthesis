package edu.kit.ifv.mobitopp.simulation;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.events.EventQueue;
import edu.kit.ifv.mobitopp.simulation.person.*;
import edu.kit.ifv.mobitopp.simulation.tour.TourFactory;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.List;
import java.util.Set;

/** Preserve exported typed education coordinates; keep KIT destination and mode choice. */
public class SynthesisSimulationPerson extends SimulationPersonPassenger {
    public SynthesisSimulationPerson(Person person, EventQueue queue, SimulationOptions options,
            List<Time> days, Set<Mode> modes, TourFactory tours, TripFactory trips,
            PersonState initialState, PublicTransportBehaviour publicTransport, long seed,
            PersonListener listener) {
        super(person, queue, options, days, modes, tours, trips, initialState, publicTransport, seed, listener);
    }
    @Override protected Location selectLocation(Person person, ActivityIfc activity, Zone zone) {
        // Keep KIT's random draw consumption (opportunity draw or centroid fallback)
        // so changing coordinates does not perturb later destination/mode decisions.
        Location selected = super.selectLocation(person, activity, zone);
        var type = activity.activityType();
        if ((type == ActivityType.EDUCATION_PRIMARY || type == ActivityType.EDUCATION_SECONDARY
                || type == ActivityType.EDUCATION_TERTIARY)
                && person.hasFixedZoneFor(type) && zone.equals(person.fixedZoneFor(type)))
            return person.fixedDestinationFor(type);
        return selected;
    }
}
