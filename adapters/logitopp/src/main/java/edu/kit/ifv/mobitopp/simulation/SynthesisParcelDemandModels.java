package edu.kit.ifv.mobitopp.simulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import edu.kit.ifv.mobitopp.simulation.demand.ParcelDemandModel;
import edu.kit.ifv.mobitopp.simulation.demand.PrivateParcelDemandModelBuilder;
import edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider;
import edu.kit.ifv.mobitopp.simulation.parcels.PrivateParcelBuilder;
import edu.kit.ifv.mobitopp.simulation.person.PickUpParcelPerson;

public final class SynthesisParcelDemandModels {

	private SynthesisParcelDemandModels() {
	}

	public static ParcelDemandModel<PickUpParcelPerson, PrivateParcelBuilder> build(
			SynthesisParcelDemandConfig config,
			Map<CEPServiceProvider, Double> providerShares,
			DeliveryResults results) {
		ParcelDemandModel<PickUpParcelPerson, PrivateParcelBuilder> deliveries =
				deliveryModel(config, providerShares, results);
		if (!config.returnsEnabled()) {
			return deliveries;
		}

		Map<Integer, Integer> quantities = new ConcurrentHashMap<>();
		PrivateParcelDemandModelBuilder pickupBuilder =
				PrivateParcelDemandModelBuilder.forPrivateParcels(results);
		pickupBuilder.useParcelFactory(agent -> new SynthesisPrivateParcelBuilder(agent, results));
		pickupBuilder.useNumberSelector((recipient, randomNumber) ->
				quantities.getOrDefault(recipient.getOid(), 0));
		pickupBuilder.allAsPickup();
		ParcelDemandModel<PickUpParcelPerson, PrivateParcelBuilder> pickups =
				pickupBuilder
						.shareBasedParcelDestinationSelection(config.pickupShares(), false)
						.shareBasedCepspSelection(providerShares, false)
						.distributionCenterSelectionInCepspByFleetsize(false)
						.useDistributionCenterAsProducer()
						.useAgentAsConsumer()
						.equalParcelSizeSelection(false)
						.selectVolumeBasedOnShipmentSize(false)
						.randomArrivalDaySelectionExcludeSunday(false)
						.build();

		return new ParcelDemandModel<>() {
			@Override
			public Collection<PrivateParcelBuilder> createParcelDemand(
					PickUpParcelPerson recipient) {
				Collection<PrivateParcelBuilder> deliveryParcels =
						deliveries.createParcelDemand(recipient);
				Collection<PrivateParcelBuilder> combined = new ArrayList<>(deliveryParcels);
				int pickupCount = pickupCount(
						deliveryParcels.size(), config.returnShare(), recipient.getNextRandom());
				if (pickupCount == 0) {
					return combined;
				}
				quantities.put(recipient.getOid(), pickupCount);
				try {
					combined.addAll(pickups.createParcelDemand(recipient));
				} finally {
					quantities.remove(recipient.getOid());
				}
				return combined;
			}

			@Override
			public void printStatistics(String label) {
				deliveries.printStatistics(label + " delivery");
				pickups.printStatistics(label + " pickup");
			}
		};
	}

	private static ParcelDemandModel<PickUpParcelPerson, PrivateParcelBuilder> deliveryModel(
			SynthesisParcelDemandConfig config,
			Map<CEPServiceProvider, Double> providerShares,
			DeliveryResults results) {
		PrivateParcelDemandModelBuilder builder =
				PrivateParcelDemandModelBuilder.forPrivateParcels(results);
		builder.useParcelFactory(agent -> new SynthesisPrivateParcelBuilder(agent, results));
		builder.useNormalDistributionNumberSelector(
				config.deliveryMean(), config.deliveryStdDev(),
				config.deliveryMin(), config.deliveryMax());
		builder.allAsDelivery();
		return builder
				.shareBasedParcelDestinationSelection(config.deliveryShares(), false)
				.shareBasedCepspSelection(providerShares, false)
				.distributionCenterSelectionInCepspByFleetsize(false)
				.useDistributionCenterAsProducer()
				.useAgentAsConsumer()
				.equalParcelSizeSelection(false)
				.selectVolumeBasedOnShipmentSize(false)
				.randomArrivalDaySelectionExcludeSunday(false)
				.build();
	}

	static int pickupCount(int deliveries, double share, double randomNumber) {
		double expected = deliveries * share;
		int base = (int) Math.floor(expected);
		return base + (randomNumber < expected - base ? 1 : 0);
	}
}
