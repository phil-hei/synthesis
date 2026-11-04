import { Region } from './scenario.model';
export interface SourcePolicy {
  source: 'legacy' | 'dataset' | 'profile' | 'excluded' | 'model' | 'builtins'; revision?: string; profile?: string; gaps: 'legacy' | 'none' | 'providers';
  providers?: SourceCandidate[]; gapProviders?: SourceCandidate[];
  internalTrips: number; inboundTrips: number; outboundTrips: number; throughTrips: number;
  vehicleClass: 'truck' | 'trailer'; demonstrationAccepted?: boolean; assumptionStatement?: string;
}
export interface SourcePlan { enabled: boolean; qualityMode?: 'exploration' | 'analysis'; aspects: Record<string, SourcePolicy>; }
export function emptySourcePolicy(): SourcePolicy { return { source: 'legacy', gaps: 'legacy', internalTrips: 0, inboundTrips: 0, outboundTrips: 0, throughTrips: 0, vehicleClass: 'truck', demonstrationAccepted: false, assumptionStatement: '' }; }
export interface ImportSchema { id: string; label: string; aspect: string; unit: string; fields: { id: string; label: string; type: string; required: boolean; aliases: string[] }[]; }
export interface ImportMapping { tableId: string; headerRow: number; columns: Record<string, number>; decimalSeparator: string; }
export interface CoverageDeclaration { kind: 'point-register' | 'municipality-aggregate'; completeness: 'unknown' | 'partial' | 'complete'; statement: string; }
export interface Geography { codeSystem: 'de:AGS'; edition: string; allocationMethod: 'explicit-point-weights-v1' | 'uniform-area-v1'; allocationRevision?: string; }
export interface ImportMetadata { label: string; source: string; referenceDate: string; unit: string; coverage: Region; licence?: string; attribution?: string; coverageDeclaration?: CoverageDeclaration; geography?: Geography; }
export interface DataImport {
  draft: { id: string; filename: string; schemaId: string; source: { format: string; sourceHash: string; tables: { id: string; name: string; rows: { sourceRow: number; cells: string[] }[] }[] } };
  mapping: ImportMapping;
}
export interface ImportPreview { status: string; inputRows: number; mappedRows: number; rejectedRows: number; blankRows: number; issues: { sourceRow: number; field: string; code: string; message: string }[]; sample: { sourceRow: number; values: Record<string, unknown> }[]; }
export interface DatasetRevision { id: string; schemaId: string; label: string; source: string; referenceDate: string; unit: string; rows: number; }
export interface DataProfile { id: string; name: string; schemaId: string; format: string; structure: string; mapping: ImportMapping; }
export interface SourcePlanCheck { ready: boolean; issues: string[]; decisions: { aspect: string; source: string; status: string; evidence: string; strategy?: 'choose' | 'fill-gaps'; label?: string; revision?: string; licence?: string; attribution?: string; coverageDeclaration?: CoverageDeclaration; applicationStatus?: string; scientificUse?: string; assumptionStatement?: string; geography?: Geography; profile?: string; gapProviders?: SourceCandidate[]; candidates?: CandidateDecision[] }[]; }

export interface DataPack { id: string; packId: string; title: string; edition: string; sources: { id: string; aspect: string; schema: string; revision: string; label: string; referenceDate: string; licence: string; attribution: string }[]; }
export interface DataLibraryEntry { id: string; status: 'available' | 'invalid'; issue: string; pack?: DataPack; }
export interface SourceCandidate { provider: string; excluded: boolean; onUnavailable: 'fail' | 'next'; }
export interface SourceChain { candidates: SourceCandidate[]; fallback: 'legacy' | 'none'; strategy?: 'choose' | 'fill-gaps'; }
export interface SourceProfileDefinition { contract: 'synthesis-source-profile-v1'; name: string; aspects: Record<string, SourceChain>; }
export interface SourceProfile { id: string; definition: SourceProfileDefinition; }
export interface CandidateDecision { provider: string; rank: number; status: string; reason: string; onUnavailable: string; }

export interface SourceProfileLibraryEntry { id: string; status: 'available' | 'invalid'; issue: string; profile?: SourceProfile; requiredPacks: DataPack[]; }
