import type { AtlasNode } from '@/api/codeAtlas';
import { layoutAtlas } from './atlasLayout';

// The same module/file ordering in both renderers; height distinguishes symbol kinds.
export function layoutAtlas3d(nodes: AtlasNode[]) {
  return layoutAtlas(nodes, []).map(n => ({ ...n, x: (n.x - 600) * .6, z: (n.y - 400) * .6,
    y: /file|module/i.test(n.kind) ? 25 : /class|interface|enum/i.test(n.kind) ? 50 : 75,
    radius: /class|interface/i.test(n.kind) ? 13 : 9 }));
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
