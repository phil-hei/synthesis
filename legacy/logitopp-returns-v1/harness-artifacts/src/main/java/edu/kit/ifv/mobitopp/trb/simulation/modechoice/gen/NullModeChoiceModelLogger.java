package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen.ModeChoiceModelLogger;
import java.util.Map;
import java.util.Set;

public class NullModeChoiceModelLogger implements ModeChoiceModelLogger {
	
	@Override
	public void logStart() {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void logProbabilities(Map<String, Double> probabilities) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(String category, String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
	@Override
	public void log(String key, double value) {
		//Do Nothing, since this is a Null-Object Logger
	}
	
}

