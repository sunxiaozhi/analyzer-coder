export type SourceType = 'code' | 'kb' | 'mixed'

export type AnalysisMode = 'report' | 'graph'

export type ActiveView = 'analysis' | 'index' | 'query'

export type OutputType = 'markdown' | 'mermaid' | 'json' | 'text'

export type ConsoleSection =
  | 'projects'
  | 'accounts'
  | 'assets'
  | 'evidence'
  | 'knowledge'
  | 'analysis'
  | 'api-map'
  | 'vectors'
  | 'search'

export interface AnalyzerForm {
  projectId: string
  path: string
  codePath: string
  frontendPath: string
  backendPath: string
  kbPath: string
  source: SourceType
  mode: AnalysisMode
  store: string
  query: string
  filterSource: '' | 'code' | 'kb' | 'knowledge_asset'
}

export interface ProjectRecord {
  id: string
  projectKey?: string
  name: string
  gitUrl: string
  branch: string
  path: string
  createdAt: string
  updatedAt: string
}

export interface ProjectForm {
  name: string
  gitUrl: string
  branch: string
}

export interface AuthUser {
  id: string
  username: string
  displayName: string
  isAdmin: boolean
  lastProjectId: string
  projectIds: string[]
  createdAt: string
  updatedAt: string
}

export interface UserForm {
  username: string
  displayName: string
  password: string
  projectIds: string[]
  isAdmin: boolean
}

export interface UserEditForm {
  id: string
  username: string
  displayName: string
  isAdmin: boolean
}

export interface KnowledgeFile {
  path: string
  name: string
  size: number
  updatedAt: string
}

export interface KnowledgeTemplate {
  id: string
  name: string
  path: string
  content: string
  createdAt: string
  updatedAt: string
}

export interface KnowledgeTemplateForm {
  id: string
  name: string
  path: string
  content: string
}

export type KnowledgeAssetType =
  | 'business_rule'
  | 'adr'
  | 'incident'
  | 'api_doc'
  | 'standard'
  | 'glossary'
  | 'module_note'

export type KnowledgeAssetStatus = 'draft' | 'pending_review' | 'confirmed' | 'stale' | 'archived'

export interface KnowledgeEvidence {
  type: string
  filePath: string
  symbolName: string
  startLine: number
  endLine: number
  note: string
}

export interface KnowledgeAsset {
  id: string
  type: KnowledgeAssetType
  title: string
  summary: string
  content: string
  status: KnowledgeAssetStatus
  ownerUserId: string
  reviewerUserId: string
  tags: string[]
  evidence: KnowledgeEvidence[]
  sourcePath: string
  confirmedAt: string
  reviewDueAt: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

export interface KnowledgeAssetForm {
  id: string
  type: KnowledgeAssetType
  title: string
  summary: string
  content: string
  status: KnowledgeAssetStatus
  ownerUserId: string
  reviewerUserId: string
  tagsText: string
  evidence: KnowledgeEvidence[]
  sourcePath: string
  reviewDueAt: string
}

export interface KnowledgeAssetFilters {
  type: '' | KnowledgeAssetType
  status: '' | KnowledgeAssetStatus
  query: string
}

export interface KnowledgeAssetPagination {
  page: number
  pageSize: number
  total: number
}

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

export interface QueryResultMetadata {
  source_type: string
  kind: string
  file_path: string
  start_line: number
  [key: string]: JsonValue | undefined
}

export interface QueryResult {
  id: string
  score: number
  text: string
  metadata: QueryResultMetadata
}

export interface RelatedEvidence {
  id: string
  score: number
  text: string
  metadata: QueryResultMetadata
}

export interface EvidenceRelation {
  from: string
  to: string
  reason: string
  score: number
  terms: string[]
}

export interface QueryEvidence {
  code: QueryResult[]
  knowledge: QueryResult[]
  relatedCode: RelatedEvidence[]
  relatedKnowledge: RelatedEvidence[]
  relations: EvidenceRelation[]
}

export interface IndexStatus {
  exists: boolean
  store: string
  size: number
  updatedAt: string
  total: number
  sources: Record<string, number>
  kinds: Record<string, number>
  projectId: string
}

export interface IndexRecord {
  id: string
  text: string
  metadata: QueryResultMetadata
}

export interface IndexRecordFilters {
  source: '' | 'code' | 'kb' | 'knowledge_asset'
  kind: string
  query: string
}

export interface FrontendApiCall {
  method: string
  path: string
  normalized_path: string
  file_path: string
  line: number
  callee: string
  expression: string
}

export interface BackendApiEndpoint {
  methods: string[]
  path: string
  normalized_path: string
  file_path: string
  line: number
  controller: string
  handler: string
}

export interface ApiMapping {
  status: 'matched' | 'method_mismatch' | 'unmatched'
  confidence: string
  frontend: FrontendApiCall
  backend: BackendApiEndpoint | null
  reason: string
  match_strategy: string
}

export interface ApiMappingSummary {
  frontendCalls: number
  backendEndpoints: number
  matched: number
  methodMismatches: number
  unmatched: number
}

export interface ApiMappingResult {
  frontend_calls: FrontendApiCall[]
  backend_endpoints: BackendApiEndpoint[]
  mappings: ApiMapping[]
  summary: ApiMappingSummary
  savedPath: string
  projectId: string
}
