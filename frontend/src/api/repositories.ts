import { request } from '@/api/http';
import type { TaskReviewSummary } from '@/api/taskReviews';
import type { PageResult } from '@/types/pagination';
import type {
  CodeChunkListResponse,
  IndexJob,
  IndexJobType,
  RegisterRepositoryPayload,
  Repository,
  RepositoryAssetType,
  RepositoryFileContent,
  RepositorySnapshotFiles,
  RescanRepositoryResponse,
} from '@/types/api';

export type PreparationStageState = 'READY' | 'RUNNING' | 'PENDING' | 'FAILED' | 'DEGRADED';

export interface PreparationStage {
  key: 'snapshot' | 'content' | 'vectors' | 'graph' | 'knowledge_drift';
  label: string;
  state: PreparationStageState;
  detail: string;
}

export interface ProjectProfileCount {
  name: string;
  count: number;
}

export interface ProjectKeyAsset {
  path: string;
  assetType: RepositoryAssetType;
}

export interface ProjectProfile {
  fileCount: number;
  totalBytes: number;
  chunkCount: number;
  vectorizedChunks: number;
  missingChunks: number;
  knowledgeCards: number;
  retrievalCapability: 'CHARACTER_HASH' | 'SEMANTIC_EMBEDDING';
  retrievalCapabilityLabel: '字符相似度' | '语义检索';
  graphNodes: number;
  graphEdges: number;
  languages: ProjectProfileCount[];
  modules: ProjectProfileCount[];
  entryPoints: string[];
  assets: ProjectProfileCount[];
  keyAssets: ProjectKeyAsset[];
}

export interface ProjectArchitectureNode {
  id: string;
  label: string;
  path: string;
  kind: 'PROJECT' | 'MODULE' | 'RESOURCE';
  fileCount: number;
  codeFileCount: number;
  primaryLanguage: string;
  resourceType: string | null;
}

export interface ProjectArchitectureEdge {
  source: string;
  target: string;
  relation: 'CONTAINS' | 'DEPENDS_ON' | 'CONNECTS_TO';
  weight: number;
  samples: string[];
  evidenceSamples: ProjectArchitectureEvidenceSample[];
}

export interface ProjectArchitectureEvidenceSample {
  filePath: string;
  relatedFilePath: string | null;
  snapshotId: string;
  contentHash: string;
}

export interface ProjectArchitectureRisk {
  id: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  type: 'CYCLE' | 'BOUNDARY' | 'INSECURE_TRANSPORT';
  title: string;
  detail: string;
  modules: string[];
}

export interface ProjectArchitectureCoverage {
  analyzedFiles: number;
  totalCodeFiles: number;
  skippedLargeFiles: number;
  skippedByLimit: number;
  unreadableFiles: number;
  partial: boolean;
  notes: string[];
}

export interface ProjectArchitectureMap {
  repositoryId: string;
  snapshotId: string;
  commitSha: string | null;
  generatedAt: string;
  nodes: ProjectArchitectureNode[];
  edges: ProjectArchitectureEdge[];
  risks: ProjectArchitectureRisk[];
  coverage: ProjectArchitectureCoverage;
}

export interface ProjectTechnologyFact {
  name: string;
  category: 'LANGUAGE' | 'FRAMEWORK' | 'DATA' | 'UI' | 'STATE' | 'BUILD' | 'RUNTIME' | 'TEST' | 'INFRASTRUCTURE';
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  detail: string;
  evidencePaths: string[];
}

export interface ProjectFileCategory {
  key: string;
  label: string;
  detail: string;
  count: number;
  samples: string[];
}

export interface ProjectModuleHotspot {
  module: string;
  codeFiles: number;
  incomingWeight: number;
  outgoingWeight: number;
  relationWeight: number;
}

export interface ProjectGraphFacts {
  codeGraphReady: boolean;
  codeGraphVersion: string | null;
  symbolNodes: number;
  symbolEdges: number;
  modules: number;
  dependencyEdges: number;
  runtimeEdges: number;
  hotspots: ProjectModuleHotspot[];
  analyzedCodeFiles: number;
  totalCodeFiles: number;
  partial: boolean;
}

