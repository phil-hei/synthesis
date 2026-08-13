package edu.kit.ifv.mobitopp.simulation.modechoice.gen;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeChoiceModel;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.MixedModeChoiceHelper;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.ModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.util.Set;

public class GeneratedMixedUtilityFunction {
	
	private final double asc_shift_relief_high_bike;
	private final double asc_shift_relief_high_car_d;
	private final double asc_shift_relief_high_car_p;
	private final double asc_shift_relief_high_ped;
	private final double asc_shift_relief_high_put;
	private final double b_arbeit_bike;
	private final double b_arbeit_bs;
	private final double b_arbeit_car_d;
	private final double b_arbeit_car_p;
	private final double b_arbeit_cs;
	private final double b_arbeit_ped;
	private final double b_arbeit_put;
	private final double b_arbeit_rp;
	private final double b_arbwo_bike;
	private final double b_arbwo_car_d;
	private final double b_arbwo_car_p;
	private final double b_arbwo_ped;
	private final double b_arbwo_put;
	private final double b_ausb_bike;
	private final double b_ausb_bs;
	private final double b_ausb_car_d;
	private final double b_ausb_car_p;
	private final double b_ausb_ped;
	private final double b_ausb_putd;
	private final double b_cost;
	private final double b_cost_put;
	private final double b_dienst_bike;
	private final double b_dienst_car_d;
	private final double b_dienst_car_p;
	private final double b_dienst_ped;
	private final double b_dienst_put;
	private final double b_eink_bike;
	private final double b_eink_bs;
	private final double b_eink_car_d;
	private final double b_eink_car_p;
	private final double b_eink_ped;
	private final double b_eink_put;
	private final double b_eink_rp;
	private final double b_freiz_bike;
	private final double b_freiz_car_d;
	private final double b_freiz_car_p;
	private final double b_freiz_ped;
	private final double b_freiz_put;
	private final double b_home_car_p;
	private final double b_home_put;
	private final double b_inc_high_on_b_cost;
	private final double b_inc_high_on_b_cost_put;
	private final double b_logsum_acc_cs;
	private final double b_logsum_acc_put;
	private final double b_mode_bef_bike;
	private final double b_mode_bef_ped;
	private final double b_mode_bef_put;
	private final double b_park_car_d;
	private final double b_service_bike;
	private final double b_service_car_d;
	private final double b_service_car_p;
	private final double b_service_ped;
	private final double b_service_put;
	private final double b_tt_arbeit_put;
	private final double b_tt_ausb_ped;
	private final double b_tt_ausb_put;
	private final double b_tt_dienst_bike;
	private final double b_tt_dienst_ped;
	private final double b_tt_freiz_bike;
	private final double b_tt_freiz_car_p;
	private final double b_tt_freiz_ped;
	private final double b_tt_service_put;
	private final double b_u_put;
	private final double b_wz_rp;
	private final double b_zz_rp;
	private final double elasticity_acc_put;
	private final double elasticity_parken;
	private final double parking_cost_ph;
	
	private final MixedModeChoiceHelper helper;
	private final ModeChoiceModelLogger log;
	
