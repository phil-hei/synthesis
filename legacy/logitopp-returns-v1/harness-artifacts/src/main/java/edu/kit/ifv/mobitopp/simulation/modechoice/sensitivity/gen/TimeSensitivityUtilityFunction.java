package edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.simulation.Mode;
import edu.kit.ifv.mobitopp.simulation.modechoice.PersonChangerFunction;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.PersonChangerFunctionLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.sensitivity.gen.TimeSensitivityHelper;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.lang.Double;
import java.util.Map;

public class TimeSensitivityUtilityFunction {
	
	private final double age_0_17_on_b_tt_bike;
	private final double age_0_17_on_b_tt_car_d;
	private final double age_0_17_on_b_tt_car_p;
	private final double age_0_17_on_b_tt_ped;
	private final double age_0_17_on_b_tt_put;
	private final double age_18_29_on_b_tt_bike;
	private final double age_18_29_on_b_tt_car_d;
	private final double age_18_29_on_b_tt_car_p;
	private final double age_18_29_on_b_tt_ped;
	private final double age_18_29_on_b_tt_put;
	private final double age_50_59_on_b_tt_bike;
	private final double age_50_59_on_b_tt_car_d;
	private final double age_50_59_on_b_tt_car_p;
	private final double age_50_59_on_b_tt_ped;
	private final double age_50_59_on_b_tt_put;
	private final double age_60_69_on_b_tt_bike;
	private final double age_60_69_on_b_tt_car_d;
	private final double age_60_69_on_b_tt_car_p;
	private final double age_60_69_on_b_tt_ped;
	private final double age_60_69_on_b_tt_put;
	private final double age_70_100_on_b_tt_bike;
	private final double age_70_100_on_b_tt_car_d;
	private final double age_70_100_on_b_tt_car_p;
	private final double age_70_100_on_b_tt_ped;
	private final double age_70_100_on_b_tt_put;
	private final double b_tt_bike_mu;
	private final double b_tt_bike_sig;
	private final double b_tt_bs;
	private final double b_tt_car_d_mu;
	private final double b_tt_car_d_sig;
	private final double b_tt_car_p_mu;
	private final double b_tt_car_p_sig;
	private final double b_tt_cs;
	private final double b_tt_ped;
	private final double b_tt_put_mu;
	private final double b_tt_put_sig;
	private final double b_tt_rp;
	private final double b_tt_taxi;
	private final double beruft_on_b_tt_bike;
	private final double beruft_on_b_tt_car_d;
	private final double beruft_on_b_tt_car_p;
	private final double beruft_on_b_tt_ped;
	private final double beruft_on_b_tt_put;
	
	private final TimeSensitivityHelper helper;
	private final PersonChangerFunctionLogger log;
	
	public TimeSensitivityUtilityFunction(LogitParameters logitParameters, TimeSensitivityHelper helper, PersonChangerFunctionLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.age_0_17_on_b_tt_bike = logitParameters.get("age_0_17_on_b_tt_bike");
		this.age_0_17_on_b_tt_car_d = logitParameters.get("age_0_17_on_b_tt_car_d");
		this.age_0_17_on_b_tt_car_p = logitParameters.get("age_0_17_on_b_tt_car_p");
		this.age_0_17_on_b_tt_ped = logitParameters.get("age_0_17_on_b_tt_ped");
		this.age_0_17_on_b_tt_put = logitParameters.get("age_0_17_on_b_tt_put");
		this.age_18_29_on_b_tt_bike = logitParameters.get("age_18_29_on_b_tt_bike");
		this.age_18_29_on_b_tt_car_d = logitParameters.get("age_18_29_on_b_tt_car_d");
		this.age_18_29_on_b_tt_car_p = logitParameters.get("age_18_29_on_b_tt_car_p");
		this.age_18_29_on_b_tt_ped = logitParameters.get("age_18_29_on_b_tt_ped");
		this.age_18_29_on_b_tt_put = logitParameters.get("age_18_29_on_b_tt_put");
		this.age_50_59_on_b_tt_bike = logitParameters.get("age_50_59_on_b_tt_bike");
		this.age_50_59_on_b_tt_car_d = logitParameters.get("age_50_59_on_b_tt_car_d");
		this.age_50_59_on_b_tt_car_p = logitParameters.get("age_50_59_on_b_tt_car_p");
		this.age_50_59_on_b_tt_ped = logitParameters.get("age_50_59_on_b_tt_ped");
		this.age_50_59_on_b_tt_put = logitParameters.get("age_50_59_on_b_tt_put");
		this.age_60_69_on_b_tt_bike = logitParameters.get("age_60_69_on_b_tt_bike");
		this.age_60_69_on_b_tt_car_d = logitParameters.get("age_60_69_on_b_tt_car_d");
		this.age_60_69_on_b_tt_car_p = logitParameters.get("age_60_69_on_b_tt_car_p");
		this.age_60_69_on_b_tt_ped = logitParameters.get("age_60_69_on_b_tt_ped");
		this.age_60_69_on_b_tt_put = logitParameters.get("age_60_69_on_b_tt_put");
		this.age_70_100_on_b_tt_bike = logitParameters.get("age_70_100_on_b_tt_bike");
		this.age_70_100_on_b_tt_car_d = logitParameters.get("age_70_100_on_b_tt_car_d");
		this.age_70_100_on_b_tt_car_p = logitParameters.get("age_70_100_on_b_tt_car_p");
		this.age_70_100_on_b_tt_ped = logitParameters.get("age_70_100_on_b_tt_ped");
		this.age_70_100_on_b_tt_put = logitParameters.get("age_70_100_on_b_tt_put");
		this.b_tt_bike_mu = logitParameters.get("b_tt_bike_mu");
		this.b_tt_bike_sig = logitParameters.get("b_tt_bike_sig");
		this.b_tt_bs = logitParameters.get("b_tt_bs");
		this.b_tt_car_d_mu = logitParameters.get("b_tt_car_d_mu");
		this.b_tt_car_d_sig = logitParameters.get("b_tt_car_d_sig");
		this.b_tt_car_p_mu = logitParameters.get("b_tt_car_p_mu");
		this.b_tt_car_p_sig = logitParameters.get("b_tt_car_p_sig");
		this.b_tt_cs = logitParameters.get("b_tt_cs");
		this.b_tt_ped = logitParameters.get("b_tt_ped");
		this.b_tt_put_mu = logitParameters.get("b_tt_put_mu");
		this.b_tt_put_sig = logitParameters.get("b_tt_put_sig");
		this.b_tt_rp = logitParameters.get("b_tt_rp");
		this.b_tt_taxi = logitParameters.get("b_tt_taxi");
		this.beruft_on_b_tt_bike = logitParameters.get("beruft_on_b_tt_bike");
		this.beruft_on_b_tt_car_d = logitParameters.get("beruft_on_b_tt_car_d");
		this.beruft_on_b_tt_car_p = logitParameters.get("beruft_on_b_tt_car_p");
		this.beruft_on_b_tt_ped = logitParameters.get("beruft_on_b_tt_ped");
		this.beruft_on_b_tt_put = logitParameters.get("beruft_on_b_tt_put");
		

	}
	
