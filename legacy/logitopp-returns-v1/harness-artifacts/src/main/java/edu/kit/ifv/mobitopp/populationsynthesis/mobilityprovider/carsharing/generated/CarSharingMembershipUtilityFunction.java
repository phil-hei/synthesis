package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.CarSharingMembershipHelper;
import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.MobilityProviderCustomerModelLogger;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;

public class CarSharingMembershipUtilityFunction {
	
	private final double asc_cs;
	private final double b_alter_18_29;
	private final double b_alter_30_39;
	private final double b_alter_40_49;
	private final double b_alter_50_59;
	private final double b_anzauto_1;
	private final double b_anzauto_2;
	private final double b_cs_cars;
	private final double b_female;
	private final double b_hhgro_1;
	private final double b_hhgro_2;
	private final double b_hhgro_3;
	private final double b_oek_2;
	private final double b_oek_3;
	private final double b_parken;
	private final double b_taetigkeit_1;
	private final double b_taetigkeit_2;
	private final double b_taetigkeit_3;
	private final double b_zeitkarte;
	private final double elasticity_parken;
	
	private final CarSharingMembershipHelper helper;
	private final MobilityProviderCustomerModelLogger log;
	
	public CarSharingMembershipUtilityFunction(LogitParameters logitParameters, CarSharingMembershipHelper helper, MobilityProviderCustomerModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		
		this.asc_cs = logitParameters.get("asc_cs");
		this.b_alter_18_29 = logitParameters.get("b_alter_18_29");
		this.b_alter_30_39 = logitParameters.get("b_alter_30_39");
		this.b_alter_40_49 = logitParameters.get("b_alter_40_49");
		this.b_alter_50_59 = logitParameters.get("b_alter_50_59");
		this.b_anzauto_1 = logitParameters.get("b_anzauto_1");
		this.b_anzauto_2 = logitParameters.get("b_anzauto_2");
		this.b_cs_cars = logitParameters.get("b_cs_cars");
		this.b_female = logitParameters.get("b_female");
		this.b_hhgro_1 = logitParameters.get("b_hhgro_1");
		this.b_hhgro_2 = logitParameters.get("b_hhgro_2");
		this.b_hhgro_3 = logitParameters.get("b_hhgro_3");
		this.b_oek_2 = logitParameters.get("b_oek_2");
		this.b_oek_3 = logitParameters.get("b_oek_3");
		this.b_parken = logitParameters.get("b_parken");
		this.b_taetigkeit_1 = logitParameters.get("b_taetigkeit_1");
		this.b_taetigkeit_2 = logitParameters.get("b_taetigkeit_2");
		this.b_taetigkeit_3 = logitParameters.get("b_taetigkeit_3");
		this.b_zeitkarte = logitParameters.get("b_zeitkarte");
		this.elasticity_parken = logitParameters.get("elasticity_parken");
		

	}
	
