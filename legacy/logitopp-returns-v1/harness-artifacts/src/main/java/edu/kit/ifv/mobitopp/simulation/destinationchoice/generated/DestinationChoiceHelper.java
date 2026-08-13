package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceUtilityModel;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.Collection;

public interface DestinationChoiceHelper {
	
	double getACCESS_TIME_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAGE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getATTRACTIVITY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_BS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_FUSS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_MF(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_OEV(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_PKW(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getAVAIL_RAD(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getCARS_PER_ADULT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getDISTANCE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getEGRESS_TIME_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getHAS_COMMUTER_TICKET(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getHOUSEHOLD_ECONOMICAL_STATUS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_EDUCATION(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_HOMEKEEPER(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_STUDENT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_STUDENT_PRIMARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_STUDENT_SECONDARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_STUDENT_TERTIARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_EMPLOYMENT_UNEMPLOYED(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getIS_INTRAZONAL(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_COST_BIKESHARING_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_COST_CAR(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_COST_CAR_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_COST_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_COST_PUBLICTRANSPORT_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_BIKE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_BIKE_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_CAR(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_CAR_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_PEDESTRIAN(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_PEDESTRIAN_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	double getTRAVEL_TIME_PUBLICTRANSPORT_FIX(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	
	/**
	  * Returns a random double value. 
	  * This random value can be based on the given parameters as well as the implementing classes fields.
	  */
	double getRandom(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	/**
	  * Checks if the given category is available (category = e.g. mode of transportation, depends on the kind of choice model)
	  */
	boolean isAvailable(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	/**
	  * Converts the given category String to the return type Zone of DestinationChoiceUtilityModel.selectDestination(...).
	  */
	Zone resolve(String category);
			
	Collection<Zone> getChoiceSet(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
}

