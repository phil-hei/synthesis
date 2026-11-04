export interface VehicleOperationsConfiguration {
  schemaVersion: 'synthesis-vehicle-operations-input-v1'; enabled: boolean; reserveFraction: number;
  bindings: { sourceTripKey: string; vehicleId: string }[];
  chainTrips?: boolean;
}
/** The backend validates the full existing catalogue contract; unknown model fields are retained. */
export interface VehicleConfigurationInput {
  schemaVersion: string; application: string;
  initialVehicles: { vehicleId: string; [key: string]: unknown }[];
  [key: string]: unknown;
}
export interface VehicleOperationsBundle {
  vehicleConfiguration: VehicleConfigurationInput;
  vehicleOperations: VehicleOperationsConfiguration;
}
export interface OperationsPreview extends VehicleOperationsBundle {
  valid: boolean; errors: { path: string; message: string }[];
  vehicleConfigurationSha256: string; operationsSha256: string;
}
export interface VehicleEnergyLedger {
  contract: string; status: 'pending' | 'tracked' | 'partially-tracked' | 'review-required';
  configurationSha256: string; vehicleConfigurationSha256: string;
  selectedSegments?: number; assignedSegments?: number; unassignedSegments?: number;
  knownEnergySegments?: number; issues?: number; bindingsNotSelected?: string[];
  vehicles?: { vehicleId: string; unit: string; balanceAfter: number | null; stateValid: boolean }[];
}
export interface EnergyNetworkReport {
  contract: string; status: 'started' | 'failed' | 'completed'; message?: string;
  region: { minLat: number; minLon: number; maxLat: number; maxLon: number }; referenceYear: number;
  configuration: { enabled: boolean; useBnetza: boolean; useOsm: boolean; verifyNetworkAccess: boolean; maxSnapDistanceM: number };
  vehicleConfigurationSha256: string; stationRecords?: number;
  profileChecks?: number; mappedProfileChecks?: number; blockedProfileChecks?: number; unmappedProfileChecks?: number;
}
export interface NativeVehicleChainReport {
  contract: string; status: 'planned' | 'routed' | 'failed' | 'complete' | 'review-required' | 'incomplete'; message?: string;
  configurationSha256: string; vehicleConfigurationSha256: string; phase?: 'executed';
  physicalVehicles?: number; assignedSegments?: number; unassignedSegments?: number;
  completedSegments?: number; unfinishedSegments?: number; notDepartedSegments?: number; completedPhysicalVehicles?: number;
  vehicles?: { vehicleId: string; nativeVehicleId: string; nativeCompleted: boolean; teleports: number | null;
    segments: { requestId: string; sourceTripKey: string; plannedDeparture: number; depart: number; arrival: number; departureDelay: number | null; status: string }[] }[];
}
export function emptyOperations(): VehicleOperationsConfiguration {
  return { schemaVersion: 'synthesis-vehicle-operations-input-v1', enabled: false, reserveFraction: .1, bindings: [], chainTrips: false };
}
export function emptyVehicleConfiguration(): VehicleConfigurationInput {
  return { schemaVersion: 'synthesis-vehicle-configuration-v1', application: 'catalogOnly', allowBehaviorApproximation: false,
    requiredCapabilities: [], vehicleTypes: [], catalogRefs: [], trafficAssignments: [], fleetAssignments: [], initialVehicles: [] };
}
export function canonicalJson(value: unknown): string {
  return JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);
}
