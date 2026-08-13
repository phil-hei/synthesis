package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.TimeSensitivityHelper;
import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import java.lang.Double;
import java.util.Collection;
import java.util.Map;
import edu.kit.ifv.mobitopp.populationsynthesis.EconomicalStatus;
import edu.kit.ifv.mobitopp.simulation.Employment;
import edu.kit.ifv.mobitopp.simulation.Gender;
import edu.kit.ifv.mobitopp.simulation.Graduation;
import edu.kit.ifv.mobitopp.simulation.ImpedanceIfc;
import edu.kit.ifv.mobitopp.simulation.StandardMode;
import java.util.Optional;
import edu.kit.ifv.mobitopp.data.Value;
import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.ActivityType;

public abstract class DefaultTimeSensitivityHelper implements TimeSensitivityHelper {
	
	
	
	public double getAGE(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.age();
	}
	
	public double getIS_EMPLOYMENT_FULLTIME(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.FULLTIME)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_MARGINAL(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.MARGINAL)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_PARTTIME(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.PARTTIME)) ? 1.0 : 0.0;
	}
	

}

