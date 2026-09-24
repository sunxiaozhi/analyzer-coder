import type { AtlasNode, AtlasEdge } from '@/api/codeAtlas';

export const atlasColors = ['#2f7fd3', '#168fa3', '#577fa5', '#508d93'];
export function layoutAtlas(nodes: AtlasNode[], edges: AtlasEdge[]) {
  const degree = new Map<string, number>();
  edges.forEach(e => { degree.set(e.source, (degree.get(e.source) ?? 0) + 1); degree.set(e.target, (degree.get(e.target) ?? 0) + 1); });
  const groups = [...new Set(nodes.map(n => n.module))].sort();
  const blocks = groups.map((name, group) => {
    const files = [...new Set(nodes.filter(n => n.module === name).map(n => n.filePath))].sort();
    let y = 70, width = 260;
    const members = files.flatMap(file => {
      const items = nodes.filter(n => n.module === name && n.filePath === file).sort((a, b) => a.startLine - b.startLine || a.id.localeCompare(b.id));
      const columns = Math.min(5, Math.ceil(Math.sqrt(items.length * 1.4)));
      const placed = items.map((node, i) => ({ ...node, x: 130 + i % columns * 260, y: y + 90 + Math.floor(i / columns) * 160 }));
      width = Math.max(width, columns * 260); y += Math.ceil(items.length / columns) * 160 + 70;
      return placed;
    });
    return { name, group, members, width, height: y };
  });
  const rowLimit = Math.max(1040, Math.sqrt(blocks.reduce((area, b) => area + b.width * b.height, 0) * 2.6));
  let x = 0, y = 0, rowHeight = 0;
  const placed = blocks.flatMap(block => {
    if (x && x + block.width > rowLimit) { x = 0; y += rowHeight + 100; rowHeight = 0; }
    const result = block.members.map(node => ({ ...node, x: x + node.x, y: y + node.y,
      color: atlasColors[block.group % atlasColors.length], radius: node.kind === 'MODULE' ? 30 : 12 + Math.min(7, Math.sqrt(degree.get(node.id) ?? 0)) }));
    x += block.width + 120; rowHeight = Math.max(rowHeight, block.height); return result;
  });
  if (!placed.length) return placed;
  const cx = (Math.min(...placed.map(n => n.x)) + Math.max(...placed.map(n => n.x))) / 2;
  const cy = (Math.min(...placed.map(n => n.y)) + Math.max(...placed.map(n => n.y))) / 2;
  return placed.map(n => ({ ...n, x: n.x - cx + 600, y: n.y - cy + 400 }));
}
export function atlasRegions(nodes: ReturnType<typeof layoutAtlas>, byFile = false) {
  const keys = [...new Set(nodes.map(n => byFile ? n.filePath : n.module))];
  return keys.map(key => {
    const members = nodes.filter(n => (byFile ? n.filePath : n.module) === key);
    const x = Math.min(...members.map(n => n.x)) - 120, y = Math.min(...members.map(n => n.y)) - (byFile ? 48 : 86);
    return { key, label: byFile ? key.split('/').pop() || key : key, x, y,
      width: Math.max(...members.map(n => n.x)) + 120 - x,
      height: Math.max(...members.map(n => n.y)) + (byFile ? 70 : 100) - y };
  });
}