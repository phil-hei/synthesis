package edu.kit.ifv.mobitopp.simulation.modechoice.gen;

import static java.lang.Math.exp;
import static java.lang.Math.log;
import static java.util.stream.Collectors.toMap;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.GeneratedMixedUtilityFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.ModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.NullModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import edu.kit.ifv.mobitopp.util.randomvariable.DiscreteRandomVariable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class GeneratedMixedModeChoice implements ModeChoiceModel {
	
	private final double lambda_root;
	
	private final GeneratedMixedUtilityFunction utilities;
	private final MixedModeChoiceHelper helper;
	private final ModeChoiceModelLogger log;
	
	/**
	  * Instantiates a GeneratedMixedModeChoice with the given LogitParameters, MixedModeChoiceHelper, ModeChoiceModelLogger
	  */
	public GeneratedMixedModeChoice(LogitParameters logitParameters, MixedModeChoiceHelper helper, ModeChoiceModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		this.utilities = new GeneratedMixedUtilityFunction(logitParameters, helper, log);
		this.lambda_root = logitParameters.get("lambda_root");
	}
	
	/**
	  * Convenience constuctor: Uses a NullModeChoiceModelLogger as logger
	  */
	public GeneratedMixedModeChoice(LogitParameters logitParameters, MixedModeChoiceHelper helper) {
		this(logitParameters, helper, new NullModeChoiceModelLogger());
	}
	
	public Mode selectMode(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		this.log.logStart(person, origin, destination);
		
		Collection<String> _choiceSet = helper.getChoiceSet(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		
		// Availability flags
		boolean is_walk_available = _choiceSet.contains("Pedestrian");
		boolean is_bike_available = _choiceSet.contains("Bike");
		boolean is_passenger_available = _choiceSet.contains("Passenger");
		boolean is_card_available = _choiceSet.contains("Car");
		boolean is_pt_available = _choiceSet.contains("PublicTransport");
		boolean is_taxi_available = _choiceSet.contains("Taxi");
		boolean is_cs_ff_available = _choiceSet.contains("Carsharing_FreeFloating");
		boolean is_cs_sb_available = _choiceSet.contains("Carsharing_Station");
		boolean is_bs_available = _choiceSet.contains("BikeSharing");
		boolean is_rp_available = _choiceSet.contains("RidePooling");
		
		
		// Calculate probabilities for each category using the given utilities object with respect to the availability flags above
		double U_walk = is_walk_available ? utilities.calculateU_walk(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_walk", U_walk);
		double U_bike = is_bike_available ? utilities.calculateU_bike(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_bike", U_bike);
		double U_card = is_card_available ? utilities.calculateU_card(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_card", U_card);
		double U_carp = is_passenger_available ? utilities.calculateU_carp(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_carp", U_carp);
		double U_pt = is_pt_available ? utilities.calculateU_pt(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_pt", U_pt);
		double U_taxi = is_taxi_available ? utilities.calculateU_taxi(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_taxi", U_taxi);
		double U_cs_ff = is_cs_ff_available ? utilities.calculateU_cs_ff(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_cs_ff", U_cs_ff);
		double U_cs_sb = is_cs_sb_available ? utilities.calculateU_cs_sb(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_cs_sb", U_cs_sb);
		double U_bs = is_bs_available ? utilities.calculateU_bs(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_bs", U_bs);
		double U_rp = is_rp_available ? utilities.calculateU_rp(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber) : 0.0d;
		this.log.log(person, origin, destination, "U_rp", U_rp);
		
		
		//nest: root --------------------------------------------------------------------------------
		double exp_U_walk = is_walk_available ? exp(U_walk/lambda_root) : 0.0d;
		double exp_U_bike = is_bike_available ? exp(U_bike/lambda_root) : 0.0d;
		double exp_U_carp = is_passenger_available ? exp(U_carp/lambda_root) : 0.0d;
		double exp_U_card = is_card_available ? exp(U_card/lambda_root) : 0.0d;
		double exp_U_pt = is_pt_available ? exp(U_pt/lambda_root) : 0.0d;
		double exp_U_taxi = is_taxi_available ? exp(U_taxi/lambda_root) : 0.0d;
		double exp_U_cs_ff = is_cs_ff_available ? exp(U_cs_ff/lambda_root) : 0.0d;
		double exp_U_cs_sb = is_cs_sb_available ? exp(U_cs_sb/lambda_root) : 0.0d;
		double exp_U_bs = is_bs_available ? exp(U_bs/lambda_root) : 0.0d;
		double exp_U_rp = is_rp_available ? exp(U_rp/lambda_root) : 0.0d;
		
		//nest sum and logsum of root
		double nest_root_sum = exp_U_walk + exp_U_bike + exp_U_carp + exp_U_card + exp_U_pt + exp_U_taxi + exp_U_cs_ff + exp_U_cs_sb + exp_U_bs + exp_U_rp;
		double logsum_nest_root = log(nest_root_sum);
		
		//nest probabilities of root
		double p_walk_in_nest_root = is_walk_available ? (exp_U_walk)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_walk", exp_U_walk);
		double p_bike_in_nest_root = is_bike_available ? (exp_U_bike)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_bike", exp_U_bike);
		double p_passenger_in_nest_root = is_passenger_available ? (exp_U_carp)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_carp", exp_U_carp);
		double p_card_in_nest_root = is_card_available ? (exp_U_card)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_card", exp_U_card);
		double p_pt_in_nest_root = is_pt_available ? (exp_U_pt)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_pt", exp_U_pt);
		double p_taxi_in_nest_root = is_taxi_available ? (exp_U_taxi)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_taxi", exp_U_taxi);
		double p_cs_ff_in_nest_root = is_cs_ff_available ? (exp_U_cs_ff)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_cs_ff", exp_U_cs_ff);
		double p_cs_sb_in_nest_root = is_cs_sb_available ? (exp_U_cs_sb)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_cs_sb", exp_U_cs_sb);
		double p_bs_in_nest_root = is_bs_available ? (exp_U_bs)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_bs", exp_U_bs);
		double p_rp_in_nest_root = is_rp_available ? (exp_U_rp)/nest_root_sum : 0.0d;
		this.log.log(person, origin, destination, "exp_U_rp", exp_U_rp);
		//-------------------------------------------------------------------------------------------
		
		
		
		// Put probability of each category in a map. The probabilities are computed by multiplying the nest-probabilities along the paths in the nest structure.
		// If a category is marked '?' in the nest structure it is only added to the map if it is available
		Map<String, Double> probabilities = new HashMap<>();
		if (is_bike_available) {probabilities.put("Bike", p_bike_in_nest_root);}
		if (is_bs_available) {probabilities.put("BikeSharing", p_bs_in_nest_root);}
		if (is_card_available) {probabilities.put("Car", p_card_in_nest_root);}
		if (is_cs_ff_available) {probabilities.put("Carsharing_FreeFloating", p_cs_ff_in_nest_root);}
		if (is_cs_sb_available) {probabilities.put("Carsharing_Station", p_cs_sb_in_nest_root);}
		if (is_passenger_available) {probabilities.put("Passenger", p_passenger_in_nest_root);}
		if (is_walk_available) {probabilities.put("Pedestrian", p_walk_in_nest_root);}
		if (is_pt_available) {probabilities.put("PublicTransport", p_pt_in_nest_root);}
		if (is_rp_available) {probabilities.put("RidePooling", p_rp_in_nest_root);}
		if (is_taxi_available) {probabilities.put("Taxi", p_taxi_in_nest_root);}
		
		this.log.logProbabilities(probabilities, person, origin, destination);
		
		//Filter probabilities
		Map<String, Double> filteredProbabilities = probabilities
			.entrySet()
			.stream()
			.filter(e -> !Double.isNaN(e.getValue()))
			.collect(toMap(Entry::getKey, Entry::getValue));
		
		DiscreteRandomVariable<String> distribution = new DiscreteRandomVariable<>(filteredProbabilities);
		String choice = distribution.realization(helper.getRandom(person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber));
		return helper.resolve(choice);
	}

}

