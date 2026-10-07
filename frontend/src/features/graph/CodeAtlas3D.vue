<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { atlasEdgeKey, type AtlasEdge, type AtlasNode } from '@/api/codeAtlas';
import { seedAtlas3d, type SpatialNode } from './atlasLayout3d';
import { createAtlasRenderer } from './atlasRenderer';
import { boxesOverlap, projectAtlasPoint, type ScreenBox } from './atlasProjection';
import AtlasSpatialTree from './AtlasSpatialTree.vue';
import { buildSpatialTree, indexSpatialTree, spatialSymbolKey, type SpatialTreeEntry } from './atlasSpatialTree';

const props = defineProps<{ nodes: AtlasNode[]; edges: AtlasEdge[]; layoutEdges?: AtlasEdge[]; selectedId?: string; connected: Set<string>; autoRotate: boolean; forceCompatibility?: boolean; highlighted?: Set<string> }>();
const emit = defineEmits<{
  select: [node: AtlasNode]; expand: [node: AtlasNode]; edge: [edge: AtlasEdge];
  'clear-selection': [];
  ready: [engine: 'webgl' | 'compatible']; degraded: [reason: string]; unavailable: [reason?: string];
}>();
const host = ref<HTMLDivElement>(), softwareMode = ref(false), arranging = ref(false), layoutError = ref(false), hovered = ref('');
const legend = ref<HTMLDetailsElement>(), compact = ref(false);
const focusedEntryId = ref<string | null>(null);
const expandedEntries = shallowRef(new Set<string>()), treeContainer = ref<HTMLDivElement>();
const navigationTree = computed(() => buildSpatialTree(props.nodes));
const navigationIndex = computed(() => indexSpatialTree(navigationTree.value));
const focusedEntry = computed(() => navigationIndex.value.get(focusedEntryId.value ?? '')?.entry);
const positions = shallowRef<SpatialNode[]>([]);
const byId = computed(() => new Map(positions.value.map(n => [n.id, n])));
const labels = shallowRef<{ node: SpatialNode; x: number; y: number }[]>([]);
const relationLabels = shallowRef<{ key: string; edge: AtlasEdge; x: number; y: number; color: string }[]>([]);
const softwareNodes = shallowRef<{ node: SpatialNode; x: number; y: number; size: number; opacity: number; depth: number }[]>([]);
const softwareEdges = shallowRef<{ key: string; edge: AtlasEdge; points: string; color: string; opacity: number; active: boolean }[]>([]);
const activeId = computed(() => props.selectedId || hovered.value);
const scopeNodes = computed(() => new Set(focusedEntry.value?.nodeIds ?? []));
const hasFocus = computed(() => !!activeId.value || focusedEntryId.value !== null);
const activeNodes = computed(() => {
  const ids = new Set(props.selectedId ? props.connected : []);
  if (activeId.value) ids.add(activeId.value);
  for (const edge of props.edges) {
    if ((!props.selectedId && (edge.source === hovered.value || edge.target === hovered.value)) || props.highlighted?.has(atlasEdgeKey(edge))) { ids.add(edge.source); ids.add(edge.target); }
  }
  return ids;
});
const edgeActive = (e: AtlasEdge) => !!props.highlighted?.has(atlasEdgeKey(e)) || e.source === activeId.value || e.target === activeId.value || scopeNodes.value.has(e.source) || scopeNodes.value.has(e.target);
const edgeColor = (e: AtlasEdge) => {
  if (props.highlighted?.has(atlasEdgeKey(e))) return '#c5a3ff';
  if (e.source === activeId.value) return '#f2bc75';
  if (e.target === activeId.value) return '#5bd4f0';
  if (scopeNodes.value.has(e.source)) return scopeNodes.value.has(e.target) ? byId.value.get(e.source)!.color : '#f2bc75';
  return scopeNodes.value.has(e.target) ? '#5bd4f0' : '#647eaa';
};
const edgeOpacity = (e: AtlasEdge) => edgeActive(e) ? .88 : hasFocus.value ? .055 : .22;
const nodeActive = (id: string) => !hasFocus.value || scopeNodes.value.has(id) || activeNodes.value.has(id) || id === hovered.value;
const navigationActiveIds = computed(() => hasFocus.value ? new Set([...scopeNodes.value, ...activeNodes.value]) : null);
const vector = (n: { x: number; y: number; z: number }) => new THREE.Vector3(n.x, n.y, n.z);
const curves = computed(() => props.edges.flatMap((edge, i) => {
  const a = byId.value.get(edge.source), b = byId.value.get(edge.target);
  if (!a || !b) return [];
  const start = vector(a), end = vector(b), middle = start.clone().lerp(end, .5);
  if (a === b) { middle.x += 38; middle.y += 42; end.z += 2; }
  else middle.y += start.distanceTo(end) * .035;
  const curve = new THREE.QuadraticBezierCurve3(start, middle, end);
  return [{ key: atlasEdgeKey(edge) + i, edge, curve, points: curve.getPoints(a === b ? 16 : 8) }];
}));

