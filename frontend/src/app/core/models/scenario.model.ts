export interface Region {
  minLat: number;
  minLon: number;
  maxLat: number;
  maxLon: number;
}

export interface Depot {
  name: string;
  lat: number;
  lon: number;
  employees: number;
  share: number;
  vehicleType: 'TRUCK' | 'CARGO_BIKE';
}

export interface DeliveryService {
  name: string;
  share: number;
}

export interface ReturnDemand {
  /** Experimenteller MVP; standardmäßig aus. */
  enabled: boolean;
  /** Exogene Zusatznachfrage ohne Verknüpfung zu einem Ursprungspaket. */
  model: 'exogenous-share';
  /** Zusätzliche Pickups relativ zu den privaten Zustellaufträgen. */
  shareOfPrivateOrders: number;
  pickupShares: {
    home: number;
    work: number;
  };
}

export interface RegionEstimate {
  estimatedPopulation: number;
  source: 'zensus-2022' | 'osm-places' | 'none' | 'unavailable';
  sourceLabel: string;
  referenceDate: string;
  sourceUrl: string;
  places: { name: string; type: string; population: number }[];
}

export interface Scenario {
  id?: string;
  name: string;
  createdAt?: string;
  /** Null, solange noch keine Region auf der Karte gesetzt wurde. */
  region: Region | null;
  zoning: {
    cellSizeMeters: number;
    maxZones: number;
    mode: 'grid' | 'epsg3035' | 'inspire';
  };
  /** Nachfragesimulation/Weiterverarbeitung: logitopp | sumo | mobilecity; SUMO impliziert logiTopp. */
  targetModels: string[];
  referenceYear: number;
  mobileCity: { useTemplate: boolean; templateId: string; useRegionalFleet?: boolean; electricShare?: number | null; useRegionalCharging?: boolean; chargingPointsPer1000?: number | null; carsharingVehiclesPer1000?: number | null; carsharingMemberShare?: number | null; autoSurrounding?: boolean; surroundingBufferKm?: number; surroundingPopulation?: number | null; surroundingEducationTripShare?: number | null; surroundingWorkTripShare?: number | null; surroundingShoppingTripShare?: number | null; surroundingLeisureTripShare?: number | null; regionalExport?: boolean; validateNative?: boolean; maxDistricts?: number; allowSyntheticTransitFallback?: boolean; transitVehiclesPer1000?: number; settlementAreaShare?: number };
  population: {
    totalPersons: number | null;
    personsPerKm2: number;
    samplingFraction: number;
    seed: number;
    /** @deprecated Kompatibilitätsfeld; POI-/Siedlungsdaten werden serverseitig automatisch gewählt. */
    useOsmData: boolean;
    useRealRouting: boolean;
    externalTrips: boolean;
    /** Achse A – Verhaltensquelle: actitopp | gta | template. */
    behaviorSource: string;
    /** GTA 3d: Was-wäre-wenn-Kontext (Policy/Technologie, Freitext) für die LLM-Agenten. */
    gtaWhatIf: string;
    /** Netz-Feedback: SUMO-Stauzeiten → Pkw-Matrix → erneute logiTopp-Entscheidung. */
    sumoFeedback: boolean;
    /** Optional hochgeladener GTFS-Feed (Pfad); Fallback zum Auto-Fetch. */
    gtfsUploadPath?: string;
    useKitPopulation: boolean;
    /** Regionale Alters-/Haushaltsanteile aus Zensus 2022; experimentell. */
    useCensusStructure?: boolean;
    calibrateModeChoice: boolean;
    /** Distanz-Kalibrierung der Zielwahl (b_intrazonal → Ziel-<1km-Anteil), experimentell. */
    calibrateDistance: boolean;
    /** Bestätigte Korrektur des Zielwahl-Distanz-Abklingens (b_tt_* +0,06), experimentell. */
    correctDistanceDecay: boolean;
    /** Zwei-Hebel-Kalibrierung: ÖV-Niveau (asc_put) + Fernweg-Schräge (b_tt_put), experimentell. */
    calibrateModeDistance: boolean;
    /** Ziel-Modal-Split der Kalibrierung (Modus → Anteil), Default MiD. */
    calibrationTarget: { [mode: string]: number };
    /** GTA 3c: zusätzliche LLM-Moduswahl auf den simulierten Trips (Vergleich zu MNL/MiD). */
    llmModeChoice: boolean;
    carsPerHousehold: number;
  };
  logistics: {
    parcelsPerPersonWeek: number;
    parcelStdDev: number;
    maxParcelsPerPerson: number;
    shareHome: number;
    shareWork: number;
    sharePackstation: number;
    returns: ReturnDemand;
    depots: Depot[];
    services: DeliveryService[];
  };
}

export interface StageInfo {
  name: string;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED';
  durationMs: number;
  message?: string;
}

export interface GenerationJob {
  scenarioId: string;
  state: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED';
  currentStage?: string;
  stages: StageInfo[];
  log: string[];
  summary?: Record<string, unknown>;
  error?: string;
}

export interface ArtifactInfo {
  path: string;
  sizeBytes: number;
}

export interface UploadSummary {
  name: string;
  format: string;
  points: number;
  totalPopulation: number;
  skippedLines: number;
}

export interface UploadInfo {
  name: string;
  sizeBytes: number;
}

export interface RoadNetworkUploadSummary {
  name: string;
  nodes: number;
  ways: number;
  highwayWays: number;
  minLat: number;
  minLon: number;
  maxLat: number;
  maxLon: number;
  sha256: string;
}

export interface OsmDataUploadSummary {
  name: string;
  elements: number;
  pois: number;
  places: number;
  sha256: string;
}

export interface CommuterUploadSummary {
  name: string;
  relations: number;
  totalCommuters: number;
  relationsWithHomeInRegion: number;
  source: string;
  exportTotalCommuters: number;
  coverage: number;
  skippedAggregateRows: number;
  unresolvedMunicipalities: number;
  workplaceMargin: WorkplaceMarginUploadSummary | null;
}

export interface WorkplaceMarginUploadSummary {
  name: string;
  source: string;
  date: string;
  municipalities: number;
  residentWorkers: number;
  outboundWorkers: number;
  externalShare: number;
  unresolvedMunicipalities: number;
}
