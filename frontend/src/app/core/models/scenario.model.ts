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

export interface RegionEstimate {
  estimatedPopulation: number;
  source: 'osm-places' | 'none' | 'unavailable';
  places: { name: string; type: string; population: number }[];
}

export interface Scenario {
  id?: string;
  name: string;
  createdAt?: string;
  /** Null, solange noch keine Region auf der Karte gesetzt wurde. */
  region: Region | null;
  zoning: { cellSizeMeters: number; mode: string };
  /** Achse B – Zielmodelle (Mehrfachauswahl): logitopp | sumo | mobilecity. */
  targetModels: string[];
  population: {
    totalPersons: number | null;
    personsPerKm2: number;
    samplingFraction: number;
    seed: number;
    useOsmData: boolean;
    useRealRouting: boolean;
    externalTrips: boolean;
    /** Achse A – Verhaltensquelle: actitopp | gta | template. */
    behaviorSource: string;
    /** GTA 3d: Was-wäre-wenn-Kontext (Policy/Technologie, Freitext) für die LLM-Agenten. */
    gtaWhatIf: string;
    /** Netz-Feedback: SUMO-Stauzeiten → Pkw-Matrix → erneute logiTopp-Entscheidung. */
    sumoFeedback: boolean;
    /** ÖV in SUMO (Phase C): GTFS → gtfs2pt.py → Bus-Fahrplan, Nachfrage fährt intermodal mit. */
    publicTransport: boolean;
    /** Optional hochgeladener GTFS-Feed (Pfad); Fallback zum Auto-Fetch. */
    gtfsUploadPath?: string;
    useKitPopulation: boolean;
    calibrateModeChoice: boolean;
    /** Distanz-Kalibrierung der Zielwahl (b_intrazonal → Ziel-<1km-Anteil), experimentell. */
    calibrateDistance: boolean;
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
