import type { AtlasNode } from '@/api/codeAtlas';
import { atlasModuleColor } from './atlasLayout3d';

export interface SpatialTreeEntry {
  id: string;
  kind: 'module' | 'directory' | 'file' | 'symbol';
  name: string;
  module: string;
  color: string;
  filePath?: string;
  node?: AtlasNode;
  nodeIds: string[];
  children: SpatialTreeEntry[];
}

export const spatialSymbolKey = (id: string) => JSON.stringify(['symbol', id]);
const normalized = (path: string) => path.replace(/\\/g, '/').replace(/^\/+|\/+$/g, '');

export function buildSpatialTree(nodes: AtlasNode[]): SpatialTreeEntry[] {
  const roots: SpatialTreeEntry[] = [], entries = new Map<string, SpatialTreeEntry>();
  function entry(kind: SpatialTreeEntry['kind'], module: string, path: string, name: string, parent?: SpatialTreeEntry) {
    const id = JSON.stringify([kind, module, path]);
    let result = entries.get(id);
    if (!result) {
      result = { id, kind, name, module, color: atlasModuleColor(module), nodeIds: [], children: [] };
      entries.set(id, result); (parent ? parent.children : roots).push(result);
    }
    return result;
  }
  for (const node of nodes) {
    let parent = entry('module', node.module, '', node.module || '根目录');
    parent.nodeIds.push(node.id);
    if (node.filePath) {
      const module = normalized(node.module), path = normalized(node.filePath);
      const relative = module && path.startsWith(module + '/') ? path.slice(module.length + 1) : path;
      const parts = relative.split('/').filter(Boolean), name = parts.pop() || node.filePath;
      let directory = '';
      for (const part of parts) {
        directory = directory ? directory + '/' + part : part;
        parent = entry('directory', node.module, directory, part, parent);
        parent.nodeIds.push(node.id);
      }
      parent = entry('file', node.module, node.filePath, name, parent);
      parent.filePath = node.filePath; parent.nodeIds.push(node.id);
    }
    parent.children.push({ id: spatialSymbolKey(node.id), kind: 'symbol', name: node.label,
      module: node.module, color: parent.color, filePath: node.filePath, node, nodeIds: [node.id], children: [] });
  }
  const rank = { module: 0, directory: 1, file: 2, symbol: 3 };
  function compact(list: SpatialTreeEntry[]): SpatialTreeEntry[] {
    return list.map(item => {
      while (item.kind === 'directory' && item.children.length === 1 && item.children[0].kind === 'directory') {
        const child = item.children[0]; item = { ...child, name: item.name + '/' + child.name };
      }
      item.children = compact(item.children);
      return item;
    }).sort((a, b) => rank[a.kind] - rank[b.kind] || a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  }
  return compact(roots);
}

export function indexSpatialTree(roots: SpatialTreeEntry[]) {
  const index = new Map<string, { entry: SpatialTreeEntry; ancestors: string[] }>();
  function visit(entries: SpatialTreeEntry[], ancestors: string[]) {
    for (const entry of entries) {
      index.set(entry.id, { entry, ancestors });
      visit(entry.children, [...ancestors, entry.id]);
    }
  }
  visit(roots, []);
  return index;
}
