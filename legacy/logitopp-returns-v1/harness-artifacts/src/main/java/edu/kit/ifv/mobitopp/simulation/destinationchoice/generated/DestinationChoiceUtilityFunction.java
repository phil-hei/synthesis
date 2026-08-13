package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceUtilityModel;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceHelper;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.time.Time;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;

public class DestinationChoiceUtilityFunction {
	
	private final double asc_bike;
	private final double asc_bs;
	private final double asc_car_d;
	private final double asc_car_p;
	private final double asc_ped;
	private final double asc_put;
	private final double b_0_1;
	private final double b_1_2;
	private final double b_attr;
	private final double b_cost;
	private final double b_intrazonal;
	private final double b_logsum_drive;
	private final double b_logsum_drive_fix;
	private final double b_logsum_pt_active;
	private final double b_tt_acc_put;
	private final double b_tt_bike;
	private final double b_tt_car_d;
	private final double b_tt_car_p;
	private final double b_tt_ped;
	private final double b_tt_put;
	private final double max_attractivity;
	private final double shift_age_1_on_logsum_attr;
	private final double shift_age_1_on_logsum_drive;
	private final double shift_age_1_on_logsum_drive_fix;
	private final double shift_age_1_on_logsum_pt;
	private final double shift_age_1_on_logsum_pt_fix;
	private final double shift_age_56_on_logsum_attr;
	private final double shift_age_56_on_logsum_drive;
	private final double shift_age_56_on_logsum_drive_fix;
	private final double shift_age_56_on_logsum_pt;
	private final double shift_age_56_on_logsum_pt_fix;
	private final double shift_age_78_on_logsum_attr;
	private final double shift_age_78_on_logsum_drive;
	private final double shift_age_78_on_logsum_drive_fix;
	private final double shift_age_78_on_logsum_pt;
	private final double shift_age_78_on_logsum_pt_fix;
	private final double shift_arb_on_logsum_drive;
	private final double shift_b_0_1_on_logsum_attr;
	private final double shift_b_1_2_on_logsum_attr;
	private final double shift_carav_on_logsum_attr;
	private final double shift_carav_on_logsum_drive;
	private final double shift_carav_on_logsum_drive_fix;
	private final double shift_carav_on_logsum_pt;
	private final double shift_carav_on_logsum_pt_fix;
	private final double shift_educ_on_logsum_attr;
	private final double shift_educ_on_logsum_drive;
	private final double shift_educ_on_logsum_drive_fix;
	private final double shift_educ_on_logsum_pt;
	private final double shift_educ_on_logsum_pt_fix;
	private final double shift_high_inc_on_logsum_attr;
	private final double shift_high_inc_on_logsum_drive;
	private final double shift_high_inc_on_logsum_drive_fix;
	private final double shift_high_inc_on_logsum_pt;
	private final double shift_high_inc_on_logsum_pt_fix;
	private final double shift_home_on_logsum_attr;
	private final double shift_home_on_logsum_drive_fix;
	private final double shift_home_on_logsum_pt;
	private final double shift_home_on_logsum_pt_fix;
	private final double shift_intrazonal_on_attr;
	private final double shift_nocar_on_logsum_drive;
	private final double shift_nocar_on_logsum_drive_fix;
	private final double shift_nocar_on_logsum_pt;
	private final double shift_nocar_on_logsum_pt_fix;
	private final double shift_purp_on_logsum_pt;
	private final double shift_purp_on_logsum_pt_fix;
	private final double shift_zk_on_logsum_attr;
	private final double shift_zk_on_logsum_drive;
	private final double shift_zk_on_logsum_drive_fix;
	private final double shift_zk_on_logsum_pt;
	private final double shift_zk_on_logsum_pt_fix;
	
	private final DestinationChoiceHelper helper;
	private final DestinationChoiceUtilityModelLogger log;
	
