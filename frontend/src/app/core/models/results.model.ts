/** Ergebnis-Artefakte der Pipeline (JSON-Formate aus artifacts/…). */

/** Modus → Anteil (0..1), Schlüssel: pedestrian/bike/car/passenger/publicTransport. */
export interface ModalSplit {
  [mode: string]: number;
}

/** simulation/kpis.json – aggregierte logiTopp-Auswertung. */
export interface SimKpis {
  demand?: {
    trips?: number;
    tripsPerPersonDay?: number;
    avgTripKm?: number;
    modalSplit?: ModalSplit;
  };
  mid2017?: {
    tripsPerPersonDay?: number;
    avgTripKm?: number;
    modalSplit?: ModalSplit;
  };
  parcels?: {
    total?: number;
    delivered?: number;
    deliveryRate?: number;
  };
  simulationSeconds?: number;
}

/** sumo/summary.json – Kennzahlen des SUMO-Laufs (vehicleTripStatistics). */
export interface SumoSummary {
  vehicles: number;
  meanDurationMin: number;
  meanRouteLengthKm: number;
  meanSpeedKmh: number;
  runtimeSeconds: number;
  /** Eingespeiste PKW-Trips bzw. Zustelltouren-Legs (ältere Läufe: nicht vorhanden). */
  carTrips?: number;
  deliveryTrips?: number;
}

/** population/gta-personas.json – GTA-Personas mit LLM-Begründungen (GTA 3d). */
export interface GtaPersonas {
  whatIf?: string;
  personas: {
    label: string;
    persons: number;
    reason?: string;
  }[];
}

/** simulation/llm-mode-choice.json – LLM-Moduswahl im Vergleich zu MNL und MiD. */
export interface LlmModeChoiceResult {
  trips: number;
  modalSplit: ModalSplit;
  mid2017: ModalSplit;
  logitoppMnl: ModalSplit;
  rmseToMid: number;
  rmseToMnl: number;
  bands?: {
    band: string;
    trips: number;
    meanKm: number;
    travelTimesMin: ModalSplit;
    modalSplit: ModalSplit;
  }[];
}
