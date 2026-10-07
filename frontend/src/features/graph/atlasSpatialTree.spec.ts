import { describe, expect, it } from 'vitest';
import type { AtlasNode } from '@/api/codeAtlas';
import { buildSpatialTree, indexSpatialTree, spatialSymbolKey } from './atlasSpatialTree';

const node = (id: string, filePath: string, module = 'app'): AtlasNode => ({
  id, label: id, kind: 'method', filePath, module, startLine: 1, endLine: 2, count: 1,
});

describe('3D code navigation tree', () => {
  it('groups symbols under files and directories while preserving scope membership', () => {
    const tree = buildSpatialTree([node('save', 'app/src/api/service.ts'), node('read', 'app/src/api/service.ts'), node('run', 'app/src/job.ts')]);
    const index = indexSpatialTree(tree), entries = [...index.values()].map(r => r.entry);
    const file = entries.find(e => e.kind === 'file' && e.name === 'service.ts')!;
    expect(file.filePath).toBe('app/src/api/service.ts');
    expect(file.nodeIds).toEqual(['save', 'read']);
    expect(file.children.map(e => e.name)).toEqual(['read', 'save']);
    expect(tree[0].nodeIds).toEqual(['save', 'read', 'run']);
    expect(index.get(spatialSymbolKey('save'))!.ancestors.map(id => index.get(id)!.entry.name)).toEqual(['app', 'src', 'api', 'service.ts']);
  });

  it('compresses single-directory chains and retains exact Windows paths for file actions', () => {
    const path = 'app\\src\\main\\service.ts';
    const tree = buildSpatialTree([node('save', path)]), index = indexSpatialTree(tree);
    expect(tree[0].children[0].name).toBe('src/main');
    expect(index.get(spatialSymbolKey('save'))!.ancestors.map(id => index.get(id)!.entry.name)).toEqual(['app', 'src/main', 'service.ts']);
    expect(tree[0].children[0].children[0].filePath).toBe(path);
  });

  it('keeps root, same-named files and module prefixes distinct', () => {
    const tree = buildSpatialTree([node('root', 'index.ts', ''), node('a', 'app/a/index.ts'), node('b', 'app/b/index.ts'), node('c', 'application/index.ts')]);
    const entries = [...indexSpatialTree(tree).values()].map(r => r.entry);
    expect(tree.find(e => e.module === '')!.name).toBe('根目录');
    expect(entries.filter(e => e.kind === 'file')).toHaveLength(4);
    expect(new Set(entries.map(e => e.id)).size).toBe(entries.length);
    expect(entries.some(e => e.kind === 'directory' && e.name === 'application')).toBe(true);
  });

  it('keeps symbols without a file selectable and does not mutate API nodes', () => {
    const nodes = [node('external', '', 'external'), node('save', 'app/a.ts')], snapshot = JSON.stringify(nodes);
    const tree = buildSpatialTree(nodes), index = indexSpatialTree(tree);
    expect(index.get(spatialSymbolKey('external'))!.entry.node).toBe(nodes[0]);
    expect(index.get(spatialSymbolKey('external'))!.ancestors).toHaveLength(1);
    expect(JSON.stringify(nodes)).toBe(snapshot);
    expect(buildSpatialTree([])).toEqual([]);
  });
});
