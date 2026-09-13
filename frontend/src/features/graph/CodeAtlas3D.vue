<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';
import { atlasDistricts, layoutAtlas3d } from './atlasLayout3d';
import { createAtlasRenderer } from './atlasRenderer';
import { boxesOverlap, nodeScreenSize, projectAtlasPoint, type ScreenBox } from './atlasProjection';
import {
  atlasFileType, fileIconPath, fileIconUrl, folderIconPath, MODULE_ICON_SIZE, MODULE_RING_SIZE,
  type AtlasFileType,
} from './atlasFileType';

const props = defineProps<{ nodes: AtlasNode[]; edges: AtlasEdge[]; selectedId?: string; connected: Set<string>; autoRotate: boolean; forceCompatibility?: boolean }>();
const emit = defineEmits<{
  select: [node: AtlasNode]; expand: [node: AtlasNode];
  ready: [engine: 'webgl' | 'compatible']; degraded: [reason: string]; unavailable: [reason?: string];
}>();
const host = ref<HTMLDivElement>();
const labels = ref<{ id: string; label: string; title: string; x: number; y: number; color: string }[]>([]);
const softwareMode = ref(false);
const softwareNodes = ref<{
  id: string; node: AtlasNode; icon: string; x: number; y: number;
  size: number; opacity: number; zIndex: number; color: string;
}[]>([]);
const softwareEdges = ref<{ key: string; points: string; color: string; opacity: number }[]>([]);
const softwareDistricts = ref<{ module: string; points: string; color: string }[]>([]);
const hovered = ref('');
const positions = computed(() => layoutAtlas3d(props.nodes));
const districts = computed(() => atlasDistricts(positions.value));
let renderer: THREE.WebGLRenderer | undefined, controls: OrbitControls | undefined;
const scene = new THREE.Scene(), graph = new THREE.Group(), edgeGroup = new THREE.Group(), districtGroup = new THREE.Group();
const camera = new THREE.PerspectiveCamera(45, 1, 1, 30000);
const raycaster = new THREE.Raycaster();
const meshes = new Map<string, THREE.Mesh<THREE.SphereGeometry, THREE.MeshStandardMaterial>>();
const icons = new Map<string, THREE.Sprite>();
const rings = new Map<string, THREE.Sprite>();
const iconTextures = new Map<string, THREE.CanvasTexture>();
const ringTextures = new Map<string, THREE.CanvasTexture>();
function iconTexture(entry: AtlasFileType) {
  if (iconTextures.has(entry.id)) return iconTextures.get(entry.id)!;
  const canvas = document.createElement('canvas'); canvas.width = 192; canvas.height = 192;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas unavailable');
  ctx.scale(3, 3); ctx.lineJoin = 'round'; ctx.lineWidth = 3;
  ctx.fillStyle = '#ffffff'; ctx.strokeStyle = entry.color;
  const path = new Path2D(entry.id === 'folder' ? folderIconPath : fileIconPath);
  ctx.fill(path); ctx.stroke(path);
  ctx.stroke(new Path2D(entry.id === 'folder' ? 'M5 24H59' : 'M39 5V18H52'));
  if (entry.badge) {
    ctx.fillStyle = entry.color; ctx.font = 'bold 14px Arial,sans-serif'; ctx.textAlign = 'center';
    ctx.fillText(entry.badge, 33, 42);
  }
  const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
  iconTextures.set(entry.id, texture); return texture;
}
function ringTexture(color: string) {
  if (ringTextures.has(color)) return ringTextures.get(color)!;
  const canvas = document.createElement('canvas'); canvas.width = 256; canvas.height = 256;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas unavailable');
  ctx.strokeStyle = color; ctx.lineWidth = 7;
  ctx.beginPath(); ctx.arc(128, 128, 105, 0, Math.PI * 2); ctx.stroke();
  ctx.globalAlpha = .25; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.arc(128, 128, 114, 0, Math.PI * 2); ctx.stroke();
  const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
  ringTextures.set(color, texture); return texture;
}
let pulses: { mesh: THREE.Mesh; curve: THREE.QuadraticBezierCurve3 }[] = [];
let observer: ResizeObserver | undefined, frame = 0, lastTime = 0;
let down: { x: number; y: number } | undefined;
let focus: { target: THREE.Vector3; eye: THREE.Vector3 } | undefined;
let reduced = false, stopped = false;
let motionQuery: MediaQueryList | undefined;
let focusTimer: ReturnType<typeof setTimeout> | undefined;
let sceneDirty = true;
let edgeDirty = true;
let viewport = { width: 1, height: 1 };
let lastFocusId = '';
let overviewCamera: { eye: THREE.Vector3; target: THREE.Vector3 } | undefined;
const edgeColor = (source: string, target: string) => !props.selectedId ? '#5e8faf' : source === target ? '#6650a4' : target === props.selectedId ? '#08768a' : '#2467ae';
const projectedNodes = new Map<string, { x: number; y: number; size: number; depth: number }>();
const edgeCurves = computed(() => {
  const byId = new Map(positions.value.map(node => [node.id, node]));
  return props.edges.flatMap((edge, index) => {
    const a = byId.get(edge.source), b = byId.get(edge.target);
    if (!a || !b) return [];
    const start = vector(a), end = vector(b), middle = start.clone().lerp(end, .5);
    middle.y += Math.max(35, start.distanceTo(end) * .16);
    if (edge.source === edge.target) { middle.x += 85; end.z += 1; }
    const curve = new THREE.QuadraticBezierCurve3(start, middle, end);
    return [{ key: edge.source + '-' + edge.target + '-' + edge.kind + '-' + index, color: edgeColor(edge.source, edge.target), points: curve.getPoints(20) }];
  });
});
const vector = (n: { x: number; y: number; z: number }) => new THREE.Vector3(n.x, n.y, n.z);

