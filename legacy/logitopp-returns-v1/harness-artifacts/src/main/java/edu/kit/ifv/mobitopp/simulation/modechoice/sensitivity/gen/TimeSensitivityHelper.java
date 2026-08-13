package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import java.lang.Double;
import java.util.Collection;
import java.util.Map;

public interface TimeSensitivityHelper {
	
	double getAGE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_TIME_BIKE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_TIME_CAR(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_TIME_PASSENGER(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_TIME_PUBLICTRANSPORT(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_FULLTIME(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_MARGINAL(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_PARTTIME(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	
	/**
	  * Returns a random double value. 
	  * This random value can be based on the given parameters as well as the implementing classes fields.
	  */
	double getRandom(PersonBuilder person, Map<Mode, Double> draws);
	
	/**
	  * Checks if the given category is available (category = e.g. mode of transportation, depends on the kind of choice model)
	  */
	boolean isAvailable(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	/**
	  * Converts the given category String to the return type double of PersonChangerFunction.calculateSensitivity(...).
	  */
	double resolve(String category);
			
	Collection<String> getChoiceSet(PersonBuilder person, Map<Mode, Double> draws);
}

