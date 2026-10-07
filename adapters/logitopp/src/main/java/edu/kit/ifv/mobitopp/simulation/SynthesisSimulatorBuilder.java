/* SyntheSis adapter assembly, derived from KIT mobitopp-rastatt SimulatorBuilder.
 * KIT upstream sources are compiled separately and are never patched.
 * Original project: https://github.com/kit-ifv/mobitopp-rastatt
 * This class has a distinct name and does not replace the upstream class.
 */
package edu.kit.ifv.mobitopp.simulation;

import static edu.kit.ifv.mobitopp.time.DayOfWeek.FRIDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.MONDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.SATURDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.SUNDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.THURSDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.TUESDAY;
import static edu.kit.ifv.mobitopp.time.DayOfWeek.WEDNESDAY;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import edu.kit.ifv.mobitopp.concurrent.ParallelExecutor;
import edu.kit.ifv.mobitopp.concurrent.WaitingExecutor;
import edu.kit.ifv.mobitopp.data.ZoneId;
import edu.kit.ifv.mobitopp.data.ZoneRepository;
import edu.kit.ifv.mobitopp.simulation.activityschedule.ActivityPeriodFixer;
import edu.kit.ifv.mobitopp.simulation.activityschedule.LeisureWalkActivityPeriodFixer;
import edu.kit.ifv.mobitopp.simulation.activityschedule.randomizer.DefaultActivityDurationRandomizer;
import edu.kit.ifv.mobitopp.simulation.destinationChoice.DestinationChoiceModel;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceHelperImpl;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceLogger;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.DestinationChoiceModelLoader;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.DestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.simulation.destinationchoice.generated.NullDestinationChoiceUtilityModelLogger;
import edu.kit.ifv.mobitopp.simulation.modeChoice.ModeAvailabilityModel;
import edu.kit.ifv.mobitopp.simulation.modeavailability.ModeAvailabilityInDestinationChoice;
import edu.kit.ifv.mobitopp.simulation.modeavailability.ModeAvailabilityModelConsideringBikeSharing;
import edu.kit.ifv.mobitopp.simulation.modechoice.ModeChoiceHelperForDestinationChoice;
import edu.kit.ifv.mobitopp.simulation.modechoice.ModeChoiceLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.RastattModeResolver;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.ModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.simulation.modechoice.gen.NullModeChoiceModelLogger;
import edu.kit.ifv.mobitopp.simulation.person.PersonStateSimple;
import edu.kit.ifv.mobitopp.simulation.person.TripFactory;
import edu.kit.ifv.mobitopp.simulation.tour.TourBasedModeChoiceModel;
import edu.kit.ifv.mobitopp.util.parameter.LogitParameters;
import edu.kit.ifv.mobitopp.util.parameter.ParameterFormularParser;
import edu.kit.ifv.mobitopp.visum.export.VisumDmdExportShortTerm;
import lombok.Getter;

public class SynthesisSimulatorBuilder {

	private static final int SINGLE_THREAD = 1;

	private static final Set<Mode> CHOICE_SET = Set
		.of(StandardMode.CAR, //
			StandardMode.PASSENGER, //
			StandardMode.BIKE, //
			StandardMode.PEDESTRIAN, //
			StandardMode.PUBLICTRANSPORT, //
			StandardMode.CARSHARING_STATION, //
			StandardMode.CARSHARING_FREE //
		);

	private final SimulationContext context;
	private ModeAvailabilityModel modeAvailabilityModel;
	@Getter private VisumDmdExportShortTerm export;

