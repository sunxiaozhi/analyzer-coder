export type SourceType = 'code' | 'kb' | 'mixed'

export type AnalysisMode = 'report' | 'summary' | 'json' | 'chunks' | 'graph'

export type ActiveView = 'analysis' | 'index' | 'query'

export type OutputType = 'markdown' | 'mermaid' | 'json' | 'text'

export type ConsoleSection = 'projects' | 'analysis' | 'vectors' | 'search'

export interface AnalyzerForm {
  projectId: string
  path: string
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
