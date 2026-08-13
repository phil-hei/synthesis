package edu.kit.ifv.mobitopp.simulation.destinationchoice.generated;

import static edu.kit.ifv.mobitopp.util.collections.StreamUtils.toLinkedMap;

import edu.kit.ifv.mobitopp.data.Zone;
import edu.kit.ifv.mobitopp.simulation.Person;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityIfc;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceUtilityModel;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityFunction;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.NullDestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.time.Time;
import edu.kit.ifv.mobitopp.util.logit.DefaultLogitModel;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public class GeneratedDestinationChoiceModel implements DestinationChoiceUtilityModel {
	
	
	private final DestinationChoiceUtilityFunction utilities;
	private final DestinationChoiceHelper helper;
	private final DestinationChoiceUtilityModelLogger log;
	
	/**
	  * Instantiates a GeneratedDestinationChoiceModel with the given LogitParameters, DestinationChoiceHelper, DestinationChoiceUtilityModelLogger
	  */
	public GeneratedDestinationChoiceModel(LogitParameters logitParameters, DestinationChoiceHelper helper, DestinationChoiceUtilityModelLogger log) {
		super();
		this.helper = helper;
		this.log = log;
		this.utilities = new DestinationChoiceUtilityFunction(logitParameters, helper, log);
	}
	
	/**
	  * Convenience constuctor: Uses a NullDestinationChoiceUtilityModelLogger as logger
	  */
	public GeneratedDestinationChoiceModel(LogitParameters logitParameters, DestinationChoiceHelper helper) {
		this(logitParameters, helper, new NullDestinationChoiceUtilityModelLogger());
	}
	
	public Zone selectDestination(Person person, Zone origin, Zone destination, ActivityIfc previousActivity, ActivityIfc nextActivity, Time time, double randomNumber) {
		this.log.logStart(person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		
		Map<Zone, Double> utilMap =
			helper.getChoiceSet(person, origin, destination, previousActivity, nextActivity, time, randomNumber)
				  .stream()
				  .collect(toLinkedMap(Function.identity(), category -> (double) utilities.calculateU_destination(category, person, origin, destination, previousActivity, nextActivity, time, randomNumber)));
		
		//no probabilities available -> no logging this.log.logProbabilities(probabilities, person, origin, destination, previousActivity, nextActivity, time, randomNumber);
		
		return new DefaultLogitModel<Zone>().select(utilMap, helper.getRandom(person, origin, destination, previousActivity, nextActivity, time, randomNumber));
	}

}