	public GeneratedMixedUtilityFunction(LogitParameters logitParameters, MixedModeChoiceHelper helper, ModeChoiceModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.asc_shift_relief_high_bike = logitParameters.get("asc_shift_relief_high_bike");
		this.asc_shift_relief_high_car_d = logitParameters.get("asc_shift_relief_high_car_d");
		this.asc_shift_relief_high_car_p = logitParameters.get("asc_shift_relief_high_car_p");
		this.asc_shift_relief_high_ped = logitParameters.get("asc_shift_relief_high_ped");
		this.asc_shift_relief_high_put = logitParameters.get("asc_shift_relief_high_put");
		this.b_arbeit_bike = logitParameters.get("b_arbeit_bike");
		this.b_arbeit_bs = logitParameters.get("b_arbeit_bs");
		this.b_arbeit_car_d = logitParameters.get("b_arbeit_car_d");
		this.b_arbeit_car_p = logitParameters.get("b_arbeit_car_p");
		this.b_arbeit_cs = logitParameters.get("b_arbeit_cs");
		this.b_arbeit_ped = logitParameters.get("b_arbeit_ped");
		this.b_arbeit_put = logitParameters.get("b_arbeit_put");
		this.b_arbeit_rp = logitParameters.get("b_arbeit_rp");
		this.b_arbwo_bike = logitParameters.get("b_arbwo_bike");
		this.b_arbwo_car_d = logitParameters.get("b_arbwo_car_d");
		this.b_arbwo_car_p = logitParameters.get("b_arbwo_car_p");
		this.b_arbwo_ped = logitParameters.get("b_arbwo_ped");
		this.b_arbwo_put = logitParameters.get("b_arbwo_put");
		this.b_ausb_bike = logitParameters.get("b_ausb_bike");
		this.b_ausb_bs = logitParameters.get("b_ausb_bs");
		this.b_ausb_car_d = logitParameters.get("b_ausb_car_d");
		this.b_ausb_car_p = logitParameters.get("b_ausb_car_p");
		this.b_ausb_ped = logitParameters.get("b_ausb_ped");
		this.b_ausb_putd = logitParameters.get("b_ausb_putd");
		this.b_cost = logitParameters.get("b_cost");
		this.b_cost_put = logitParameters.get("b_cost_put");
		this.b_dienst_bike = logitParameters.get("b_dienst_bike");
		this.b_dienst_car_d = logitParameters.get("b_dienst_car_d");
		this.b_dienst_car_p = logitParameters.get("b_dienst_car_p");
		this.b_dienst_ped = logitParameters.get("b_dienst_ped");
		this.b_dienst_put = logitParameters.get("b_dienst_put");
		this.b_eink_bike = logitParameters.get("b_eink_bike");
		this.b_eink_bs = logitParameters.get("b_eink_bs");
		this.b_eink_car_d = logitParameters.get("b_eink_car_d");
		this.b_eink_car_p = logitParameters.get("b_eink_car_p");
		this.b_eink_ped = logitParameters.get("b_eink_ped");
		this.b_eink_put = logitParameters.get("b_eink_put");
		this.b_eink_rp = logitParameters.get("b_eink_rp");
		this.b_freiz_bike = logitParameters.get("b_freiz_bike");
		this.b_freiz_car_d = logitParameters.get("b_freiz_car_d");
		this.b_freiz_car_p = logitParameters.get("b_freiz_car_p");
		this.b_freiz_ped = logitParameters.get("b_freiz_ped");
		this.b_freiz_put = logitParameters.get("b_freiz_put");
		this.b_home_car_p = logitParameters.get("b_home_car_p");
		this.b_home_put = logitParameters.get("b_home_put");
		this.b_inc_high_on_b_cost = logitParameters.get("b_inc_high_on_b_cost");
		this.b_inc_high_on_b_cost_put = logitParameters.get("b_inc_high_on_b_cost_put");
		this.b_logsum_acc_cs = logitParameters.get("b_logsum_acc_cs");
		this.b_logsum_acc_put = logitParameters.get("b_logsum_acc_put");
		this.b_mode_bef_bike = logitParameters.get("b_mode_bef_bike");
		this.b_mode_bef_ped = logitParameters.get("b_mode_bef_ped");
		this.b_mode_bef_put = logitParameters.get("b_mode_bef_put");
		this.b_park_car_d = logitParameters.get("b_park_car_d");
		this.b_service_bike = logitParameters.get("b_service_bike");
		this.b_service_car_d = logitParameters.get("b_service_car_d");
		this.b_service_car_p = logitParameters.get("b_service_car_p");
		this.b_service_ped = logitParameters.get("b_service_ped");
		this.b_service_put = logitParameters.get("b_service_put");
		this.b_tt_arbeit_put = logitParameters.get("b_tt_arbeit_put");
		this.b_tt_ausb_ped = logitParameters.get("b_tt_ausb_ped");
		this.b_tt_ausb_put = logitParameters.get("b_tt_ausb_put");
		this.b_tt_dienst_bike = logitParameters.get("b_tt_dienst_bike");
		this.b_tt_dienst_ped = logitParameters.get("b_tt_dienst_ped");
		this.b_tt_freiz_bike = logitParameters.get("b_tt_freiz_bike");
		this.b_tt_freiz_car_p = logitParameters.get("b_tt_freiz_car_p");
		this.b_tt_freiz_ped = logitParameters.get("b_tt_freiz_ped");
		this.b_tt_service_put = logitParameters.get("b_tt_service_put");
		this.b_u_put = logitParameters.get("b_u_put");
		this.b_wz_rp = logitParameters.get("b_wz_rp");
		this.b_zz_rp = logitParameters.get("b_zz_rp");
		this.elasticity_acc_put = logitParameters.get("elasticity_acc_put");
		this.elasticity_parken = logitParameters.get("elasticity_parken");
		this.parking_cost_ph = logitParameters.get("parking_cost_ph");
		

	}
	
