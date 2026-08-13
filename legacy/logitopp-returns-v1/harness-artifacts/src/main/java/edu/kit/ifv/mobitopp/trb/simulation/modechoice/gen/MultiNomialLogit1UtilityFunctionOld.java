package edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen.ModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.trb.simulation.modechoice.gen.MultiNomialLogit1HelperOld;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.util.Set;

public class MultiNomialLogit1UtilityFunctionOld {
	
	private final double age_0_17_on_asc_bike;
	private final double age_0_17_on_asc_car_d;
	private final double age_0_17_on_asc_car_p;
	private final double age_0_17_on_asc_ped;
	private final double age_0_17_on_asc_put;
	private final double age_0_17_on_b_cost_car_d;
	private final double age_0_17_on_b_cost_put;
	private final double age_18_29_on_asc_bike;
	private final double age_18_29_on_asc_car_d;
	private final double age_18_29_on_asc_car_p;
	private final double age_18_29_on_asc_ped;
	private final double age_18_29_on_asc_put;
	private final double age_18_29_on_b_cost_car_d;
	private final double age_18_29_on_b_cost_put;
	private final double age_50_59_on_asc_bike;
	private final double age_50_59_on_asc_car_d;
	private final double age_50_59_on_asc_car_p;
	private final double age_50_59_on_asc_ped;
	private final double age_50_59_on_asc_put;
	private final double age_50_59_on_b_cost_car_d;
	private final double age_50_59_on_b_cost_put;
	private final double age_60_69_on_asc_bike;
	private final double age_60_69_on_asc_car_d;
	private final double age_60_69_on_asc_car_p;
	private final double age_60_69_on_asc_ped;
	private final double age_60_69_on_asc_put;
	private final double age_60_69_on_b_cost_car_d;
	private final double age_60_69_on_b_cost_put;
	private final double age_70_100_on_asc_bike;
	private final double age_70_100_on_asc_car_d;
	private final double age_70_100_on_asc_car_p;
	private final double age_70_100_on_asc_ped;
	private final double age_70_100_on_asc_put;
	private final double age_70_100_on_b_cost_car_d;
	private final double age_70_100_on_b_cost_put;
	private final double asc_bike;
	private final double asc_car_d;
	private final double asc_car_p;
	private final double asc_ped;
	private final double asc_put;
	private final double b_acc_put;
	private final double b_cost_car_d;
	private final double b_cost_put;
	private final double b_tt_bike;
	private final double b_tt_car_d;
	private final double b_tt_car_p;
	private final double b_tt_ped;
	private final double b_tt_put;
	private final double b_u_put;
	private final double beruft_on_asc_bike;
	private final double beruft_on_asc_car_d;
	private final double beruft_on_asc_car_p;
	private final double beruft_on_asc_ped;
	private final double beruft_on_asc_put;
	private final double beruft_on_b_cost_car_d;
	private final double beruft_on_b_cost_put;
	private final double deactivated;
	private final double female_on_asc_bike;
	private final double female_on_asc_car_d;
	private final double female_on_asc_car_p;
	private final double female_on_asc_ped;
	private final double female_on_asc_put;
	private final double female_on_b_cost_car_d;
	private final double female_on_b_cost_put;
	private final double inc_high_on_asc_bike;
	private final double inc_high_on_asc_car_d;
	private final double inc_high_on_asc_car_p;
	private final double inc_high_on_asc_ped;
	private final double inc_high_on_asc_put;
	private final double inc_high_on_b_cost_car_d;
	private final double inc_high_on_b_cost_put;
	private final double zk_on_asc_bike;
	private final double zk_on_asc_car_d;
	private final double zk_on_asc_car_p;
	private final double zk_on_asc_ped;
	private final double zk_on_asc_put;
	private final double zk_on_b_cost_car_d;
	private final double zk_on_b_cost_put;
	
	private final MultiNomialLogit1HelperOld helper;
	private final ModeChoiceModelLogger log;
	
