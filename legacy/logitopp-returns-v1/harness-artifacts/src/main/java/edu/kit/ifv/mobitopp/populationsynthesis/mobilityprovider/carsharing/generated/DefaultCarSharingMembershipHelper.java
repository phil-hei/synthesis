package edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated;

import edu.kit.ifv.mobitopp.populationsynthesis.mobilityprovider.carsharing.generated.CarSharingMembershipHelper;
import edu.kit.ifv.mobitopp.populationsynthesis.PersonBuilder;
import edu.kit.ifv.mobitopp.populationsynthesis.carownership.MobilityProviderCustomerModel;
import java.util.Collection;
import edu.kit.ifv.mobitopp.populationsynthesis.EconomicalStatus;
import edu.kit.ifv.mobitopp.simulation.Employment;
import edu.kit.ifv.mobitopp.simulation.Gender;
import edu.kit.ifv.mobitopp.simulation.Graduation;
import edu.kit.ifv.mobitopp.simulation.ImpedanceIfc;
import edu.kit.ifv.mobitopp.simulation.StandardMode;
import java.util.Optional;
import edu.kit.ifv.mobitopp.data.Value;
import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.ActivityType;

public abstract class DefaultCarSharingMembershipHelper implements CarSharingMembershipHelper {
	
	
	
	public double getAGE(String category, PersonBuilder person) {
		return person.age();
	}
	
	public double getGENDER(String category, PersonBuilder person) {
		return person.gender().getTypeAsInt();
	}
	
	public double getHAS_COMMUTER_TICKET(String category, PersonBuilder person) {
		return (person.hasCommuterTicket()) ? 1.0d : 0.0d;
	}
	
	public double getHOUSEHOLD_ECONOMICAL_STATUS(String category, PersonBuilder person) {
		return person.household().economicalStatus().getCode();
	}
	
	public double getHOUSEHOLD_NUMBER_OF_OWNED_CARS(String category, PersonBuilder person) {
		return person.household().getNumberOfOwnedCars();
	}
	
	public double getHOUSEHOLD_SIZE(String category, PersonBuilder person) {
		return person.household().getSize();
	}
	
	public double getIS_EMPLOYMENT_EDUCATION(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.EDUCATION)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_FULLTIME(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.FULLTIME)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_HOMEKEEPER(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.HOMEKEEPER)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_MARGINAL(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.MARGINAL)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_PARTTIME(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.PARTTIME)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.STUDENT)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_PRIMARY(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.STUDENT_PRIMARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_SECONDARY(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.STUDENT_SECONDARY)) ? 1.0 : 0.0;
	}
	
	public double getIS_EMPLOYMENT_STUDENT_TERTIARY(String category, PersonBuilder person) {
		return (person.employment().equals(Employment.STUDENT_TERTIARY)) ? 1.0 : 0.0;
	}
	

}

