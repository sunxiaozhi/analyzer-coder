<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue';
import { useRouter } from 'vue-router';
import { Search, Orbit, ArrowLeft, Plus, Minus, Maximize, RefreshCw, X, ArrowUpRight, Crosshair } from 'lucide-vue-next';
import { getCodeAtlas, type AtlasView, type AtlasNode } from '@/api/codeAtlas';
import { getRepositoryFile } from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { useBranchContextStore } from '@/stores/branchContextStore';
import { useBranchReadScope } from '@/features/branches/useBranchReadScope';
import { layoutAtlas } from '@/features/graph/atlasLayout';
import { atlasFileType, fileIconUrl } from '@/features/graph/atlasFileType';

const CodeAtlas3D = defineAsyncComponent(() => import('@/features/graph/CodeAtlas3D.vue'));
const threeView = ref<{ home: () => void; zoomBy: (factor: number) => void }>();
const mode = ref<'3d' | '2d'>('3d'), autoRotate = ref(false), threeError = ref('');
const compatibility3d = ref(false), forceCompatibility = ref(false), diagnosticReason = ref('');
const rendererVersion = ref(0);
function ready3d(engine: 'webgl' | 'compatible') {
  compatibility3d.value = engine === 'compatible';
  if (engine === 'webgl') { threeError.value = ''; diagnosticReason.value = ''; }
}
function degraded3d(reason: string) {
  compatibility3d.value = true;
  forceCompatibility.value = true;
  threeError.value = '已启用兼容 3D，可继续旋转、缩放和查看关系。';
  if (reason !== '已选择兼容渲染' || !diagnosticReason.value) diagnosticReason.value = reason;
}
function fallback3d(reason = '') {
  compatibility3d.value = false;
  threeError.value = '3D 初始化失败，已切换到平面模式。';
  diagnosticReason.value = reason; mode.value = '2d';
}
function changeRenderer(compatible: boolean) {
  forceCompatibility.value = compatible;
  compatibility3d.value = compatible;
  threeError.value = ''; diagnosticReason.value = '';
  mode.value = '3d'; rendererVersion.value++;
}
function chooseMode(value: '3d' | '2d') {
  if (mode.value === value) return;
  mode.value = value;
}
const repositories = useRepositoryStore();
const branchContext = useBranchContextStore();
const readScope = useBranchReadScope();
const router = useRouter();
const data = ref<AtlasView | null>(null);
const loading = ref(false), error = ref(''), query = ref(''), module = ref('');
const selected = ref<AtlasNode | null>(null), source = ref(''), sourceError = ref(''), sourceLoading = ref(false);
const zoom = ref(1), pan = ref({ x: 0, y: 0 });
const direction = ref('both');
const relief = ref(true), showAllLinks = ref(false);
const visibleLinks = computed(() => selected.value ? related.value : (showAllLinks.value || data.value?.level === 'MODULE') ? links.value : []);
function showLabel(node: AtlasNode) { return zoom.value >= .6 || selected.value?.id === node.id; }
function compactLabel(label: string) {
  let width = 0, result = '';
  for (const char of label) {
    width += char.charCodeAt(0) > 255 ? 2 : 1;
    if (width > 26) return result + '…';
    result += char;
  }
  return result;
}
let revision = 0, sourceRevision = 0;
let dragging: { x: number; y: number; px: number; py: number } | null = null;
const nodes = computed(() => layoutAtlas(data.value?.nodes ?? [], data.value?.edges ?? []));
const fileTypes = computed(() => [...new Map(nodes.value.map(n => {
  const type = atlasFileType(n); return [type.id, type] as const;
})).values()]);
const byId = computed(() => new Map(nodes.value.map(n => [n.id, n])));
const links = computed(() => (data.value?.edges ?? []).flatMap(e => {
  const a = byId.value.get(e.source), b = byId.value.get(e.target);
  if (!a || !b) return [];
  const dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy) || 1;
  const path = a.id === b.id
    ? `M${a.x},${a.y - a.radius} C${a.x + 60},${a.y - 70} ${a.x + 70},${a.y + 50} ${a.x + a.radius},${a.y}`
    : `M${a.x + dx / length * a.radius},${a.y + dy / length * a.radius} L${b.x - dx / length * (b.radius + 5)},${b.y - dy / length * (b.radius + 5)}`;
  return [{ ...e, a, b, path }];
}));
const connected = computed(() => {
  const ids = new Set<string>();
  if (!selected.value) return ids;
  ids.add(selected.value.id);
  links.value.forEach(e => {
    if (direction.value !== 'in' && e.source === selected.value?.id) ids.add(e.target);
    if (direction.value !== 'out' && e.target === selected.value?.id) ids.add(e.source);
  });
  return ids;
});
const related = computed(() => links.value.filter(e =>
  (direction.value !== 'in' && e.source === selected.value?.id) || (direction.value !== 'out' && e.target === selected.value?.id)));
