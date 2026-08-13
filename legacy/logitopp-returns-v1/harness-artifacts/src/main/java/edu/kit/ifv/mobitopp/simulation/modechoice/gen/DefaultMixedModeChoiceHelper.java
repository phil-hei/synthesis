package edu.kit.ifv.mobitopp.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.simulation.modechoice.gen.MixedModeChoiceHelper;
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

public abstract class DefaultMixedModeChoiceHelper implements MixedModeChoiceHelper {
	
	private ImpedanceIfc impedance;
	
	public DefaultMixedModeChoiceHelper(ImpedanceIfc impedance) {
		this.impedance = impedance;
	}
	
	private static final double defaultAttributeValue = 0.0d;
	
	private double getDefaultDynamicZoneValue(String value, Zone zone) {
		System.out
			.println(String
				.format("Could not resolve attribute %s for zone %s. Using %s instead.", value,
					zone.getId(), defaultAttributeValue));
		return defaultAttributeValue;
	}
	
	
	public double getHOUSEHOLD_ECONOMICAL_STATUS(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return person.household().economicalStatus().getCode();
	}
	
	public double getIS_ACTIVITY_TYPE_BUSINESS(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.BUSINESS) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_EDUCATION(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.EDUCATION) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.EDUCATION_OCCUP) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.EDUCATION_PRIMARY) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.EDUCATION_SECONDARY) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.EDUCATION_TERTIARY) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_HOME(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.HOME) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_LEISURE_INDOOR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.LEISURE_INDOOR) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_LEISURE_OTHER(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.LEISURE_OTHER) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.LEISURE_OUTDOOR) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.PRIVATE_BUSINESS) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_PTIVATE_VISIT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.PRIVATE_VISIT) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_SERVICE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.SERVICE) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_SHOPPING_DAILY(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.SHOPPING_DAILY) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_SHOPPING_OTHER(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.SHOPPING_OTHER) ? 1.0d : 0.0d;
	}
	
	public double getIS_ACTIVITY_TYPE_WORK(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return nextActivity.activityType().equals(ActivityType.WORK) ? 1.0d : 0.0d;
	}
	
	public double getIS_PREV_MODE_BIKE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (StandardMode.BIKE == previousActivity.mode()) ? 1.0d : 0.0d;
	}
	
	public double getIS_PREV_MODE_PEDESTRIAN(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (StandardMode.PEDESTRIAN == previousActivity.mode()) ? 1.0d : 0.0d;
	}
	
	public double getIS_PREV_MODE_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return (StandardMode.PUBLICTRANSPORT == previousActivity.mode()) ? 1.0d : 0.0d;
	}
	
	public double getPARKING_STRESS(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getParkingStress(destination.getId(), previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_BIKESHARING(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.BIKESHARING, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.CAR, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_CARSHARING_FREE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.CARSHARING_FREE, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_CARSHARING_STATION(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.CARSHARING_STATION, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_RIDE_POOLING(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.RIDE_POOLING, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_COST_TAXI(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelCost(origin.getId(), destination.getId(), StandardMode.TAXI, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_BIKE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.BIKE, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_BIKESHARING(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.BIKESHARING, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_CAR(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.CAR, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_CARSHARING_FREE(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.CARSHARING_FREE, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_CARSHARING_STATION(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.CARSHARING_STATION, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_PEDESTRIAN(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PEDESTRIAN, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_PUBLICTRANSPORT(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.PUBLICTRANSPORT, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_RIDE_POOLING(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.RIDE_POOLING, previousActivity.calculatePlannedEndDate());
	}
	
	public double getTRAVEL_TIME_TAXI(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return this.impedance.getTravelTime(origin.getId(), destination.getId(), StandardMode.TAXI, previousActivity.calculatePlannedEndDate());
	}
	
	public double getZONE_ID_DESTINATION(String category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		return origin
			   .getProperty("id_destination")
			   .map(Value::asDouble)
			   .orElseGet(() -> getDefaultDynamicZoneValue("id_destination",origin));
	}
	

}

