package edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen;

import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.ModePreferenceHelper;
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

public abstract class DefaultModePreferenceHelper implements ModePreferenceHelper {
	
	
	
	public double getAGE(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.age();
	}
	
	public double getGENDER(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.gender().getTypeAsInt();
	}
	
	public double getHAS_COMMUTER_TICKET(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.hasCommuterTicket()) ? 1.0d : 0.0d;
	}
	
	public double getHOUSEHOLD_ECONOMICAL_STATUS(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.household().economicalStatus().getCode();
	}
	
	public double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.household().getNumberOfOwnedCars();
	}
	
	public double getHOUSEHOLD_SIZE(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return person.household().getSize();
	}
	
	public double getIS_EMPLOYMENT_EDUCATION(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.EDUCATION)) ? 1.0 : 0.0;
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
	
	public double getIS_EMPLOYMENT_STUDENT(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.STUDENT)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_PRIMARY(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.STUDENT_PRIMARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_SECONDARY(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.STUDENT_SECONDARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_TERTIARY(String category, PersonBuilder person, Map<Mode, Double> draws) {
		return (person.employment().equals(Employment.STUDENT_TERTIARY)) ? 1.0 : 0.0;
	}
	

}

