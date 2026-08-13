package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen.ModeChoiceModelLogger;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultModeChoiceModelLogger implements ModeChoiceModelLogger {
	
	@Override
	public void logStart() {
		log.debug("Start choice model ModeChoiceModel for " +  "[]" );
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities) {
		StringBuilder message = new StringBuilder();
		message.append("Probabilities for " +  "[]" );
		message.append(": ");
		probabilities.entrySet().forEach(entry -> message
				.append(String.format("%s = %s, ", entry.getKey(), entry.getValue())));
		log.debug(message.toString());

	}
	
	@Override
	public void log(String category, String key, double value) {
		log.debug("Utility Parameter - "  + String.format("%s: %s = %s", String.valueOf(category), key, value) + " for " +  "[]" );
	}
	
	@Override
	public void log(String key, double value) {
		log.info(String.format("Utility %s = %s", String.valueOf(key), value) + " for " +  "[]" );
	}
	
}

