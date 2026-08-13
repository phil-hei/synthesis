package edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.ModePreferenceHelper;
import edu.kit.ifv.mobitopp.simulation.modechoice.preference.gen.PersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.lang.Double;
import java.util.Map;

public class ModePreferenceUtilityFunction {
	
	private final double age_0_17_on_asc_bike;
	private final double age_0_17_on_asc_car_d;
	private final double age_0_17_on_asc_car_p;
	private final double age_0_17_on_asc_ped;
	private final double age_0_17_on_asc_put;
	private final double age_0_17_on_asc_taxi;
	private final double age_18_29_on_asc_bike;
	private final double age_18_29_on_asc_bs;
	private final double age_18_29_on_asc_car_d;
	private final double age_18_29_on_asc_car_p;
	private final double age_18_29_on_asc_cs;
	private final double age_18_29_on_asc_ped;
	private final double age_18_29_on_asc_put;
	private final double age_18_29_on_asc_rp;
	private final double age_50_59_on_asc_bike;
	private final double age_50_59_on_asc_car_d;
	private final double age_50_59_on_asc_car_p;
	private final double age_50_59_on_asc_cs;
	private final double age_50_59_on_asc_ped;
	private final double age_50_59_on_asc_put;
	private final double age_50_59_on_asc_rp;
	private final double age_60_69_on_asc_bike;
	private final double age_60_69_on_asc_car_d;
	private final double age_60_69_on_asc_car_p;
	private final double age_60_69_on_asc_ped;
	private final double age_60_69_on_asc_put;
	private final double age_60_69_on_asc_rp;
	private final double age_70_100_on_asc_bike;
	private final double age_70_100_on_asc_car_d;
	private final double age_70_100_on_asc_car_p;
	private final double age_70_100_on_asc_ped;
	private final double age_70_100_on_asc_put;
	private final double asc_bike_mu;
	private final double asc_bike_sig;
	private final double asc_bs;
	private final double asc_car_d_mu;
	private final double asc_car_d_sig;
	private final double asc_car_p_mu;
	private final double asc_car_p_sig;
	private final double asc_cs_ff;
	private final double asc_cs_sb_mu;
	private final double asc_cs_sb_sig;
	private final double asc_ped_mu;
	private final double asc_ped_sig;
	private final double asc_put_mu;
	private final double asc_put_sig;
	private final double asc_rp;
	private final double asc_taxi;
	private final double beruft_on_asc_bike;
	private final double beruft_on_asc_car_d;
	private final double beruft_on_asc_car_p;
	private final double beruft_on_asc_cs_sb;
	private final double beruft_on_asc_ped;
	private final double beruft_on_asc_put;
	private final double beruft_on_asc_rp;
	private final double csmit_on_asc_bike;
	private final double csmit_on_asc_car_d;
	private final double csmit_on_asc_car_p;
	private final double csmit_on_asc_cs;
	private final double csmit_on_asc_ped;
	private final double csmit_on_asc_put;
	private final double csmit_on_sig_cs;
	private final double ebike_on_asc_bike;
	private final double female_on_asc_bike;
	private final double female_on_asc_bs;
	private final double female_on_asc_car_d;
	private final double female_on_asc_car_p;
	private final double female_on_asc_cs;
	private final double female_on_asc_ped;
	private final double female_on_asc_put;
	private final double hhgr_2_on_asc_bike;
	private final double hhgr_2_on_asc_bs;
	private final double hhgr_2_on_asc_car_d;
	private final double hhgr_2_on_asc_car_p;
	private final double hhgr_2_on_asc_cs;
	private final double hhgr_2_on_asc_ped;
	private final double hhgr_2_on_asc_put;
	private final double hhgr_34_on_asc_bike;
	private final double hhgr_34_on_asc_bs;
	private final double hhgr_34_on_asc_car_d;
	private final double hhgr_34_on_asc_car_p;
	private final double hhgr_34_on_asc_cs;
	private final double hhgr_34_on_asc_ped;
	private final double hhgr_34_on_asc_put;
	private final double inc_high_on_asc_bike;
	private final double inc_high_on_asc_car_d;
	private final double inc_high_on_asc_car_p;
	private final double inc_high_on_asc_ped;
	private final double inc_high_on_asc_put;
	private final double inc_low_on_asc_bike;
	private final double inc_low_on_asc_car_d;
	private final double inc_low_on_asc_car_p;
	private final double inc_low_on_asc_ped;
	private final double inc_low_on_asc_put;
	private final double pkw_0_on_asc_bike;
	private final double pkw_0_on_asc_car_d;
	private final double pkw_0_on_asc_car_p;
	private final double pkw_0_on_asc_cs;
	private final double pkw_0_on_asc_ped;
	private final double pkw_0_on_asc_put;
	private final double pkw_1_on_asc_bike;
	private final double pkw_1_on_asc_car_d;
	private final double pkw_1_on_asc_car_p;
	private final double pkw_1_on_asc_cs;
	private final double pkw_1_on_asc_ped;
	private final double pkw_1_on_asc_put;
	private final double student_on_asc_bike;
	private final double student_on_asc_car_d;
	private final double student_on_asc_car_p;
	private final double student_on_asc_ped;
	private final double student_on_asc_put;
	private final double student_on_asc_rp;
	private final double zk_on_asc_bike;
	private final double zk_on_asc_car_d;
	private final double zk_on_asc_car_p;
	private final double zk_on_asc_ped;
	private final double zk_on_asc_put;
	
