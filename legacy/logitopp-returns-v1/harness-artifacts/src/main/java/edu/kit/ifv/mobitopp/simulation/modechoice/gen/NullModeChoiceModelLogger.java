package edu.kit.ifv.mobitopp.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.ModeChoiceModelLogger;
import java.util.Map;
import java.util.Set;

public class NullModeChoiceModelLogger implements ModeChoiceModelLogger {
	
	@Override
	public void logStart(Person person, Zone origin, Zone destination) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities, Person person, Zone origin, Zone destination) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, String category, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(Person person, Zone origin, Zone destination, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
}

