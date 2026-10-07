package edu.kit.ifv.mobitopp.simulation;

import java.util.List;

import edu.kit.ifv.mobitopp.simulation.parcels.IParcel;
import edu.kit.ifv.mobitopp.simulation.parcels.clustering.DeliveryClusteringStrategy;

/**
 * Gruppiert Pickup- und Zustellpakete nach dem tatsächlichen Tourstopp.
 * AgentDeliveryClustering gruppiert Pickups nach Producer; bei privaten
 * Pickups ist das für alle Aufträge dasselbe Depot.
 */
public class SynthesisParcelLocationClustering implements DeliveryClusteringStrategy {

	@Override
	public boolean canBeGrouped(IParcel first, IParcel second) {
		return first.getZone().equals(second.getZone())
				&& first.getLocation().equals(second.getLocation());
	}

	@Override
	public ZoneAndLocation getStopLocation(List<IParcel> parcels) {
		return parcels.get(0).getZoneAndLocation();
	}
}
