package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.PersonChangerFunctionLogger;
import java.lang.Double;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultPersonChangerFunctionLogger implements PersonChangerFunctionLogger {
	
	@Override
	public void logStart(PersonBuilder person) {
		log.debug("Start choice model PersonChangerFunction for " + String.format("person %s", String.valueOf(person)));
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities, PersonBuilder person) {
		StringBuilder message = new StringBuilder();
		message.append("Probabilities for " + String.format("person %s", String.valueOf(person)));
		message.append(": ");
		probabilities.entrySet().forEach(entry -> message
				.append(String.format("%s = %s, ", entry.getKey(), entry.getValue())));
		log.debug(message.toString());

	}
	
	@Override
	public void log(PersonBuilder person, String category, String key, double value) {
		log.debug("Utility Parameter - "  + String.format("%s: %s = %s", String.valueOf(category), key, value) + " for " + String.format("person %s", String.valueOf(person)));
	}
	
	@Override
	public void log(PersonBuilder person, String key, double value) {
		log.info(String.format("Utility %s = %s", String.valueOf(key), value) + " for " + String.format("person %s", String.valueOf(person)));
	}
	
}