function dispose(group: THREE.Object3D) {
  group.traverse(obj => {
    const item = obj as THREE.Mesh;
    item.geometry?.dispose();
    if (item.material) (Array.isArray(item.material) ? item.material : [item.material]).forEach(m => m.dispose());
  });
  group.clear();
}
function home() {
  if (!controls) return;
  const radius = Math.max(100, ...positions.value.map(n => vector(n).length() + n.radius));
  const fov = Math.min(camera.fov * Math.PI / 360, Math.atan(Math.tan(camera.fov * Math.PI / 360) * camera.aspect));
  const distance = radius / Math.sin(fov) * 1.15;
  camera.far = Math.max(30000, distance * 5); camera.updateProjectionMatrix();
  controls.maxDistance = distance * 4;
  focus = undefined;
  overviewCamera = undefined;
  camera.position.copy(new THREE.Vector3(.35, 1.15, 1).normalize().multiplyScalar(distance));
  controls.target.set(0, 0, 0); controls.update(); camera.updateMatrixWorld(true); sceneDirty = true;
}
function zoomBy(factor: number) {
  if (!controls) return;
  const offset = camera.position.clone().sub(controls.target);
  offset.setLength(THREE.MathUtils.clamp(offset.length() * factor, controls.minDistance, controls.maxDistance));
  focus = undefined; camera.position.copy(controls.target).add(offset); controls.update(); camera.updateMatrixWorld(true); sceneDirty = true;
}
defineExpose({ home, zoomBy });
function rebuild() {
  if (softwareMode.value) { home(); sceneDirty = true; return; }
  dispose(districtGroup);
  districts.value.forEach(d => {
    const plate = new THREE.Mesh(new THREE.BoxGeometry(d.width, 12, d.depth),
      new THREE.MeshStandardMaterial({ color: new THREE.Color(d.color).lerp(new THREE.Color('#ffffff'), .88), roughness: .8 }));
    plate.position.set(d.x, -8, d.z); districtGroup.add(plate);
    const border = new THREE.LineSegments(new THREE.EdgesGeometry(plate.geometry),
      new THREE.LineBasicMaterial({ color: d.color, transparent: true, opacity: .36 }));
    border.position.copy(plate.position); districtGroup.add(border);
  });
  dispose(graph); meshes.clear(); icons.clear(); rings.clear();
  positions.value.forEach(n => {
    const material = new THREE.MeshStandardMaterial({ color: n.color, metalness: .35, roughness: .24, emissive: n.color, emissiveIntensity: .12 });
    const mesh = new THREE.Mesh(new THREE.SphereGeometry(n.radius, 24, 16), material);
    mesh.position.copy(vector(n)); mesh.userData.nodeId = n.id;
    meshes.set(n.id, mesh); graph.add(mesh);
    // Keep a spherical picking target, but render a camera-facing file icon.
    mesh.material.visible = false;
    const icon = new THREE.Sprite(new THREE.SpriteMaterial({ map: iconTexture(atlasFileType(n)), depthTest: true, depthWrite: false }));
    icon.position.copy(mesh.position); icon.scale.setScalar(n.kind === 'MODULE' ? MODULE_ICON_SIZE : 38);
    icon.renderOrder = 2;
    graph.add(icon); icons.set(n.id, icon);
    if (n.kind === 'MODULE') {
      const ring = new THREE.Sprite(new THREE.SpriteMaterial({
        map: ringTexture(n.color), transparent: true, opacity: .72,
        depthTest: true, depthWrite: false, alphaTest: .04,
      }));
      ring.position.copy(mesh.position); ring.scale.setScalar(MODULE_RING_SIZE); ring.renderOrder = 1;
      graph.add(ring); rings.set(n.id, ring);
    }
  });
  rebuildEdges(); highlight(); home();
}
function rebuildEdges() {
  if (softwareMode.value) return;
  dispose(edgeGroup); pulses = [];
  props.edges.forEach((edge, i) => {
    const a = meshes.get(edge.source), b = meshes.get(edge.target);
    if (!a || !b) return;
    const start = a.position.clone(), end = b.position.clone();
    const middle = start.clone().lerp(end, .5);
    middle.y += Math.max(35, start.distanceTo(end) * .16);
    if (a === b) { middle.x += 85; end.z += 1; }
    const curve = new THREE.QuadraticBezierCurve3(start, middle, end);
    const color = new THREE.Color(edgeColor(edge.source, edge.target));
    edgeGroup.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(curve.getPoints(32)), new THREE.LineBasicMaterial({ color, transparent: true, opacity: props.selectedId ? .8 : .28 })));
    const arrow = new THREE.ArrowHelper(curve.getTangent(.8).normalize(), curve.getPoint(.8), 12, color.getHex(), 7, 4);
    edgeGroup.add(arrow);
    // Animate at most 160 actual edges; all requested edges still have lines.
    if (props.selectedId && i < 80) {
      const pulse = new THREE.Mesh(new THREE.SphereGeometry(2.4, 8, 6), new THREE.MeshBasicMaterial({ color }));
      pulse.position.copy(curve.getPoint(.5)); edgeGroup.add(pulse); pulses.push({ mesh: pulse, curve });
    }
  });
}
function highlight() {
  sceneDirty = true;
  if (softwareMode.value) return;
  meshes.forEach((mesh, id) => {
    const active = !props.selectedId || props.connected.has(id);
    mesh.material.color.set(active ? positions.value.find(n => n.id === id)!.color : '#a9bbc8');
    mesh.material.emissiveIntensity = id === props.selectedId ? .65 : .12;
    mesh.scale.setScalar(id === props.selectedId ? 1.3 : 1);
    const icon = icons.get(id);
    if (icon) {
      icon.material.opacity = active ? 1 : .18;
      const size = positions.value.find(n => n.id === id)!.kind === 'MODULE' ? MODULE_ICON_SIZE : 38;
      icon.scale.setScalar(size * (id === props.selectedId ? 1.3 : 1));
    }
    const ring = rings.get(id);
    if (ring) {
      ring.material.opacity = active ? (id === props.selectedId ? 1 : .72) : .2;
      ring.scale.setScalar(MODULE_RING_SIZE * (id === props.selectedId ? 1.3 : 1));
    }
  });
}
function focusSelected() {
  const layoutNode = positions.value.find(node => node.id === props.selectedId);
  if (!layoutNode || !controls) return;
  const neighborhood = positions.value.filter(n => n.id === props.selectedId || props.connected.has(n.id));
  const bounds = new THREE.Box3().setFromPoints(neighborhood.map(vector));
  const target = bounds.getCenter(new THREE.Vector3());
  const radius = Math.max(100, bounds.getSize(new THREE.Vector3()).length() / 2 + 70);
  const angle = Math.min(camera.fov * Math.PI / 360, Math.atan(Math.tan(camera.fov * Math.PI / 360) * camera.aspect));
  const offset = camera.position.clone().sub(controls.target).normalize().multiplyScalar(radius / Math.sin(angle));
  focus = { target, eye: target.clone().add(offset) };
  if (reduced) { camera.position.copy(focus.eye); controls.target.copy(focus.target); focus = undefined; sceneDirty = true; }
}
function pick(event: PointerEvent | MouseEvent) {
  if (!renderer) return;
  const rect = renderer.domElement.getBoundingClientRect();
  raycaster.setFromCamera(new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1), camera);
  const hit = raycaster.intersectObjects([...icons.values()], false)[0]?.object;
  return hit ? [...icons].find(([, icon]) => icon === hit)?.[0] : undefined;
}
function cancelFocus() { focus = undefined; clearTimeout(focusTimer); }
function pointerDown(event: PointerEvent) { down = { x: event.clientX, y: event.clientY }; cancelFocus(); }
function pointerUp(event: PointerEvent) {
  if (!down || event.button !== 0 || Math.hypot(event.clientX - down.x, event.clientY - down.y) > 5) { down = undefined; return; }
  down = undefined;
  const id = pick(event), node = props.nodes.find(n => n.id === id);
  if (node) emit('select', node);
}
function pointerMove(event: PointerEvent) { if (!softwareMode.value) hovered.value = pick(event) ?? ''; }
function doubleClick(event: MouseEvent) { clearTimeout(focusTimer); const node = props.nodes.find(n => n.id === pick(event)); if (node) emit('expand', node); }
function updateProjectedNodes() {
  projectedNodes.clear();
  const { width, height } = viewport;
  for (const node of positions.value) {
    const point = projectAtlasPoint(vector(node), camera, width, height);
    if (!point) continue;
    const worldSize = node.kind === 'MODULE' ? MODULE_RING_SIZE : 38;
    const size = nodeScreenSize(worldSize, point.pixelsPerUnit, node.id === props.selectedId);
    projectedNodes.set(node.id, { ...point, size });
    // Keep icon sizes consistent in GPU and compatibility renderers.
    if (renderer) {
      const icon = icons.get(node.id), ring = rings.get(node.id);
      const scale = size / point.pixelsPerUnit;
      if (icon) icon.scale.setScalar(node.kind === 'MODULE' ? scale * MODULE_ICON_SIZE / MODULE_RING_SIZE : scale);
      ring?.scale.setScalar(scale);
    }
  }
}
function updateLabels() {
  const { width, height } = viewport;
  const occupied: ScreenBox[] = [];
  const iconBoxes = [...projectedNodes].map(([id, n]) => ({ id, left: n.x - n.size / 2, right: n.x + n.size / 2, top: n.y - n.size / 2, bottom: n.y + n.size / 2 }));
  const result: typeof labels.value = [];
  const priority = (id: string) => id === props.selectedId ? 3 : id === hovered.value ? 2 : props.connected.has(id) ? 1 : 0;
  const order = [...positions.value].sort((a, b) => priority(b.id) - priority(a.id) ||
    (projectedNodes.get(a.id)?.depth ?? Infinity) - (projectedNodes.get(b.id)?.depth ?? Infinity));
  for (const n of order) {
    if (props.selectedId && !props.connected.has(n.id) && n.id !== props.selectedId && n.id !== hovered.value) continue;
    if (result.length >= 32) break;
    const point = projectedNodes.get(n.id);
    if (!point) continue;
    const candidates = [
      { x: point.x, y: point.y + point.size / 2 + 7 },
      { x: point.x, y: point.y - point.size / 2 - 34 },
      { x: point.x + point.size / 2 + 84, y: point.y - 13 },
      { x: point.x - point.size / 2 - 84, y: point.y - 13 },
    ];
    for (const { x, y } of candidates) {
      const box = { left: x - 77, right: x + 77, top: y, bottom: y + 27 };
      if (box.left < 8 || box.right > width - 8 || y < 8 || box.bottom > height - 68) continue;
      if (occupied.some(other => boxesOverlap(box, other))) continue;
      if (iconBoxes.some(other => boxesOverlap(box, other, 2))) continue;
      occupied.push(box);
      result.push({ id: n.id, label: n.label, title: n.label + ' · ' + atlasFileType(n).label + ' · ' + (n.filePath || n.module), x, y, color: n.color });
      break;
    }
  }
  labels.value = result;
}
function updateSoftwareScene() {
  if (!softwareMode.value) return;
  const { width, height } = viewport;
  // Rank by depth instead of quantizing normalized depth, which gave distant nodes identical stacking orders.
  const sorted = [...positions.value].sort((a, b) => (projectedNodes.get(b.id)?.depth ?? 0) - (projectedNodes.get(a.id)?.depth ?? 0));
  softwareNodes.value = sorted.flatMap((node, index) => {
    const point = projectedNodes.get(node.id);
    if (!point || point.x < -100 || point.x > width + 100 || point.y < -100 || point.y > height + 100) return [];
    const active = !props.selectedId || props.connected.has(node.id);
    return [{ id: node.id, node, icon: fileIconUrl(atlasFileType(node)), x: point.x, y: point.y,
      size: point.size, opacity: active ? 1 : .18, zIndex: index + 1, color: node.color }];
  });
  softwareDistricts.value = districts.value.flatMap(d => {
    const corners = [[d.left, d.back], [d.right, d.back], [d.right, d.front], [d.left, d.front]]
      .map(([x, z]) => projectAtlasPoint(new THREE.Vector3(x, -2, z), camera, width, height));
    if (corners.some(p => !p)) return [];
    return [{ module: d.module, color: d.color, points: corners.map(p => `${p!.x},${p!.y}`).join(' ') }];
  });
  softwareEdges.value = edgeCurves.value.flatMap(edge => {
    const projected = edge.points.map(point => projectAtlasPoint(point, camera, width, height));
    if (projected.some(point => !point)) return [];
    return [{ key: edge.key, points: projected.map(point => point!.x.toFixed(1) + ',' + point!.y.toFixed(1)).join(' '),
      color: props.selectedId ? edge.color : '#5e8faf', opacity: props.selectedId ? .78 : .23 }];
  });
}
function animate(time: number) {
  if (stopped) return;
  frame = requestAnimationFrame(animate);
  if (document.hidden || !controls || time - lastTime < 32) return;
  const delta = Math.min(.1, (time - lastTime) / 1000); lastTime = time;
  controls.autoRotate = props.autoRotate && !reduced && !props.selectedId && !hovered.value;
  if (focus) {
    camera.position.lerp(focus.eye, .12); controls.target.lerp(focus.target, .12);
    if (camera.position.distanceTo(focus.eye) < .5) focus = undefined;
  }
  const moved = controls.update(delta);
  if (moved || sceneDirty || edgeDirty || focus) {
    camera.updateMatrixWorld(true);
    updateProjectedNodes(); updateSoftwareScene(); updateLabels();
    sceneDirty = false; edgeDirty = false;
  }
  if (renderer) {
    if (!reduced) pulses.forEach((p, i) => p.mesh.position.copy(p.curve.getPoint((time * .00018 + i * .13) % 1)));
    renderer.render(scene, camera);
  }
}
function motionChanged() { reduced = motionQuery?.matches ?? false; }
function configureControls(element: HTMLElement) {
  controls?.dispose();
  controls = new OrbitControls(camera, element);
  controls.enableDamping = true; controls.autoRotateSpeed = .45; controls.minDistance = 50;
  controls.addEventListener('start', () => { focus = undefined; });
  controls.addEventListener('change', () => { sceneDirty = true; });
}
function resizeScene() {
  if (!host.value) return;
  const width = host.value.clientWidth, height = host.value.clientHeight;
  if (!width || !height) return;
  viewport = { width, height }; sceneDirty = true;
  renderer?.setSize(width, height);
  camera.aspect = width / height; camera.updateProjectionMatrix();
}
function disposeWebglResources() {
  dispose(graph); dispose(edgeGroup); dispose(districtGroup);
  iconTextures.forEach(texture => texture.dispose()); iconTextures.clear();
  ringTextures.forEach(texture => texture.dispose()); ringTextures.clear();
  rings.clear(); icons.clear(); meshes.clear(); pulses = [];
  renderer?.domElement.removeEventListener('webglcontextlost', contextLost);
  renderer?.dispose(); renderer?.forceContextLoss(); renderer?.domElement.remove(); renderer = undefined;
}
function enableCompatibility(reason: string) {
  if (softwareMode.value) return;
  disposeWebglResources();
  softwareMode.value = true;
  configureControls(host.value!);
  resizeScene(); home(); camera.updateMatrixWorld(true); updateProjectedNodes(); updateSoftwareScene(); updateLabels();
  emit('ready', 'compatible');
  emit('degraded', reason);
}
function contextLost(event: Event) {
  event.preventDefault();
  enableCompatibility('WebGL 上下文已丢失');
}
onMounted(() => {
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)'); motionChanged();
  motionQuery.addEventListener('change', motionChanged);
  try {
    if (props.forceCompatibility) {
      enableCompatibility('已选择兼容渲染');
    } else {
    renderer = createAtlasRenderer();
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.75));
    renderer.setClearColor('#edf3f8', 0); host.value!.prepend(renderer.domElement);
    renderer.domElement.addEventListener('webglcontextlost', contextLost);
    configureControls(renderer.domElement);
    scene.add(districtGroup, graph, edgeGroup, new THREE.HemisphereLight('#ffffff', '#60758a', 2.4));
    const light = new THREE.DirectionalLight('#ffffff', 3); light.position.set(-300, 600, 500); scene.add(light);
    const rim = new THREE.DirectionalLight('#8cc9ff', 2); rim.position.set(400, -100, -400); scene.add(rim);
    resizeScene(); rebuild(); emit('ready', 'webgl');
    }
  } catch (error) {
    try {
      enableCompatibility(error instanceof Error ? error.message : 'WebGL 初始化失败');
    } catch (fallbackError) {
      emit('unavailable', fallbackError instanceof Error ? fallbackError.message : '兼容 3D 初始化失败');
    }
  }
  observer = new ResizeObserver(resizeScene);
  observer.observe(host.value!);
  window.addEventListener('resize', resizeScene);
  frame = requestAnimationFrame(animate);
});
watch(() => props.nodes, rebuild);
watch(() => props.edges, () => { edgeDirty = true; rebuildEdges(); });
watch(hovered, () => { sceneDirty = true; });
watch(() => [props.selectedId, props.connected], () => { highlight(); });
watch(() => props.selectedId, (id, previous) => {
  clearTimeout(focusTimer);
  // Preserve the double-click target before moving the camera.
  focus = undefined;
  if (id && !previous && controls) overviewCamera = { eye: camera.position.clone(), target: controls.target.clone() };
  if (!id && overviewCamera) {
    focus = overviewCamera; overviewCamera = undefined;
    if (reduced && controls) { camera.position.copy(focus.eye); controls.target.copy(focus.target); focus = undefined; sceneDirty = true; }
  } else if (id !== lastFocusId) focusTimer = setTimeout(focusSelected, 350);
  lastFocusId = props.selectedId ?? '';
});
onBeforeUnmount(() => {
  stopped = true; cancelAnimationFrame(frame); observer?.disconnect();
  window.removeEventListener('resize', resizeScene);
  clearTimeout(focusTimer);
  motionQuery?.removeEventListener('change', motionChanged); controls?.dispose();
  disposeWebglResources();
});
</script>