	public MultiNomialLogit1UtilityFunctionOld(LogitParameters logitParameters, MultiNomialLogit1HelperOld helper, ModeChoiceModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.age_0_17_on_asc_bike = logitParameters.get("age_0_17_on_asc_bike");
		this.age_0_17_on_asc_car_d = logitParameters.get("age_0_17_on_asc_car_d");
		this.age_0_17_on_asc_car_p = logitParameters.get("age_0_17_on_asc_car_p");
		this.age_0_17_on_asc_ped = logitParameters.get("age_0_17_on_asc_ped");
		this.age_0_17_on_asc_put = logitParameters.get("age_0_17_on_asc_put");
		this.age_0_17_on_b_cost_car_d = logitParameters.get("age_0_17_on_b_cost_car_d");
		this.age_0_17_on_b_cost_put = logitParameters.get("age_0_17_on_b_cost_put");
		this.age_18_29_on_asc_bike = logitParameters.get("age_18_29_on_asc_bike");
		this.age_18_29_on_asc_car_d = logitParameters.get("age_18_29_on_asc_car_d");
		this.age_18_29_on_asc_car_p = logitParameters.get("age_18_29_on_asc_car_p");
		this.age_18_29_on_asc_ped = logitParameters.get("age_18_29_on_asc_ped");
		this.age_18_29_on_asc_put = logitParameters.get("age_18_29_on_asc_put");
		this.age_18_29_on_b_cost_car_d = logitParameters.get("age_18_29_on_b_cost_car_d");
		this.age_18_29_on_b_cost_put = logitParameters.get("age_18_29_on_b_cost_put");
		this.age_50_59_on_asc_bike = logitParameters.get("age_50_59_on_asc_bike");
		this.age_50_59_on_asc_car_d = logitParameters.get("age_50_59_on_asc_car_d");
		this.age_50_59_on_asc_car_p = logitParameters.get("age_50_59_on_asc_car_p");
		this.age_50_59_on_asc_ped = logitParameters.get("age_50_59_on_asc_ped");
		this.age_50_59_on_asc_put = logitParameters.get("age_50_59_on_asc_put");
		this.age_50_59_on_b_cost_car_d = logitParameters.get("age_50_59_on_b_cost_car_d");
		this.age_50_59_on_b_cost_put = logitParameters.get("age_50_59_on_b_cost_put");
		this.age_60_69_on_asc_bike = logitParameters.get("age_60_69_on_asc_bike");
		this.age_60_69_on_asc_car_d = logitParameters.get("age_60_69_on_asc_car_d");
		this.age_60_69_on_asc_car_p = logitParameters.get("age_60_69_on_asc_car_p");
		this.age_60_69_on_asc_ped = logitParameters.get("age_60_69_on_asc_ped");
		this.age_60_69_on_asc_put = logitParameters.get("age_60_69_on_asc_put");
		this.age_60_69_on_b_cost_car_d = logitParameters.get("age_60_69_on_b_cost_car_d");
		this.age_60_69_on_b_cost_put = logitParameters.get("age_60_69_on_b_cost_put");
		this.age_70_100_on_asc_bike = logitParameters.get("age_70_100_on_asc_bike");
		this.age_70_100_on_asc_car_d = logitParameters.get("age_70_100_on_asc_car_d");
		this.age_70_100_on_asc_car_p = logitParameters.get("age_70_100_on_asc_car_p");
		this.age_70_100_on_asc_ped = logitParameters.get("age_70_100_on_asc_ped");
		this.age_70_100_on_asc_put = logitParameters.get("age_70_100_on_asc_put");
		this.age_70_100_on_b_cost_car_d = logitParameters.get("age_70_100_on_b_cost_car_d");
		this.age_70_100_on_b_cost_put = logitParameters.get("age_70_100_on_b_cost_put");
		this.asc_bike = logitParameters.get("asc_bike");
		this.asc_car_d = logitParameters.get("asc_car_d");
		this.asc_car_p = logitParameters.get("asc_car_p");
		this.asc_ped = logitParameters.get("asc_ped");
		this.asc_put = logitParameters.get("asc_put");
		this.b_acc_put = logitParameters.get("b_acc_put");
		this.b_cost_car_d = logitParameters.get("b_cost_car_d");
		this.b_cost_put = logitParameters.get("b_cost_put");
		this.b_tt_bike = logitParameters.get("b_tt_bike");
		this.b_tt_car_d = logitParameters.get("b_tt_car_d");
		this.b_tt_car_p = logitParameters.get("b_tt_car_p");
		this.b_tt_ped = logitParameters.get("b_tt_ped");
		this.b_tt_put = logitParameters.get("b_tt_put");
		this.b_u_put = logitParameters.get("b_u_put");
		this.beruft_on_asc_bike = logitParameters.get("beruft_on_asc_bike");
		this.beruft_on_asc_car_d = logitParameters.get("beruft_on_asc_car_d");
		this.beruft_on_asc_car_p = logitParameters.get("beruft_on_asc_car_p");
		this.beruft_on_asc_ped = logitParameters.get("beruft_on_asc_ped");
		this.beruft_on_asc_put = logitParameters.get("beruft_on_asc_put");
		this.beruft_on_b_cost_car_d = logitParameters.get("beruft_on_b_cost_car_d");
		this.beruft_on_b_cost_put = logitParameters.get("beruft_on_b_cost_put");
		this.deactivated = logitParameters.get("deactivated");
		this.female_on_asc_bike = logitParameters.get("female_on_asc_bike");
		this.female_on_asc_car_d = logitParameters.get("female_on_asc_car_d");
		this.female_on_asc_car_p = logitParameters.get("female_on_asc_car_p");
		this.female_on_asc_ped = logitParameters.get("female_on_asc_ped");
		this.female_on_asc_put = logitParameters.get("female_on_asc_put");
		this.female_on_b_cost_car_d = logitParameters.get("female_on_b_cost_car_d");
		this.female_on_b_cost_put = logitParameters.get("female_on_b_cost_put");
		this.inc_high_on_asc_bike = logitParameters.get("inc_high_on_asc_bike");
		this.inc_high_on_asc_car_d = logitParameters.get("inc_high_on_asc_car_d");
		this.inc_high_on_asc_car_p = logitParameters.get("inc_high_on_asc_car_p");
		this.inc_high_on_asc_ped = logitParameters.get("inc_high_on_asc_ped");
		this.inc_high_on_asc_put = logitParameters.get("inc_high_on_asc_put");
		this.inc_high_on_b_cost_car_d = logitParameters.get("inc_high_on_b_cost_car_d");
		this.inc_high_on_b_cost_put = logitParameters.get("inc_high_on_b_cost_put");
		this.zk_on_asc_bike = logitParameters.get("zk_on_asc_bike");
		this.zk_on_asc_car_d = logitParameters.get("zk_on_asc_car_d");
		this.zk_on_asc_car_p = logitParameters.get("zk_on_asc_car_p");
		this.zk_on_asc_ped = logitParameters.get("zk_on_asc_ped");
		this.zk_on_asc_put = logitParameters.get("zk_on_asc_put");
		this.zk_on_b_cost_car_d = logitParameters.get("zk_on_b_cost_car_d");
		this.zk_on_b_cost_put = logitParameters.get("zk_on_b_cost_put");
		

	}
	