const excerpt = computed(() => {
  if (!selected.value || !source.value) return [];
  const start = Math.max(1, selected.value.startLine - 3);
  return source.value.split('\n').slice(start - 1, Math.min(start + 100, Math.max(start + 20, selected.value.endLine + 3)))
    .map((text, i) => ({ text, line: start + i }));
});
function resetCamera(fit = false) {
  if (mode.value === '3d') threeView.value?.home();
  const extent = nodes.value.reduce((max, n) => Math.max(max, (Math.abs(n.x - 600) + 125) / 520, (Math.abs(n.y - 400) + 70) / 310), 1);
  zoom.value = fit ? Math.max(.08, 1 / extent) : Math.max(.8, 1 / extent); pan.value = { x: 0, y: 0 };
}
function changeZoom(delta: number) {
  if (mode.value === '3d') { threeView.value?.zoomBy(delta > 0 ? .85 : 1.15); return; }
  zoom.value = Math.min(3.5, Math.max(.08, zoom.value + delta));
}
function clearSelection() { sourceRevision++; selected.value = null; source.value = ''; sourceError.value = ''; sourceLoading.value = false; }
async function load() {
  const id = repositories.selectedRepositoryId;
  const version = ++revision;
  clearSelection(); data.value = null; error.value = ''; resetCamera();
  if (!id) { loading.value = false; return; }
  if (readScope.blocked.value) { loading.value = false; error.value = readScope.reason.value; return; }
  loading.value = true;
  try {
    const result = branchContext.context?.contextId
      ? await getCodeAtlas(id, module.value, query.value.trim(), branchContext.context.contextId)
      : await getCodeAtlas(id, module.value, query.value.trim());
    if (version === revision && id === repositories.selectedRepositoryId) {
      if (branchContext.context && result.snapshotId !== branchContext.context.snapshotId) throw new Error('图谱与当前分支快照不一致，请重新构建该分支图谱。');
      data.value = result; resetCamera();
    }
  } catch (e) { if (version === revision) error.value = e instanceof Error ? e.message : '图谱加载失败'; }
  finally { if (version === revision) loading.value = false; }
}
function overview() { module.value = ''; query.value = ''; void load(); }
async function select(node: AtlasNode) {
  clearSelection(); selected.value = node;
  const position = byId.value.get(node.id);
  if (position) pan.value = { x: -(position.x - 600) * zoom.value, y: (400 - position.y) * zoom.value };
  if (!node.filePath || !data.value) return;
  const version = ++sourceRevision, repoId = data.value.repositoryId, snapshot = data.value.snapshotId;
  sourceLoading.value = true;
  try {
    const file = branchContext.context?.contextId
      ? await getRepositoryFile(repoId, node.filePath, branchContext.context.contextId)
      : await getRepositoryFile(repoId, node.filePath);
    if (version !== sourceRevision) return;
    if (file.snapshotId !== snapshot) throw new Error('源码版本已更新，请刷新图谱后查看。');
    source.value = file.content;
  } catch (e) { if (version === sourceRevision) sourceError.value = e instanceof Error ? e.message : '源码读取失败'; }
  finally { if (version === sourceRevision) sourceLoading.value = false; }
}
function expand(node: AtlasNode) { if (node.kind === 'MODULE') { module.value = node.module; query.value = ''; void load(); } }
function openSource() {
  if (!selected.value || !data.value) return;
  void router.push({ name: 'search', query: { path: selected.value.filePath, snapshotId: data.value.snapshotId, startLine: String(selected.value.startLine || 1), branchId: branchContext.context?.branchId, contextId: branchContext.context?.contextId } });
}
function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || (event.target as Element).closest('[data-node]')) return;
  const svg = event.currentTarget as SVGSVGElement;
  svg.setPointerCapture(event.pointerId);
  dragging = { x: event.clientX, y: event.clientY, px: pan.value.x, py: pan.value.y };
}
function pointerMove(event: PointerEvent) {
  if (!dragging) return;
  const rect = (event.currentTarget as SVGSVGElement).getBoundingClientRect();
  const factor = Math.max(1200 / rect.width, 800 / rect.height);
  pan.value = { x: dragging.px + (event.clientX - dragging.x) * factor, y: dragging.py + (event.clientY - dragging.y) * factor };
}
watch(() => [repositories.selectedRepositoryId, branchContext.identity] as const, () => { module.value = ''; query.value = ''; void load(); }, { immediate: true });
onMounted(() => { if (!repositories.repositories.length) void repositories.loadRepositories(); });
onBeforeUnmount(() => { revision++; sourceRevision++; });
</script>