let renderer: THREE.WebGLRenderer | undefined, controls: OrbitControls | undefined, worker: Worker | undefined;
const scene = new THREE.Scene(), graph = new THREE.Group(), edgeGroup = new THREE.Group();
const camera = new THREE.PerspectiveCamera(45, 1, 1, 30000), raycaster = new THREE.Raycaster();
const meshes = new Map<string, THREE.Mesh<THREE.SphereGeometry, THREE.MeshBasicMaterial>>();
const glows = new Map<string, THREE.Sprite>();
const projected = new Map<string, { x: number; y: number; size: number; depth: number }>();
let glowTexture: THREE.CanvasTexture | undefined;
let pulses: { mesh: THREE.Mesh; curve: THREE.QuadraticBezierCurve3 }[] = [];
let frame = 0, lastTime = 0, sceneDirty = true, stopped = false, reduced = false, interacted = false;
let observer: ResizeObserver | undefined, motionQuery: MediaQueryList | undefined;
let down: { x: number; y: number } | undefined;
let viewport = { width: 1, height: 1 };
let focus: { eye: THREE.Vector3; target: THREE.Vector3 } | undefined;
let overviewCamera: typeof focus, focusTimer: ReturnType<typeof setTimeout> | undefined;

function dispose(group: THREE.Group) {
  const geometries = new Set<THREE.BufferGeometry>(), materials = new Set<THREE.Material>();
  group.traverse(object => { const mesh = object as THREE.Mesh; if (mesh.geometry) geometries.add(mesh.geometry); if (mesh.material) (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).forEach(m => materials.add(m)); });
  geometries.forEach(g => g.dispose()); materials.forEach(m => m.dispose()); group.clear();
}
function getGlowTexture() {
  if (glowTexture) return glowTexture;
  const canvas = document.createElement('canvas'); canvas.width = 64; canvas.height = 64;
  const ctx = canvas.getContext('2d'); if (!ctx) throw new Error('无法创建节点光晕');
  const gradient = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
  gradient.addColorStop(0, '#ffffff'); gradient.addColorStop(.16, '#ffffff80'); gradient.addColorStop(.42, '#ffffff22'); gradient.addColorStop(1, '#ffffff00');
  ctx.fillStyle = gradient; ctx.fillRect(0, 0, 64, 64);
  glowTexture = new THREE.CanvasTexture(canvas); glowTexture.colorSpace = THREE.SRGBColorSpace; return glowTexture;
}
function fitOverview() {
  if (!controls) return;
  const bounds = new THREE.Box3().setFromPoints(positions.value.map(vector));
  const target = positions.value.length ? bounds.getCenter(new THREE.Vector3()) : new THREE.Vector3();
  const radius = Math.max(75, ...positions.value.map(n => vector(n).distanceTo(target) + n.radius));
  const angle = Math.min(camera.fov * Math.PI / 360, Math.atan(Math.tan(camera.fov * Math.PI / 360) * camera.aspect));
  const distance = radius / Math.sin(angle) * 1.08;
  camera.far = Math.max(30000, distance * 6); camera.updateProjectionMatrix(); controls.maxDistance = distance * 4;
  camera.position.copy(new THREE.Vector3(.18, .3, 1).normalize().multiplyScalar(distance).add(target));
  controls.target.copy(target); focus = undefined; overviewCamera = undefined; controls.update(); sceneDirty = true;
}
function home() {
  cancelFocus(); focusedEntryId.value = null; hovered.value = ''; overviewCamera = undefined;
  emit('clear-selection'); fitOverview();
  if (compact.value && legend.value) legend.value.open = false;
}
function focusScope() { focusNodes(positions.value.filter(n => scopeNodes.value.has(n.id))); }
function toggleEntry(id: string) {
  const next = new Set(expandedEntries.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  expandedEntries.value = next; sceneDirty = true;
}
function revealEntry(id: string, includeSelf = false) {
  const record = navigationIndex.value.get(id); if (!record) return;
  expandedEntries.value = new Set([...expandedEntries.value, ...record.ancestors, ...(includeSelf ? [id] : [])]);
  sceneDirty = true;
}
function chooseScope(entry: SpatialTreeEntry) {
  revealEntry(entry.id, true);
  if (focusedEntryId.value === entry.id) { home(); return; }
  cancelFocus(); hovered.value = ''; focusedEntryId.value = entry.id; overviewCamera = undefined; interacted = false;
  emit('clear-selection'); focusScope();
  if (compact.value && legend.value) legend.value.open = false;
}
function revealSelected() {
  if (!props.selectedId) return;
  revealEntry(spatialSymbolKey(props.selectedId));
  void nextTick(() => {
    const container = treeContainer.value, row = container?.querySelector<HTMLElement>('[data-current-symbol]');
    if (!row || !container || !legend.value?.open) return;
    const bounds = container.getBoundingClientRect(), item = row.getBoundingClientRect();
    if (item.top < bounds.top) container.scrollTop -= bounds.top - item.top;
    else if (item.bottom > bounds.bottom) container.scrollTop += item.bottom - bounds.bottom;
  });
}
function zoomBy(factor: number) {
  if (!controls) return;
  const offset = camera.position.clone().sub(controls.target);
  offset.setLength(THREE.MathUtils.clamp(offset.length() * factor, controls.minDistance, controls.maxDistance));
  focus = undefined; interacted = true; camera.position.copy(controls.target).add(offset); controls.update(); sceneDirty = true;
}
defineExpose({ home, zoomBy });
function rebuild(fit = true) {
  dispose(graph); meshes.clear(); glows.clear();
  if (renderer) {
    const geometry = new THREE.SphereGeometry(1, 12, 8);
    for (const n of positions.value) {
      const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({ color: n.color, transparent: true }));
      mesh.position.copy(vector(n)); mesh.scale.setScalar(n.radius); meshes.set(n.id, mesh); graph.add(mesh);
      const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: getGlowTexture(), color: n.color, transparent: true, opacity: .55, blending: THREE.AdditiveBlending, depthWrite: false }));
      glow.position.copy(mesh.position); glow.scale.setScalar(n.radius * 7); glows.set(n.id, glow); graph.add(glow);
    }
  }
  highlight(); rebuildEdges(); if (fit) fitOverview(); sceneDirty = true;
}
function requestLayout() {
  worker?.terminate(); worker = undefined; arranging.value = false; layoutError.value = false;
  const edges = props.layoutEdges ?? props.edges;
  const firstLayout = positions.value.length === 0;
  positions.value = seedAtlas3d(props.nodes, edges); interacted = false;
  if (!navigationIndex.value.has(focusedEntryId.value ?? '')) focusedEntryId.value = null;
  expandedEntries.value = new Set([...expandedEntries.value].filter(id => navigationIndex.value.has(id)));
  revealSelected();
  rebuild(firstLayout || (!props.selectedId && focusedEntryId.value === null));
  if (focusedEntryId.value !== null) focusScope();
  if (!props.nodes.length) return;
  try {
    const current = new Worker(new URL('./atlasLayout3d.worker.ts', import.meta.url), { type: 'module' });
    worker = current; arranging.value = true;
    current.onmessage = (event: MessageEvent<SpatialNode[]>) => {
      if (stopped || worker !== current) return;
      positions.value = event.data; arranging.value = false; current.terminate(); worker = undefined;
      rebuild(!interacted && !props.selectedId && focusedEntryId.value === null);
      if (!interacted) { if (props.selectedId) focusSelected(); else if (focusedEntryId.value !== null) focusScope(); }
    };
    current.onerror = () => { if (worker !== current) return; arranging.value = false; layoutError.value = true; current.terminate(); worker = undefined; };
    // Vue proxies cannot be structured-cloned. Copy the plain graph records first.
    current.postMessage({ nodes: props.nodes.map(n => ({ ...n })), edges: edges.map(e => ({ source: e.source, target: e.target, kind: e.kind, count: e.count })) });
  } catch { worker?.terminate(); worker = undefined; arranging.value = false; layoutError.value = true; }
}
function rebuildEdges() {
  sceneDirty = true; dispose(edgeGroup); pulses = [];
  if (!renderer) return;
  const buckets = new Map<string, { vertices: number[]; colors: number[]; edges: AtlasEdge[]; active: boolean; dashed: boolean }>();
  let arrows = 0;
  for (const record of curves.value) {
    const active = edgeActive(record.edge), dashed = /inherit|extend|implement/i.test(record.edge.kind), key = `${active}:${dashed}`;
    if (!buckets.has(key)) buckets.set(key, { vertices: [], colors: [], edges: [], active, dashed });
    const bucket = buckets.get(key)!, color = new THREE.Color(edgeColor(record.edge));
    for (let i = 1; i < record.points.length; i++) {
      bucket.vertices.push(...record.points[i - 1].toArray(), ...record.points[i].toArray());
      bucket.colors.push(...color.toArray(), ...color.toArray()); bucket.edges.push(record.edge);
    }
    if (active && arrows++ < 100) {
      const arrow = new THREE.ArrowHelper(record.curve.getTangent(.78), record.curve.getPoint(.78), 8, color.getHex(), 5, 2.4); edgeGroup.add(arrow);
      if (pulses.length < 24) {
        const particle = new THREE.Mesh(new THREE.SphereGeometry(1.5, 6, 4), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .95 }));
        particle.position.copy(record.curve.getPoint(.45)); edgeGroup.add(particle); pulses.push({ mesh: particle, curve: record.curve });
      }
    }
  }
  for (const bucket of buckets.values()) {
    const geometry = new THREE.BufferGeometry(); geometry.setAttribute('position', new THREE.Float32BufferAttribute(bucket.vertices, 3)); geometry.setAttribute('color', new THREE.Float32BufferAttribute(bucket.colors, 3));
    const options = { vertexColors: true, transparent: true, opacity: bucket.active ? .88 : hasFocus.value ? .055 : .22, depthWrite: false };
    const material = bucket.dashed ? new THREE.LineDashedMaterial({ ...options, dashSize: 5, gapSize: 3 }) : new THREE.LineBasicMaterial(options);
    const lines = new THREE.LineSegments(geometry, material); lines.computeLineDistances(); lines.userData.edges = bucket.edges; edgeGroup.add(lines);
  }
}
function highlight() {
  sceneDirty = true;
  for (const node of positions.value) {
    const active = nodeActive(node.id), hot = node.id === activeId.value || node.id === hovered.value;
    const mesh = meshes.get(node.id), glow = glows.get(node.id);
    if (mesh) { mesh.material.opacity = active ? 1 : .16; mesh.material.color.set(hot ? '#e9f4ff' : node.color); }
    if (glow) glow.material.opacity = hot ? .95 : active ? .48 : .035;
  }
}
function focusSelected() {
  if (!byId.value.has(props.selectedId ?? '') || !controls) return;
  focusNodes(positions.value.filter(n => n.id === props.selectedId || props.connected.has(n.id)));
}
function focusNodes(nodes: SpatialNode[]) {
  if (!nodes.length || !controls) return;
  const bounds = new THREE.Box3().setFromPoints(nodes.map(vector)), target = bounds.getCenter(new THREE.Vector3());
  const radius = Math.max(65, bounds.getSize(new THREE.Vector3()).length() / 2 + 30);
  const angle = Math.min(camera.fov * Math.PI / 360, Math.atan(Math.tan(camera.fov * Math.PI / 360) * camera.aspect));
  const direction = camera.position.clone().sub(controls.target).normalize();
  focus = { target, eye: target.clone().add(direction.multiplyScalar(radius / Math.sin(angle) * 1.12)) };
  if (reduced) { camera.position.copy(focus.eye); controls.target.copy(focus.target); focus = undefined; sceneDirty = true; }
}
function pick(event: PointerEvent | MouseEvent) {
  if (!host.value) return;
  const rect = host.value.getBoundingClientRect(), x = event.clientX - rect.left, y = event.clientY - rect.top;
  let closest: { id: string; distance: number } | undefined;
  for (const [id, point] of projected) {
    const distance = Math.hypot(point.x - x, point.y - y);
    if (distance <= Math.max(8, point.size / 2 + 4) && (!closest || distance < closest.distance)) closest = { id, distance };
  }
  return closest?.id;
}
function cancelFocus() { focus = undefined; clearTimeout(focusTimer); }
function pointerDown(event: PointerEvent) { down = { x: event.clientX, y: event.clientY }; cancelFocus(); }
function pointerUp(event: PointerEvent) {
  if (!down || event.button !== 0 || Math.hypot(event.clientX - down.x, event.clientY - down.y) > 5) { down = undefined; return; }
  down = undefined;
  const node = byId.value.get(pick(event) ?? '');
  if (node) { emit('select', node); return; }
  if (!renderer) return;
  const rect = host.value!.getBoundingClientRect();
  raycaster.setFromCamera(new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, 1 - (event.clientY - rect.top) / rect.height * 2), camera);
  raycaster.params.Line.threshold = 2;
  const hit = raycaster.intersectObjects(edgeGroup.children, false).find(item => item.object.userData.edges);
  if (hit) { const edge = hit.object.userData.edges[Math.floor((hit.index ?? 0) / 2)] as AtlasEdge | undefined; if (edge) emit('edge', edge); }
}
function pointerMove(event: PointerEvent) { if (!down) hovered.value = pick(event) ?? ''; }
function doubleClick(event: MouseEvent) { cancelFocus(); const node = byId.value.get(pick(event) ?? ''); if (node) emit('expand', node); }
function updateProjection() {
  projected.clear();
  const { width, height } = viewport;
  for (const node of positions.value) {
    const point = projectAtlasPoint(vector(node), camera, width, height); if (!point) continue;
    const hot = node.id === activeId.value || node.id === hovered.value;
    const size = THREE.MathUtils.clamp(node.radius * 2 * point.pixelsPerUnit * (hot ? 1.25 : 1), 4, hot ? 26 : 19);
    projected.set(node.id, { ...point, size });
    const worldSize = size / point.pixelsPerUnit;
    meshes.get(node.id)?.scale.setScalar(worldSize / 2); glows.get(node.id)?.scale.setScalar(worldSize * (hot ? 4.8 : 3.6));
  }
  const occupied: ScreenBox[] = [{ left: 8, right: (legend.value?.offsetLeft ?? 12) + (legend.value?.offsetWidth ?? 210) + 8, top: 6, bottom: (legend.value?.offsetTop ?? 10) + (legend.value?.offsetHeight ?? 45) + 8 }];
  const nodeBoxes = [...projected.values()].map(p => ({ left: p.x - p.size / 2, right: p.x + p.size / 2, top: p.y - p.size / 2, bottom: p.y + p.size / 2 }));
  const priority = (n: SpatialNode) => n.id === hovered.value ? 1e6 : n.id === props.selectedId ? 9e5 : activeNodes.value.has(n.id) ? 1e4 + n.degree : n.degree;
  const result: typeof labels.value = [];
  for (const node of [...positions.value].sort((a, b) => priority(b) - priority(a))) {
    const point = projected.get(node.id); if (!point) continue;
    if (hasFocus.value && !nodeActive(node.id)) continue;
    if (!hasFocus.value && result.length >= (width < 600 ? 3 : 7)) break;
    if (result.length >= 16) break;
    const labelWidth = Math.min(172, Math.max(65, node.label.length * 7 + 18));
    for (const sign of [1, -1]) {
      const x = point.x, y = point.y + (sign === 1 ? point.size / 2 + 6 : -point.size / 2 - 29);
      const box = { left: x - labelWidth / 2, right: x + labelWidth / 2, top: y, bottom: y + 24 };
      if (box.left < 10 || box.right > width - 10 || y < 12 || box.bottom > height - (props.selectedId ? 170 : 65)) continue;
      if (occupied.some(b => boxesOverlap(box, b)) || nodeBoxes.some(b => boxesOverlap(box, b, 1))) continue;
      occupied.push(box); result.push({ node, x, y }); break;
    }
  }
  labels.value = result;
  relationLabels.value = curves.value.filter(r => edgeActive(r.edge)).slice(0, 20).flatMap(r => {
    const p = projectAtlasPoint(r.curve.getPoint(.55), camera, width, height); if (!p) return [];
    const box = { left: p.x - 48, right: p.x + 48, top: p.y - 12, bottom: p.y + 12 };
    if (box.left < 15 || box.right > width - 15 || box.top < 45 || box.bottom > height - 175 || occupied.some(b => boxesOverlap(b, box))) return [];
    occupied.push(box); return [{ key: r.key, edge: r.edge, x: p.x, y: p.y, color: edgeColor(r.edge) }];
  });
  if (softwareMode.value) {
    softwareNodes.value = positions.value.flatMap(node => { const p = projected.get(node.id); return !p || p.x < 0 || p.x > width || p.y < 0 || p.y > height ? [] : [{ node, ...p, opacity: nodeActive(node.id) ? 1 : .16 }]; }).sort((a, b) => b.depth - a.depth);
    softwareEdges.value = curves.value.flatMap(r => {
      const points = r.points.map(p => projectAtlasPoint(p, camera, width, height)); if (points.some(p => !p)) return [];
      return [{ key: r.key, edge: r.edge, points: points.map(p => `${p!.x.toFixed(1)},${p!.y.toFixed(1)}`).join(' '), color: edgeColor(r.edge), opacity: edgeOpacity(r.edge), active: edgeActive(r.edge) }];
    });
  }
}
function animate(time: number) {
  if (stopped) return; frame = requestAnimationFrame(animate);
  if (document.hidden || !controls || time - lastTime < 32) return;
  const delta = Math.min(.1, (time - lastTime) / 1000); lastTime = time;
  controls.autoRotate = props.autoRotate && !reduced && !hasFocus.value;
  const focusing = !!focus;
  if (focus) { camera.position.lerp(focus.eye, .12); controls.target.lerp(focus.target, .12); if (camera.position.distanceTo(focus.eye) < .5) focus = undefined; }
  const moved = controls.update(delta), dirty = moved || sceneDirty || focusing;
  if (dirty) { camera.updateMatrixWorld(true); updateProjection(); sceneDirty = false; }
  const movingParticles = !reduced && pulses.length > 0;
  if (renderer && (dirty || movingParticles)) {
    if (movingParticles) pulses.forEach((p, i) => p.mesh.position.copy(p.curve.getPoint((time * .00015 + i * .17) % 1)));
    renderer.render(scene, camera);
  }
}
function motionChanged() { reduced = motionQuery?.matches ?? false; sceneDirty = true; }
function configureControls(element: HTMLElement) {
  controls?.dispose(); controls = new OrbitControls(camera, element); controls.enableDamping = true; controls.autoRotateSpeed = .35; controls.minDistance = 35;
  controls.addEventListener('start', () => { interacted = true; cancelFocus(); }); controls.addEventListener('change', () => { sceneDirty = true; });
}
function resizeScene() {
  if (!host.value) return; const width = host.value.clientWidth, height = host.value.clientHeight; if (!width || !height) return;
  viewport = { width, height }; compact.value = width < 600; renderer?.setSize(width, height); camera.aspect = width / height; camera.updateProjectionMatrix(); sceneDirty = true;
}
function disposeWebgl() {
  dispose(graph); dispose(edgeGroup); meshes.clear(); glows.clear(); pulses = [];
  glowTexture?.dispose(); glowTexture = undefined;
  renderer?.domElement.removeEventListener('webglcontextlost', contextLost); renderer?.dispose(); renderer?.forceContextLoss(); renderer?.domElement.remove(); renderer = undefined;
}
function enableCompatibility(reason: string) {
  disposeWebgl(); softwareMode.value = true; configureControls(host.value!); resizeScene(); fitOverview(); if (focusedEntryId.value !== null) focusScope(); sceneDirty = true;
  emit('ready', 'compatible'); emit('degraded', reason);
}
function contextLost(event: Event) { event.preventDefault(); enableCompatibility('WebGL 上下文已丢失'); }
onMounted(() => {
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)'); motionChanged(); motionQuery.addEventListener('change', motionChanged);
  try {
    if (props.forceCompatibility) enableCompatibility('已选择兼容渲染');
    else {
      renderer = createAtlasRenderer(); renderer.setPixelRatio(Math.min(window.devicePixelRatio, props.nodes.length > 500 ? 1.25 : 1.75));
      renderer.setClearColor('#070e20', 0); host.value!.prepend(renderer.domElement); renderer.domElement.addEventListener('webglcontextlost', contextLost);
      configureControls(renderer.domElement); scene.add(graph, edgeGroup); resizeScene(); emit('ready', 'webgl');
    }
    requestLayout();
  } catch (error) {
    try { enableCompatibility(error instanceof Error ? error.message : 'WebGL 初始化失败'); requestLayout(); }
    catch (fallbackError) { emit('unavailable', fallbackError instanceof Error ? fallbackError.message : '兼容 3D 初始化失败'); }
  }
  observer = new ResizeObserver(resizeScene); observer.observe(host.value!); frame = requestAnimationFrame(animate);
});
watch([() => props.nodes, () => props.layoutEdges], requestLayout);
watch(() => props.edges, rebuildEdges);
watch([activeId, hovered, activeNodes, scopeNodes, () => props.highlighted], () => { highlight(); rebuildEdges(); });
watch(() => props.selectedId, (id, previous) => {
  cancelFocus();
  if (!id && focusedEntryId.value !== null) { overviewCamera = undefined; focusScope(); return; }
  if (id) { focusedEntryId.value = null; revealSelected(); }
  if (id && !previous && controls) overviewCamera = { eye: camera.position.clone(), target: controls.target.clone() };
  if (!id && overviewCamera) { focus = overviewCamera; overviewCamera = undefined; if (reduced && controls) { camera.position.copy(focus.eye); controls.target.copy(focus.target); focus = undefined; sceneDirty = true; } }
  else if (id) focusTimer = setTimeout(focusSelected, 350);
});
onBeforeUnmount(() => { stopped = true; cancelAnimationFrame(frame); worker?.terminate(); observer?.disconnect(); clearTimeout(focusTimer); motionQuery?.removeEventListener('change', motionChanged); controls?.dispose(); disposeWebgl(); });
</script>

