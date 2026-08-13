package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultDestinationChoiceUtilityModelLogger implements DestinationChoiceUtilityModelLogger {
	
	@Override
	public void logStart(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		log.debug("Start choice model DestinationChoiceUtilityModel for " + String.format("person %s, origin %s, destination %s, previousActivity %s, nextActivity %s, time %s, randomNumber %s", String.valueOf(person), String.valueOf(origin), String.valueOf(destination), String.valueOf(previousActivity), String.valueOf(nextActivity), String.valueOf(time), String.valueOf(randomNumber)));
	}
	
	@Override
	public void logProbabilities(Map<Zone, Double> probabilities, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		StringBuilder message = new StringBuilder();
		message.append("Probabilities for " + String.format("person %s, origin %s, destination %s, previousActivity %s, nextActivity %s, time %s, randomNumber %s", String.valueOf(person), String.valueOf(origin), String.valueOf(destination), String.valueOf(previousActivity), String.valueOf(nextActivity), String.valueOf(time), String.valueOf(randomNumber)));
		message.append(": ");
		probabilities.entrySet().forEach(entry -> message
				.append(String.format("%s = %s, ", entry.getKey(), entry.getValue())));
		log.debug(message.toString());

	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, Zone category, String key, double value) {
		log.debug("Utility Parameter - "  + String.format("%s: %s = %s", String.valueOf(category), key, value) + " for " + String.format("person %s, origin %s, destination %s, previousActivity %s, nextActivity %s, time %s, randomNumber %s", String.valueOf(person), String.valueOf(origin), String.valueOf(destination), String.valueOf(previousActivity), String.valueOf(nextActivity), String.valueOf(time), String.valueOf(randomNumber)));
	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber, String key, double value) {
		log.info(String.format("Utility %s = %s", String.valueOf(key), value) + " for " + String.format("person %s, origin %s, destination %s, previousActivity %s, nextActivity %s, time %s, randomNumber %s", String.valueOf(person), String.valueOf(origin), String.valueOf(destination), String.valueOf(previousActivity), String.valueOf(nextActivity), String.valueOf(time), String.valueOf(randomNumber)));
	}
	
}

