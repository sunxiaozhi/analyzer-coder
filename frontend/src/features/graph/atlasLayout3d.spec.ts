import { expect, it } from 'vitest';
import { atlasDistricts, layoutAtlas3d } from './atlasLayout3d';
import type { AtlasNode } from '@/api/codeAtlas';
const fixture = (groups: number): AtlasNode[] => Array.from({ length: 240 }, (_, i) => ({
  id: String(i), label: 'symbol-' + i, kind: 'method', filePath: 'src/a.ts',
  module: 'module-' + i % groups, startLine: 1, endLine: 2, count: 1,
}));
it.each([1, 6, 120])('uses true depth with separated nodes for %i module groups', groups => {
  const nodes = layoutAtlas3d(fixture(groups));
  expect(new Set(nodes.map(n => n.z)).size).toBeGreaterThan(1);
  for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
    expect(Math.hypot(nodes[i].x - nodes[j].x, nodes[i].y - nodes[j].y, nodes[i].z - nodes[j].z)).toBeGreaterThan(nodes[i].radius + nodes[j].radius + 10);
  }
});
it('is deterministic and handles empty input', () => {
  expect(layoutAtlas3d(fixture(6))).toEqual(layoutAtlas3d(fixture(6).reverse()));
  expect(layoutAtlas3d([])).toEqual([]);
});

it('keeps every node inside a non-overlapping module district', () => {
  const nodes = layoutAtlas3d(fixture(6));
  const districts = atlasDistricts(nodes);
  for (const node of nodes) {
    const district = districts.find(d => d.module === node.module)!;
    expect(node.x - node.radius).toBeGreaterThan(district.left);
    expect(node.x + node.radius).toBeLessThan(district.right);
    expect(node.z - node.radius).toBeGreaterThan(district.back);
    expect(node.z + node.radius).toBeLessThan(district.front);
    expect(node.y).toBeGreaterThan(0);
  }
  for (let i = 0; i < districts.length; i++) for (let j = i + 1; j < districts.length; j++) {
    const a = districts[i], b = districts[j];
    expect(a.right < b.left || b.right < a.left || a.front < b.back || b.front < a.back).toBe(true);
  }
  expect(atlasDistricts([])).toEqual([]);
});
