package edu.kit.ifv.mobitopp.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import java.util.Map;
import java.util.Set;

public interface ModeChoiceModelLogger {
	
	public void logStart(Person person, Zone origin, Zone destination);
	
	public void logProbabilities(Map<String, Double> probabilities, Person person, Zone origin, Zone destination);
	
	public void log(Person person, Zone origin, Zone destination, String category, String key, double value);
			
	public void log(Person person, Zone origin, Zone destination, String key, double value);
	
}