	public GeneratedMixedUtilityFunction(LogitParameters logitParameters, MixedModeChoiceHelper helper) {
		this(logitParameters, helper, new NullModeChoiceModelLogger());
	}
	
	
	public double calculateU_walk(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Pedestrian";
		
		double PREF_PED_value = helper.getPREF_PED(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_PED_value", PREF_PED_value);
		
		double B_TT_PED_value = helper.getB_TT_PED(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_PED_value", B_TT_PED_value);
		
		double IS_ACTIVITY_TYPE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_BUSINESS_value", IS_ACTIVITY_TYPE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_INDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_INDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_INDOOR_value", IS_ACTIVITY_TYPE_LEISURE_INDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value", IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OTHER_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OTHER_value", IS_ACTIVITY_TYPE_LEISURE_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PTIVATE_VISIT_value = helper.getIS_ACTIVITY_TYPE_PTIVATE_VISIT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PTIVATE_VISIT_value", IS_ACTIVITY_TYPE_PTIVATE_VISIT_value);
		
		double TRAVEL_TIME_PEDESTRIAN_value = helper.getTRAVEL_TIME_PEDESTRIAN(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_PEDESTRIAN_value", TRAVEL_TIME_PEDESTRIAN_value);
		
		double RELIEF_value = helper.getRELIEF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "RELIEF_value", RELIEF_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_SERVICE_value = helper.getIS_ACTIVITY_TYPE_SERVICE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SERVICE_value", IS_ACTIVITY_TYPE_SERVICE_value);
		
		double IS_WEEKDAY_value = helper.getIS_WEEKDAY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_WEEKDAY_value", IS_WEEKDAY_value);
		
		double IS_PREV_MODE_PEDESTRIAN_value = helper.getIS_PREV_MODE_PEDESTRIAN(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_PREV_MODE_PEDESTRIAN_value", IS_PREV_MODE_PEDESTRIAN_value);
		
		
		
		return   (PREF_PED_value)
				+((B_TT_PED_value + b_tt_dienst_ped*IS_ACTIVITY_TYPE_BUSINESS_value + b_tt_ausb_ped*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d) + b_tt_freiz_ped*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))*TRAVEL_TIME_PEDESTRIAN_value)
				+(asc_shift_relief_high_ped*((RELIEF_value >= 250.0d) ? 1.0d : 0.0d))
				+(b_arbeit_ped*IS_ACTIVITY_TYPE_WORK_value)
				+(b_dienst_ped*IS_ACTIVITY_TYPE_BUSINESS_value)
				+(b_ausb_ped*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_ped*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_freiz_ped*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_service_ped*IS_ACTIVITY_TYPE_SERVICE_value)
				+(b_arbwo_ped*IS_WEEKDAY_value)
				+(b_mode_bef_ped*IS_PREV_MODE_PEDESTRIAN_value);
	}
	public double calculateU_bike(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Bike";
		
		double PREF_BIKE_value = helper.getPREF_BIKE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_BIKE_value", PREF_BIKE_value);
		
		double B_TT_BIKE_value = helper.getB_TT_BIKE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_BIKE_value", B_TT_BIKE_value);
		
		double IS_ACTIVITY_TYPE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_BUSINESS_value", IS_ACTIVITY_TYPE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_INDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_INDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_INDOOR_value", IS_ACTIVITY_TYPE_LEISURE_INDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value", IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OTHER_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OTHER_value", IS_ACTIVITY_TYPE_LEISURE_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PTIVATE_VISIT_value = helper.getIS_ACTIVITY_TYPE_PTIVATE_VISIT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PTIVATE_VISIT_value", IS_ACTIVITY_TYPE_PTIVATE_VISIT_value);
		
		double TRAVEL_TIME_BIKE_value = helper.getTRAVEL_TIME_BIKE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_BIKE_value", TRAVEL_TIME_BIKE_value);
		
		double RELIEF_value = helper.getRELIEF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "RELIEF_value", RELIEF_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_SERVICE_value = helper.getIS_ACTIVITY_TYPE_SERVICE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SERVICE_value", IS_ACTIVITY_TYPE_SERVICE_value);
		
		double IS_WEEKDAY_value = helper.getIS_WEEKDAY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_WEEKDAY_value", IS_WEEKDAY_value);
		
		double IS_PREV_MODE_BIKE_value = helper.getIS_PREV_MODE_BIKE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_PREV_MODE_BIKE_value", IS_PREV_MODE_BIKE_value);
		
		
		
		return   (PREF_BIKE_value)
				+((-Math.exp(B_TT_BIKE_value + b_tt_dienst_bike*IS_ACTIVITY_TYPE_BUSINESS_value + b_tt_freiz_bike*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d)))*TRAVEL_TIME_BIKE_value)
				+(asc_shift_relief_high_bike*((RELIEF_value >= 250.0d) ? 1.0d : 0.0d))
				+(b_arbeit_bike*IS_ACTIVITY_TYPE_WORK_value)
				+(b_dienst_bike*IS_ACTIVITY_TYPE_BUSINESS_value)
				+(b_ausb_bike*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_bike*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_freiz_bike*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_service_bike*IS_ACTIVITY_TYPE_SERVICE_value)
				+(b_arbwo_bike*IS_WEEKDAY_value)
				+(b_mode_bef_bike*IS_PREV_MODE_BIKE_value);
	}
	public double calculateU_card(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Car";
		
		double PREF_CAR_D_value = helper.getPREF_CAR_D(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_CAR_D_value", PREF_CAR_D_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_CAR_value = helper.getTRAVEL_COST_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_CAR_value", TRAVEL_COST_CAR_value);
		
		double NEXT_ACTIVITY_DURATION_value = helper.getNEXT_ACTIVITY_DURATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "NEXT_ACTIVITY_DURATION_value", NEXT_ACTIVITY_DURATION_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_INDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_INDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_INDOOR_value", IS_ACTIVITY_TYPE_LEISURE_INDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value", IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OTHER_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OTHER_value", IS_ACTIVITY_TYPE_LEISURE_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PTIVATE_VISIT_value = helper.getIS_ACTIVITY_TYPE_PTIVATE_VISIT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PTIVATE_VISIT_value", IS_ACTIVITY_TYPE_PTIVATE_VISIT_value);
		
		double IS_ACTIVITY_TYPE_SERVICE_value = helper.getIS_ACTIVITY_TYPE_SERVICE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SERVICE_value", IS_ACTIVITY_TYPE_SERVICE_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double ZONE_ID_DESTINATION_value = helper.getZONE_ID_DESTINATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "ZONE_ID_DESTINATION_value", ZONE_ID_DESTINATION_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double B_TT_CAR_D_value = helper.getB_TT_CAR_D(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_CAR_D_value", B_TT_CAR_D_value);
		
