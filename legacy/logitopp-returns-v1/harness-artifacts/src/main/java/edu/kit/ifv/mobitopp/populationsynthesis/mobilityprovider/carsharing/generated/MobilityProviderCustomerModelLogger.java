package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import java.util.Map;

public interface MobilityProviderCustomerModelLogger {
	
	public void logStart();
	
	public void logProbabilities(Map<String, Double> probabilities);
	
	public void log(String category, String key, double value);
			
	public void log(String key, double value);
	
}