	public MultiNomialLogit1UtilityFunctionOld(LogitParameters logitParameters, MultiNomialLogit1HelperOld helper) {
		this(logitParameters, helper, new NullModeChoiceModelLogger());
	}
	
	
	public double calculateU_walk(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Pedestrian";
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double ECONOMICAL_STATUS_value = helper.getECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ECONOMICAL_STATUS_value", ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double TRAVEL_TIME_PEDESTRIAN_value = helper.getTRAVEL_TIME_PEDESTRIAN(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_TIME_PEDESTRIAN_value", TRAVEL_TIME_PEDESTRIAN_value);
		
		
		
		return   (asc_ped)
				+(age_0_17_on_asc_ped*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_ped*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_ped*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_ped*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_ped*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_ped*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_ped*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_ped*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_ped*HAS_COMMUTER_TICKET_value)
				+(b_tt_ped*TRAVEL_TIME_PEDESTRIAN_value);
	}
	public double calculateU_bike(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Bike";
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double ECONOMICAL_STATUS_value = helper.getECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ECONOMICAL_STATUS_value", ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double TRAVEL_TIME_BIKE_value = helper.getTRAVEL_TIME_BIKE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_TIME_BIKE_value", TRAVEL_TIME_BIKE_value);
		
		
		
		return   (asc_bike)
				+(age_0_17_on_asc_bike*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_bike*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_bike*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_bike*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_bike*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_bike*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_bike*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_bike*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_bike*HAS_COMMUTER_TICKET_value)
				+(b_tt_bike*TRAVEL_TIME_BIKE_value);
	}
	public double calculateU_card(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Car";
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double ECONOMICAL_STATUS_value = helper.getECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ECONOMICAL_STATUS_value", ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double TRAVEL_TIME_CAR_value = helper.getTRAVEL_TIME_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_TIME_CAR_value", TRAVEL_TIME_CAR_value);
		
		double TRAVEL_COST_CAR_value = helper.getTRAVEL_COST_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_COST_CAR_value", TRAVEL_COST_CAR_value);
		
		
		
		return   (asc_car_d)
				+(age_0_17_on_asc_car_d*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_car_d*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_car_d*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_car_d*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_car_d*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_car_d*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_car_d*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_car_d*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_car_d*HAS_COMMUTER_TICKET_value)
				+(b_tt_car_d*TRAVEL_TIME_CAR_value)
				+((b_cost_car_d + age_0_17_on_b_cost_car_d*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + age_18_29_on_b_cost_car_d*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d) + age_50_59_on_b_cost_car_d*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d) + age_60_69_on_b_cost_car_d*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + age_70_100_on_b_cost_car_d*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d) + beruft_on_b_cost_car_d*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d) + female_on_b_cost_car_d*((GENDER_value == 2.0d) ? 1.0d : 0.0d) + inc_high_on_b_cost_car_d*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d) + zk_on_b_cost_car_d*HAS_COMMUTER_TICKET_value)*TRAVEL_COST_CAR_value);
	}
	public double calculateU_carp(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Passenger";
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double ECONOMICAL_STATUS_value = helper.getECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ECONOMICAL_STATUS_value", ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double TRAVEL_TIME_CAR_value = helper.getTRAVEL_TIME_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_TIME_CAR_value", TRAVEL_TIME_CAR_value);
		
		
		
		return   (asc_car_p)
				+(age_0_17_on_asc_car_p*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_car_p*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_car_p*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_car_p*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_car_p*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_car_p*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_car_p*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_car_p*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_car_p*HAS_COMMUTER_TICKET_value)
				+(b_tt_car_p*TRAVEL_TIME_CAR_value);
	}
	public double calculateU_pt(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "PublicTransport";
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double ECONOMICAL_STATUS_value = helper.getECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ECONOMICAL_STATUS_value", ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double TRAVEL_TIME_PUBLICTRANSPORT_value = helper.getTRAVEL_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_TIME_PUBLICTRANSPORT_value", TRAVEL_TIME_PUBLICTRANSPORT_value);
		
		double ACCESS_TIME_PUBLICTRANSPORT_value = helper.getACCESS_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "ACCESS_TIME_PUBLICTRANSPORT_value", ACCESS_TIME_PUBLICTRANSPORT_value);
		
		double EGRESS_TIME_PUBLICTRANSPORT_value = helper.getEGRESS_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "EGRESS_TIME_PUBLICTRANSPORT_value", EGRESS_TIME_PUBLICTRANSPORT_value);
		
		double TRANSFER_PUBLICTRANSPORT_value = helper.getTRANSFER_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRANSFER_PUBLICTRANSPORT_value", TRANSFER_PUBLICTRANSPORT_value);
		
		double TRAVEL_COST_PUBLICTRANSPORT_value = helper.getTRAVEL_COST_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(category, "TRAVEL_COST_PUBLICTRANSPORT_value", TRAVEL_COST_PUBLICTRANSPORT_value);
		
		
		
		return   (asc_put)
				+(age_0_17_on_asc_put*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_put*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_put*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_put*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_put*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_put*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_put*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_put*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_put*HAS_COMMUTER_TICKET_value)
				+(b_tt_put*TRAVEL_TIME_PUBLICTRANSPORT_value)
				+(b_acc_put*(ACCESS_TIME_PUBLICTRANSPORT_value + EGRESS_TIME_PUBLICTRANSPORT_value))
				+(b_u_put*TRANSFER_PUBLICTRANSPORT_value)
				+((b_cost_put + age_0_17_on_b_cost_put*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + age_18_29_on_b_cost_put*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d) + age_50_59_on_b_cost_put*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d) + age_60_69_on_b_cost_put*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + age_70_100_on_b_cost_put*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d) + beruft_on_b_cost_put*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d) + female_on_b_cost_put*((GENDER_value == 2.0d) ? 1.0d : 0.0d) + inc_high_on_b_cost_put*((4.0d <= ECONOMICAL_STATUS_value && ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d) + zk_on_b_cost_put*HAS_COMMUTER_TICKET_value)*TRAVEL_COST_PUBLICTRANSPORT_value);
	}
	public double calculateU_taxi(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Taxi";
		
		
		
		return   (deactivated);
	}
	public double calculateU_cs_ff(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Carsharing_FreeFloating";
		
		
		
		return   (deactivated);
	}
	public double calculateU_cs_sb(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Carsharing_Station";
		
		
		
		return   (deactivated);
	}
	public double calculateU_bs(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "BikeSharing";
		
		
		
		return   (deactivated);
	}
	public double calculateU_rp(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "RidePooling";
		
		
		
		return   (deactivated);
	}
}

