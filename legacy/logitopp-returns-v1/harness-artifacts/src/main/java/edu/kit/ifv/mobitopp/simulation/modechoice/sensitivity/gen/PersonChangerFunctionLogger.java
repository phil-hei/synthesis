package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import java.lang.Double;
import java.util.Map;

public interface PersonChangerFunctionLogger {
	
	public void logStart(PersonBuilder person);
	
	public void logProbabilities(Map<String, Double> probabilities, PersonBuilder person);
	
	public void log(PersonBuilder person, String category, String key, double value);
			
	public void log(PersonBuilder person, String key, double value);
	
}