	public CarSharingMembershipUtilityFunction(LogitParameters logitParameters, CarSharingMembershipHelper helper) {
		this(logitParameters, helper, new NullMobilityProviderCustomerModelLogger());
	}
	
	
	public double calculateU_cs_member(PersonBuilder person) {
		String category = "Cs_member";
		
		double HOUSEHOLD_SIZE_value = helper.getHOUSEHOLD_SIZE(category, person);
		this.log.log(category, "HOUSEHOLD_SIZE_value", HOUSEHOLD_SIZE_value);
		
		double GENDER_value = helper.getGENDER(category, person);
		this.log.log(category, "GENDER_value", GENDER_value);
		
		double AGE_value = helper.getAGE(category, person);
		this.log.log(category, "AGE_value", AGE_value);
		
		double HOUSEHOLD_NUMBER_OF_OWNED_CARS_value = helper.getHOUSEHOLD_NUMBER_OF_OWNED_CARS(category, person);
		this.log.log(category, "HOUSEHOLD_NUMBER_OF_OWNED_CARS_value", HOUSEHOLD_NUMBER_OF_OWNED_CARS_value);
		
		double IS_EMPLOYMENT_FULLTIME_value = helper.getIS_EMPLOYMENT_FULLTIME(category, person);
		this.log.log(category, "IS_EMPLOYMENT_FULLTIME_value", IS_EMPLOYMENT_FULLTIME_value);
		
		double IS_EMPLOYMENT_PARTTIME_value = helper.getIS_EMPLOYMENT_PARTTIME(category, person);
		this.log.log(category, "IS_EMPLOYMENT_PARTTIME_value", IS_EMPLOYMENT_PARTTIME_value);
		
		double IS_EMPLOYMENT_MARGINAL_value = helper.getIS_EMPLOYMENT_MARGINAL(category, person);
		this.log.log(category, "IS_EMPLOYMENT_MARGINAL_value", IS_EMPLOYMENT_MARGINAL_value);
		
		double IS_EMPLOYMENT_STUDENT_value = helper.getIS_EMPLOYMENT_STUDENT(category, person);
		this.log.log(category, "IS_EMPLOYMENT_STUDENT_value", IS_EMPLOYMENT_STUDENT_value);
		
		double IS_EMPLOYMENT_STUDENT_PRIMARY_value = helper.getIS_EMPLOYMENT_STUDENT_PRIMARY(category, person);
		this.log.log(category, "IS_EMPLOYMENT_STUDENT_PRIMARY_value", IS_EMPLOYMENT_STUDENT_PRIMARY_value);
		
		double IS_EMPLOYMENT_STUDENT_SECONDARY_value = helper.getIS_EMPLOYMENT_STUDENT_SECONDARY(category, person);
		this.log.log(category, "IS_EMPLOYMENT_STUDENT_SECONDARY_value", IS_EMPLOYMENT_STUDENT_SECONDARY_value);
		
		double IS_EMPLOYMENT_STUDENT_TERTIARY_value = helper.getIS_EMPLOYMENT_STUDENT_TERTIARY(category, person);
		this.log.log(category, "IS_EMPLOYMENT_STUDENT_TERTIARY_value", IS_EMPLOYMENT_STUDENT_TERTIARY_value);
		
		double IS_EMPLOYMENT_EDUCATION_value = helper.getIS_EMPLOYMENT_EDUCATION(category, person);
		this.log.log(category, "IS_EMPLOYMENT_EDUCATION_value", IS_EMPLOYMENT_EDUCATION_value);
		
		double IS_EMPLOYMENT_HOMEKEEPER_value = helper.getIS_EMPLOYMENT_HOMEKEEPER(category, person);
		this.log.log(category, "IS_EMPLOYMENT_HOMEKEEPER_value", IS_EMPLOYMENT_HOMEKEEPER_value);
		
		double HAS_COMMUTER_TICKET_value = helper.getHAS_COMMUTER_TICKET(category, person);
		this.log.log(category, "HAS_COMMUTER_TICKET_value", HAS_COMMUTER_TICKET_value);
		
		double PARKEN_value = helper.getPARKEN(category, person);
		this.log.log(category, "PARKEN_value", PARKEN_value);
		
		double CS_DENSITY_value = helper.getCS_DENSITY(category, person);
		this.log.log(category, "CS_DENSITY_value", CS_DENSITY_value);
		
		double HOUSEHOLD_ECONOMICAL_STATUS_value = helper.getHOUSEHOLD_ECONOMICAL_STATUS(category, person);
		this.log.log(category, "HOUSEHOLD_ECONOMICAL_STATUS_value", HOUSEHOLD_ECONOMICAL_STATUS_value);
		
		
		
		return   (asc_cs)
				+(b_hhgro_1*((HOUSEHOLD_SIZE_value == 1.0d) ? 1.0d : 0.0d))
				+(b_hhgro_2*((HOUSEHOLD_SIZE_value == 2.0d) ? 1.0d : 0.0d))
				+(b_hhgro_3*((HOUSEHOLD_SIZE_value == 3.0d) ? 1.0d : 0.0d))
				+(b_female*((GENDER_value == 2.0d) ? 1.0d : 0.0d))
				+(b_alter_18_29*((18.0d <= AGE_value && AGE_value <= 29.0d) ? 1.0d : 0.0d))
				+(b_alter_30_39*((30.0d <= AGE_value && AGE_value <= 39.0d) ? 1.0d : 0.0d))
				+(b_alter_40_49*((40.0d <= AGE_value && AGE_value <= 49.0d) ? 1.0d : 0.0d))
				+(b_alter_50_59*((50.0d <= AGE_value && AGE_value <= 59.0d) ? 1.0d : 0.0d))
				+(b_anzauto_1*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value == 1.0d) ? 1.0d : 0.0d))
				+(b_anzauto_2*((HOUSEHOLD_NUMBER_OF_OWNED_CARS_value >= 2.0d) ? 1.0d : 0.0d))
				+(b_taetigkeit_1*(((IS_EMPLOYMENT_FULLTIME_value + IS_EMPLOYMENT_PARTTIME_value + IS_EMPLOYMENT_MARGINAL_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_taetigkeit_2*(((IS_EMPLOYMENT_STUDENT_value + IS_EMPLOYMENT_STUDENT_PRIMARY_value + IS_EMPLOYMENT_STUDENT_SECONDARY_value + IS_EMPLOYMENT_STUDENT_TERTIARY_value + IS_EMPLOYMENT_EDUCATION_value) > 0.0d) ? 1.0d : 0.0d))
				+(b_taetigkeit_3*IS_EMPLOYMENT_HOMEKEEPER_value)
				+(b_zeitkarte*HAS_COMMUTER_TICKET_value)
				+(b_parken*(Math.pow(PARKEN_value, elasticity_parken)))
				+(b_cs_cars*CS_DENSITY_value)
				+(b_oek_2*((HOUSEHOLD_ECONOMICAL_STATUS_value == 3.0d) ? 1.0d : 0.0d))
				+(b_oek_3*((HOUSEHOLD_ECONOMICAL_STATUS_value == 4.0d) ? 1.0d : 0.0d))
				+(b_oek_3*((HOUSEHOLD_ECONOMICAL_STATUS_value == 5.0d) ? 1.0d : 0.0d));
	}
	public double calculateU_no_cs_member(PersonBuilder person) {
		String category = "No_cs_member";
		
		
		
		return   (0.0d);
	}
}