	public TimeSensitivityUtilityFunction(LogitParameters logitParameters, TimeSensitivityHelper helper) {
		this(logitParameters, helper, new NullPersonChangerFunctionLogger());
	}
	
	
	public double calculateU_tt_ped(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Pedestrian";
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		
		
		return   (b_tt_ped)
				+(age_0_17_on_b_tt_ped*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_b_tt_ped*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_b_tt_ped*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_b_tt_ped*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_b_tt_ped*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_b_tt_ped*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_tt_bike(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Bike";
		
		double DRAW_TIME_BIKE_value = helper.getDRAW_TIME_BIKE(category, person, draws);
		this.log.log(person, category, "DRAW_TIME_BIKE_value", DRAW_TIME_BIKE_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		
		
		return   (b_tt_bike_mu)
				+(b_tt_bike_sig*DRAW_TIME_BIKE_value)
				+(age_0_17_on_b_tt_bike*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_b_tt_bike*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_b_tt_bike*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_b_tt_bike*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_b_tt_bike*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_b_tt_bike*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_tt_car_p(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Passenger";
		
		double DRAW_TIME_PASSENGER_value = helper.getDRAW_TIME_PASSENGER(category, person, draws);
		this.log.log(person, category, "DRAW_TIME_PASSENGER_value", DRAW_TIME_PASSENGER_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		
		
		return   (b_tt_car_p_mu)
				+(b_tt_car_p_sig*DRAW_TIME_PASSENGER_value)
				+(age_0_17_on_b_tt_car_p*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_b_tt_car_p*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_b_tt_car_p*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_b_tt_car_p*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_b_tt_car_p*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_b_tt_car_p*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_tt_car_d(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Car";
		
		double DRAW_TIME_CAR_value = helper.getDRAW_TIME_CAR(category, person, draws);
		this.log.log(person, category, "DRAW_TIME_CAR_value", DRAW_TIME_CAR_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		
		
		return   (b_tt_car_d_mu)
				+(b_tt_car_d_sig*DRAW_TIME_CAR_value)
				+(age_0_17_on_b_tt_car_d*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_b_tt_car_d*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_b_tt_car_d*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_b_tt_car_d*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_b_tt_car_d*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_b_tt_car_d*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_tt_put(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "PublicTransport";
		
		double DRAW_TIME_PUBLICTRANSPORT_value = helper.getDRAW_TIME_PUBLICTRANSPORT(category, person, draws);
		this.log.log(person, category, "DRAW_TIME_PUBLICTRANSPORT_value", DRAW_TIME_PUBLICTRANSPORT_value);
		
		double AGE_value = helper.getAGE(category, person, draws);
		this.log.log(person, category, "AGE_value", AGE_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person, draws);
		this.log.log(person, category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		
		
		return   (b_tt_put_mu)
				+(b_tt_put_sig*DRAW_TIME_PUBLICTRANSPORT_value)
				+(age_0_17_on_b_tt_put*((0.0d <= AGE_value && AGE_value <= 17.0d) ? 1.0d : 0.0d))
				+(age_18_29_on_b_tt_put*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(age_50_59_on_b_tt_put*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(age_60_69_on_b_tt_put*((60.0d <= AGE_value && AGE_value <= 69.0d) ? 1.0d : 0.0d))
				+(age_70_100_on_b_tt_put*((70.0d <= AGE_value && AGE_value <= 100.0d) ? 1.0d : 0.0d))
				+(beruft_on_b_tt_put*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_tt_taxi(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Taxi";
		
		
		
		return   (b_tt_taxi);
	}
	public double calculateU_tt_cs_ff(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Carsharing_FreeFloating";
		
		
		
		return   (b_tt_cs);
	}
	public double calculateU_tt_cs_sb(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "Carsharing_Station";
		
		
		
		return   (b_tt_cs);
	}
	public double calculateU_tt_rp(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "RidePooling";
		
		
		
		return   (b_tt_rp);
	}
	public double calculateU_tt_bs(PersonBuilder person, Map<Mode, Double> draws) {
		String category = "BikeSharing";
		
		
		
		return   (b_tt_bs);
	}
}

