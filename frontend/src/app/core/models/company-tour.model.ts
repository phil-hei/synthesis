export interface TourMapping {
  tableId: string;
  headerRow: number;
  columns: Record<string, number>;
  dateFormat: string;
  timezone: string;
  groupStops: boolean;
  fillDownFields?: string[];
  joins?: Record<string, { columns: number[]; separator: string }>;
  timeFormat?: string;
}
export interface TourTableProfile {
  id: string;
  name: string;
  headerRow: number;
  dataRows: number;
  columns: { index: number; header: string; types: Record<string, number> }[];
}
export interface CompanyTourDraft {
  schemaVersion: string;
  sourceHash: string;
  mappingHash: string;
  status: 'invalid' | 'needs-context';
  simulationReady: boolean;
  inputRows: number;
  mappedRows: number;
  rejectedRows: number;
  blankRows: number;
  unmappedColumns: number[];
  issues: { code: string; sourceRow: number; field: string }[];
  tours: {
    tourId: string;
    sourceTourCode?: string;
    serviceDate: string;
    stops: { stopId?: string; sequence: number; location: { street: string; postalCode: string; city: string };
      plannedArrival: string; plannedDeparture: string;
      plannedDwellSec: number; sourceRows: number[];
      orders: { sourceRow: number; transactionId: string; orderId: string; windowStart: string; windowEnd: string }[] }[];
  }[];
}
export interface CompanyTourImport {
  importId: string;
  tables: TourTableProfile[];
  mapping: TourMapping;
  draft: CompanyTourDraft;
  mappingConfirmed: boolean;
  llmEnabled: boolean;
  sourceAnalysisEnabled?: boolean;
  source?: { contract: string; sourceHash: string; format: string;
    blocks: { id: number; reference: string; text: string }[];
    evidence: { tableId: string; row: number; reference: string }[] } | null;
  analysis?: { id: string; evidence: { row: number; fields: Record<string,{block:number;start:number;end:number}> }[];
    ignoredBlocks: { block: number; reason: string }[] } | null;
  context?: CompanyTourContext | null;
  execution?: { ready: boolean; networkVerified: boolean; executionHash: string; selectedTours: number; legs: number;
    problems: { code: string; tourId: string; stopId: string; message: string }[] };
  geocoding?: { enabled: boolean; endpoint: string; maxBatch: number; message: string };
  preflight?: { region: { minLon: number; minLat: number; maxLon: number; maxLat: number } | null;
    selectedTours: number; missingVehicles: number; missingDepotTimes: number; locatedPoints: number;
    externalPoints: number; unlocatedPoints: number; pendingAddresses: number; networkCoverageVerified: boolean };
  active?: boolean;
  vehicles?: { vehicleId: string; vehicleClass: string; typeKey: string }[];
  selection?: { enabled: boolean; imports: { importId: string; executionHash: string }[] };
}
export interface CompanyTourPoint { lon: number | null; lat: number | null; provenance: string; }
export interface CompanyTourContext {
  depotAddresses?: Record<string, { start: string; end: string }>;
  companyId: string;
  regionalProjectionConfirmed?: boolean;
  networkProjectionRequested?: boolean;
  simulationDate: string;
  dateShiftConfirmed: boolean;
  sequenceConfirmed: boolean;
  windowsConfirmed: boolean;
  locationsConfirmed: boolean;
  windowPolicy: 'report-only' | 'arrival' | 'service';
  tours: { tourId: string; included: boolean; vehicleId: string; startTime: string; returnTime: string;
    start: CompanyTourPoint; end: CompanyTourPoint; stopOrder: string[] }[];
  locations: Record<string, CompanyTourPoint>;
}
export interface CompanyTourLocationLookup {
  sourceHash: string; matched: number; ambiguous: number; missing: number;
  matches: { stopId: string; candidates: CompanyTourPoint[] }[];
}

export interface TourImportProfile {
  id: string; name: string; format: string; strategy: string; structure: string; mapping: TourMapping;
}

export interface CompanyTourGeocodingLookup {
  importId: string; endpoint: string; cachedQueries: number;
  answers: { targetId: string; query: string; candidates: { label: string; point: CompanyTourPoint; reference: string; attribution: string }[] }[];
}
