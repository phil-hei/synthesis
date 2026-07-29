/** Ergebnis-Artefakte der Pipeline (JSON-Formate aus artifacts/…). */

/** Modus → Anteil (0..1), Schlüssel: pedestrian/bike/car/passenger/publicTransport. */
export interface ModalSplit {
  [mode: string]: number;
}

/** Distanzband → Anteil (0..1), aggregiert aus MiD 2017 Tabelle A W12. */
export interface DistanceBands {
  under1Km: number;
  km1ToUnder2: number;
  km2ToUnder5: number;
  km5ToUnder10: number;
  km10ToUnder20: number;
  km20AndMore: number;
}

/** Kanonische Reihenfolge und UI-Bezeichnung der sechs Distanzbänder. */
export const DISTANCE_BAND_ROWS: ReadonlyArray<{ key: keyof DistanceBands; label: string }> = [
  { key: 'under1Km', label: '< 1 km' },
  { key: 'km1ToUnder2', label: '1–< 2 km' },
  { key: 'km2ToUnder5', label: '2–< 5 km' },
  { key: 'km5ToUnder10', label: '5–< 10 km' },
  { key: 'km10ToUnder20', label: '10–< 20 km' },
  { key: 'km20AndMore', label: '≥ 20 km' }
];

/** Bedingter Modal Split je Distanzband (MiD 2017 Tabelle A W10.5). */
export type ModalSplitByDistanceBand = Partial<Record<keyof DistanceBands, ModalSplit>>;

/** Richtungsgetrennte Paket-KPIs aus synthesis-logistics-kpis-v1. */
export interface LogisticsKpis {
  contract: 'synthesis-logistics-kpis-v1';
  deliveries: {
    generated: number;
    delivered: number;
    failedReturning: number;
    deliveryRate: number;
  };
  customerReturns: {
    generated: number;
    plannedPickups: number;
    collected: number;
    collectionRate: number;
  };
}

/** simulation/depot-stats.json – Flotte und richtungsgetrennte Logistik je Depot. */
export interface DepotStats {
  name: string;
  cepsp: string;
  vehicleType: string;
  vehicles: number;
  parcelsAssigned: number;
  deliveriesAssigned?: number;
  customerReturnsAssigned?: number;
  delivered: number;
  pickupsPlanned?: number;
  pickupsCollected?: number;
  failedReturning?: number;
  tours: number;
  distanceKm: number;
}

/** simulation/kpis.json – aggregierte logiTopp-Auswertung. */
export interface SimKpis {
  demand?: {
    trips?: number;
    tripsPerPersonDay?: number;
    avgTripKm?: number;
    distanceBands?: DistanceBands;
    distanceBandRmseToMid2017?: number | null;
    modalSplit?: ModalSplit;
    modalSplitByDistanceBand?: ModalSplitByDistanceBand;
    modeDistanceJointRmseToMid2017?: number | null;
  };
  mid2017?: {
    tripsPerPersonDay?: number;
    avgTripKm?: number;
    distanceBands?: DistanceBands;
    distanceBandsSource?: string;
    distanceBandsSourceUrl?: string;
    modalSplit?: ModalSplit;
    modalSplitByDistanceBand?: ModalSplitByDistanceBand;
    modeDistanceSource?: string;
    modeDistanceSourceUrl?: string;
  };
  parcels?: {
    total?: number;
    delivered?: number;
    deliveryRate?: number;
  };
  /** Seit synthesis-logistics-kpis-v1; bei älteren Läufen nicht vorhanden. */
  logistics?: LogisticsKpis;
  simulationSeconds?: number;
}

/** sumo/summary.json – Kennzahlen des SUMO-Laufs (vehicleTripStatistics). */
export interface SumoSummary {
  vehicles: number;
  meanDurationMin: number;
  meanRouteLengthKm: number;
  meanSpeedKmh: number;
  runtimeSeconds: number;
  /** Eingespeiste PKW-, Rad-, Fuß- bzw. Zustelltouren-Legs (ältere Läufe: nicht vorhanden). */
  carTrips?: number;
  bikeTrips?: number;
  walkTrips?: number;
  deliveryTrips?: number;
  /** ÖV (Phase C): mitfahrende Nachfrage-Personen und fahrplangebundene ÖV-Fahrzeuge (Bus). */
  ptRiders?: number;
  ptVehicles?: number;
  /** CO₂ in kg (HBEFA-Emissionsklassen), gesamt und je Fahrzeugtyp (pkw/lkw/lastenrad). */
  co2Kg?: number;
  co2KgByType?: { [vType: string]: number };
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