<template>
  <div ref="host" class="atlas-three" :class="{ 'has-selection': selectedId, 'has-hover': hovered }" :data-engine="softwareMode ? 'compatible' : 'webgl'" :data-layout="arranging ? 'pending' : 'ready'" aria-label="三维代码图谱：拖动旋转，滚轮缩放，右键拖动平移" @contextmenu.prevent @pointerdown="pointerDown" @pointerup="pointerUp" @pointermove="pointerMove" @pointerleave="hovered = ''" @dblclick="doubleClick">
    <div v-if="softwareMode" class="software-scene">
      <svg aria-hidden="true"><polyline v-for="edge in softwareEdges" :key="edge.key" :points="edge.points" :stroke="edge.color" :opacity="edge.opacity" :stroke-width="edge.active ? 1.6 : .8" :stroke-dasharray="/inherit|extend|implement/i.test(edge.edge.kind) ? '5 3' : undefined" @pointerdown.stop @pointerup.stop @click.stop="emit('edge', edge.edge)" /></svg>
      <button v-for="(item, index) in softwareNodes" :key="item.node.id" class="software-node" :class="{ selected: selectedId === item.node.id }" :style="{ left: item.x + 'px', top: item.y + 'px', opacity: item.opacity, zIndex: index + 1, '--node-size': item.size + 'px', '--node-color': item.node.color }" :title="item.node.label + ' · ' + item.node.kind" :aria-label="item.node.label + ' · ' + item.node.kind" @pointerdown.stop="cancelFocus" @pointerup.stop @focus="hovered = item.node.id" @blur="hovered = ''" @mouseenter="hovered = item.node.id" @mouseleave="hovered = ''" @click.stop="emit('select', item.node)" @dblclick.stop="emit('expand', item.node)"><span /></button>
    </div>
    <details ref="legend" class="spatial-legend" :open="!compact" aria-label="代码导航" @toggle="sceneDirty = true" @pointerdown.stop @pointerup.stop @pointermove.stop @wheel.stop @dblclick.stop>
      <summary class="legend-heading">代码 <span :title="focusedEntry?.filePath || focusedEntry?.name">{{ focusedEntry?.name || navigationTree.length + ' 个模块' }}</span></summary>
      <button class="legend-all" :aria-pressed="focusedEntryId === null && !selectedId" @click.stop="home">全部代码 <small>{{ positions.length }}</small></button>
      <div ref="treeContainer" class="legend-modules">
        <AtlasSpatialTree :entries="navigationTree" :expanded="expandedEntries" :focused-id="focusedEntryId" :selected-id="selectedId" :active-ids="navigationActiveIds" @toggle="toggleEntry" @focus="chooseScope" @select="emit('select', $event)"/>
      </div>
      <div class="legend-note" role="status">{{ focusedEntryId === null ? '展开目录 · 点击文件或符号定位' : '再次点击当前项返回全部' }}</div>
      <div class="legend-note">节点大小表示关联数量</div>
    </details>
    <div v-if="hasFocus" class="flow-key"><span><i class="incoming"/>入向</span><span><i class="outgoing"/>出向</span></div>
    <div v-if="arranging || layoutError" class="layout-status" role="status">{{ arranging ? '正在排列代码关系…' : '已使用模块布局' }}</div>
    <div class="relation-labels"><button v-for="label in relationLabels" :key="label.key" :style="{ left: label.x + 'px', top: label.y + 'px', color: label.color }" :title="label.edge.sourceLines?.map(n => 'L' + n).join(', ')" @pointerdown.stop @pointerup.stop @click.stop="emit('edge', label.edge)">{{ label.edge.kind }}{{ label.edge.count > 1 ? ' ×' + label.edge.count : '' }}</button></div>
    <div class="three-labels"><button v-for="label in labels" :key="label.node.id" :data-spatial-node="label.node.id" :style="{ left: label.x + 'px', top: label.y + 'px', '--node-color': label.node.color }" :title="(label.node.qualifiedName || label.node.label) + ' · ' + label.node.kind + ' · ' + label.node.filePath" :class="{ selected: selectedId === label.node.id }" @focus="hovered = label.node.id" @blur="hovered = ''" @pointerdown.stop @pointerup.stop @click.stop="emit('select', label.node)" @dblclick.stop="emit('expand', label.node)"><i/>{{ label.node.label }}</button></div>
  </div>