export interface ProjectSuggestion {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  category: string;
  title: string;
  detail: string;
  evidence: string[];
}

export interface ProjectCodeFacts {
  snapshotId: string;
  commitSha: string | null;
  generatedAt: string;
  projectType: string;
  confidence: number;
  codeFileCount: number;
  technologies: ProjectTechnologyFact[];
  fileCategories: ProjectFileCategory[];
  graph: ProjectGraphFacts;
  suggestions: ProjectSuggestion[];
  evidenceNotes: string[];
}
export interface ProjectArchitectureSymbol {
  symbolName: string;
  symbolKind: string | null;
  filePath: string;
  startLine: number | null;
  endLine: number | null;
  language: string | null;
}

export interface ProjectArchitectureModuleSymbols {
  repositoryId: string;
  snapshotId: string;
  module: string;
  symbols: ProjectArchitectureSymbol[];
  truncated: boolean;
}



export interface RepositoryPreparation {
  repositoryId: string;
  state: 'READY' | 'DEGRADED' | 'PROCESSING' | 'ACTION_REQUIRED' | 'NOT_READY';
  progress: number;
  message: string;
  stages: PreparationStage[];
  profile: ProjectProfile;
  activeJobId: string | null;
  activeJobType: IndexJobType | null;
  activeJobStatus: IndexJob['status'] | null;
}

export interface ProjectContextItem {
  id: string;
  chunkId: string | null;
  assetType: RepositoryAssetType;
  sourceType: 'VERIFIED_KNOWLEDGE' | 'CODE_FACT' | 'RETRIEVAL_CANDIDATE' | 'UNKNOWN';
  title: string;
  filePath: string | null;
  symbolName: string | null;
  startLine: number | null;
  endLine: number | null;
  excerpt: string;
  content: string;
  contentHash: string | null;
}

export type ProjectHealthState = 'READY' | 'DEGRADED' | 'BLOCKED' | 'PREPARING';

export interface ProjectKnowledgeHealth {
  total: number;
  current: number;
  suspect: number;
  stale: number;
  unverified: number;
  trusted: number;
  requiredWithoutOwner: number;
  unreviewed: number;
}

export interface ProjectHealthIssue {
  code: string;
  severity: 'BLOCKING' | 'WARNING';
  title: string;
  detail: string;
  actionTarget: 'PREPARATION' | 'KNOWLEDGE';
}

export interface ProjectHealthOverview {
  repositoryId: string;
  snapshotId: string | null;
  commitSha: string | null;
  state: ProjectHealthState;
  readyForReview: boolean;
  knowledge: ProjectKnowledgeHealth;
  recentReviews: TaskReviewSummary[];
  issues: ProjectHealthIssue[];
  generatedAt: string;
}

export interface ProjectContextPack {
  repositoryId: string;
  repositoryName: string;
  snapshotId: string;
  commitSha: string | null;
  task: string;
  items: ProjectContextItem[];
  markdown: string;
}

export function listRepositories(): Promise<Repository[]> {
  return request<Repository[]>('/api/repositories');
}


export function listRepositoryPage(params: { query?: string; pageNum: number; pageSize: number }): Promise<PageResult<Repository>> {
  const search = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
  if (params.query?.trim()) search.set('query', params.query.trim());
  return request<PageResult<Repository>>(`/api/repositories/page?${search}`);
}

export function registerRepository(payload: RegisterRepositoryPayload): Promise<Repository> {
  return request<Repository>('/api/repositories', { method: 'POST', body: JSON.stringify(payload) });
}


