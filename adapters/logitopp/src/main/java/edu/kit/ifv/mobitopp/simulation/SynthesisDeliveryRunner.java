package edu.kit.ifv.mobitopp.simulation;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

import edu.kit.ifv.mobitopp.Disclaimer;
import edu.kit.ifv.mobitopp.data.local.DynamicTypeMapping;
import edu.kit.ifv.mobitopp.data.local.configuration.TravelTimeMatrixType;
import edu.kit.ifv.mobitopp.populationsynthesis.DefaultMappings;
import edu.kit.ifv.mobitopp.util.dataimport.Bbsr17Repository;
import edu.kit.ifv.mobitopp.visum.StandardNetfileLanguages;

/**
 * SyntheSis-Validierung: Short-Term-Modul mit logiTopp-Pakets simulation
 * (DemandSimulatorDelivery statt DemandSimulatorPassenger).
 */
public class SynthesisDeliveryRunner extends Simulation {

	public SynthesisDeliveryRunner(SimulationContext context) {
		super(context);
	}

	@Override
	protected DemandSimulator simulator() {
		return new SynthesisSimulatorBuilder(context()).buildDelivery();
	}

	public static void main(String... args) throws IOException {
		if (1 > args.length) {
			System.out.println("Usage: ... <configuration file>");
			System.exit(-1);
		}
		Disclaimer.disclaim();

		File configurationFile = new File(args[0]);
		LocalDateTime start = LocalDateTime.now();
		PersonChangerFactory factory = new MixedPersonChangerFactory();
		SimulationContext context = new ContextBuilder(new Bbsr17Repository())
				.use(StandardNetfileLanguages::english)
				.use(typeMapping())
				.use(factory)
				.buildFrom(configurationFile);
		new SynthesisDeliveryRunner(context).simulate();
		System.out.println("logiTopp-Simulation took " + Duration.between(start, LocalDateTime.now()));
	}

	private static DynamicTypeMapping typeMapping() {
		DynamicTypeMapping mapping = DefaultMappings.autonomousModes();
		mapping.add(StandardMode.TAXI, TravelTimeMatrixType.car);
		// logiTopp-Zustellfahrzeuge nutzen die Pkw-Reisezeitmatrix
		mapping.add(StandardMode.TRUCK, TravelTimeMatrixType.car);
		mapping.add(StandardMode.BIKE, TravelTimeMatrixType.bike);
		return mapping;
	}
}
