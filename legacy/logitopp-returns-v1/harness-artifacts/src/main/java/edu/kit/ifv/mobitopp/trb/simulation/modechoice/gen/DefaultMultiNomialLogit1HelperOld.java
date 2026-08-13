package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen.MultiNomialLogit1HelperOld;
import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import java.util.Collection;
import java.util.Set;
import edu.kit.ifv.mobitopp.populationsynthesis.EconomicalStatus;
import edu.kit.ifv.mobitopp.simulation.Employment;
import edu.kit.ifv.mobitopp.simulation.Gender;
import edu.kit.ifv.mobitopp.simulation.Graduation;
import edu.kit.ifv.mobitopp.simulation.ImpedanceIfc;
import edu.kit.ifv.mobitopp.simulation.StandardMode;
import java.util.Optional;
import edu.kit.ifv.mobitopp.data.Value;
import edu.kit.ifv.mobitopp.simulation.ActivityType;

public abstract class DefaultMultiNomialLogit1HelperOld implements MultiNomialLogit1HelperOld {
	
	private ImpedanceIfc impedance;
	
	public DefaultMultiNomialLogit1HelperOld(ImpedanceIfc impedance) {
		this.impedance = impedance;
	}
	
	
	public double getAGE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return person.age();
	}
	
	public double getGENDER(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return person.gender().getTypeAsInt();
	}
	
	public double getHAS_COMMUTER_TICKET(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (person.hasCommuterTicket()) ? 1.0d : 0.0d;
	}
	
	public double getIS_EMPLOYMENT_FULLTIME(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (person.employment().equals(Employment.FULLTIME)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_MARGINAL(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (person.employment().equals(Employment.MARGINAL)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_PARTTIME(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (person.employment().equals(Employment.PARTTIME)) ? 1.0 : 0.0;
	}
	
	public double getTRAVEL_COST_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.CAR, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_BIKE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.BIKE, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.CAR, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_PEDESTRIAN(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PEDESTRIAN, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, previousActivity.calculatePlannedEndDate());
	}
	

}

