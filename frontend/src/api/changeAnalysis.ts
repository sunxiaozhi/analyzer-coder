import { request } from '@/api/http';
import type { ProjectArchitectureRisk } from '@/api/repositories';

export type ChangeEvidenceCoverageLevel = 'HIGH' | 'MEDIUM' | 'LOW';
export type ChangeAnalysisCandidateState = 'PENDING' | 'CONFIRMED' | 'EXCLUDED';
export type ChangeIntentParserMode = 'MODEL' | 'RULES';

export interface ChangeIntentInterpretation {
  parserMode: ChangeIntentParserMode;
  provider: string | null;
  fallbackReason: string | null;
  changeType: 'FEATURE' | 'BUGFIX' | 'REFACTOR' | 'CONFIG' | 'TEST' | 'DOCUMENTATION' | 'UNKNOWN';
  goal: string;
  domains: string[];
  entities: string[];
  candidateSymbols: string[];
  constraints: string[];
  expectedImpacts: string[];
  unknowns: string[];
  searchQueries: string[];
}

export interface ChangeRetrievalQuery {
  query: string;
  purpose: '原始任务' | '语义扩展' | '测试覆盖';
  hitCount: number;
}

export interface ChangeEvidenceCoverage {
  level: ChangeEvidenceCoverageLevel;
  label: string;
  detail: string;
}

export interface ChangeCandidateEvidence {
  chunkId: string | null;
  sourceType: string;
  snapshotId: string;
  filePath: string;
  symbolName: string | null;
  symbolKind: string | null;
  startLine: number | null;
  endLine: number | null;
  excerpt: string;
  contentHash: string;
  score: number;
  channels: string[];
  matchedQueries: string[];
  moduleId: string | null;
}

export interface ChangeModuleImpact {
  moduleId: string;
  label: string;
  role: 'DIRECT' | 'RELATED';
  evidenceCount: number;
  incomingWeight: number;
  outgoingWeight: number;
}

export interface ChangeDependencyImpact {
  source: string;
  target: string;
  relation: 'DEPENDS_ON' | 'CONNECTS_TO';
  weight: number;
  samples: ChangeDependencyEvidenceSample[];
}

export interface ChangeDependencyEvidenceSample {
  filePath: string;
  relatedFilePath: string | null;
  snapshotId: string;
  contentHash: string;
}

export interface ChangeTestSuggestion {
  filePath: string | null;
  startLine: number | null;
  endLine: number | null;
  snapshotId: string | null;
  contentHash: string | null;
  existing: boolean;
  reason: string;
}

export interface ChangeAnalysisUnknown {
  code: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  detail: string;
}

export interface ChangeImpactAnalysis {
  analysisId: string;
  repositoryId: string;
  snapshotId: string;
  commitSha: string | null;
  generatedAt: string;
  task: string;
  intent: ChangeIntentInterpretation;
  retrievalQueries: ChangeRetrievalQuery[];
  evidenceCoverage: ChangeEvidenceCoverage;
  candidates: ChangeCandidateEvidence[];
  modules: ChangeModuleImpact[];
  dependencies: ChangeDependencyImpact[];
  risks: ProjectArchitectureRisk[];
  tests: ChangeTestSuggestion[];
  unknowns: ChangeAnalysisUnknown[];
}

export function createChangeAnalysis(
  repositoryId: string,
  task: string,
  modelConfigId: string | null,
): Promise<ChangeImpactAnalysis> {
  return request<ChangeImpactAnalysis>(`/api/repositories/${repositoryId}/change-analyses`, {
    method: 'POST',
    body: JSON.stringify({ task, modelConfigId }),
  });
}
