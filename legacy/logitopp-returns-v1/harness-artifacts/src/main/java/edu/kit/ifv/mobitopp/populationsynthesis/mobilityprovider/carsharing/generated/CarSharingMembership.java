package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import static java.lang.Math.exp;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.CarSharingMembershipUtilityFunction;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.MobilityProviderCustomerModelLogger;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.NullMobilityProviderCustomerModelLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.util.Collection;
import java.util.Map;

public class CarSharingMembership implements MobilityProviderCustomerModel {
	
	
	private final CarSharingMembershipUtilityFunction utilities;
	private final CarSharingMembershipHelper helper;
	private final MobilityProviderCustomerModelLogger log;
	
	/**
	  * Instantiates a CarSharingMembership with the given CarSharingMembershipHelper, MobilityProviderCustomerModelLogger
	  */
	public CarSharingMembership(LogitParameters logitParameters, CarSharingMembershipHelper helper, MobilityProviderCustomerModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		this.utilities = new CarSharingMembershipUtilityFunction(logitParameters, helper, log);
	}
	
	/**
	  * Convenience constuctor: Uses a NullMobilityProviderCustomerModelLogger as logger
	  */
	public CarSharingMembership(LogitParameters logitParameters, CarSharingMembershipHelper helper) {
		this(logitParameters, helper, new NullMobilityProviderCustomerModelLogger());
	}
	
	public boolean estimateCustomership(PersonBuilder person) {
		this.log.logStart();
		
		// Calculate single utilitiy/probability for non zero utility function
		double utility = utilities.calculateU_cs_member(person);
		double probability = exp(utility) / (1.0 + exp(utility));
		this.log.log("utility", utility);
		
		Map<String, Double> probabilities = Map.of("Cs_member", probability);
		this.log.logProbabilities(probabilities);
		
		double random = helper.getRandom(person);
		String choice = (random < probability) ? "Cs_member" : "No_cs_member";
		return helper.resolve(choice);
	}

}

