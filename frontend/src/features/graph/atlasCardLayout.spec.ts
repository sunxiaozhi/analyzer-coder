import { describe, expect, it } from 'vitest';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';
import { CARD_WIDTH, CARD_HEIGHT, dependencyRanks, layoutAtlasCards, projectAtlasCards } from './atlasCardLayout';

const node = (id: string, filePath = 'src/a.ts', module = 'src'): AtlasNode => ({
  id, label: id, filePath, module, kind: 'function', startLine: 1, endLine: 10, count: 1,
});
const edge = (source: string, target: string, kind = 'calls', count = 1): AtlasEdge => ({ source, target, kind, count, sourceLines: [8] });

describe('file dependency projection', () => {
  it('aggregates cross-file occurrences, keeps direction and relation kinds, and retains source evidence', () => {
    const nodes = [node('a'), node('b'), node('c', 'other/a.ts', 'other')];
    const edges = [edge('a', 'b'), edge('a', 'c', 'calls', 2), edge('b', 'c', 'calls', 3), edge('c', 'a', 'imports'), edge('missing', 'a')];
    const result = projectAtlasCards(nodes, edges, 'files');
    expect(result.cards).toHaveLength(2);
    expect(result.edges).toHaveLength(2);
    const calls = result.edges.find(e => e.kind === 'calls')!;
    expect(calls.count).toBe(5);
    expect(calls.originals).toEqual([edges[1], edges[2]]);
    expect(result.cards.find(c => c.id === calls.source)?.members.map(n => n.id)).toEqual(['a', 'b']);
    expect(result.edges.find(e => e.kind === 'imports')).toMatchObject({ source: calls.target, target: calls.source });
    expect(result.cards.find(c => c.id === calls.target)?.incoming).toBe(5);
  });

  it('keeps intra-file and recursive relations at symbol level', () => {
    const result = projectAtlasCards([node('a'), node('b')], [edge('a', 'b'), edge('a', 'a')], 'symbols');
    expect(result.cards.map(c => c.id)).toEqual(['a', 'b']);
    expect(result.cards.every(c => !c.aggregated)).toBe(true);
    expect(result.edges).toHaveLength(2);
  });

  it('preserves backend module nodes and nodes without a source location', () => {
    const result = projectAtlasCards([{ ...node('module', ''), kind: 'MODULE', count: 12 }, node('external', '')], [], 'files');
    expect(result.cards.every(c => !c.aggregated)).toBe(true);
    expect(result.cards.find(c => c.id === 'module')?.members[0].count).toBe(12);
  });
});

describe('dependency layout', () => {
  it('ranks dependencies after callers and condenses recursive cycles', () => {
    const ranks = dependencyRanks(['entry', 'a', 'b', 'db', 'alone'], [edge('entry', 'a'), edge('a', 'b'), edge('b', 'a'), edge('b', 'db'), edge('db', 'db')]);
    expect(ranks.get('entry')).toBeLessThan(ranks.get('a')!);
    expect(ranks.get('a')).toBe(ranks.get('b'));
    expect(ranks.get('b')).toBeLessThan(ranks.get('db')!);
    expect(ranks.get('alone')).toBe(0);
  });

  it.each([1, 12, 120])('packs 240 cards without overlap across %i modules and stays stable on reordered input', groups => {
    const nodes = Array.from({ length: 240 }, (_, i) => node(String(i), 'src/' + i + '.ts', 'm' + i % groups));
    const edges = nodes.slice(1).map((n, i) => edge(nodes[i].id, n.id));
    const graph = projectAtlasCards(nodes, edges, 'symbols');
    const result = layoutAtlasCards(graph.cards, graph.edges);
    const reversed = projectAtlasCards([...nodes].reverse(), [...edges].reverse(), 'symbols');
    expect(layoutAtlasCards(reversed.cards, reversed.edges)).toEqual(result);
    let overlap = false;
    for (let i = 0; i < result.cards.length; i++) for (let j = i + 1; j < result.cards.length; j++) {
      const a = result.cards[i], b = result.cards[j];
      if (Math.abs(a.x - b.x) < CARD_WIDTH && Math.abs(a.y - b.y) < CARD_HEIGHT) overlap = true;
    }
    expect(overlap).toBe(false);
    for (const card of result.cards) {
      const group = result.groups.find(g => g.key === card.module)!;
      expect(card.x).toBeGreaterThanOrEqual(group.x);
      expect(card.x + CARD_WIDTH).toBeLessThanOrEqual(group.x + group.width);
      expect(card.y + CARD_HEIGHT).toBeLessThanOrEqual(group.y + group.height);
    }
  });

  it('handles the 1200-node limit and empty graphs with finite extents', () => {
    const nodes = Array.from({ length: 1200 }, (_, i) => node(String(i)));
    const edges = nodes.slice(1).map((n, i) => edge(nodes[i].id, n.id));
    const graph = projectAtlasCards(nodes, edges, 'symbols');
    const result = layoutAtlasCards(graph.cards, graph.edges);
    expect(result.cards).toHaveLength(1200);
    expect(Number.isFinite(result.width) && Number.isFinite(result.height)).toBe(true);
    expect(result.height).toBeLessThan(10000);
    expect(layoutAtlasCards([], [])).toEqual({ cards: [], groups: [], width: 0, height: 0 });
  });
});
