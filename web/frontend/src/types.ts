export type SourceType = 'code' | 'kb' | 'mixed'

export type AnalysisMode = 'report' | 'summary' | 'json' | 'chunks' | 'graph'

export type ActiveView = 'analysis' | 'index' | 'query'

export type OutputType = 'markdown' | 'mermaid' | 'json' | 'text'

export type ConsoleSection = 'projects' | 'accounts' | 'knowledge' | 'analysis' | 'vectors' | 'search'

export interface AnalyzerForm {
  projectId: string
  path: string
  codePath: string
  kbPath: string
  source: SourceType
  mode: AnalysisMode
  store: string
  query: string
  filterSource: '' | 'code' | 'kb'
}

export interface ProjectRecord {
  id: string
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

export interface UserPasswordForm {
  id: string
  password: string
}

export interface KnowledgeFile {
  path: string
  name: string
  size: number
  updatedAt: string
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
