import { request } from './http';
import { branchContextOptions } from './branchContext';

export interface AtlasNode {
  id: string; label: string; kind: string; filePath: string;
  startLine: number; endLine: number; module: string; count: number;
}
export interface AtlasEdge { source: string; target: string; kind: string; count: number }
export interface AtlasView {
  repositoryId: string; snapshotId: string; level: 'MODULE' | 'SYMBOL';
  nodes: AtlasNode[]; edges: AtlasEdge[]; totalNodes: number; totalEdges: number; partial: boolean;
}
export function getCodeAtlas(repositoryId: string, module = '', query = '', contextId?: string | null) {
  return request<AtlasView>(
    `/api/repositories/${repositoryId}/codegraph/explore?${new URLSearchParams({ module, query })}`,
    branchContextOptions(contextId),
  );
}