	public DestinationChoiceUtilityFunction(LogitParameters logitParameters, DestinationChoiceHelper helper, DestinationChoiceUtilityModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.asc_bike = logitParameters.get("asc_bike");
		this.asc_bs = logitParameters.get("asc_bs");
		this.asc_car_d = logitParameters.get("asc_car_d");
		this.asc_car_p = logitParameters.get("asc_car_p");
		this.asc_ped = logitParameters.get("asc_ped");
		this.asc_put = logitParameters.get("asc_put");
		this.b_0_1 = logitParameters.get("b_0_1");
		this.b_1_2 = logitParameters.get("b_1_2");
		this.b_attr = logitParameters.get("b_attr");
		this.b_cost = logitParameters.get("b_cost");
		this.b_intrazonal = logitParameters.get("b_intrazonal");
		this.b_logsum_drive = logitParameters.get("b_logsum_drive");
		this.b_logsum_drive_fix = logitParameters.get("b_logsum_drive_fix");
		this.b_logsum_pt_active = logitParameters.get("b_logsum_pt_active");
		this.b_tt_acc_put = logitParameters.get("b_tt_acc_put");
		this.b_tt_bike = logitParameters.get("b_tt_bike");
		this.b_tt_car_d = logitParameters.get("b_tt_car_d");
		this.b_tt_car_p = logitParameters.get("b_tt_car_p");
		this.b_tt_ped = logitParameters.get("b_tt_ped");
		this.b_tt_put = logitParameters.get("b_tt_put");
		this.max_attractivity = logitParameters.get("max_attractivity");
		this.shift_age_1_on_logsum_attr = logitParameters.get("shift_age_1_on_logsum_attr");
		this.shift_age_1_on_logsum_drive = logitParameters.get("shift_age_1_on_logsum_drive");
		this.shift_age_1_on_logsum_drive_fix = logitParameters.get("shift_age_1_on_logsum_drive_fix");
		this.shift_age_1_on_logsum_pt = logitParameters.get("shift_age_1_on_logsum_pt");
		this.shift_age_1_on_logsum_pt_fix = logitParameters.get("shift_age_1_on_logsum_pt_fix");
		this.shift_age_56_on_logsum_attr = logitParameters.get("shift_age_56_on_logsum_attr");
		this.shift_age_56_on_logsum_drive = logitParameters.get("shift_age_56_on_logsum_drive");
		this.shift_age_56_on_logsum_drive_fix = logitParameters.get("shift_age_56_on_logsum_drive_fix");
		this.shift_age_56_on_logsum_pt = logitParameters.get("shift_age_56_on_logsum_pt");
		this.shift_age_56_on_logsum_pt_fix = logitParameters.get("shift_age_56_on_logsum_pt_fix");
		this.shift_age_78_on_logsum_attr = logitParameters.get("shift_age_78_on_logsum_attr");
		this.shift_age_78_on_logsum_drive = logitParameters.get("shift_age_78_on_logsum_drive");
		this.shift_age_78_on_logsum_drive_fix = logitParameters.get("shift_age_78_on_logsum_drive_fix");
		this.shift_age_78_on_logsum_pt = logitParameters.get("shift_age_78_on_logsum_pt");
		this.shift_age_78_on_logsum_pt_fix = logitParameters.get("shift_age_78_on_logsum_pt_fix");
		this.shift_arb_on_logsum_drive = logitParameters.get("shift_arb_on_logsum_drive");
		this.shift_b_0_1_on_logsum_attr = logitParameters.get("shift_b_0_1_on_logsum_attr");
		this.shift_b_1_2_on_logsum_attr = logitParameters.get("shift_b_1_2_on_logsum_attr");
		this.shift_carav_on_logsum_attr = logitParameters.get("shift_carav_on_logsum_attr");
		this.shift_carav_on_logsum_drive = logitParameters.get("shift_carav_on_logsum_drive");
		this.shift_carav_on_logsum_drive_fix = logitParameters.get("shift_carav_on_logsum_drive_fix");
		this.shift_carav_on_logsum_pt = logitParameters.get("shift_carav_on_logsum_pt");
		this.shift_carav_on_logsum_pt_fix = logitParameters.get("shift_carav_on_logsum_pt_fix");
		this.shift_educ_on_logsum_attr = logitParameters.get("shift_educ_on_logsum_attr");
		this.shift_educ_on_logsum_drive = logitParameters.get("shift_educ_on_logsum_drive");
		this.shift_educ_on_logsum_drive_fix = logitParameters.get("shift_educ_on_logsum_drive_fix");
		this.shift_educ_on_logsum_pt = logitParameters.get("shift_educ_on_logsum_pt");
		this.shift_educ_on_logsum_pt_fix = logitParameters.get("shift_educ_on_logsum_pt_fix");
		this.shift_high_inc_on_logsum_attr = logitParameters.get("shift_high_inc_on_logsum_attr");
		this.shift_high_inc_on_logsum_drive = logitParameters.get("shift_high_inc_on_logsum_drive");
		this.shift_high_inc_on_logsum_drive_fix = logitParameters.get("shift_high_inc_on_logsum_drive_fix");
		this.shift_high_inc_on_logsum_pt = logitParameters.get("shift_high_inc_on_logsum_pt");
		this.shift_high_inc_on_logsum_pt_fix = logitParameters.get("shift_high_inc_on_logsum_pt_fix");
		this.shift_home_on_logsum_attr = logitParameters.get("shift_home_on_logsum_attr");
		this.shift_home_on_logsum_drive_fix = logitParameters.get("shift_home_on_logsum_drive_fix");
		this.shift_home_on_logsum_pt = logitParameters.get("shift_home_on_logsum_pt");
		this.shift_home_on_logsum_pt_fix = logitParameters.get("shift_home_on_logsum_pt_fix");
		this.shift_intrazonal_on_attr = logitParameters.get("shift_intrazonal_on_attr");
		this.shift_nocar_on_logsum_drive = logitParameters.get("shift_nocar_on_logsum_drive");
		this.shift_nocar_on_logsum_drive_fix = logitParameters.get("shift_nocar_on_logsum_drive_fix");
		this.shift_nocar_on_logsum_pt = logitParameters.get("shift_nocar_on_logsum_pt");
		this.shift_nocar_on_logsum_pt_fix = logitParameters.get("shift_nocar_on_logsum_pt_fix");
		this.shift_purp_on_logsum_pt = logitParameters.get("shift_purp_on_logsum_pt");
		this.shift_purp_on_logsum_pt_fix = logitParameters.get("shift_purp_on_logsum_pt_fix");
		this.shift_zk_on_logsum_attr = logitParameters.get("shift_zk_on_logsum_attr");
		this.shift_zk_on_logsum_drive = logitParameters.get("shift_zk_on_logsum_drive");
		this.shift_zk_on_logsum_drive_fix = logitParameters.get("shift_zk_on_logsum_drive_fix");
		this.shift_zk_on_logsum_pt = logitParameters.get("shift_zk_on_logsum_pt");
		this.shift_zk_on_logsum_pt_fix = logitParameters.get("shift_zk_on_logsum_pt_fix");
		

	}
	