export function updateRepository(repositoryId: string, payload: {
  name: string; description: string; defaultBranch: string; version: number;
}): Promise<Repository> {
  return request<Repository>(`/api/repositories/${repositoryId}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  });
}

export function rescanRepository(repositoryId: string): Promise<RescanRepositoryResponse> {
  return request<RescanRepositoryResponse>(`/api/repositories/${repositoryId}/rescan`, { method: 'POST' });
}

export function deleteRepository(repositoryId: string): Promise<void> {
  return request<void>(`/api/repositories/${repositoryId}`, { method: 'DELETE' });
}

export function startIndex(repositoryId: string, type: IndexJobType): Promise<IndexJob> {
  return request<IndexJob>(`/api/repositories/${repositoryId}/index`, {
    method: 'POST',
    body: JSON.stringify({ type }),
  });
}

export function listIndexJobs(): Promise<IndexJob[]> {
  return request<IndexJob[]>('/api/index-jobs');
}

export function getLatestIndexStatus(repositoryId: string): Promise<IndexJob> {
  return request<IndexJob>(`/api/repositories/${repositoryId}/index/status`);
}

export function getIndexJob(indexJobId: string): Promise<IndexJob> {
  return request<IndexJob>(`/api/index-jobs/${indexJobId}`);
}

export function listChunks(
  repositoryId: string,
  params: { q?: string; limit?: number; offset?: number } = {},
): Promise<CodeChunkListResponse> {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.limit) search.set('limit', String(params.limit));
  if (params.offset) search.set('offset', String(params.offset));
  const suffix = search.toString() ? `?${search}` : '';
  return request<CodeChunkListResponse>(`/api/repositories/${repositoryId}/chunks${suffix}`);
}
export function syncRemoteRepository(repositoryId: string): Promise<RescanRepositoryResponse & { indexJobId: string | null }> {
  return request(`/api/repositories/${repositoryId}/sync`, { method: 'POST' });
}

export function getRepositoryProfile(repositoryId: string): Promise<RepositoryPreparation> {
  return request<RepositoryPreparation>(`/api/repositories/${repositoryId}/profile`);
}
export function getProjectHealthOverview(repositoryId: string): Promise<ProjectHealthOverview> {
  return request<ProjectHealthOverview>(`/api/repositories/${repositoryId}/health-overview`);
}
export function getProjectArchitectureMap(repositoryId: string): Promise<ProjectArchitectureMap> {
  return request<ProjectArchitectureMap>(`/api/repositories/${repositoryId}/architecture-map`);
}
export function getProjectCodeFacts(repositoryId: string): Promise<ProjectCodeFacts> {
  return request<ProjectCodeFacts>(`/api/repositories/${repositoryId}/code-facts`);
}
export function getProjectArchitectureModuleSymbols(
  repositoryId: string,
  module: string,
  limit = 80,
): Promise<ProjectArchitectureModuleSymbols> {
  const query = new URLSearchParams({ module, limit: String(limit) });
  return request<ProjectArchitectureModuleSymbols>(
    `/api/repositories/${repositoryId}/architecture-map/modules/symbols?${query}`,
  );
}




export function prepareRepository(repositoryId: string): Promise<RepositoryPreparation> {
  return request<RepositoryPreparation>(`/api/repositories/${repositoryId}/prepare`, { method: 'POST' });
}

export function retryPreparationStage(
  repositoryId: string,
  stage: PreparationStage['key'],
): Promise<RepositoryPreparation> {
  return request<RepositoryPreparation>(
    `/api/repositories/${repositoryId}/prepare/stages/${stage}/retry`,
    { method: 'POST' },
  );
}

export function listRepositoryFiles(repositoryId: string): Promise<RepositorySnapshotFiles> {
  return request<RepositorySnapshotFiles>(`/api/repositories/${repositoryId}/files`);
}

export function getRepositoryFile(repositoryId: string, path: string): Promise<RepositoryFileContent> {
  return request<RepositoryFileContent>(
    `/api/repositories/${repositoryId}/files/content?path=${encodeURIComponent(path)}`,
  );
}

export function generateProjectContextPack(
  repositoryId: string,
  payload: { task: string; maxItems?: number; maxChars?: number },
): Promise<ProjectContextPack> {
  return request<ProjectContextPack>(`/api/repositories/${repositoryId}/context-pack`, {
    method: 'POST', body: JSON.stringify(payload),
  });
}
