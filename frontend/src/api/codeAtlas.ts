import { request } from './http';
import { branchContextOptions } from './branchContext';

export interface AtlasNode {
  id: string; label: string; kind: string; filePath: string;
  startLine: number; endLine: number; module: string; count: number;
  qualifiedName?: string; incomingCount?: number; outgoingCount?: number;
  hiddenNeighborCount?: number; neighborCount?: number;
}
export interface AtlasEdge {
  source: string; target: string; kind: string; count: number;
  sourceLines?: number[]; sourceLinesTruncated?: boolean;
}
export interface AtlasView {
  repositoryId: string; contentVersion: string; level: 'MODULE' | 'SYMBOL';
  nodes: AtlasNode[]; edges: AtlasEdge[]; totalNodes: number; totalEdges: number; partial: boolean;
  repositoryNodes?: number; repositoryEdges?: number; unmappedNodes?: number; relationKinds?: string[];
}
export interface AtlasExploreOptions { focusId?: string; direction?: 'both' | 'in' | 'out'; depth?: number; limit?: number }
export const atlasEdgeKey = (edge: AtlasEdge) => JSON.stringify([edge.source, edge.target, edge.kind]);
export function getCodeAtlas(repositoryId: string, module = '', query = '', contextId?: string | null, options: AtlasExploreOptions = {}) {
  const params = new URLSearchParams({ module, query });
  Object.entries(options).forEach(([key, value]) => { if (value !== undefined) params.set(key, String(value)); });
  return request<AtlasView>(`/api/repositories/${repositoryId}/codegraph/explore?${params}`, branchContextOptions(contextId));
}