<template>
  <div ref="host" class="atlas-three" :class="{ 'has-selection': selectedId }" :data-engine="softwareMode ? 'compatible' : 'webgl'" @contextmenu.prevent aria-label="三维代码图谱：拖动旋转，滚轮缩放，右键拖动平移"
    @pointerdown="pointerDown" @pointerup="pointerUp" @pointermove="pointerMove" @pointerleave="hovered = ''" @dblclick="doubleClick">
    <div v-if="softwareMode" class="software-scene">
      <svg aria-hidden="true">
        <defs><marker id="software-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0 10 5 0 10Z" fill="#5e8faf" /></marker></defs>
        <polygon v-for="district in softwareDistricts" :key="district.module" :points="district.points" :fill="district.color" :stroke="district.color" class="software-district"><title>{{ district.module }}</title></polygon>
        <polyline v-for="edge in softwareEdges" :key="edge.key" :points="edge.points"
          :stroke="edge.color" :opacity="edge.opacity" marker-end="url(#software-arrow)" />
      </svg>
      <button v-for="item in softwareNodes" :key="item.id" class="software-node"
        :class="{ module: item.node.kind === 'MODULE', selected: selectedId === item.id }"
        :style="{ left: item.x + 'px', top: item.y + 'px', width: item.size + 'px', height: item.size + 'px',
          opacity: item.opacity, zIndex: item.zIndex, '--node-color': item.color }"
        :title="item.node.label + ' · ' + atlasFileType(item.node).label"
        :aria-label="item.node.label + ' · ' + atlasFileType(item.node).label"
        @pointerdown.stop="cancelFocus" @pointerup.stop @focus="hovered = item.id" @blur="hovered = ''" @mouseenter="hovered = item.id" @mouseleave="hovered = ''"
        @click.stop="emit('select', item.node)" @dblclick.stop="emit('expand', item.node)">
        <span v-if="item.node.kind === 'MODULE'" class="software-ring" />
        <img :src="item.icon" alt="" />
      </button>
    </div>
    <div class="three-labels">
      <button v-for="label in labels" :key="label.id" :style="{ left: label.x + 'px', top: label.y + 'px', borderColor: label.color }"
        :title="label.title" :aria-label="label.title" :class="{ selected: selectedId === label.id }"
        @focus="hovered = label.id" @blur="hovered = ''"
        @pointerdown.stop @pointerup.stop @click.stop="emit('select', nodes.find(n => n.id === label.id)!)"
        @dblclick.stop="emit('expand', nodes.find(n => n.id === label.id)!)">{{ label.label }}</button>
    </div>
  </div>