</template>

<style scoped>
.atlas-three{position:absolute;inset:0;overflow:hidden;isolation:isolate;touch-action:none;background:radial-gradient(ellipse at 54% 45%,#12213a 0,#0b1428 43%,#070e20 100%);color:#dce9ff}
.atlas-three :deep(canvas){display:block;width:100%;height:100%;touch-action:none}.atlas-three.has-hover :deep(canvas){cursor:pointer}
.three-labels,.relation-labels{position:absolute;inset:0;pointer-events:none;z-index:1}.three-labels button{position:absolute;transform:translateX(-50%);max-width:172px;display:flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:4px 7px;border:1px solid transparent;border-radius:5px;background:#0a1529c9;color:#b9cce6;font:11px Consolas,'Microsoft YaHei',monospace;cursor:pointer;pointer-events:auto}.three-labels button i{width:4px;height:4px;border-radius:50%;background:var(--node-color);flex:none}.three-labels button:hover,.three-labels .selected{color:#edf5ff;background:#152842;border-color:#47668e}.three-labels button:focus-visible,.software-node:focus-visible,.relation-labels button:focus-visible{outline:2px solid #8fbaff;outline-offset:3px}.relation-labels button{position:absolute;transform:translate(-50%,-50%);max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:1px solid #354c6a66;background:#0c182bdc;padding:3px 6px;border-radius:4px;font:11px Consolas,monospace;pointer-events:auto;cursor:pointer}
.software-scene{position:absolute;inset:0;z-index:0}.software-scene svg{position:absolute;inset:0;width:100%;height:100%;overflow:hidden;pointer-events:none}.software-scene polyline{fill:none;stroke-linejoin:round;pointer-events:stroke;cursor:pointer}.software-node{position:absolute;width:22px;height:22px;padding:0;display:grid;place-items:center;transform:translate(-50%,-50%);border:0;background:none;cursor:pointer}.software-node span{width:var(--node-size);height:var(--node-size);border-radius:50%;background:var(--node-color);box-shadow:0 0 7px var(--node-color),0 0 19px color-mix(in srgb,var(--node-color) 30%,transparent);pointer-events:none}.software-node.selected span{background:#eff7ff;outline:1px solid var(--node-color);outline-offset:4px}
.spatial-legend{position:absolute;top:20px;left:22px;z-index:2;width:260px;max-width:260px;max-height:calc(100% - 85px);overflow-y:auto;padding:10px 12px;margin:-10px -12px;border-radius:7px;background:#0a1425dc;pointer-events:auto;touch-action:pan-y;font:12px 'Segoe UI','Microsoft YaHei',sans-serif}
.legend-heading{display:flex;align-items:center;gap:8px;list-style:none;white-space:nowrap;cursor:pointer;color:#c8d9ef;font-weight:600}.legend-heading>span{min-width:0;overflow:hidden;text-overflow:ellipsis;font:11px Consolas,monospace;color:#8196b6}.legend-heading::-webkit-details-marker{display:none}.legend-heading::after{content:'';flex:none;width:5px;height:5px;border-right:1px solid #8196b6;border-bottom:1px solid #8196b6;transform:rotate(45deg);margin-top:-3px}.spatial-legend[open] .legend-heading{margin-bottom:12px}.spatial-legend:not([open]) .legend-heading::after{transform:rotate(-45deg);margin-top:0}.legend-heading:focus-visible{outline:2px solid #8fbaff;outline-offset:4px}
.legend-modules{max-height:min(380px,48vh);overflow-y:auto;overscroll-behavior:contain;padding:2px;scrollbar-width:thin;scrollbar-color:#314665 transparent}.spatial-legend .legend-all{display:flex;align-items:center;justify-content:space-between;width:100%;margin-bottom:6px;border:1px solid transparent;border-radius:4px;background:transparent;padding:6px;text-align:left;color:#acbed8;font:inherit;cursor:pointer}.spatial-legend .legend-all:hover{background:#172944;color:#eef5ff}.spatial-legend .legend-all small{font:11px Consolas,monospace;color:#8196b6}.spatial-legend .legend-all[aria-pressed=true]{color:#eef5ff}.spatial-legend button:focus-visible{outline:2px solid #8fbaff;outline-offset:-2px}.legend-note{font-size:11px;line-height:1.6;color:#8196b6;margin-top:8px}
.flow-key{position:absolute;right:20px;top:20px;display:flex;gap:15px;pointer-events:none;color:#aebfd8;font-size:11px}.flow-key span{display:flex;gap:6px;align-items:center}.flow-key i{width:6px;height:6px;border-radius:50%;flex:none}.incoming{background:#5bd4f0}.outgoing{background:#f2bc75}.layout-status{position:absolute;top:48px;right:20px;color:#98aecd;font-size:11px;pointer-events:none}
@media(max-width:600px){.spatial-legend{left:14px;top:16px;width:240px;max-width:calc(100% - 28px)}.spatial-legend:not([open]){width:170px}.legend-modules{max-height:210px}.flow-key{right:14px;top:16px;gap:9px}.layout-status{right:14px;top:40px}}
</style>
