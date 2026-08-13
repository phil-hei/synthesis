package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceHelper;
import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceUtilityModel;
import edu.kit.ifv.mobitopp.time.Time;
import java.util.Collection;
import edu.kit.ifv.mobitopp.populationsynthesis.EconomicalStatus;
import edu.kit.ifv.mobitopp.simulation.Employment;
import edu.kit.ifv.mobitopp.simulation.Gender;
import edu.kit.ifv.mobitopp.simulation.Graduation;
import edu.kit.ifv.mobitopp.simulation.ImpedanceIfc;
import edu.kit.ifv.mobitopp.simulation.StandardMode;
import java.util.Optional;
import edu.kit.ifv.mobitopp.data.Value;
import edu.kit.ifv.mobitopp.simulation.ActivityType;

public abstract class DefaultDestinationChoiceHelper implements DestinationChoiceHelper {
	
	private ImpedanceIfc impedance;
	
	public DefaultDestinationChoiceHelper(ImpedanceIfc impedance) {
		this.impedance = impedance;
	}
	
	
	public double getAGE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return person.age();
	}
	
	public double getDISTANCE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getDistance(origin.getId(), destination.getId());
	}
	
	public double getHAS_COMMUTER_TICKET(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.hasCommuterTicket()) ? 1.0d : 0.0d;
	}
	
	public double getHOUSEHOLD_ECONOMICAL_STATUS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return person.household().economicalStatus().getCode();
	}
	
	public double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return person.household().getNumberOfOwnedCars();
	}
	
	public double getIS_EMPLOYMENT_EDUCATION(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.EDUCATION)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_HOMEKEEPER(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.HOMEKEEPER)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.STUDENT)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_PRIMARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.STUDENT_PRIMARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_SECONDARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.STUDENT_SECONDARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_TERTIARY(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.STUDENT_TERTIARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_UNEMPLOYED(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return (person.employment().equals(Employment.UNEMPLOYED)) ? 1.0 : 0.0;
	}
	
	public double getTRAVEL_COST_CAR(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.CAR, time);
	}
	
	public double getTRAVEL_COST_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, time);
	}
	
	public double getTRAVEL_TIME_BIKE(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.BIKE, time);
	}
	
	public double getTRAVEL_TIME_CAR(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.CAR, time);
	}
	
	public double getTRAVEL_TIME_PEDESTRIAN(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PEDESTRIAN, time);
	}
	
	public double getTRAVEL_TIME_PUBLICTRANSPORT(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, time);
	}
	

}

