package edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen;

import static java.lang.Math.exp;
import static java.lang.Math.log;
import static java.util.stream.Collectors.toMap;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.ModePreferenceUtilityFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.NullPersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.PersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import edu.kit.ifv.mobitopp.util.randomvariable.DiscreteRandomVariable;
import java.lang.Double;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class ModePreference implements PersonChangerFunction {
	
	private final double lambda_root;
	
	private final ModePreferenceUtilityFunction utilities;
	private final ModePreferenceHelper helper;
	private final PersonChangerFunctionLogger log;
	
	/**
	  * Instantiates a ModePreference with the given LogitParameters, ModePreferenceHelper, PersonChangerFunctionLogger
	  */
	public ModePreference(LogitParameters logitParameters, ModePreferenceHelper helper, PersonChangerFunctionLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		this.utilities = new ModePreferenceUtilityFunction(logitParameters, helper, log);
		this.lambda_root = logitParameters.get("lambda_root");
	}
	
	/**
	  * Convenience constuctor: Uses a NullPersonChangerFunctionLogger as logger
	  */
	public ModePreference(LogitParameters logitParameters, ModePreferenceHelper helper) {
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
		boolean is_cs_sb_available = _choiceSet.contains("Carsharing_Station");
		boolean is_cs_ff_available = _choiceSet.contains("Carsharing_FreeFloating");
		boolean is_rp_available = _choiceSet.contains("RidePooling");
		boolean is_taxi_available = _choiceSet.contains("Taxi");
		boolean is_bs_available = _choiceSet.contains("BikeSharing");
		
		
		// Calculate probabilities for each category using the given utilities object with respect to the availability flags above
		double Asc_bike = is_bike_available ? utilities.calculateAsc_bike(person, draws) : 0.0d;
		this.log.log(person, "Asc_bike", Asc_bike);
		double Asc_car_p = is_passenger_available ? utilities.calculateAsc_car_p(person, draws) : 0.0d;
		this.log.log(person, "Asc_car_p", Asc_car_p);
		double Asc_car_d = is_card_available ? utilities.calculateAsc_car_d(person, draws) : 0.0d;
		this.log.log(person, "Asc_car_d", Asc_car_d);
		double Asc_put = is_pt_available ? utilities.calculateAsc_put(person, draws) : 0.0d;
		this.log.log(person, "Asc_put", Asc_put);
		double Asc_cs_sb = is_cs_sb_available ? utilities.calculateAsc_cs_sb(person, draws) : 0.0d;
		this.log.log(person, "Asc_cs_sb", Asc_cs_sb);
		double Asc_cs_ff = is_cs_ff_available ? utilities.calculateAsc_cs_ff(person, draws) : 0.0d;
		this.log.log(person, "Asc_cs_ff", Asc_cs_ff);
		double Asc_rp = is_rp_available ? utilities.calculateAsc_rp(person, draws) : 0.0d;
		this.log.log(person, "Asc_rp", Asc_rp);
		double Asc_ped = is_walk_available ? utilities.calculateAsc_ped(person, draws) : 0.0d;
		this.log.log(person, "Asc_ped", Asc_ped);
		double Asc_taxi = is_taxi_available ? utilities.calculateAsc_taxi(person, draws) : 0.0d;
		this.log.log(person, "Asc_taxi", Asc_taxi);
		double Asc_bs = is_bs_available ? utilities.calculateAsc_bs(person, draws) : 0.0d;
		this.log.log(person, "Asc_bs", Asc_bs);
		
		
		//nest: root --------------------------------------------------------------------------------
		double exp_Asc_ped = is_walk_available ? exp(Asc_ped/lambda_root) : 0.0d;
		double exp_Asc_bike = is_bike_available ? exp(Asc_bike/lambda_root) : 0.0d;
		double exp_Asc_car_p = is_passenger_available ? exp(Asc_car_p/lambda_root) : 0.0d;
		double exp_Asc_car_d = is_card_available ? exp(Asc_car_d/lambda_root) : 0.0d;
		double exp_Asc_put = is_pt_available ? exp(Asc_put/lambda_root) : 0.0d;
		double exp_Asc_cs_sb = is_cs_sb_available ? exp(Asc_cs_sb/lambda_root) : 0.0d;
		double exp_Asc_cs_ff = is_cs_ff_available ? exp(Asc_cs_ff/lambda_root) : 0.0d;
		double exp_Asc_rp = is_rp_available ? exp(Asc_rp/lambda_root) : 0.0d;
		double exp_Asc_taxi = is_taxi_available ? exp(Asc_taxi/lambda_root) : 0.0d;
		double exp_Asc_bs = is_bs_available ? exp(Asc_bs/lambda_root) : 0.0d;
		
		//nest sum and logsum of root
		double nest_root_sum = exp_Asc_ped + exp_Asc_bike + exp_Asc_car_p + exp_Asc_car_d + exp_Asc_put + exp_Asc_cs_sb + exp_Asc_cs_ff + exp_Asc_rp + exp_Asc_taxi + exp_Asc_bs;
		double logsum_nest_root = log(nest_root_sum);
		
		//nest probabilities of root
		double p_walk_in_nest_root = is_walk_available ? (exp_Asc_ped)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_ped", exp_Asc_ped);
		double p_bike_in_nest_root = is_bike_available ? (exp_Asc_bike)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_bike", exp_Asc_bike);
		double p_passenger_in_nest_root = is_passenger_available ? (exp_Asc_car_p)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_car_p", exp_Asc_car_p);
		double p_card_in_nest_root = is_card_available ? (exp_Asc_car_d)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_car_d", exp_Asc_car_d);
		double p_pt_in_nest_root = is_pt_available ? (exp_Asc_put)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_put", exp_Asc_put);
		double p_cs_sb_in_nest_root = is_cs_sb_available ? (exp_Asc_cs_sb)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_cs_sb", exp_Asc_cs_sb);
		double p_cs_ff_in_nest_root = is_cs_ff_available ? (exp_Asc_cs_ff)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_cs_ff", exp_Asc_cs_ff);
		double p_rp_in_nest_root = is_rp_available ? (exp_Asc_rp)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_rp", exp_Asc_rp);
		double p_taxi_in_nest_root = is_taxi_available ? (exp_Asc_taxi)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_taxi", exp_Asc_taxi);
		double p_bs_in_nest_root = is_bs_available ? (exp_Asc_bs)/nest_root_sum : 0.0d;
		this.log.log(person, "exp_Asc_bs", exp_Asc_bs);
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

