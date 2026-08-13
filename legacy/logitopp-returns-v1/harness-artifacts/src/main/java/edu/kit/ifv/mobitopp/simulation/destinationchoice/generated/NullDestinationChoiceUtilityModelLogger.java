package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.Map;

public class NullDestinationChoiceUtilityModelLogger implements DestinationChoiceUtilityModelLogger {
	
	@Override
	public void logStart(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void logProbabilities(Map<Zone, Double> probabilities, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, Zone category, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
}

