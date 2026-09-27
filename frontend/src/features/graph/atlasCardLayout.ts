import { atlasEdgeKey, type AtlasEdge, type AtlasNode } from '@/api/codeAtlas';

export type AtlasCardLevel = 'files' | 'symbols';
export interface AtlasCard {
  id: string; label: string; module: string; filePath: string; kind: string;
  members: AtlasNode[]; incoming: number; outgoing: number; aggregated: boolean;
}
export interface AtlasCardEdge {
  id: string; source: string; target: string; kind: string; count: number; originals: AtlasEdge[];
}
export const CARD_WIDTH = 252;
export const CARD_HEIGHT = 112;

/** File cards are a projection of loaded symbols, never synthetic backend nodes. */
export function projectAtlasCards(nodes: AtlasNode[], edges: AtlasEdge[], level: AtlasCardLevel) {
  const cards = new Map<string, AtlasCard>(), owners = new Map<string, string>();
  for (const node of [...nodes].sort((a, b) => a.id.localeCompare(b.id))) {
    const aggregate = level === 'files' && node.kind !== 'MODULE' && !!node.filePath;
    const id = aggregate ? JSON.stringify(['file', node.module, node.filePath]) : node.id;
    owners.set(node.id, id);
    if (!cards.has(id)) cards.set(id, { id, label: aggregate ? node.filePath.replace(/\\/g, '/').split('/').pop()! : node.label,
      module: node.module, filePath: node.filePath, kind: aggregate ? 'FILE' : node.kind, members: [], incoming: 0, outgoing: 0, aggregated: aggregate });
    cards.get(id)!.members.push(node);
  }
  const links = new Map<string, AtlasCardEdge>();
  for (const edge of edges) {
    const source = owners.get(edge.source), target = owners.get(edge.target);
    if (!source || !target) continue;
    if (source === target && cards.get(source)!.aggregated) continue;
    const id = atlasEdgeKey({ ...edge, source, target });
    const existing = links.get(id);
    if (existing) { existing.count += edge.count; existing.originals.push(edge); }
    else links.set(id, { id, source, target, kind: edge.kind, count: edge.count, originals: [edge] });
    cards.get(source)!.outgoing += edge.count;
    cards.get(target)!.incoming += edge.count;
  }
  return { cards: [...cards.values()], edges: [...links.values()].sort((a, b) => a.id.localeCompare(b.id)) };
}

/** Condense cycles before ranking so recursive code stays finite and deterministic. */
export function dependencyRanks(ids: string[], edges: { source: string; target: string }[]) {
  const adjacency = new Map([...ids].sort().map(id => [id, new Set<string>()]));
  for (const edge of edges) if (adjacency.has(edge.source) && adjacency.has(edge.target)) adjacency.get(edge.source)!.add(edge.target);
  const indices = new Map<string, number>(), low = new Map<string, number>(), stack: string[] = [], stacked = new Set<string>();
  const components: string[][] = [], owner = new Map<string, number>();
  let index = 0;
  function visit(id: string) {
    indices.set(id, index); low.set(id, index++); stack.push(id); stacked.add(id);
    for (const next of [...adjacency.get(id)!].sort()) {
      if (!indices.has(next)) { visit(next); low.set(id, Math.min(low.get(id)!, low.get(next)!)); }
      else if (stacked.has(next)) low.set(id, Math.min(low.get(id)!, indices.get(next)!));
    }
    if (low.get(id) !== indices.get(id)) return;
    const members: string[] = [];
    let next: string;
    do { next = stack.pop()!; stacked.delete(next); owner.set(next, components.length); members.push(next); } while (next !== id);
    components.push(members);
  }
  for (const id of adjacency.keys()) if (!indices.has(id)) visit(id);
  const outgoing = components.map(() => new Set<number>()), incoming = components.map(() => 0), ranks = components.map(() => 0);
  for (const [id, targets] of adjacency) for (const target of targets) {
    const a = owner.get(id)!, b = owner.get(target)!;
    if (a !== b && !outgoing[a].has(b)) { outgoing[a].add(b); incoming[b]++; }
  }
  const queue = incoming.flatMap((count, i) => count === 0 ? [i] : []);
  for (let i = 0; i < queue.length; i++) for (const next of outgoing[queue[i]]) {
    ranks[next] = Math.max(ranks[next], ranks[queue[i]] + 1);
    if (--incoming[next] === 0) queue.push(next);
  }
  return new Map(ids.map(id => [id, ranks[owner.get(id)!]]));
}

