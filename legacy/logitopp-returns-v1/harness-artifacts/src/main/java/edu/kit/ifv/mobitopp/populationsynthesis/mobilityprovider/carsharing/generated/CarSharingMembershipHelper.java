package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import java.util.Collection;

public interface CarSharingMembershipHelper {
	
	double getAGE(String category, PersonBuilder person);
	
	double getCS_DENSITY(String category, PersonBuilder person);
	
	double getGENDER(String category, PersonBuilder person);
	
	double getHAS_COMMUTER_TICKET(String category, PersonBuilder person);
	
	double getHOUSEHOLD_ECONOMICAL_STATUS(String category, PersonBuilder person);
	
	double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(String category, PersonBuilder person);
	
	double getHOUSEHOLD_SIZE(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_EDUCATION(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_FULLTIME(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_HOMEKEEPER(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_MARGINAL(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_PARTTIME(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_STUDENT(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_STUDENT_PRIMARY(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_STUDENT_SECONDARY(String category, PersonBuilder person);
	
	double getIS_EMPLOYMENT_STUDENT_TERTIARY(String category, PersonBuilder person);
	
	double getPARKEN(String category, PersonBuilder person);
	
	
	/**
	  * Returns a random double value. 
	  * This random value can be based on the given parameters as well as the implementing classes fields.
	  */
	double getRandom(PersonBuilder person);
	
	
	/**
	  * Converts the given category String to the return type boolean of MobilityProviderCustomerModel.estimateCustomership(...).
	  */
	boolean resolve(String category);
			
	Collection<String> getChoiceSet(PersonBuilder person);
}

