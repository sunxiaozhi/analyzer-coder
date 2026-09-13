import type { AtlasNode } from '@/api/codeAtlas';
import { atlasColors } from './atlasLayout';

// Stable module districts on a shared ground plane. File order expresses
// containment, while height is decorative, never a quality/architecture metric.
export function layoutAtlas3d(nodes: AtlasNode[]) {
  const groups = [...new Set(nodes.map(n => n.module))].sort();
  const blocks = groups.map(group => {
    const members = nodes.filter(n => n.module === group).sort((a, b) => a.filePath.localeCompare(b.filePath) || a.id.localeCompare(b.id));
    const columns = Math.ceil(Math.sqrt(members.length));
    return { group, members, columns, width: Math.max(220, columns * 150), depth: Math.max(220, Math.ceil(members.length / columns) * 140) };
  });
  const limit = Math.max(500, Math.sqrt(blocks.reduce((sum, b) => sum + (b.width + 120) * (b.depth + 120), 0) * 1.3));
  let x = 0, z = 0, rowDepth = 0;
  const result = blocks.flatMap((block, gi) => {
    if (x && x + block.width > limit) { x = 0; z += rowDepth + 120; rowDepth = 0; }
    const placed = block.members.map((node, i) => {
      return { ...node, x: x + 75 + i % block.columns * 150,
        y: node.kind === 'MODULE' ? 48 : 35, z: z + 70 + Math.floor(i / block.columns) * 140,
        radius: node.kind === 'MODULE' ? 22 : 9,
        color: atlasColors[gi % atlasColors.length] };
    });
    x += block.width + 120; rowDepth = Math.max(rowDepth, block.depth);
    return placed;
  });
  if (!result.length) return result;
  const mid = (axis: 'x' | 'y' | 'z') => (Math.min(...result.map(n => n[axis])) + Math.max(...result.map(n => n[axis]))) / 2;
  const cx = mid('x'), cz = mid('z');
  return result.map(n => ({ ...n, x: n.x - cx, z: n.z - cz }));
}

export function atlasDistricts(nodes: ReturnType<typeof layoutAtlas3d>) {
  return [...new Set(nodes.map(n => n.module))].sort().map(module => {
    const members = nodes.filter(n => n.module === module);
    const left = Math.min(...members.map(n => n.x)) - 65, right = Math.max(...members.map(n => n.x)) + 65;
    const back = Math.min(...members.map(n => n.z)) - 60, front = Math.max(...members.map(n => n.z)) + 70;
    return { module, color: members[0].color, x: (left + right) / 2, z: (back + front) / 2,
      width: right - left, depth: front - back, left, right, back, front };
  });
}
