// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { atlasModuleColor, layoutAtlas3d } from './atlasLayout3d';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';

const fixture = (count = 72, groups = 3): AtlasNode[] => Array.from({ length: count }, (_, i) => ({
  id: String(i), label: 'symbol-' + i, kind: 'method', filePath: `src/${i % groups}/file-${i}.ts`,
  module: 'module-' + i % groups, startLine: 1, endLine: 2, count: 1,
}));
const distance = (a: { x: number; y: number; z: number }, b: typeof a) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);

describe('spatial dependency layout', () => {
  it('is deterministic under node and edge ordering without mutating API data', () => {
    const nodes = fixture(), edges: AtlasEdge[] = nodes.slice(1).map(n => ({ source: '0', target: n.id, kind: 'calls', count: 1 }));
    const snapshot = JSON.stringify({ nodes, edges });
    expect(layoutAtlas3d(nodes, edges)).toEqual(layoutAtlas3d([...nodes].reverse(), [...edges].reverse()));
    expect(JSON.stringify({ nodes, edges })).toBe(snapshot);
  });
  it('uses all three dimensions and keeps node bodies from overlapping', () => {
    const nodes = layoutAtlas3d(fixture());
    for (const axis of ['x', 'y', 'z'] as const) expect(Math.max(...nodes.map(n => n[axis])) - Math.min(...nodes.map(n => n[axis]))).toBeGreaterThan(80);
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) expect(distance(nodes[i], nodes[j])).toBeGreaterThan(nodes[i].radius + nodes[j].radius);
  });
  it('places dependencies closer together than the same nodes without links', () => {
    const nodes = fixture(24, 1);
    const edges: AtlasEdge[] = Array.from({ length: 12 }, (_, i) => ({ source: String(i), target: String(i + 12), kind: 'calls', count: 1 }));
    const disconnected = new Map(layoutAtlas3d(nodes).map(n => [n.id, n]));
    const connected = new Map(layoutAtlas3d(nodes, edges).map(n => [n.id, n]));
    const total = (map: typeof connected) => edges.reduce((sum, e) => sum + distance(map.get(e.source)!, map.get(e.target)!), 0);
    expect(total(connected)).toBeLessThan(total(disconnected) * .75);
  });
  it('ignores missing endpoints and self-loops in layout forces while retaining their nodes', () => {
    const nodes = fixture(12);
    expect(layoutAtlas3d(nodes, [{ source: 'missing', target: '0', kind: 'calls', count: 1 }, { source: '1', target: '1', kind: 'calls', count: 1 }])).toEqual(layoutAtlas3d(nodes));
    expect(layoutAtlas3d([])).toEqual([]);
    expect(layoutAtlas3d(nodes.slice(0, 1))[0]).toMatchObject({ x: 0, y: 0, z: 0 });
  });
  it('retains module colors after filtering and scales node size with unique neighbors', () => {
    const nodes = fixture(12);
    const edges = ['1', '2', '3'].map(target => ({ source: '0', target, kind: 'calls', count: 1 }));
    const result = layoutAtlas3d(nodes, [...edges, ...edges]);
    expect(result.find(n => n.id === '0')!.degree).toBe(3);
    expect(result.find(n => n.id === '0')!.radius).toBeGreaterThan(result.find(n => n.id === '4')!.radius);
    for (const n of layoutAtlas3d(nodes.filter(n => n.module === 'module-1'))) expect(n.color).toBe(atlasModuleColor('module-1'));
  });
  it('produces finite bounded positions for the maximum loaded graph', () => {
    const nodes = fixture(1200, 12), edges = nodes.slice(1).map((n, i) => ({ source: String(i), target: n.id, kind: 'calls', count: 1 }));
    const result = layoutAtlas3d(nodes, edges);
    expect(result).toHaveLength(1200);
    expect(result.every(n => [n.x, n.y, n.z].every(v => Number.isFinite(v) && Math.abs(v) < 10000))).toBe(true);
  }, 15000);
});