<template>
  <section class="atlas" :class="{ relief, 'has-detail': selected }">
    <header class="atlas-toolbar">
      <div class="view-switch" aria-label="节点显示方式"><button :aria-pressed="mode === '3d'" @click="chooseMode('3d')">3D 空间</button><button data-view-2d :aria-pressed="mode === '2d'" @click="chooseMode('2d')">平面阅读</button></div>
      <label v-if="mode === '3d'"><input v-model="autoRotate" type="checkbox" /> 自动环绕</label>
      <label v-if="data?.level !== 'MODULE'"><input v-model="showAllLinks" type="checkbox" :disabled="!!selected" /> 全部连线</label>
      <form class="atlas-search" @submit.prevent="load"><Search :size="16" /><input v-model="query" aria-label="搜索符号或文件" placeholder="搜索类、方法或文件…" maxlength="500" /><button type="submit" :disabled="loading">定位</button></form>
      <button class="tool" :disabled="loading" title="刷新图谱" @click="load"><RefreshCw :size="16" /></button>
      <details class="render-diagnostics">
        <summary title="操作说明、文件图例与渲染诊断">图例与设置</summary>
        <div class="render-panel">
          <p>拖动旋转 · 滚轮缩放 · 右键平移 · 双击模块展开。选中节点查看关联，关闭详情返回原视角。</p>
          <p>区域表示模块归属，空间高度不代表架构层级。连线来自静态解析，不代表实际运行轨迹。</p>
          <div v-if="fileTypes.length" class="file-type-legend" aria-label="当前图谱文件类型图例"><span v-for="type in fileTypes" :key="type.id"><img :src="fileIconUrl(type)" alt="" />{{ type.label }}</span></div>
          <p>当前模式：{{ mode === '2d' ? '平面阅读' : compatibility3d ? '兼容 3D（无需 WebGL）' : '自动选择 WebGL 2' }}</p>
          <p v-if="diagnosticReason" class="diagnostic-reason">{{ diagnosticReason }}</p>
          <p>有 GPU 仍需要浏览器允许 WebGL 2。可检查浏览器“使用图形加速”设置；Chrome 的 chrome://gpu 或 Edge 的 edge://gpu 中可查看 WebGL 状态。</p>
          <div><button data-render-auto @click="changeRenderer(false)">重新检测 WebGL</button><button data-render-compatible @click="changeRenderer(true)">使用兼容 3D</button></div>
        </div>
      </details>
    </header>
    <div class="atlas-caption"><button v-if="module || query" @click="overview"><ArrowLeft :size="14" /> 项目全景</button><span v-else>项目全景</span><strong>{{ module || (query ? '检索结果' : repositories.selectedRepository?.name) }}</strong><span v-if="selected" class="relation-legend"><span class="incoming">↙ 入向</span><span class="outgoing">↗ 出向</span></span><span v-if="threeError" role="status" class="render-notice" :title="threeError">{{ mode === '2d' ? threeError : '已启用兼容 3D' }}</span></div>
    <div class="atlas-stage" :class="{ 'is-three': mode === '3d' }">
      <CodeAtlas3D v-if="mode === '3d' && data?.nodes.length" :key="rendererVersion" ref="threeView" :force-compatibility="forceCompatibility" :nodes="data.nodes" :edges="visibleLinks" :selected-id="selected?.id" :connected="connected" :auto-rotate="autoRotate" @select="select" @expand="expand" @ready="ready3d" @degraded="degraded3d" @unavailable="fallback3d" />
      <svg v-if="mode === '2d' && data?.nodes.length" class="atlas-svg" viewBox="0 0 1200 800" aria-label="交互式代码关系图谱" @wheel.prevent="changeZoom($event.deltaY > 0 ? -.1 : .1)" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="dragging = null" @pointercancel="dragging = null">
        <defs><radialGradient id="atlas-sphere" cx="30%" cy="22%" r="80%"><stop offset="0" stop-color="#fff" stop-opacity=".5" /><stop offset=".4" stop-color="#fff" stop-opacity=".05" /><stop offset="1" stop-color="#10283f" stop-opacity=".5" /></radialGradient><radialGradient id="atlas-halo"><stop offset="0" stop-color="currentColor" stop-opacity=".2" /><stop offset="1" stop-color="currentColor" stop-opacity="0" /></radialGradient><marker id="atlas-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--app-text-subtle)" /></marker></defs>
        <g class="atlas-camera" :transform="`translate(${600 + pan.x},${400 + pan.y}) scale(${zoom}) translate(-600,-400)`">
          <g v-for="link in visibleLinks" :key="`${link.source}-${link.target}-${link.kind}`" class="atlas-link" :class="{ dim: selected && !related.includes(link), active: selected && related.includes(link) }">
            <path :d="link.path" fill="none" :stroke="selected ? link.target === selected.id ? '#08768a' : '#2467ae' : 'var(--app-text-muted)'" :stroke-width="Math.min(4, 1 + Math.log2(link.count + 1) / 3)" marker-end="url(#atlas-arrow)" />
          </g>
          <g v-for="node in nodes" :key="node.id" data-node class="atlas-node" :class="{ dim: selected && !connected.has(node.id), selected: selected?.id === node.id }" :transform="`translate(${node.x},${node.y})`" :style="{ color: node.color }" tabindex="0" role="button" :aria-label="`${node.label}，${node.kind === 'MODULE' ? '模块，按回车展开' : '查看源码'}`" @click="select(node)" @dblclick="expand(node)" @keydown.enter.prevent="node.kind === 'MODULE' ? expand(node) : select(node)" @keydown.space.prevent="select(node)">
            <title>{{ node.label }} · {{ atlasFileType(node).label }} · {{ node.filePath || `${node.count} 个符号` }}</title>
            <ellipse v-if="relief" :cy="node.radius + 9" :rx="node.radius * 1.05" :ry="node.radius * .25" fill="#17324d" opacity=".16" />
            <image :href="fileIconUrl(atlasFileType(node))" :x="-node.radius - 8" :y="-node.radius - 8" :width="(node.radius + 8) * 2" :height="(node.radius + 8) * 2" />
            <circle v-if="selected?.id === node.id" :r="node.radius + 7" class="focus-ring" />
            <text v-if="node.kind === 'MODULE'" text-anchor="middle" y="14" fill="#9a6010" font-size="14" font-weight="600">{{ node.count }}</text>
            <g v-if="showLabel(node)" class="node-caption">
              <rect x="-118" :y="node.radius + 17" width="236" height="30" rx="6" />
              <text text-anchor="middle" :y="node.radius + 37" class="node-label">{{ compactLabel(node.label) }}</text>
            </g>
          </g>
        </g>
      </svg>
      <div v-if="loading || error || !data?.nodes.length" class="atlas-empty"><Orbit :size="52" :class="{ spinning: loading }" /><h2>{{ loading ? '正在读取代码星图' : error ? '图谱暂不可用' : !repositories.selectedRepositoryId ? '选择一个项目，开始探索' : '当前范围没有符号' }}</h2><p>{{ error || (loading ? '读取当前快照中的符号与关系' : '需要当前快照已发布的 CodeGraph 产物。可以先准备项目，或调整检索范围。') }}</p><button v-if="error || (!loading && !data)" @click="router.push('/overview')">前往项目准备 <ArrowUpRight :size="14" /></button><button v-else-if="!loading" @click="overview">返回全景</button></div>
      <div class="camera-tools"><button title="缩小" @click="changeZoom(-.2)"><Minus :size="17" /></button><span>{{ mode === '3d' ? '3D' : Math.round(zoom * 100) + '%' }}</span><button title="放大" @click="changeZoom(.2)"><Plus :size="17" /></button><button title="重置视角" @click="resetCamera()"><Maximize :size="17" /></button><button title="适应全部节点" @click="resetCamera(true)">全景</button><button title="取消聚焦" @click="clearSelection"><Crosshair :size="17" /></button></div>
    </div>
      <aside v-if="selected" class="atlas-detail" aria-label="节点详情" @keydown.esc="clearSelection"><header><span>{{ selected.kind === 'MODULE' ? '模块' : selected.kind }}</span><button title="关闭详情" aria-label="关闭详情" @click="clearSelection"><X :size="17" /></button></header><h2>{{ selected.label }}</h2><p class="source-path">{{ selected.filePath || selected.module }}</p>
        <button v-if="selected.kind === 'MODULE'" class="expand-button" @click="expand(selected)">展开 {{ selected.count }} 个符号 <ArrowUpRight :size="16" /></button>
        <div class="direction"><button :class="{ chosen: direction === 'both' }" @click="direction = 'both'">全部</button><button :class="{ chosen: direction === 'in' }" @click="direction = 'in'">入向关系</button><button :class="{ chosen: direction === 'out' }" @click="direction = 'out'">出向关系</button></div>
        <details class="relations" :class="{ 'relations-fill': !selected.filePath }" :open="!selected.filePath"><summary>关联节点 · {{ related.length }}</summary><div class="related-list"><button v-for="(edge, index) in related.slice(0, 40)" :key="index" @click="select(edge.source === selected.id ? edge.b : edge.a)"><span>{{ edge.source === selected.id ? '↗' : '↙' }} {{ edge.source === selected.id ? edge.b.label : edge.a.label }}</span><small>{{ edge.kind }}{{ edge.count > 1 ? ` ×${edge.count}` : '' }}</small></button><p v-if="!related.length">当前视图内没有匹配关系。</p><p v-if="related.length > 40">仅列出前 40 条关系。</p></div></details>
        <section v-if="selected.filePath" class="source-section"><div class="source-heading"><b>源码摘录</b><button @click="openSource">打开文件 <ArrowUpRight :size="13" /></button></div><p v-if="sourceLoading">正在加载源码…</p><p v-else-if="sourceError" role="alert">{{ sourceError }}</p><pre v-else class="atlas-source" aria-label="源码摘录，长行自动换行"><span v-for="line in excerpt" :key="line.line" :class="{ marked: line.line >= selected.startLine && line.line <= selected.endLine }"><i aria-hidden="true">{{ line.line }}</i><code>{{ line.text || ' ' }}</code></span></pre></section>
      </aside>
    <footer class="atlas-status"><span v-if="data" :title="`快照 ${data.snapshotId} · 仅统计当前展示范围`">{{ data.nodes.length }} / {{ data.totalNodes }} 节点 · {{ visibleLinks.length }} 条连线</span><span v-if="data?.level === 'SYMBOL' && !selected && !showAllLinks">选中节点查看关联</span><span v-if="data?.partial" class="partial">当前为部分图谱，请缩小模块或搜索范围</span></footer>
  </section>
