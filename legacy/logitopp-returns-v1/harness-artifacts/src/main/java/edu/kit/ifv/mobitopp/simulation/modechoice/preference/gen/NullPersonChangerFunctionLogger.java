package edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.PersonChangerFunctionLogger;
import java.lang.Double;
import java.util.Map;

public class NullPersonChangerFunctionLogger implements PersonChangerFunctionLogger {
	
	@Override
	public void logStart(PersonBuilder person) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities, PersonBuilder person) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(PersonBuilder person, String category, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(PersonBuilder person, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
}

