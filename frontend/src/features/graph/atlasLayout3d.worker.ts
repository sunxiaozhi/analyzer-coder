import { layoutAtlas3d } from './atlasLayout3d';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';
self.onmessage = (event: MessageEvent<{ nodes: AtlasNode[]; edges: AtlasEdge[] }>) => {
  self.postMessage(layoutAtlas3d(event.data.nodes, event.data.edges));
};