	public DestinationChoiceUtilityFunction(LogitParameters logitParameters, DestinationChoiceHelper helper) {
		this(logitParameters, helper, new NullDestinationChoiceUtilityModelLogger());
	}
	
	
	public double calculateU_destination(Zone category, Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		
		double AGE_value = helper.getAGE(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double IS_EMPLOYMENT_HOMEKEEPER_value = helper.getIS_EMPLOYMENT_HOMEKEEPER(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_HOMEKEEPER_value", IS_EMPLOYMENT_HOMEKEEPER_value);
		
		double IS_EMPLOYMENT_UNEMPLOYED_value = helper.getIS_EMPLOYMENT_UNEMPLOYED(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_EMPLOYMENT_UNEMPLOYED_value", IS_EMPLOYMENT_UNEMPLOYED_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double CARS_PER_ADULT_value = helper.getCARS_PER_ADULT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "CARS_PER_ADULT_value", CARS_PER_ADULT_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double DISTANCE_value = helper.getDISTANCE(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "DISTANCE_value", DISTANCE_value);
		
		double IS_INTRAZONAL_value = helper.getIS_INTRAZONAL(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "IS_INTRAZONAL_value", IS_INTRAZONAL_value);
		
		double ATTRACTIVITY_value = helper.getATTRACTIVITY(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "ATTRACTIVITY_value", ATTRACTIVITY_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double TRAVEL_TIME_PUBLICTRANSPORT_value = helper.getTRAVEL_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_PUBLICTRANSPORT_value", TRAVEL_TIME_PUBLICTRANSPORT_value);
		
		double TRAVEL_COST_PUBLICTRANSPORT_value = helper.getTRAVEL_COST_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_COST_PUBLICTRANSPORT_value", TRAVEL_COST_PUBLICTRANSPORT_value);
		
		double ACCESS_TIME_PUBLICTRANSPORT_value = helper.getACCESS_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "ACCESS_TIME_PUBLICTRANSPORT_value", ACCESS_TIME_PUBLICTRANSPORT_value);
		
		double EGRESS_TIME_PUBLICTRANSPORT_value = helper.getEGRESS_TIME_PUBLICTRANSPORT(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "EGRESS_TIME_PUBLICTRANSPORT_value", EGRESS_TIME_PUBLICTRANSPORT_value);
		
		double TRAVEL_TIME_PEDESTRIAN_value = helper.getTRAVEL_TIME_PEDESTRIAN(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_PEDESTRIAN_value", TRAVEL_TIME_PEDESTRIAN_value);
		
		double TRAVEL_TIME_BIKE_value = helper.getTRAVEL_TIME_BIKE(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_BIKE_value", TRAVEL_TIME_BIKE_value);
		
		double AVAIL_FUSS_value = helper.getAVAIL_FUSS(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_FUSS_value", AVAIL_FUSS_value);
		
		double AVAIL_RAD_value = helper.getAVAIL_RAD(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_RAD_value", AVAIL_RAD_value);
		
		double AVAIL_OEV_value = helper.getAVAIL_OEV(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_OEV_value", AVAIL_OEV_value);
		
		double AVAIL_PKW_value = helper.getAVAIL_PKW(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_PKW_value", AVAIL_PKW_value);
		
		double TRAVEL_TIME_CAR_value = helper.getTRAVEL_TIME_CAR(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_CAR_value", TRAVEL_TIME_CAR_value);
		
		double TRAVEL_COST_CAR_value = helper.getTRAVEL_COST_CAR(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_COST_CAR_value", TRAVEL_COST_CAR_value);
		
		double AVAIL_MF_value = helper.getAVAIL_MF(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_MF_value", AVAIL_MF_value);
		
		double TRAVEL_TIME_PUBLICTRANSPORT_FIX_value = helper.getTRAVEL_TIME_PUBLICTRANSPORT_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_PUBLICTRANSPORT_FIX_value", TRAVEL_TIME_PUBLICTRANSPORT_FIX_value);
		
		double TRAVEL_COST_PUBLICTRANSPORT_FIX_value = helper.getTRAVEL_COST_PUBLICTRANSPORT_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_COST_PUBLICTRANSPORT_FIX_value", TRAVEL_COST_PUBLICTRANSPORT_FIX_value);
		
		double TRAVEL_TIME_PEDESTRIAN_FIX_value = helper.getTRAVEL_TIME_PEDESTRIAN_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_PEDESTRIAN_FIX_value", TRAVEL_TIME_PEDESTRIAN_FIX_value);
		
		double TRAVEL_TIME_BIKE_FIX_value = helper.getTRAVEL_TIME_BIKE_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_BIKE_FIX_value", TRAVEL_TIME_BIKE_FIX_value);
		
		double AVAIL_BS_value = helper.getAVAIL_BS(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "AVAIL_BS_value", AVAIL_BS_value);
		
		double TRAVEL_COST_BIKESHARING_FIX_value = helper.getTRAVEL_COST_BIKESHARING_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_COST_BIKESHARING_FIX_value", TRAVEL_COST_BIKESHARING_FIX_value);
		
		double TRAVEL_TIME_CAR_FIX_value = helper.getTRAVEL_TIME_CAR_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_TIME_CAR_FIX_value", TRAVEL_TIME_CAR_FIX_value);
		
		double TRAVEL_COST_CAR_FIX_value = helper.getTRAVEL_COST_CAR_FIX(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		this.log.log(person, origin, destination, previousActivity, nextActivity, time, randomNumber, category, "TRAVEL_COST_CAR_FIX_value", TRAVEL_COST_CAR_FIX_value);
		
		
		
		return   ((b_attr + shift_age_1_on_logsum_attr*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + shift_age_56_on_logsum_attr*((50.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + shift_age_78_on_logsum_attr*((70.0d <= AGE_value && AGE_value <= 120.0d) ? 1.0d : 0.0d) + shift_educ_on_logsum_attr*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d) + shift_home_on_logsum_attr*(((IS_EMPLOYMENT_HOMEKEEPER_value + IS_EMPLOYMENT_UNEMPLOYED_value) > 0.0d) ? 1.0d : 0.0d) + shift_zk_on_logsum_attr*HAS_COMMUTER_TICKET_value + shift_carav_on_logsum_attr*((CARS_PER_ADULT_value >= 1.0d) ? 1.0d : 0.0d) + shift_high_inc_on_logsum_attr*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d) + shift_b_0_1_on_logsum_attr*((0.0d < DISTANCE_value && DISTANCE_value <= 1.0d) ? 1.0d : 0.0d) + shift_b_1_2_on_logsum_attr*((1.0d < DISTANCE_value && DISTANCE_value <= 2.0d) ? 1.0d : 0.0d) + shift_intrazonal_on_attr*IS_INTRAZONAL_value)*(Math.log(((ATTRACTIVITY_value > max_attractivity) ? (max_attractivity) : (ATTRACTIVITY_value)))))
				+(b_0_1*((0.0d < DISTANCE_value && DISTANCE_value <= 1.0d) ? 1.0d : 0.0d))
				+(b_1_2*((1.0d < DISTANCE_value && DISTANCE_value <= 2.0d) ? 1.0d : 0.0d))
				+(b_intrazonal*IS_INTRAZONAL_value)
				+((b_logsum_pt_active + shift_purp_on_logsum_pt + shift_age_1_on_logsum_pt*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + shift_age_56_on_logsum_pt*((50.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + shift_age_78_on_logsum_pt*((70.0d <= AGE_value && AGE_value <= 120.0d) ? 1.0d : 0.0d) + shift_educ_on_logsum_pt*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d) + shift_home_on_logsum_pt*(((IS_EMPLOYMENT_HOMEKEEPER_value + IS_EMPLOYMENT_UNEMPLOYED_value) > 0.0d) ? 1.0d : 0.0d) + shift_zk_on_logsum_pt*HAS_COMMUTER_TICKET_value + shift_carav_on_logsum_pt*((CARS_PER_ADULT_value >= 1.0d) ? 1.0d : 0.0d) + shift_nocar_on_logsum_pt*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d) + shift_high_inc_on_logsum_pt*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*(Math.log(Math.exp(asc_put + b_tt_put*(((1000.0d > TRAVEL_TIME_PUBLICTRANSPORT_value) ? (TRAVEL_TIME_PUBLICTRANSPORT_value) : (999.0d))) + b_cost*(((1000.0d > TRAVEL_COST_PUBLICTRANSPORT_value) ? (TRAVEL_COST_PUBLICTRANSPORT_value) : (999.0d))) + b_tt_acc_put*(((1000.0d > ACCESS_TIME_PUBLICTRANSPORT_value && 1000.0d > EGRESS_TIME_PUBLICTRANSPORT_value) ? (ACCESS_TIME_PUBLICTRANSPORT_value + EGRESS_TIME_PUBLICTRANSPORT_value) : (999.0d)))) + Math.exp(asc_ped + b_tt_ped*(((1000.0d > TRAVEL_TIME_PEDESTRIAN_value) ? (TRAVEL_TIME_PEDESTRIAN_value) : (999.0d)))) + Math.exp(asc_bike + b_tt_bike*(((1000.0d > TRAVEL_TIME_BIKE_value) ? (TRAVEL_TIME_BIKE_value) : (999.0d))))) + ((AVAIL_FUSS_value == 0.0d && AVAIL_RAD_value == 0.0d && AVAIL_OEV_value == 0.0d) ? 1.0d : 0.0d)*(-50.0d)) + (b_logsum_drive + shift_age_1_on_logsum_drive*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + shift_age_56_on_logsum_drive*((50.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + shift_age_78_on_logsum_drive*((70.0d <= AGE_value && AGE_value <= 120.0d) ? 1.0d : 0.0d) + shift_educ_on_logsum_drive*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d) + shift_arb_on_logsum_drive*(((IS_EMPLOYMENT_HOMEKEEPER_value + IS_EMPLOYMENT_UNEMPLOYED_value) > 0.0d) ? 1.0d : 0.0d) + shift_zk_on_logsum_drive*HAS_COMMUTER_TICKET_value + shift_carav_on_logsum_drive*((CARS_PER_ADULT_value >= 1.0d) ? 1.0d : 0.0d) + shift_nocar_on_logsum_drive*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d) + shift_high_inc_on_logsum_drive*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*(Math.log(AVAIL_PKW_value*(Math.exp(asc_car_d + b_tt_car_d*(((1000.0d > TRAVEL_TIME_CAR_value) ? (TRAVEL_TIME_CAR_value) : (999.0d))) + b_cost*(((1000.0d > TRAVEL_COST_CAR_value) ? (TRAVEL_COST_CAR_value) : (999.0d))))) + Math.exp(asc_car_p + b_tt_car_p*(((1000.0d > TRAVEL_TIME_CAR_value) ? (TRAVEL_TIME_CAR_value + 3.0d) : (999.0d))))) + ((AVAIL_PKW_value == 0.0d && AVAIL_MF_value == 0.0d) ? 1.0d : 0.0d)*(-50.0d)) + (b_logsum_pt_active + shift_purp_on_logsum_pt_fix + shift_age_1_on_logsum_pt_fix*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + shift_age_56_on_logsum_pt_fix*((50.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + shift_age_78_on_logsum_pt_fix*((70.0d <= AGE_value && AGE_value <= 120.0d) ? 1.0d : 0.0d) + shift_educ_on_logsum_pt_fix*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d) + shift_home_on_logsum_pt_fix*(((IS_EMPLOYMENT_HOMEKEEPER_value + IS_EMPLOYMENT_UNEMPLOYED_value) > 0.0d) ? 1.0d : 0.0d) + shift_zk_on_logsum_pt_fix*HAS_COMMUTER_TICKET_value + shift_carav_on_logsum_pt_fix*((CARS_PER_ADULT_value >= 1.0d) ? 1.0d : 0.0d) + shift_nocar_on_logsum_pt_fix*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d) + shift_high_inc_on_logsum_pt_fix*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*(Math.log(Math.exp(asc_put + b_tt_put*(((1000.0d > TRAVEL_TIME_PUBLICTRANSPORT_FIX_value) ? (TRAVEL_TIME_PUBLICTRANSPORT_FIX_value) : (999.0d))) + b_cost*(((1000.0d > TRAVEL_COST_PUBLICTRANSPORT_FIX_value) ? (TRAVEL_COST_PUBLICTRANSPORT_FIX_value) : (999.0d)))) + Math.exp(asc_ped + b_tt_ped*(((1000.0d > TRAVEL_TIME_PEDESTRIAN_FIX_value) ? (TRAVEL_TIME_PEDESTRIAN_FIX_value) : (999.0d)))) + Math.exp(asc_bike + b_tt_bike*(((1000.0d > TRAVEL_TIME_BIKE_FIX_value) ? (TRAVEL_TIME_BIKE_FIX_value) : (999.0d)))) + AVAIL_BS_value*(Math.exp(asc_bs + b_tt_bike*(((1000.0d > TRAVEL_TIME_BIKE_FIX_value) ? (TRAVEL_TIME_BIKE_FIX_value) : (999.0d))) + b_cost*(((1000.0d > TRAVEL_COST_BIKESHARING_FIX_value) ? (TRAVEL_COST_BIKESHARING_FIX_value) : (999.0d)))))) + ((AVAIL_FUSS_value == 0.0d && AVAIL_RAD_value == 0.0d && AVAIL_OEV_value == 0.0d) ? 1.0d : 0.0d)*(-50.0d)) + (b_logsum_drive_fix + shift_age_1_on_logsum_drive_fix*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d) + shift_age_56_on_logsum_drive_fix*((50.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d) + shift_age_78_on_logsum_drive_fix*((70.0d <= AGE_value && AGE_value <= 120.0d) ? 1.0d : 0.0d) + shift_educ_on_logsum_drive_fix*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d) + shift_home_on_logsum_drive_fix*(((IS_EMPLOYMENT_HOMEKEEPER_value + IS_EMPLOYMENT_UNEMPLOYED_value) > 0.0d) ? 1.0d : 0.0d) + shift_zk_on_logsum_drive_fix*HAS_COMMUTER_TICKET_value + shift_carav_on_logsum_drive_fix*((CARS_PER_ADULT_value >= 1.0d) ? 1.0d : 0.0d) + shift_nocar_on_logsum_drive_fix*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d) + shift_high_inc_on_logsum_drive_fix*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))*(Math.log(AVAIL_PKW_value*(Math.exp(asc_car_d + b_tt_car_d*(((1000.0d > TRAVEL_TIME_CAR_FIX_value) ? (TRAVEL_TIME_CAR_FIX_value) : (999.0d))) + b_cost*(((1000.0d > TRAVEL_COST_CAR_FIX_value) ? (TRAVEL_COST_CAR_FIX_value) : (999.0d))))) + Math.exp(asc_car_p + b_tt_car_p*(((1000.0d > TRAVEL_TIME_CAR_FIX_value) ? (TRAVEL_TIME_CAR_FIX_value + 3.0d) : (999.0d))))) + ((AVAIL_PKW_value == 0.0d && AVAIL_MF_value == 0.0d) ? 1.0d : 0.0d)*(-50.0d)));
	}
}

