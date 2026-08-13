package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import static java.lang.Math.exp;
import static java.lang.Math.log;
import static java.util.stream.Collectors.toMap;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.NullPersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.PersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.TimeSensitivityUtilityFunction;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import edu.kit.ifv.mobitopp.util.randomvariable.DiscreteRandomVariable;
import java.lang.Double;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class TimeSensitivity implements PersonChangerFunction {
	
	private final double lambda_root;
	
	private final TimeSensitivityUtilityFunction utilities;
	private final TimeSensitivityHelper helper;
	private final PersonChangerFunctionLogger log;
	
	/**
	  * Instantiates a TimeSensitivity with the given LogitParameters, TimeSensitivityHelper, PersonChangerFunctionLogger
	  */
	public TimeSensitivity(LogitParameters logitParameters, TimeSensitivityHelper helper, PersonChangerFunctionLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		this.utilities = new TimeSensitivityUtilityFunction(logitParameters, helper, log);
		this.lambda_root = logitParameters.get("lambda_root");
	}
	
	/**
	  * Convenience constuctor: Uses a NullPersonChangerFunctionLogger as logger
	  */
	public TimeSensitivity(LogitParameters logitParameters, TimeSensitivityHelper helper) {
		this(logitParameters, helper, new NullPersonChangerFunctionLogger());
	}
	
	public double calculateSensitivity(PersonBuilder person, Map<Mode, Double> draws) {
		this.log.logStart(person);
		
		Collection<String> _choiceSet = helper.getChoiceSet(person, draws);
		
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
		double U_tt_ped = is_walk_available ? utilities.calculateU_tt_ped(person, draws) : 0.0d;
		this.log.log(person, "U_tt_ped", U_tt_ped);
		double U_tt_bike = is_bike_available ? utilities.calculateU_tt_bike(person, draws) : 0.0d;
		this.log.log(person, "U_tt_bike", U_tt_bike);
		double U_tt_car_p = is_passenger_available ? utilities.calculateU_tt_car_p(person, draws) : 0.0d;
		this.log.log(person, "U_tt_car_p", U_tt_car_p);
		double U_tt_car_d = is_card_available ? utilities.calculateU_tt_car_d(person, draws) : 0.0d;
		this.log.log(person, "U_tt_car_d", U_tt_car_d);
		double U_tt_put = is_pt_available ? utilities.calculateU_tt_put(person, draws) : 0.0d;
		this.log.log(person, "U_tt_put", U_tt_put);
		double U_tt_taxi = is_taxi_available ? utilities.calculateU_tt_taxi(person, draws) : 0.0d;
		this.log.log(person, "U_tt_taxi", U_tt_taxi);
		double U_tt_cs_ff = is_cs_ff_available ? utilities.calculateU_tt_cs_ff(person, draws) : 0.0d;
		this.log.log(person, "U_tt_cs_ff", U_tt_cs_ff);
		double U_tt_cs_sb = is_cs_sb_available ? utilities.calculateU_tt_cs_sb(person, draws) : 0.0d;
		this.log.log(person, "U_tt_cs_sb", U_tt_cs_sb);
		double U_tt_rp = is_rp_available ? utilities.calculateU_tt_rp(person, draws) : 0.0d;
		this.log.log(person, "U_tt_rp", U_tt_rp);
		double U_tt_bs = is_bs_available ? utilities.calculateU_tt_bs(person, draws) : 0.0d;
		this.log.log(person, "U_tt_bs", U_tt_bs);
		
		
		//nest: root --------------------------------------------------------------------------------
		double exp_U_tt_ped = is_walk_available ? exp(U_tt_ped/lambda_root) : 0.0d;
		double exp_U_tt_bike = is_bike_available ? exp(U_tt_bike/lambda_root) : 0.0d;
		double exp_U_tt_car_p = is_passenger_available ? exp(U_tt_car_p/lambda_root) : 0.0d;
		double exp_U_tt_car_d = is_card_available ? exp(U_tt_car_d/lambda_root) : 0.0d;
		double exp_U_tt_put = is_pt_available ? exp(U_tt_put/lambda_root) : 0.0d;
		double exp_U_tt_taxi = is_taxi_available ? exp(U_tt_taxi/lambda_root) : 0.0d;
		double exp_U_tt_cs_ff = is_cs_ff_available ? exp(U_tt_cs_ff/lambda_root) : 0.0d;
		double exp_U_tt_cs_sb = is_cs_sb_available ? exp(U_tt_cs_sb/lambda_root) : 0.0d;
		double exp_U_tt_bs = is_bs_available ? exp(U_tt_bs/lambda_root) : 0.0d;
		double exp_U_tt_rp = is_rp_available ? exp(U_tt_rp/lambda_root) : 0.0d;
		
		//nest sum and logsum of root
		double nest_root_sum = exp_U_tt_ped + exp_U_tt_bike + exp_U_tt_car_p + exp_U_tt_car_d + exp_U_tt_put + exp_U_tt_taxi + exp_U_tt_cs_ff + exp_U_tt_cs_sb + exp_U_tt_bs + exp_U_tt_rp;
		double logsum_nest_root = log(nest_root_sum);
		
		//nest probabilities of root
		double p_walk_in_nest_root = is_walk_available ? (exp_U_tt_ped)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_ped", exp_U_tt_ped);
		double p_bike_in_nest_root = is_bike_available ? (exp_U_tt_bike)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_bike", exp_U_tt_bike);
		double p_passenger_in_nest_root = is_passenger_available ? (exp_U_tt_car_p)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_car_p", exp_U_tt_car_p);
		double p_card_in_nest_root = is_card_available ? (exp_U_tt_car_d)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_car_d", exp_U_tt_car_d);
		double p_pt_in_nest_root = is_pt_available ? (exp_U_tt_put)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_put", exp_U_tt_put);
		double p_taxi_in_nest_root = is_taxi_available ? (exp_U_tt_taxi)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_taxi", exp_U_tt_taxi);
		double p_cs_ff_in_nest_root = is_cs_ff_available ? (exp_U_tt_cs_ff)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_cs_ff", exp_U_tt_cs_ff);
		double p_cs_sb_in_nest_root = is_cs_sb_available ? (exp_U_tt_cs_sb)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_cs_sb", exp_U_tt_cs_sb);
		double p_bs_in_nest_root = is_bs_available ? (exp_U_tt_bs)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_bs", exp_U_tt_bs);
		double p_rp_in_nest_root = is_rp_available ? (exp_U_tt_rp)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_U_tt_rp", exp_U_tt_rp);
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
		
		this.log.logProbabilities(probabilities, person);
		
		//Filter probabilities
		Map<String, Double> filteredProbabilities = probabilities
			.entrySet()
			.stream()
			.filter(e -> !Double.isNaN(e.getValue()))
			.collect(toMap(Entry::getKey, Entry::getValue));
		
		DiscreteRandomVariable<String> distribution = new DiscreteRandomVariable<>(filteredProbabilities);
		String choice = distribution.realization(helper.getRandom(person, draws));
		return helper.resolve(choice);
	}

}

