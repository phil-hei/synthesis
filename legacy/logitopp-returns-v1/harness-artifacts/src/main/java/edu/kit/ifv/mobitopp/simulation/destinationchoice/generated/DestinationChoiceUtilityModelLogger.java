package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.Map;

public interface DestinationChoiceUtilityModelLogger {
	
	public void logStart(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	public void logProbabilities(Map<Zone, Double> probabilities, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber);
	
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, Zone category, String key, double value);
			
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, String key, double value);
	
}

