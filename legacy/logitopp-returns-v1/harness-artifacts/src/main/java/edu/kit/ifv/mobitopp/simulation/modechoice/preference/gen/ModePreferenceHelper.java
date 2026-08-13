package edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import java.lang.Double;
import java.util.Collection;
import java.util.Map;

public interface ModePreferenceHelper {
	
	double getAGE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_BIKE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_CAR(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_CS_SB(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_PASSENGER(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_PEDESTRIAN(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getDRAW_PREF_PUBLICTRANSPORT(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getGENDER(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHAS_COMMUTER_TICKET(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHAS_E_BIKE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHAS_MEMBERSHIP_CS(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHOUSEHOLD_ECONOMICAL_STATUS(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getHOUSEHOLD_SIZE(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_EDUCATION(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_FULLTIME(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_MARGINAL(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_PARTTIME(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_STUDENT(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_STUDENT_PRIMARY(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_STUDENT_SECONDARY(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	double getIS_EMPLOYMENT_STUDENT_TERTIARY(String category, PersonBuilder person, Map<Mode, Double> draws);
	
	
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