</template>

<style scoped>
.atlas-three{position:absolute;inset:0;overflow:hidden;isolation:isolate;touch-action:none;background:radial-gradient(ellipse at 50% 45%,#fff 0,#e8f0f7 65%,#dbe7f1 100%)}
.atlas-three :deep(canvas){display:block;width:100%;height:100%;touch-action:none}
.software-scene{position:absolute;inset:0;overflow:hidden;z-index:0;touch-action:none}
.software-scene svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible;pointer-events:none}
.software-district{fill-opacity:.08;stroke-opacity:.35;stroke-width:1.2}
.software-scene polyline{fill:none;stroke-width:1.3;stroke-linejoin:round;stroke-linecap:round}
.has-selection .software-scene polyline{stroke-dasharray:5 5;animation:software-flow 1.8s linear infinite}
.software-node{position:absolute;display:grid;place-items:center;transform:translate(-50%,-50%);padding:0;border:0;background:transparent;cursor:pointer}
.software-node img{position:relative;z-index:2;width:64%;height:64%;filter:drop-shadow(0 3px 4px rgb(23 50 77 / 18%));pointer-events:none}
.software-node:not(.module) img{width:100%;height:100%}.software-node.selected img{filter:drop-shadow(0 0 8px color-mix(in srgb,var(--node-color),transparent 30%))}
.software-ring{position:absolute;inset:3%;z-index:1;border:3px solid var(--node-color);border-radius:50%;box-shadow:0 0 0 2px rgb(255 255 255 / 75%),0 0 0 4px color-mix(in srgb,var(--node-color),transparent 72%);pointer-events:none}
.three-labels{position:absolute;inset:0;pointer-events:none;z-index:1}
.three-labels button,.three-labels span{position:absolute;transform:translate(-50%,0);max-width:150px;padding:4px 9px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:1px solid;border-radius:5px;background:rgb(255 255 255 / 94%);box-shadow:0 3px 12px #17324d18;color:#17324d;font:12px Consolas,"Microsoft YaHei",monospace}
.three-labels button{cursor:pointer;pointer-events:auto}.three-labels span{pointer-events:none}
.three-labels .selected{background:#17324d;color:white}.three-labels button:focus-visible,.software-node:focus-visible{outline:3px solid #2f7fd3;outline-offset:3px}
@keyframes software-flow{to{stroke-dashoffset:-20}}
@media(prefers-reduced-motion:reduce){.software-scene polyline{animation:none}}
</style>