	private final ModePreferenceHelper helper;
	private final PersonChangerFunctionLogger log;
	
	public ModePreferenceUtilityFunction(LogitParameters logitParameters, ModePreferenceHelper helper, PersonChangerFunctionLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.age_0_17_on_asc_bike = logitParameters.get("age_0_17_on_asc_bike");
		this.age_0_17_on_asc_car_d = logitParameters.get("age_0_17_on_asc_car_d");
		this.age_0_17_on_asc_car_p = logitParameters.get("age_0_17_on_asc_car_p");
		this.age_0_17_on_asc_ped = logitParameters.get("age_0_17_on_asc_ped");
		this.age_0_17_on_asc_put = logitParameters.get("age_0_17_on_asc_put");
		this.age_0_17_on_asc_taxi = logitParameters.get("age_0_17_on_asc_taxi");
		this.age_18_29_on_asc_bike = logitParameters.get("age_18_29_on_asc_bike");
		this.age_18_29_on_asc_bs = logitParameters.get("age_18_29_on_asc_bs");
		this.age_18_29_on_asc_car_d = logitParameters.get("age_18_29_on_asc_car_d");
		this.age_18_29_on_asc_car_p = logitParameters.get("age_18_29_on_asc_car_p");
		this.age_18_29_on_asc_cs = logitParameters.get("age_18_29_on_asc_cs");
		this.age_18_29_on_asc_ped = logitParameters.get("age_18_29_on_asc_ped");
		this.age_18_29_on_asc_put = logitParameters.get("age_18_29_on_asc_put");
		this.age_18_29_on_asc_rp = logitParameters.get("age_18_29_on_asc_rp");
		this.age_50_59_on_asc_bike = logitParameters.get("age_50_59_on_asc_bike");
		this.age_50_59_on_asc_car_d = logitParameters.get("age_50_59_on_asc_car_d");
		this.age_50_59_on_asc_car_p = logitParameters.get("age_50_59_on_asc_car_p");
		this.age_50_59_on_asc_cs = logitParameters.get("age_50_59_on_asc_cs");
		this.age_50_59_on_asc_ped = logitParameters.get("age_50_59_on_asc_ped");
		this.age_50_59_on_asc_put = logitParameters.get("age_50_59_on_asc_put");
		this.age_50_59_on_asc_rp = logitParameters.get("age_50_59_on_asc_rp");
		this.age_60_69_on_asc_bike = logitParameters.get("age_60_69_on_asc_bike");
		this.age_60_69_on_asc_car_d = logitParameters.get("age_60_69_on_asc_car_d");
		this.age_60_69_on_asc_car_p = logitParameters.get("age_60_69_on_asc_car_p");
		this.age_60_69_on_asc_ped = logitParameters.get("age_60_69_on_asc_ped");
		this.age_60_69_on_asc_put = logitParameters.get("age_60_69_on_asc_put");
		this.age_60_69_on_asc_rp = logitParameters.get("age_60_69_on_asc_rp");
		this.age_70_100_on_asc_bike = logitParameters.get("age_70_100_on_asc_bike");
		this.age_70_100_on_asc_car_d = logitParameters.get("age_70_100_on_asc_car_d");
		this.age_70_100_on_asc_car_p = logitParameters.get("age_70_100_on_asc_car_p");
		this.age_70_100_on_asc_ped = logitParameters.get("age_70_100_on_asc_ped");
		this.age_70_100_on_asc_put = logitParameters.get("age_70_100_on_asc_put");
		this.asc_bike_mu = logitParameters.get("asc_bike_mu");
		this.asc_bike_sig = logitParameters.get("asc_bike_sig");
		this.asc_bs = logitParameters.get("asc_bs");
		this.asc_car_d_mu = logitParameters.get("asc_car_d_mu");
		this.asc_car_d_sig = logitParameters.get("asc_car_d_sig");
		this.asc_car_p_mu = logitParameters.get("asc_car_p_mu");
		this.asc_car_p_sig = logitParameters.get("asc_car_p_sig");
		this.asc_cs_ff = logitParameters.get("asc_cs_ff");
		this.asc_cs_sb_mu = logitParameters.get("asc_cs_sb_mu");
		this.asc_cs_sb_sig = logitParameters.get("asc_cs_sb_sig");
		this.asc_ped_mu = logitParameters.get("asc_ped_mu");
		this.asc_ped_sig = logitParameters.get("asc_ped_sig");
		this.asc_put_mu = logitParameters.get("asc_put_mu");
		this.asc_put_sig = logitParameters.get("asc_put_sig");
		this.asc_rp = logitParameters.get("asc_rp");
		this.asc_taxi = logitParameters.get("asc_taxi");
		this.beruft_on_asc_bike = logitParameters.get("beruft_on_asc_bike");
		this.beruft_on_asc_car_d = logitParameters.get("beruft_on_asc_car_d");
		this.beruft_on_asc_car_p = logitParameters.get("beruft_on_asc_car_p");
		this.beruft_on_asc_cs_sb = logitParameters.get("beruft_on_asc_cs_sb");
		this.beruft_on_asc_ped = logitParameters.get("beruft_on_asc_ped");
		this.beruft_on_asc_put = logitParameters.get("beruft_on_asc_put");
		this.beruft_on_asc_rp = logitParameters.get("beruft_on_asc_rp");
		this.csmit_on_asc_bike = logitParameters.get("csmit_on_asc_bike");
		this.csmit_on_asc_car_d = logitParameters.get("csmit_on_asc_car_d");
		this.csmit_on_asc_car_p = logitParameters.get("csmit_on_asc_car_p");
		this.csmit_on_asc_cs = logitParameters.get("csmit_on_asc_cs");
		this.csmit_on_asc_ped = logitParameters.get("csmit_on_asc_ped");
		this.csmit_on_asc_put = logitParameters.get("csmit_on_asc_put");
		this.csmit_on_sig_cs = logitParameters.get("csmit_on_sig_cs");
		this.ebike_on_asc_bike = logitParameters.get("ebike_on_asc_bike");
		this.female_on_asc_bike = logitParameters.get("female_on_asc_bike");
		this.female_on_asc_bs = logitParameters.get("female_on_asc_bs");
		this.female_on_asc_car_d = logitParameters.get("female_on_asc_car_d");
		this.female_on_asc_car_p = logitParameters.get("female_on_asc_car_p");
		this.female_on_asc_cs = logitParameters.get("female_on_asc_cs");
		this.female_on_asc_ped = logitParameters.get("female_on_asc_ped");
		this.female_on_asc_put = logitParameters.get("female_on_asc_put");
		this.hhgr_2_on_asc_bike = logitParameters.get("hhgr_2_on_asc_bike");
		this.hhgr_2_on_asc_bs = logitParameters.get("hhgr_2_on_asc_bs");
		this.hhgr_2_on_asc_car_d = logitParameters.get("hhgr_2_on_asc_car_d");
		this.hhgr_2_on_asc_car_p = logitParameters.get("hhgr_2_on_asc_car_p");
		this.hhgr_2_on_asc_cs = logitParameters.get("hhgr_2_on_asc_cs");
		this.hhgr_2_on_asc_ped = logitParameters.get("hhgr_2_on_asc_ped");
		this.hhgr_2_on_asc_put = logitParameters.get("hhgr_2_on_asc_put");
		this.hhgr_34_on_asc_bike = logitParameters.get("hhgr_34_on_asc_bike");
		this.hhgr_34_on_asc_bs = logitParameters.get("hhgr_34_on_asc_bs");
		this.hhgr_34_on_asc_car_d = logitParameters.get("hhgr_34_on_asc_car_d");
		this.hhgr_34_on_asc_car_p = logitParameters.get("hhgr_34_on_asc_car_p");
		this.hhgr_34_on_asc_cs = logitParameters.get("hhgr_34_on_asc_cs");
		this.hhgr_34_on_asc_ped = logitParameters.get("hhgr_34_on_asc_ped");
		this.hhgr_34_on_asc_put = logitParameters.get("hhgr_34_on_asc_put");
		this.inc_high_on_asc_bike = logitParameters.get("inc_high_on_asc_bike");
		this.inc_high_on_asc_car_d = logitParameters.get("inc_high_on_asc_car_d");
		this.inc_high_on_asc_car_p = logitParameters.get("inc_high_on_asc_car_p");
		this.inc_high_on_asc_ped = logitParameters.get("inc_high_on_asc_ped");
		this.inc_high_on_asc_put = logitParameters.get("inc_high_on_asc_put");
		this.inc_low_on_asc_bike = logitParameters.get("inc_low_on_asc_bike");
		this.inc_low_on_asc_car_d = logitParameters.get("inc_low_on_asc_car_d");
		this.inc_low_on_asc_car_p = logitParameters.get("inc_low_on_asc_car_p");
		this.inc_low_on_asc_ped = logitParameters.get("inc_low_on_asc_ped");
		this.inc_low_on_asc_put = logitParameters.get("inc_low_on_asc_put");
		this.pkw_0_on_asc_bike = logitParameters.get("pkw_0_on_asc_bike");
		this.pkw_0_on_asc_car_d = logitParameters.get("pkw_0_on_asc_car_d");
		this.pkw_0_on_asc_car_p = logitParameters.get("pkw_0_on_asc_car_p");
		this.pkw_0_on_asc_cs = logitParameters.get("pkw_0_on_asc_cs");
		this.pkw_0_on_asc_ped = logitParameters.get("pkw_0_on_asc_ped");
		this.pkw_0_on_asc_put = logitParameters.get("pkw_0_on_asc_put");
		this.pkw_1_on_asc_bike = logitParameters.get("pkw_1_on_asc_bike");
		this.pkw_1_on_asc_car_d = logitParameters.get("pkw_1_on_asc_car_d");
		this.pkw_1_on_asc_car_p = logitParameters.get("pkw_1_on_asc_car_p");
		this.pkw_1_on_asc_cs = logitParameters.get("pkw_1_on_asc_cs");
		this.pkw_1_on_asc_ped = logitParameters.get("pkw_1_on_asc_ped");
		this.pkw_1_on_asc_put = logitParameters.get("pkw_1_on_asc_put");
		this.student_on_asc_bike = logitParameters.get("student_on_asc_bike");
		this.student_on_asc_car_d = logitParameters.get("student_on_asc_car_d");
		this.student_on_asc_car_p = logitParameters.get("student_on_asc_car_p");
		this.student_on_asc_ped = logitParameters.get("student_on_asc_ped");
		this.student_on_asc_put = logitParameters.get("student_on_asc_put");
		this.student_on_asc_rp = logitParameters.get("student_on_asc_rp");
		this.zk_on_asc_bike = logitParameters.get("zk_on_asc_bike");
		this.zk_on_asc_car_d = logitParameters.get("zk_on_asc_car_d");
		this.zk_on_asc_car_p = logitParameters.get("zk_on_asc_car_p");
		this.zk_on_asc_ped = logitParameters.get("zk_on_asc_ped");
		this.zk_on_asc_put = logitParameters.get("zk_on_asc_put");
		

	}
	
