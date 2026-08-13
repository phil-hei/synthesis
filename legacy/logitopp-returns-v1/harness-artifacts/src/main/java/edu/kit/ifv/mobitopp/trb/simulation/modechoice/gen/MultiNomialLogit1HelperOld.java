package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import java.util.Collection;
import java.util.Set;

public interface MultiNomialLogit1HelperOld {
	
	double getACCESS_TIME_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getAGE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getECONOMICAL_STATUS(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getEGRESS_TIME_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getGENDER(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getHAS_COMMUTER_TICKET(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getIS_EMPLOYMENT_FULLTIME(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getIS_EMPLOYMENT_MARGINAL(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getIS_EMPLOYMENT_PARTTIME(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRANSFER_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_COST_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_COST_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_TIME_BIKE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_TIME_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_TIME_PEDESTRIAN(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	double getTRAVEL_TIME_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	
	/**
	  * Returns a random double value. 
	  * This random value can be based on the given parameters as well as the implementing classes fields.
	  */
	double getRandom(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	/**
	  * Checks if the given category is available (category = e.g. mode of transportation, depends on the kind of choice model)
	  */
	boolean isAvailable(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
	
	/**
	  * Converts the given category String to the return type Mode of ModeChoiceModel.selectMode(...).
	  */
	Mode resolve(String category);
			
	Collection<String> getChoiceSet(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber);
}

