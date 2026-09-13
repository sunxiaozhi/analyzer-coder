import type { AtlasNode, AtlasEdge } from '@/api/codeAtlas';

// Deeper variants of the application's semantic palette: legible on a light canvas.
export const atlasColors = ['#08768a', '#6650a4', '#16734e', '#9a6010', '#af443c', '#2467ae'];
export function layoutAtlas(nodes: AtlasNode[], edges: AtlasEdge[]) {
  const degree = new Map<string, number>();
  edges.forEach(e => {
    degree.set(e.source, (degree.get(e.source) ?? 0) + e.count);
    degree.set(e.target, (degree.get(e.target) ?? 0) + e.count);
  });
  const groups = [...new Set(nodes.map(n => n.module))].sort();
  const blocks = groups.map((name, group) => {
    const members = nodes.filter(n => n.module === name).sort((a, b) => a.id.localeCompare(b.id));
    const columns = Math.ceil(Math.sqrt(members.length * 1.4));
    return { name, group, members, columns, width: columns * 260, height: Math.ceil(members.length / columns) * 160 + 70 };
  });
  const rowLimit = Math.max(780, Math.sqrt(blocks.reduce((area, b) => area + b.width * b.height, 0) * 1.6));
  let x = 0, y = 0, rowHeight = 0;
  const positioned = blocks.flatMap(block => {
    if (x && x + block.width > rowLimit) { x = 0; y += rowHeight + 80; rowHeight = 0; }
    const result = block.members.map((node, index) => ({
      ...node, x: x + 130 + (index % block.columns) * 260,
      y: y + 90 + Math.floor(index / block.columns) * 160,
      color: atlasColors[block.group % atlasColors.length],
      radius: node.kind === 'MODULE' ? 34 : 14 + Math.min(8, Math.sqrt(degree.get(node.id) ?? 0)),
    }));
    x += block.width + 80;
    rowHeight = Math.max(rowHeight, block.height);
    return result;
  });
  if (!positioned.length) return positioned;
  const cx = (Math.min(...positioned.map(n => n.x)) + Math.max(...positioned.map(n => n.x))) / 2;
  const cy = (Math.min(...positioned.map(n => n.y)) + Math.max(...positioned.map(n => n.y))) / 2;
  return positioned.map(n => ({ ...n, x: n.x - cx + 600, y: n.y - cy + 400 }));
}
