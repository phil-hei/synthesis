package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.MobilityProviderCustomerModelLogger;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultMobilityProviderCustomerModelLogger implements MobilityProviderCustomerModelLogger {
	
	@Override
	public void logStart() {
		log.debug("Start choice model MobilityProviderCustomerModel for " +  "[]" );
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities) {
		StringBuilder message = new StringBuilder();
		message.append("Probabilities for " +  "[]" );
		message.append(": ");
		probabilities.entrySet().forEach(entry -> message
				.append(String.format("%s = %s, ", entry.getKey(), entry.getValue())));
		log.debug(message.toString());

	}
	
	@Override
	public void log(String category, String key, double value) {
		log.debug("Utility Parameter - "  + String.format("%s: %s = %s", String.valueOf(category), key, value) + " for " +  "[]" );
	}
	
	@Override
	public void log(String key, double value) {
		log.info(String.format("Utility %s = %s", String.valueOf(key), value) + " for " +  "[]" );
	}
	
}