		double TRAVEL_TIME_CAR_value = helper.getTRAVEL_TIME_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_CAR_value", TRAVEL_TIME_CAR_value);
		
		double RELIEF_value = helper.getRELIEF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "RELIEF_value", RELIEF_value);
		
		double IS_ACTIVITY_TYPE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_BUSINESS_value", IS_ACTIVITY_TYPE_BUSINESS_value);
		
		double PARKING_STRESS_value = helper.getPARKING_STRESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PARKING_STRESS_value", PARKING_STRESS_value);
		
		double IS_WEEKDAY_value = helper.getIS_WEEKDAY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_WEEKDAY_value", IS_WEEKDAY_value);
		
		
		
		return   (PREF_CAR_D_value)
				+((b_cost + b_inc_high_on_b_cost*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*(TRAVEL_COST_CAR_value + ((parking_cost_ph*NEXT_ACTIVITY_DURATION_value)*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value + IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value + IS_ACTIVITY_TYPE_SERVICE_value + IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))*((ZONE_ID_DESTINATION_value < 3000.0d) ? 1.0d : 0.0d) + (((parking_cost_ph*NEXT_ACTIVITY_DURATION_value)*0.5d)*((IS_ACTIVITY_TYPE_WORK_value > 0.0d) ? 1.0d : 0.0d))*((ZONE_ID_DESTINATION_value < 3000.0d) ? 1.0d : 0.0d)))
				+((-Math.exp(B_TT_CAR_D_value))*TRAVEL_TIME_CAR_value)
				+(asc_shift_relief_high_car_d*((RELIEF_value >= 250.0d) ? 1.0d : 0.0d))
				+(b_arbeit_car_d*IS_ACTIVITY_TYPE_WORK_value)
				+(b_dienst_car_d*IS_ACTIVITY_TYPE_BUSINESS_value)
				+(b_ausb_car_d*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_car_d*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_freiz_car_d*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_service_car_d*IS_ACTIVITY_TYPE_SERVICE_value)
				+(b_park_car_d*(Math.pow(PARKING_STRESS_value, elasticity_parken)))
				+(b_arbwo_car_d*IS_WEEKDAY_value);
	}
	public double calculateU_carp(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Passenger";
		
		double PREF_CAR_P_value = helper.getPREF_CAR_P(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_CAR_P_value", PREF_CAR_P_value);
		
		double B_TT_CAR_P_value = helper.getB_TT_CAR_P(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_CAR_P_value", B_TT_CAR_P_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_INDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_INDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_INDOOR_value", IS_ACTIVITY_TYPE_LEISURE_INDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value", IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OTHER_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OTHER_value", IS_ACTIVITY_TYPE_LEISURE_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PTIVATE_VISIT_value = helper.getIS_ACTIVITY_TYPE_PTIVATE_VISIT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PTIVATE_VISIT_value", IS_ACTIVITY_TYPE_PTIVATE_VISIT_value);
		
		double TRAVEL_TIME_CAR_value = helper.getTRAVEL_TIME_CAR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_CAR_value", TRAVEL_TIME_CAR_value);
		
		double RELIEF_value = helper.getRELIEF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "RELIEF_value", RELIEF_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_BUSINESS_value", IS_ACTIVITY_TYPE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_SERVICE_value = helper.getIS_ACTIVITY_TYPE_SERVICE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SERVICE_value", IS_ACTIVITY_TYPE_SERVICE_value);
		
		double IS_ACTIVITY_TYPE_HOME_value = helper.getIS_ACTIVITY_TYPE_HOME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_HOME_value", IS_ACTIVITY_TYPE_HOME_value);
		
		double IS_WEEKDAY_value = helper.getIS_WEEKDAY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_WEEKDAY_value", IS_WEEKDAY_value);
		
		
		
		return   (PREF_CAR_P_value)
				+((-Math.exp(B_TT_CAR_P_value + b_tt_freiz_car_p*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d)))*TRAVEL_TIME_CAR_value)
				+(asc_shift_relief_high_car_p*((RELIEF_value >= 250.0d) ? 1.0d : 0.0d))
				+(b_arbeit_car_p*IS_ACTIVITY_TYPE_WORK_value)
				+(b_dienst_car_p*IS_ACTIVITY_TYPE_BUSINESS_value)
				+(b_ausb_car_p*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_car_p*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_freiz_car_p*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_service_car_p*IS_ACTIVITY_TYPE_SERVICE_value)
				+(b_home_car_p*IS_ACTIVITY_TYPE_HOME_value)
				+(b_arbwo_car_p*IS_WEEKDAY_value);
	}
	public double calculateU_pt(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "PublicTransport";
		
		double PREF_PUT_value = helper.getPREF_PUT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_PUT_value", PREF_PUT_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_PUBLICTRANSPORT_value = helper.getTRAVEL_COST_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_PUBLICTRANSPORT_value", TRAVEL_COST_PUBLICTRANSPORT_value);
		
		double B_TT_PUT_value = helper.getB_TT_PUT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_PUT_value", B_TT_PUT_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double IS_ACTIVITY_TYPE_SERVICE_value = helper.getIS_ACTIVITY_TYPE_SERVICE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SERVICE_value", IS_ACTIVITY_TYPE_SERVICE_value);
		
		double TRAVEL_TIME_PUBLICTRANSPORT_value = helper.getTRAVEL_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_PUBLICTRANSPORT_value", TRAVEL_TIME_PUBLICTRANSPORT_value);
		
		double TRANSFER_PUBLICTRANSPORT_value = helper.getTRANSFER_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRANSFER_PUBLICTRANSPORT_value", TRANSFER_PUBLICTRANSPORT_value);
		
		double LOGSUM_ACC_PUT_value = helper.getLOGSUM_ACC_PUT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "LOGSUM_ACC_PUT_value", LOGSUM_ACC_PUT_value);
		
		double LOGSUM_EGR_PUT_value = helper.getLOGSUM_EGR_PUT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "LOGSUM_EGR_PUT_value", LOGSUM_EGR_PUT_value);
		
		double RELIEF_value = helper.getRELIEF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "RELIEF_value", RELIEF_value);
		
		double IS_ACTIVITY_TYPE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_BUSINESS_value", IS_ACTIVITY_TYPE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_INDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_INDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_INDOOR_value", IS_ACTIVITY_TYPE_LEISURE_INDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OUTDOOR(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value", IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value);
		
		double IS_ACTIVITY_TYPE_LEISURE_OTHER_value = helper.getIS_ACTIVITY_TYPE_LEISURE_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_LEISURE_OTHER_value", IS_ACTIVITY_TYPE_LEISURE_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PTIVATE_VISIT_value = helper.getIS_ACTIVITY_TYPE_PTIVATE_VISIT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PTIVATE_VISIT_value", IS_ACTIVITY_TYPE_PTIVATE_VISIT_value);
		
		double IS_ACTIVITY_TYPE_HOME_value = helper.getIS_ACTIVITY_TYPE_HOME(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_HOME_value", IS_ACTIVITY_TYPE_HOME_value);
		
		double IS_WEEKDAY_value = helper.getIS_WEEKDAY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_WEEKDAY_value", IS_WEEKDAY_value);
		
		double IS_PREV_MODE_PUBLICTRANSPORT_value = helper.getIS_PREV_MODE_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_PREV_MODE_PUBLICTRANSPORT_value", IS_PREV_MODE_PUBLICTRANSPORT_value);
		
		
		
		return   (PREF_PUT_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_PUBLICTRANSPORT_value)
				+((-Math.exp(B_TT_PUT_value + b_tt_arbeit_put*IS_ACTIVITY_TYPE_WORK_value + b_tt_ausb_put*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d) + b_tt_service_put*IS_ACTIVITY_TYPE_SERVICE_value))*TRAVEL_TIME_PUBLICTRANSPORT_value)
				+(b_u_put*TRANSFER_PUBLICTRANSPORT_value)
				+((b_logsum_acc_put/2.0d)*(Math.exp(elasticity_acc_put*LOGSUM_ACC_PUT_value)))
				+((b_logsum_acc_put/2.0d)*(Math.exp(elasticity_acc_put*LOGSUM_EGR_PUT_value)))
				+(asc_shift_relief_high_put*((RELIEF_value >= 250.0d) ? 1.0d : 0.0d))
				+(b_arbeit_put*IS_ACTIVITY_TYPE_WORK_value)
				+(b_dienst_put*IS_ACTIVITY_TYPE_BUSINESS_value)
				+(b_ausb_putd*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_put*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_freiz_put*(((IS_ACTIVITY_TYPE_LEISURE_INDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OUTDOOR_value + IS_ACTIVITY_TYPE_LEISURE_OTHER_value + IS_ACTIVITY_TYPE_PTIVATE_VISIT_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_service_put*IS_ACTIVITY_TYPE_SERVICE_value)
				+(b_home_put*IS_ACTIVITY_TYPE_HOME_value)
				+(b_arbwo_put*IS_WEEKDAY_value)
				+(b_mode_bef_put*IS_PREV_MODE_PUBLICTRANSPORT_value);
	}
	public double calculateU_taxi(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Taxi";
		
		double PREF_TAXI_value = helper.getPREF_TAXI(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_TAXI_value", PREF_TAXI_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_TAXI_value = helper.getTRAVEL_COST_TAXI(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_TAXI_value", TRAVEL_COST_TAXI_value);
		
		double B_TT_TAXI_value = helper.getB_TT_TAXI(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_TAXI_value", B_TT_TAXI_value);
		
		double TRAVEL_TIME_TAXI_value = helper.getTRAVEL_TIME_TAXI(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_TAXI_value", TRAVEL_TIME_TAXI_value);
		
		
		
		return   (PREF_TAXI_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_TAXI_value)
				+(B_TT_TAXI_value*TRAVEL_TIME_TAXI_value);
	}
	public double calculateU_cs_ff(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Carsharing_FreeFloating";
		
		double PREF_CS_FF_value = helper.getPREF_CS_FF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_CS_FF_value", PREF_CS_FF_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_CARSHARING_FREE_value = helper.getTRAVEL_COST_CARSHARING_FREE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_CARSHARING_FREE_value", TRAVEL_COST_CARSHARING_FREE_value);
		
		double B_TT_CS_FF_value = helper.getB_TT_CS_FF(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_CS_FF_value", B_TT_CS_FF_value);
		
		double TRAVEL_TIME_CARSHARING_FREE_value = helper.getTRAVEL_TIME_CARSHARING_FREE(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_CARSHARING_FREE_value", TRAVEL_TIME_CARSHARING_FREE_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		
		
		return   (PREF_CS_FF_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_CARSHARING_FREE_value)
				+(B_TT_CS_FF_value*TRAVEL_TIME_CARSHARING_FREE_value)
				+(b_arbeit_cs*IS_ACTIVITY_TYPE_WORK_value);
	}
	public double calculateU_cs_sb(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "Carsharing_Station";
		
		double PREF_CS_SB_value = helper.getPREF_CS_SB(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_CS_SB_value", PREF_CS_SB_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_CARSHARING_STATION_value = helper.getTRAVEL_COST_CARSHARING_STATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_CARSHARING_STATION_value", TRAVEL_COST_CARSHARING_STATION_value);
		
		double B_TT_CS_SB_value = helper.getB_TT_CS_SB(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_CS_SB_value", B_TT_CS_SB_value);
		
		double TRAVEL_TIME_CARSHARING_STATION_value = helper.getTRAVEL_TIME_CARSHARING_STATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_CARSHARING_STATION_value", TRAVEL_TIME_CARSHARING_STATION_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double LOGSUM_ACC_CS_value = helper.getLOGSUM_ACC_CS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "LOGSUM_ACC_CS_value", LOGSUM_ACC_CS_value);
		
		
		
		return   (PREF_CS_SB_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_CARSHARING_STATION_value)
				+(B_TT_CS_SB_value*TRAVEL_TIME_CARSHARING_STATION_value)
				+(b_arbeit_cs*IS_ACTIVITY_TYPE_WORK_value)
				+(b_logsum_acc_cs*LOGSUM_ACC_CS_value);
	}
	public double calculateU_bs(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "BikeSharing";
		
		double PREF_BS_value = helper.getPREF_BS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_BS_value", PREF_BS_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_BIKESHARING_value = helper.getTRAVEL_COST_BIKESHARING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_BIKESHARING_value", TRAVEL_COST_BIKESHARING_value);
		
		double B_TT_BS_value = helper.getB_TT_BS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_BS_value", B_TT_BS_value);
		
		double TRAVEL_TIME_BIKESHARING_value = helper.getTRAVEL_TIME_BIKESHARING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_BIKESHARING_value", TRAVEL_TIME_BIKESHARING_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value", IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value", IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value", IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_value = helper.getIS_ACTIVITY_TYPE_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_value", IS_ACTIVITY_TYPE_EDUCATION_value);
		
		double IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value = helper.getIS_ACTIVITY_TYPE_EDUCATION_OCCUP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value", IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		
		
		return   (PREF_BS_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_BIKESHARING_value)
				+(B_TT_BS_value*TRAVEL_TIME_BIKESHARING_value)
				+(b_arbeit_bs*IS_ACTIVITY_TYPE_WORK_value)
				+(b_ausb_bs*(((IS_ACTIVITY_TYPE_EDUCATION_PRIMARY_value + IS_ACTIVITY_TYPE_EDUCATION_SECONDARY_value + IS_ACTIVITY_TYPE_EDUCATION_TERTIARY_value + IS_ACTIVITY_TYPE_EDUCATION_value + IS_ACTIVITY_TYPE_EDUCATION_OCCUP_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_eink_bs*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_rp(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Set<Mode> choiceSet, double randomNumber) {
		String category = "RidePooling";
		
		double PREF_RP_value = helper.getPREF_RP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "PREF_RP_value", PREF_RP_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double TRAVEL_COST_RIDE_POOLING_value = helper.getTRAVEL_COST_RIDE_POOLING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_COST_RIDE_POOLING_value", TRAVEL_COST_RIDE_POOLING_value);
		
		double B_TT_RP_value = helper.getB_TT_RP(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "B_TT_RP_value", B_TT_RP_value);
		
		double TRAVEL_TIME_RIDE_POOLING_value = helper.getTRAVEL_TIME_RIDE_POOLING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "TRAVEL_TIME_RIDE_POOLING_value", TRAVEL_TIME_RIDE_POOLING_value);
		
		double ACCESS_TIME_RIDE_POOLING_value = helper.getACCESS_TIME_RIDE_POOLING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "ACCESS_TIME_RIDE_POOLING_value", ACCESS_TIME_RIDE_POOLING_value);
		
		double WAITING_TIME_RIDE_POOLING_value = helper.getWAITING_TIME_RIDE_POOLING(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "WAITING_TIME_RIDE_POOLING_value", WAITING_TIME_RIDE_POOLING_value);
		
		double IS_ACTIVITY_TYPE_WORK_value = helper.getIS_ACTIVITY_TYPE_WORK(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_WORK_value", IS_ACTIVITY_TYPE_WORK_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_DAILY_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_DAILY(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_DAILY_value", IS_ACTIVITY_TYPE_SHOPPING_DAILY_value);
		
		double IS_ACTIVITY_TYPE_SHOPPING_OTHER_value = helper.getIS_ACTIVITY_TYPE_SHOPPING_OTHER(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_SHOPPING_OTHER_value", IS_ACTIVITY_TYPE_SHOPPING_OTHER_value);
		
		double IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value = helper.getIS_ACTIVITY_TYPE_PRIVATE_BUSINESS(category, person, origin, destination, previousActivity, nextActivity, choiceSet, randomNumber);
		this.log.log(person, origin, destination, category, "IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value", IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value);
		
		
		
		return   (PREF_RP_value)
				+((b_cost_put + b_inc_high_on_b_cost_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*TRAVEL_COST_RIDE_POOLING_value)
				+(B_TT_RP_value*TRAVEL_TIME_RIDE_POOLING_value)
				+(b_zz_rp*ACCESS_TIME_RIDE_POOLING_value)
				+(b_wz_rp*WAITING_TIME_RIDE_POOLING_value)
				+(b_arbeit_rp*IS_ACTIVITY_TYPE_WORK_value)
				+(b_eink_rp*(((IS_ACTIVITY_TYPE_SHOPPING_DAILY_value + IS_ACTIVITY_TYPE_SHOPPING_OTHER_value + IS_ACTIVITY_TYPE_PRIVATE_BUSINESS_value) > 0.0d) ? 1.0d : 0.0d));
	}
}

