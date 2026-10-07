import { forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY, forceZ } from 'd3-force-3d';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';

export interface SpatialNode extends AtlasNode {
  x: number; y: number; z: number; radius: number; color: string; degree: number;
  cx: number; cy: number; cz: number;
}
export function atlasModuleColor(module: string) {
  let hash = 2166136261;
  for (const char of module) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  return `hsl(${(hash >>> 0) % 360}, 68%, 68%)`;
}

// Deterministic seeds provide an immediate view while the worker settles.
export function seedAtlas3d(nodes: AtlasNode[], edges: AtlasEdge[] = []): SpatialNode[] {
  const sorted = [...nodes].sort((a, b) => a.module.localeCompare(b.module) || a.id.localeCompare(b.id));
  const groups = [...new Set(sorted.map(n => n.module))];
  const counts = new Map<string, number>(), indices = new Map<string, number>(), degrees = new Map<string, Set<string>>();
  for (const n of sorted) counts.set(n.module, (counts.get(n.module) ?? 0) + 1);
  const ids = new Set(nodes.map(n => n.id));
  for (const e of edges) {
    if (!ids.has(e.source) || !ids.has(e.target) || e.source === e.target) continue;
    if (!degrees.has(e.source)) degrees.set(e.source, new Set());
    if (!degrees.has(e.target)) degrees.set(e.target, new Set());
    degrees.get(e.source)!.add(e.target); degrees.get(e.target)!.add(e.source);
  }
  const centers = new Map(groups.map((g, i) => {
    const theta = i * 2.399963229728653, radius = groups.length === 1 ? 0 : 150 * Math.sqrt(i + 1);
    return [g, { x: Math.cos(theta) * radius, y: Math.sin(i * 1.7) * radius * .34, z: Math.sin(theta) * radius }];
  }));
  return sorted.map(node => {
    const i = indices.get(node.module) ?? 0; indices.set(node.module, i + 1);
    const count = counts.get(node.module)!;
    const theta = i * 2.399963229728653, height = 1 - 2 * (i + .5) / count;
    const ring = Math.sqrt(1 - height * height), spread = count === 1 ? 0 : 27 * Math.cbrt(count);
    const center = centers.get(node.module)!, degree = degrees.get(node.id)?.size ?? 0;
    return { ...node, x: center.x + Math.cos(theta) * ring * spread,
      y: center.y + height * spread, z: center.z + Math.sin(theta) * ring * spread,
      cx: center.x, cy: center.y, cz: center.z, degree,
      radius: node.kind === 'MODULE' ? 9 : Math.min(10, 3.5 + Math.sqrt(degree) * .95), color: atlasModuleColor(node.module) };
  });
}

// The worker uses octree repulsion, avoiding all-pairs calculations on the UI thread.
export function layoutAtlas3d(nodes: AtlasNode[], edges: AtlasEdge[] = []): SpatialNode[] {
  const seeds = seedAtlas3d(nodes, edges), points = seeds.map(n => ({ ...n }));
  if (points.length < 2) return points.map(n => ({ ...n, x: 0, y: 0, z: 0 }));
  const byId = new Map(points.map(n => [n.id, n]));
  const unique = new Map<string, { source: string; target: string; distance: number }>();
  for (const edge of edges) {
    const a = byId.get(edge.source), b = byId.get(edge.target);
    if (!a || !b || a === b) continue;
    const [source, target] = [a.id, b.id].sort();
    unique.set(JSON.stringify([source, target]), { source, target,
      distance: a.filePath && a.filePath === b.filePath ? 45 : a.module === b.module ? 70 : 145 });
  }
  const links = [...unique].sort(([a], [b]) => a.localeCompare(b)).map(([, link]) => link);
  const simulation = forceSimulation(points, 3).stop().alphaDecay(.035).velocityDecay(.4)
    .force('charge', forceManyBody<SpatialNode>().strength(-125).distanceMax(750))
    .force('links', forceLink<SpatialNode, typeof links[number]>(links).id(n => n.id).distance(l => l.distance).strength(.22))
    .force('collide', forceCollide<SpatialNode>(n => n.radius + 9).strength(.9).iterations(2))
    .force('x', forceX<SpatialNode>(n => n.cx).strength(.024))
    .force('y', forceY<SpatialNode>(n => n.cy).strength(.024))
    .force('z', forceZ<SpatialNode>(n => n.cz).strength(.024));
  try { simulation.tick(180); } finally { simulation.stop(); }
  const center = { x: 0, y: 0, z: 0 };
  for (const n of points) { center.x += n.x / points.length; center.y += n.y / points.length; center.z += n.z / points.length; }
  return seeds.map(n => { const p = byId.get(n.id)!; return { ...n, x: p.x - center.x, y: p.y - center.y, z: p.z - center.z }; });
}
