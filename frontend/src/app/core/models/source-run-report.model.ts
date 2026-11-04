import { CandidateDecision, Geography, SourcePlan, SourcePlanCheck } from './data-source.model';

export const SOURCE_RUN_CONTRACT = 'synthesis-source-run-resolution-v1';
export interface SourceReference {
  kind: string; label: string; revision?: string; sourceHash?: string;
  origin?: string; referenceDate?: string; licence?: string; attribution?: string;
}
export interface ProvenanceNode {
  id: string; operation: string; evidence: string; selectionRole: string;
  source?: SourceReference; inputs: string[];
  scope: { purpose: string; zones: { externalId: number; modelIndex: number }[] };
  parameters: Record<string, unknown>;
}
export interface SourceProvenance { aspect: string; root: string; nodes: ProvenanceNode[]; }
export interface SourceAspectReport {
  status?: string; provider?: string; revision?: string; unit?: string;
  geography?: Geography;
  candidates?: CandidateDecision[]; provenance?: SourceProvenance;
  totalProvenance?: SourceProvenance; distributionProvenance?: SourceProvenance;
  scientificUse?: string; assumptionStatement?: string;
  contributions?: { relationId: string; sourceRow?: number; source: SourceReference; evidence: string; inputVehicleTrips: number; generatedVehicleTrips: number }[];
  [key: string]: unknown;
}
export interface SourceRunReport {
  contract: typeof SOURCE_RUN_CONTRACT;
  run: { id: string; scenarioId: string; state: string; startedAt: string; finishedAt?: string; error?: string; seed: number; referenceYear: number; planSha256: string; lockSha256: string };
  plan: SourcePlan; decisions: SourcePlanCheck['decisions'];
  population?: SourceAspectReport; workplaces?: SourceAspectReport; freight?: SourceAspectReport;
  populationSynthesis?: Record<string, unknown>;
  failures: Record<string, { message?: string; error?: string; candidates?: CandidateDecision[] }>;
  targets: { logitopp: { stageStatus: string }; sumo: { stageStatus: string; executionReport?: string; executionSha256?: string; execution?: {
    status: string; routingVerified: boolean; demandRetained: boolean;
    vehicles: { requested: number; routed: number; departed: number; completed: number };
    persons: { requested: number; routed: number; departed: number; completed: number };
    nativeVehicleTeleports: number;
  } } };
}
