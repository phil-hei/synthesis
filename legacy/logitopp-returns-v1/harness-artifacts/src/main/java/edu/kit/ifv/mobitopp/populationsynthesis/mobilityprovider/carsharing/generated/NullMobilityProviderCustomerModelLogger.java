package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.MobilityProviderCustomerModelLogger;
import java.util.Map;

public class NullMobilityProviderCustomerModelLogger implements MobilityProviderCustomerModelLogger {
	
	@Override
	public void logStart() {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(String category, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
}

