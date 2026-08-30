export type TruthSource =
  | 'GIT_FACT'
  | 'CODE_FACT'
  | 'VERIFIED_KNOWLEDGE'
  | 'GRAPH_INFERENCE'
  | 'RETRIEVAL_CANDIDATE'
  | 'MODEL_SUGGESTION'
  | 'UNKNOWN';

export interface Provenance {
  id: string;
  sourceType: TruthSource;
  repositoryId: string | null;
  snapshotId: string | null;
  commitSha: string | null;
  worktreeDigest: string | null;
  filePath: string | null;
  symbolName: string | null;
  symbolKind: string | null;
  startLine: number | null;
  endLine: number | null;
  contentHash: string | null;
  knowledgeCardId: string | null;
  knowledgeRevision: number | null;
  knowledgeReviewStatus: string | null;
  graphArtifactId: string | null;
  relationPath: string[];
  retrievalChannel: string | null;
  findingId: string | null;
  detail: string;
}