	public SynthesisSimulatorBuilder(SimulationContext context) {
		this.context = context;
		
		try {
			export = new VisumDmdExportShortTerm(context);
			export.init();
			context.personResults().addListener(export);
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private SimulationContext context() {
		return context;
	}

	private ImpedanceIfc impedance() {
		return context.impedance();
	}

	private ZoneRepository zoneRepository() {
		return context.zoneRepository();
	}

	public DemandSimulator build() {
		modeAvailabilityModel = createModeAvailabilityModel();
		DestinationChoiceModel targetSelector = createDestinationChoiceModel();
		TourBasedModeChoiceModel modeSelector = createModeChoiceModel();
		ZoneBasedRouteChoice routeChoice = createRouteChoice();
		ActivityPeriodFixer fixer = new LeisureWalkActivityPeriodFixer();
		DefaultActivityDurationRandomizer randomizer = createDurationRandomizer();
		TripFactory tripFactory = createTripFactory();
		ReschedulingStrategy rescheduling = createReschedulingStrategy();

		System.out.println("Initializing simulator...");
		DemandSimulatorPassenger simulator = new DemandSimulatorPassenger(targetSelector,
				modeSelector, routeChoice, fixer, randomizer, tripFactory, rescheduling, CHOICE_SET,
				PersonStateSimple.UNINITIALIZED, context());

		createMatrixWriters(simulator);
	
		return simulator;
	}


	private ModeAvailabilityModel createModeAvailabilityModel() {
		return new ModeAvailabilityModelConsideringBikeSharing(impedance());
	}

	private DestinationChoiceModel createDestinationChoiceModel() {
		LogitParameters baseParameters = new ParameterFormularParser()
				.parseToParameter(context().destinationChoiceParameters().valueAsFile("base"));

		ModeAvailabilityModel modeAvailabilityDestination = new ModeAvailabilityInDestinationChoice();
		ModeChoiceHelperForDestinationChoice helper = newModeChoiceModelLoader()
				.loadModeChoiceHelper(modeAvailabilityDestination, impedance());
		DestinationChoiceHelperImpl destinationHelper = new DestinationChoiceHelperImpl(impedance(),
				modeAvailabilityDestination, CHOICE_SET, helper);

		DestinationChoiceUtilityModelLogger logger = destinationChoiceLogger();

		return new DestinationChoiceModelLoader(baseParameters, destinationHelper, logger,
				executor()).loadFrom(context());
	}

	private DestinationChoiceUtilityModelLogger destinationChoiceLogger() {
		DestinationChoiceUtilityModelLogger logger = new NullDestinationChoiceUtilityModelLogger();
		if (isParameterSet("logDestinationChoice")) {
			int n = 1;
			if (context().experimentalParameters().hasValue("logDestinationChoiceN")) {
				n = context().experimentalParameters().valueAsInteger("logDestinationChoiceN");
			}
			logger = new DestinationChoiceLogger(n);
		}
		return logger;
	}

	private WaitingExecutor executor() {
		return new ParallelExecutor(numberOfThreads());
	}

	private int numberOfThreads() {
		if (context.experimentalParameters().hasValue("threads")) {
			return context.experimentalParameters().valueAsInteger("threads");
		}
		return SINGLE_THREAD;
	}

	private TourBasedModeChoiceModel createModeChoiceModel() {
		return newModeChoiceModelLoader().loadModel();
	}

	private NoRouteChoice createRouteChoice() {
		return new NoRouteChoice();
	}

	private DefaultActivityDurationRandomizer createDurationRandomizer() {
		return new DefaultActivityDurationRandomizer(context().seed());
	}

	private TripFactory createTripFactory() {
		return newModeChoiceModelLoader().createTripFactory();
	}

	private ReschedulingSkipTillHome createReschedulingStrategy() {
		return new ReschedulingSkipTillHome(context().simulationDays());
	}

	private ModeChoiceModelLoader newModeChoiceModelLoader() {
		ModeChoiceModelLogger loggerMain = new NullModeChoiceModelLogger();
		if (isParameterSet("logModeChoice")) {
			int n = 1;
			if (context().experimentalParameters().hasValue("logModeChoiceN")) {
				n = context().experimentalParameters().valueAsInteger("logModeChoiceN");
			}
			loggerMain = new ModeChoiceLogger(n, context().results(), new RastattModeResolver());
		}

		return new ModeChoiceModelLoader(modeAvailabilityModel, context(),
				impedance(), loggerMain);
	}


	private void createMatrixWriters(DemandSimulatorPassenger simulator) {
		if (isParameterSet("writeMatrices")) {
			List<ZoneId> zoneIds = context.dataRepository().zoneRepository().getZoneIds();


			ActiveListenersManagerBuilder builder = new ActiveListenersManagerBuilder();

			if (isParameterSet("dayMatrices")) {
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(MONDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(TUESDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(WEDNESDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(THURSDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(FRIDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(SATURDAY), context, 0, 24, 24);
				builder.addTimeSlicesBetween(zoneIds, CHOICE_SET, Set.of(SUNDAY), context, 0, 24, 24);

			} else if (isParameterSet("hourMatrices")) {
				builder
						.addHourlyWeekdayListeners(zoneIds, matrixModes(), context)
						.addHourlyDayListeners(zoneIds, matrixModes(), SATURDAY, context)
						.addHourlyDayListeners(zoneIds, matrixModes(), SUNDAY, context);
			}

			builder.logPlannedSchedule()
					.performActionsAtEnd()
					.useThreads(context.configuration().getThreadCount())
					.register(simulator)
					.build();
		}
	}

	private Set<Mode> matrixModes() {
		return Set
				.of(StandardMode.BIKE, //
						StandardMode.BIKESHARING, //
						StandardMode.CAR, //
						StandardMode.CARSHARING_STATION, //
						StandardMode.CARSHARING_FREE, //
						StandardMode.PUBLICTRANSPORT, //
						StandardMode.RIDE_POOLING, //
						StandardMode.TAXI //
				);
	}

	private boolean isParameterSet(String parameter) {
		return context.experimentalParameters().hasValue(parameter)
				&& context.experimentalParameters().valueAsBoolean(parameter);
	}

	/**
	 * SyntheSis-Validierung: baut den logiTopp-Simulator (DemandSimulatorDelivery)
	 * mit dem Default-Paketnachfragemodell. Die Verteilzentren kommen aus der CSV
	 * des experimental-Parameters "distributionCenters" (DistributionCenterParser,
	 * Marktanteile optional aus "cepspShares"); ohne den Parameter wird wie bisher
	 * ein einzelnes Depot programmatisch angelegt. Minimal-Setup ohne Business-Pakete.
	 */
	public DemandSimulator buildDelivery() {
		modeAvailabilityModel = createModeAvailabilityModel();
		DestinationChoiceModel targetSelector = createDestinationChoiceModel();
		TourBasedModeChoiceModel modeSelector = createModeChoiceModel();
		ZoneBasedRouteChoice routeChoice = createRouteChoice();
		ActivityPeriodFixer fixer = new LeisureWalkActivityPeriodFixer();
		DefaultActivityDurationRandomizer randomizer = createDurationRandomizer();
		TripFactory tripFactory = createTripFactory();
		ReschedulingStrategy rescheduling = createReschedulingStrategy();

		edu.kit.ifv.mobitopp.simulation.DeliveryResults deliveryResults =
				new edu.kit.ifv.mobitopp.simulation.DeliveryResults(context().results());

		java.util.List<edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenter> depots;
		java.util.List<edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider> providers;
		java.util.Map<edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider, Double> shares;

		if (context().experimentalParameters().hasValue("distributionCenters")) {
			// Depots aus dem Szenario (SyntheSis-Pipeline, legacy/logistics/): der Parser
			// referenziert Zonen über die externe Id und legt je cepsp-Spalte die
			// CEPServiceProvider an
			edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenterParser parser =
					new edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenterParser(
							zoneRepository(), context().fractionOfPopulation(), impedance(),
							deliveryResults, new edu.kit.ifv.mobitopp.util.location.DummyLocationProvider(),
							type -> type == edu.kit.ifv.mobitopp.simulation.distribution.fleet.VehicleType.BIKE
									? 40 : 160);
			depots = new java.util.ArrayList<>(parser.parse(
					context().experimentalParameters().valueAsFile("distributionCenters")));
			providers = new java.util.ArrayList<>(parser.getServiceProviders());

			java.util.Map<String, Double> sharesByName = new java.util.HashMap<>();
			if (context().experimentalParameters().hasValue("cepspShares")) {
				edu.kit.ifv.mobitopp.util.dataimport.CsvFile
						.createFrom(context().experimentalParameters().valueAsFile("cepspShares"))
						.stream()
						.forEach(row -> sharesByName.put(row.get("name"), row.valueAsDouble("share")));
			}
			shares = new java.util.LinkedHashMap<>();
			for (edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider provider : providers) {
				shares.put(provider, sharesByName.getOrDefault(provider.getName(), 1.0 / providers.size()));
			}

		} else {
            throw new IllegalArgumentException("Szenario-Depots fehlen: distributionCenters muss explizit konfiguriert sein");
        }
        if (depots.isEmpty()) {
            throw new IllegalArgumentException("Szenario enthält keine Zustelldepots");
        }

		// DepotOperations (Tourplanung TSP, Dummy-Zustellpolitik, Dispatch ohne Fahrplan)
		// registriert sich im Konstruktor selbst am DistributionCenter
		for (edu.kit.ifv.mobitopp.simulation.distribution.DistributionCenter depot : depots) {
					new edu.kit.ifv.mobitopp.simulation.distribution.DepotOperations(
					new edu.kit.ifv.mobitopp.simulation.distribution.tours.planning.TspBasedDeliveryTourStrategy(
							impedance(),
							new SynthesisParcelLocationClustering(),
							new edu.kit.ifv.mobitopp.simulation.distribution.tours.DummyDurationModel()),
					new edu.kit.ifv.mobitopp.simulation.distribution.policies.ParcelPolicyProvider(),
					depot,
					new edu.kit.ifv.mobitopp.simulation.distribution.dispatch.DispatchTimeStrategy(
							new edu.kit.ifv.mobitopp.simulation.distribution.timetable.TimeTable(java.util.List.of())),
					deliveryResults, impedance());
		}

		edu.kit.ifv.mobitopp.simulation.business.partners.BusinessPartnerSelector noopSelector =
				new edu.kit.ifv.mobitopp.simulation.business.partners.BusinessPartnerSelector() {
					@Override
					public java.util.Collection<edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider> select(
							edu.kit.ifv.mobitopp.simulation.business.Business business) {
						return providers;
					}

					@Override
					public void printStatistics() {
					}

					@Override
					public java.util.LinkedHashMap<edu.kit.ifv.mobitopp.simulation.distribution.CEPServiceProvider, Double> computeCurrentWeights() {
						return new java.util.LinkedHashMap<>();
					}
				};

		if (!context().experimentalParameters().hasValue("parcelDemandConfig")
				|| !context().experimentalParameters().hasValue("harnessContract")) {
			throw new IllegalArgumentException(
					"SyntheSis parcelDemandConfig/harnessContract missing");
		}
		SynthesisParcelDemandConfig parcelConfig;
		try {
			parcelConfig = SynthesisParcelDemandConfig.load(
					context().experimentalParameters().valueAsFile("parcelDemandConfig"),
					context().experimentalParameters().value("harnessContract"));
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Cannot read SyntheSis parcel demand config", e);
		}
		edu.kit.ifv.mobitopp.simulation.demand.ParcelDemandModel<
				edu.kit.ifv.mobitopp.simulation.person.PickUpParcelPerson,
				edu.kit.ifv.mobitopp.simulation.parcels.PrivateParcelBuilder> privateParcelModel =
				SynthesisParcelDemandModels.build(parcelConfig, shares, deliveryResults);

		System.out.println("Initializing logiTopp delivery simulator...");
		DemandSimulatorDelivery simulator = new DemandSimulatorDelivery(targetSelector, modeSelector,
				routeChoice, fixer, randomizer, tripFactory, rescheduling, CHOICE_SET,
				PersonStateSimple.UNINITIALIZED, context(),
				edu.kit.ifv.mobitopp.simulation.person.SimulationPersonPassenger::new,
				privateParcelModel,
				edu.kit.ifv.mobitopp.simulation.demand.BusinessParcelDemandModelBuilder
						.nullBusinessParcelOrderModel(deliveryResults),
				edu.kit.ifv.mobitopp.simulation.demand.BusinessParcelDemandModelBuilder
						.nullBusinessParcelOrderModel(deliveryResults),
				deliveryResults, p -> true, b -> false, b -> false, java.util.List.of(),
				noopSelector, noopSelector, depots);

		createMatrixWriters(simulator);
		return simulator;
	}

}