</template>

<style scoped>
.atlas{--ink:var(--app-text-primary);--muted:var(--app-text-muted);--line:var(--app-border);display:grid;grid-template-columns:minmax(0,1fr);grid-template-rows:auto auto minmax(320px,1fr) auto;height:100%;min-height:500px;min-width:0;position:relative;background:var(--app-canvas);color:var(--ink);border:1px solid var(--line);border-radius:12px;overflow:hidden;font-family:inherit}
.atlas.has-detail{grid-template-columns:minmax(0,1fr) clamp(420px,46%,680px)}
.atlas button{font:inherit;color:inherit;cursor:pointer}.atlas button:disabled{opacity:.45;cursor:default}
.atlas button:focus-visible,.atlas input:focus-visible,.atlas summary:focus-visible{outline:2px solid var(--app-color-action);outline-offset:3px}
.atlas-toolbar{grid-column:1;grid-row:1;position:relative;display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:10px 16px;font-size:12px;border-bottom:1px solid var(--line);background:var(--app-surface)}
.atlas-toolbar>label{display:flex;align-items:center;gap:5px;white-space:nowrap}
.view-switch{display:flex;gap:4px;flex:none}.view-switch button{border:1px solid var(--line);border-radius:6px;padding:7px 10px;background:var(--app-surface);white-space:nowrap}
.view-switch button[aria-pressed=true]{color:var(--app-color-action);background:var(--app-color-action-soft);border-color:var(--app-color-action)}
.atlas-search{display:flex;align-items:center;gap:8px;margin-left:auto;flex:1 1 180px;min-width:160px;max-width:400px;background:var(--app-canvas);border:1px solid var(--app-border-strong);border-radius:7px;padding:4px 6px 4px 10px}
.atlas-search input{width:100%;min-width:0;border:0;background:transparent;color:var(--ink);font:inherit;outline:none}
.atlas-search input::placeholder{color:var(--app-text-subtle)}.atlas-search button{border:0;border-radius:4px;background:var(--app-color-action);color:#fff;padding:6px 10px;white-space:nowrap}
.tool{display:flex;border:1px solid var(--line);background:transparent;padding:8px;border-radius:6px}
.render-diagnostics summary{cursor:pointer;color:var(--muted);white-space:nowrap;font-size:12px}
.render-panel{position:absolute;right:12px;top:calc(100% + 4px);z-index:10;box-sizing:border-box;width:min(420px,calc(100% - 24px));max-height:65vh;overflow:auto;padding:16px;background:var(--app-surface);border:1px solid var(--line);border-radius:8px;box-shadow:0 8px 30px #17324d22;line-height:1.7}
.render-panel p{margin:0 0 10px}.diagnostic-reason{overflow-wrap:anywhere;color:var(--app-text-regular)}
.render-panel>div{display:flex;flex-wrap:wrap;gap:8px}.render-panel button{padding:7px 10px;border:1px solid var(--line);border-radius:5px;background:var(--app-color-action-soft)}
.file-type-legend{margin:8px 0 14px;color:var(--muted)}.file-type-legend span{display:flex;align-items:center;gap:5px}.file-type-legend img{width:24px;height:24px}
.atlas-caption{grid-column:1;grid-row:2;display:flex;align-items:center;flex-wrap:wrap;gap:8px 14px;padding:8px 16px;border-bottom:1px solid var(--line);background:var(--app-surface-subtle);min-width:0;font-size:12px}
.atlas-caption>span{color:var(--muted)}.atlas-caption strong{font-size:13px;font-weight:600;overflow-wrap:anywhere}
.atlas-caption button{display:flex;align-items:center;gap:5px;background:transparent;border:0;padding:0;color:var(--app-color-action)}
.relation-legend{display:flex;gap:10px;margin-left:auto}.relation-legend .incoming{color:#08768a}.relation-legend .outgoing{color:#2467ae}.render-notice{font-size:11px}
.atlas-stage{grid-column:1;grid-row:3;position:relative;min-height:0;min-width:0;overflow:hidden;background:radial-gradient(rgb(96 113 125 / 18%) .65px,transparent .85px);background-size:27px 27px}
.atlas-svg{width:100%;height:100%;position:absolute;inset:0;touch-action:none;cursor:grab}.atlas-svg:active{cursor:grabbing}
.atlas-camera{transition:transform .3s ease-out}.atlas-svg:active .atlas-camera{transition:none}
.atlas-node{cursor:pointer;transition:opacity .25s}.atlas-node:focus{outline:none}.atlas-node.dim{opacity:.18}
.atlas-node.selected{filter:drop-shadow(0 0 7px rgb(47 127 211 / 25%))}
.node-caption rect{fill:var(--app-surface);stroke:var(--line);stroke-width:1}.atlas-node.selected .node-caption rect,.atlas-node:focus-visible .node-caption rect{stroke:var(--app-color-action);stroke-width:2}
.node-label{fill:var(--ink);font:14px Consolas,"Microsoft YaHei",monospace}
.atlas-link{opacity:.55}.atlas-link.active{opacity:1;stroke-dasharray:8 6;animation:trace 2s linear infinite}
.focus-ring{fill:none;stroke:currentColor;stroke-width:1.4;stroke-dasharray:5 4}
.atlas-empty{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px;text-align:center;color:var(--muted)}
.atlas-empty>svg{color:var(--app-color-action);opacity:.65}.atlas-empty h2{color:var(--ink);font-size:20px;font-weight:500}.atlas-empty p{font-size:13px;max-width:500px;line-height:1.8}
.atlas-empty button,.expand-button{display:flex;align-items:center;justify-content:center;gap:8px;background:var(--app-color-action-soft);color:var(--app-color-action);border:1px solid var(--line);border-radius:6px;padding:9px 14px}
.camera-tools{position:absolute;z-index:3;bottom:16px;left:16px;display:flex;align-items:center;gap:3px;background:rgb(255 255 255 / 94%);border:1px solid var(--line);border-radius:8px;padding:4px}
.camera-tools button,.atlas-detail header button{display:flex;background:transparent;border:0;padding:8px}.camera-tools span{font:11px Consolas,monospace;min-width:30px;text-align:center}
.atlas-detail{grid-column:2;grid-row:1/5;min-width:0;min-height:0;display:flex;flex-direction:column;gap:0;overflow-x:hidden;overflow-y:auto;background:var(--app-surface);border-left:1px solid var(--line);padding:16px 20px;box-sizing:border-box}
.atlas-detail>header{display:flex;flex:none;align-items:center;justify-content:space-between;color:var(--app-color-action);font:12px Consolas,monospace}
.atlas-detail h2{flex:none;margin:8px 0;font:600 19px/1.5 Consolas,"Microsoft YaHei",monospace;overflow-wrap:anywhere}
.source-path{flex:none;margin:0 0 12px;font:12px/1.7 Consolas,monospace;color:var(--muted);overflow-wrap:anywhere}
.expand-button{width:100%;margin:8px 0}
.direction{display:flex;flex:none;gap:3px;padding:4px;background:var(--app-canvas);border-radius:6px;margin:4px 0 10px}
.direction button{flex:1;border:0;border-radius:4px;background:transparent;padding:7px 2px;font-size:12px}.direction button.chosen{background:var(--app-color-action-soft);color:var(--app-color-action)}
.relations{flex:none;font-size:12px;border-bottom:1px solid var(--line);padding:0 0 12px}.relations summary{cursor:pointer;color:var(--muted);padding:4px 0}
.related-list{max-height:180px;overflow-y:auto;overflow-x:hidden}
.relations-fill[open]{flex:1 1 0;min-height:80px;overflow-y:auto;overflow-x:hidden;border-bottom:0}
.relations-fill[open]>summary{position:sticky;top:0;z-index:1;background:var(--app-surface)}
.relations-fill .related-list{max-height:none;overflow:visible}
.related-list button{display:flex;align-items:center;justify-content:space-between;gap:8px;text-align:left;width:100%;background:transparent;border:0;border-bottom:1px solid var(--line);padding:9px 0;font-size:12px}
.related-list button span{min-width:0;overflow-wrap:anywhere}.related-list small{flex:none;color:var(--muted);font-size:10px}
.related-list p,.source-section>p{color:var(--muted);font-size:12px;line-height:1.7}
.source-section{display:flex;flex-direction:column;flex:1 0 180px;min-width:0;min-height:180px}
.source-heading{display:flex;flex:none;justify-content:space-between;align-items:center;margin:14px 0 10px;font-size:13px}
.source-heading button{display:flex;gap:4px;align-items:center;background:transparent;border:0;color:var(--app-color-action);font-size:12px}
.atlas-source{flex:1;min-height:0;min-width:0;margin:0;overflow-y:auto;overflow-x:hidden;font:12px/1.85 Consolas,"Microsoft YaHei",monospace;background:var(--app-surface-subtle);padding:10px 0;border:1px solid var(--line);border-radius:6px;tab-size:4;white-space:pre-wrap}
.atlas-source>span{display:grid;grid-template-columns:44px minmax(0,1fr);min-width:0}
.atlas-source code{display:block;min-width:0;padding:0 12px 0 8px;font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;word-break:normal}
.atlas-source .marked{background:var(--app-color-action-soft)}
.atlas-source i{padding-right:9px;text-align:right;color:var(--app-text-subtle);font-style:normal;user-select:none;border-right:1px solid var(--line)}
.atlas-status{grid-column:1;grid-row:4;display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:8px 16px;min-height:16px;border-top:1px solid var(--line);font-size:11px;color:var(--muted);background:var(--app-surface)}
.partial{color:var(--app-color-warning)}.spinning{animation:turn 5s linear infinite}
@keyframes turn{to{transform:rotate(360deg)}}@keyframes trace{to{stroke-dashoffset:-28}}
@media(max-width:1100px){.atlas.has-detail{grid-template-columns:minmax(0,1fr) minmax(360px,48%)}.atlas-toolbar{padding:10px 12px}.atlas-detail{padding:12px 16px}}
@media(max-width:800px){.atlas.has-detail{grid-template-columns:minmax(0,1fr)}.atlas-detail{grid-column:auto;grid-row:auto;position:absolute;inset:0 0 0 auto;width:min(520px,100%);z-index:12;box-shadow:-12px 0 36px #17324d20}.atlas-search{max-width:none}.atlas{min-height:480px}}
@media(prefers-reduced-motion:reduce){.atlas *{animation:none!important;transition:none!important}}
</style>