	public ModePreferenceUtilityFunction(LogitParameters logitParameters, ModePreferenceHelper helper) {
		this(logitParameters, helper, new NullPersonChangerFunctionLogger());
	}
	
	
	public double calculateAsc_bike(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Bike";
		
		double DRAW_PREF_BIKE_value = helper.getDRAW_PREF_BIKE(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_BIKE_value", DRAW_PREF_BIKE_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, draws);
		this.log.log(person, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HAS_E_BIKE_value = helper.getHAS_E_BIKE(category, person, draws);
		this.log.log(person, category, "HAS_E_BIKE_value", HAS_E_BIKE_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_bike_mu)
				+(asc_bike_sig*DRAW_PREF_BIKE_value)
				+(age_0_17_on_asc_bike*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_bike*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_bike*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_bike*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_bike*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_bike*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_bike*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_bike*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_low_on_asc_bike*((1.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_bike*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_bike*HAS_COMMUTER_TICKET_value)
				+(csmit_on_asc_bike*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_bike*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_bike*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(ebike_on_asc_bike*HAS_E_BIKE_value)
				+(hhgr_2_on_asc_bike*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_bike*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_car_p(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Passenger";
		
		double DRAW_PREF_PASSENGER_value = helper.getDRAW_PREF_PASSENGER(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_PASSENGER_value", DRAW_PREF_PASSENGER_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, draws);
		this.log.log(person, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_car_p_mu)
				+(asc_car_p_sig*DRAW_PREF_PASSENGER_value)
				+(age_0_17_on_asc_car_p*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_car_p*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_car_p*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_car_p*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_car_p*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_car_p*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_car_p*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_car_p*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_low_on_asc_car_p*((1.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_car_p*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_car_p*HAS_COMMUTER_TICKET_value)
				+(csmit_on_asc_car_p*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_car_p*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_car_p*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_car_p*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_car_p*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_car_d(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Car";
		
		double DRAW_PREF_CAR_value = helper.getDRAW_PREF_CAR(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_CAR_value", DRAW_PREF_CAR_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, draws);
		this.log.log(person, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_car_d_mu)
				+(asc_car_d_sig*DRAW_PREF_CAR_value)
				+(age_0_17_on_asc_car_d*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_car_d*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_car_d*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_car_d*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_car_d*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_car_d*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_car_d*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_car_d*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_low_on_asc_car_d*((1.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_car_d*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(csmit_on_asc_car_d*HAS_MEMBERSHIP_CS_value)
				+(zk_on_asc_car_d*HAS_COMMUTER_TICKET_value)
				+(pkw_0_on_asc_car_d*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_car_d*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_car_d*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_car_d*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_put(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "PublicTransport";
		
		double DRAW_PREF_PUBLICTRANSPORT_value = helper.getDRAW_PREF_PUBLICTRANSPORT(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_PUBLICTRANSPORT_value", DRAW_PREF_PUBLICTRANSPORT_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, draws);
		this.log.log(person, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_put_mu)
				+(asc_put_sig*DRAW_PREF_PUBLICTRANSPORT_value)
				+(age_0_17_on_asc_put*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_put*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_put*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_put*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_put*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_put*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_put*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_put*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_low_on_asc_put*((1.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_put*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_put*HAS_COMMUTER_TICKET_value)
				+(csmit_on_asc_put*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_put*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_put*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_put*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_put*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_cs_sb(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Carsharing_Station";
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double DRAW_PREF_CS_SB_value = helper.getDRAW_PREF_CS_SB(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_CS_SB_value", DRAW_PREF_CS_SB_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_cs_sb_mu)
				+((asc_cs_sb_sig + csmit_on_sig_cs*HAS_MEMBERSHIP_CS_value)*DRAW_PREF_CS_SB_value)
				+(age_18_29_on_asc_cs*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_cs*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_cs_sb*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_cs*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(csmit_on_asc_cs*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_cs*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_cs*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_cs*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_cs*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_cs_ff(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Carsharing_FreeFloating";
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_cs_ff)
				+(age_18_29_on_asc_cs*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_cs*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(female_on_asc_cs*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(csmit_on_asc_cs*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_cs*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_cs*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_cs*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_cs*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_rp(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "RidePooling";
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		
		
		return   (asc_rp)
				+(age_18_29_on_asc_rp*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_rp*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_rp*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_rp*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_rp*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_ped(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Pedestrian";
		
		double DRAW_PREF_PEDESTRIAN_value = helper.getDRAW_PREF_PEDESTRIAN(category, person, draws);
		this.log.log(person, category, "DRAW_PREF_PEDESTRIAN_value", DRAW_PREF_PEDESTRIAN_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person, draws);
		this.log.log(person, category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double HAS_MEMBERSHIP_CS_value = helper.getHAS_MEMBERSHIP_CS(category, person, draws);
		this.log.log(person, category, "HAS_MEMBERSHIP_CS_value", HAS_MEMBERSHIP_CS_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		
		
		return   (asc_ped_mu)
				+(asc_ped_sig*DRAW_PREF_PEDESTRIAN_value)
				+(age_0_17_on_asc_ped*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_asc_ped*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_asc_ped*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_asc_ped*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_asc_ped*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_asc_ped*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(student_on_asc_ped*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(female_on_asc_ped*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(inc_low_on_asc_ped*((1.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 2.0d) ? 1.0d : 0.0d))
				+(inc_high_on_asc_ped*((4.0d <= HOUSEHOLD_ECONOMICAL_STATUS_value && HOUSEHOLD_ECONOMICAL_STATUS_value <= 5.0d) ? 1.0d : 0.0d))
				+(zk_on_asc_ped*HAS_COMMUTER_TICKET_value)
				+(csmit_on_asc_ped*HAS_MEMBERSHIP_CS_value)
				+(pkw_0_on_asc_ped*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 0.0d) ? 1.0d : 0.0d))
				+(pkw_1_on_asc_ped*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_ped*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_ped*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_taxi(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Taxi";
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		
		
		return   (asc_taxi)
				+(age_0_17_on_asc_taxi*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d));
	}
	public double calculateAsc_bs(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "BikeSharing";
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person, draws);
		this.log.log(person, category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		double GENDER_value = helper.getGENDER(category, person, draws);
		this.log.log(person, category, "GENDER_value", GENDER_value);
		
		
		
		return   (asc_bs)
				+(age_18_29_on_asc_bs*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(hhgr_2_on_asc_bs*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(hhgr_34_on_asc_bs*((HOUSEHOLD_SIZE_value >= 3.0d) ? 1.0d : 0.0d))
				+(female_on_asc_bs*((GENDER_value == 2.0d) ? 1.0d : 0.0d));
	}
}