export function layoutAtlasCards(cards: AtlasCard[], edges: AtlasCardEdge[]) {
  const modules = [...new Set(cards.map(card => card.module))].sort();
  const byId = new Map(cards.map(card => [card.id, card]));
  const moduleEdges = edges.flatMap(edge => {
    const a = byId.get(edge.source), b = byId.get(edge.target);
    return a && b && a.module !== b.module ? [{ source: a.module, target: b.module }] : [];
  });
  const moduleRanks = dependencyRanks(modules, moduleEdges);
  const blocks = modules.map(name => {
    const members = cards.filter(card => card.module === name);
    const ranks = dependencyRanks(members.map(card => card.id), edges);
    const columns = new Map<number, AtlasCard[]>();
    for (const card of members) {
      // Keep very long call chains navigable within a module.
      const rank = card.aggregated && modules.length > 1 ? 0 : Math.min(3, ranks.get(card.id) ?? 0);
      if (!columns.has(rank)) columns.set(rank, []);
      columns.get(rank)!.push(card);
    }
    const maxRows = Math.max(4, Math.ceil(Math.sqrt(members.length * 1.6)));
    const packed: AtlasCard[][] = [];
    for (const [, items] of [...columns].sort(([a], [b]) => a - b)) {
      items.sort((a, b) => (ranks.get(a.id)! - ranks.get(b.id)!) || a.filePath.localeCompare(b.filePath) || a.members[0].startLine - b.members[0].startLine || a.id.localeCompare(b.id));
      for (let i = 0; i < items.length; i += maxRows) packed.push(items.slice(i, i + maxRows));
    }
    const placed = packed.flatMap((items, column) => items.map((card, row) => ({ ...card,
      x: 24 + column * (CARD_WIDTH + 64), y: 64 + row * (CARD_HEIGHT + 28) })));
    return { name, rank: Math.min(4, moduleRanks.get(name) ?? 0), cards: placed,
      width: 48 + packed.length * (CARD_WIDTH + 64) - 64,
      height: 88 + Math.max(1, ...packed.map(items => items.length)) * (CARD_HEIGHT + 28) - 28 };
  });
  let left = 0;
  const groups: { key: string; x: number; y: number; width: number; height: number; count: number }[] = [];
  const placed: (AtlasCard & { x: number; y: number })[] = [];
  const rowLimit = Math.max(620, Math.sqrt(blocks.reduce((area, block) => area + block.width * block.height, 0) / 1.8));
  for (const rank of [...new Set(blocks.map(block => block.rank))].sort((a, b) => a - b)) {
    const column = blocks.filter(block => block.rank === rank);
    let top = 0, columnWidth = 0;
    for (const block of column) {
      if (top && top + block.height > rowLimit) { left += columnWidth + 64; top = 0; columnWidth = 0; }
      groups.push({ key: block.name, x: left, y: top, width: block.width, height: block.height, count: block.cards.length });
      placed.push(...block.cards.map(card => ({ ...card, x: card.x + left, y: card.y + top })));
      top += block.height + 40;
      columnWidth = Math.max(columnWidth, block.width);
    }
    left += columnWidth + 104;
  }
  return { cards: placed, groups, width: Math.max(0, left - 104), height: Math.max(0, ...groups.map(group => group.y + group.height)) };
}
