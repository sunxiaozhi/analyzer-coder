import { expect, it } from 'vitest';
import { layoutAtlas } from './atlasLayout';
import type { AtlasNode } from '@/api/codeAtlas';

function fixture(count: number, groups: number): AtlasNode[] {
  return Array.from({ length: count }, (_, i) => ({
    id: String(i), label: 'aLongSymbolNameForLayout', kind: 'method',
    filePath: 'src/a.ts', module: 'module-' + i % groups, startLine: 1, endLine: 2, count: 1,
  }));
}

it.each([1, 6, 40, 120])('keeps 240 symbols and label footprints separated across %i groups', groups => {
  const nodes = layoutAtlas(fixture(240, groups), []);
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      expect(Math.abs(nodes[i].x - nodes[j].x) >= 250 || Math.abs(nodes[i].y - nodes[j].y) >= 150).toBe(true);
    }
  }
});

it('has stable positions regardless of input order and handles empty data', () => {
  const input = fixture(24, 6);
  expect(layoutAtlas(input, [])).toEqual(layoutAtlas([...input].reverse(), []));
  expect(layoutAtlas([], [])).toEqual([]);
});
