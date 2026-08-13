package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import java.util.Map;
import java.util.Set;

public interface ModeChoiceModelLogger {
	
	public void logStart();
	
	public void logProbabilities(Map<String, Double> probabilities);
	
	public void log(String category, String key, double value);
			
	public void log(String key, double value);
	
}

