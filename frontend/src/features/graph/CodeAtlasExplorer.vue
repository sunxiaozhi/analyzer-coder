<script setup lang="ts">
import { computed, shallowRef, ref, watch, defineAsyncComponent } from 'vue';
import { ElOption, ElSelect } from 'element-plus';
import { Search, Orbit, Plus, Minus, Maximize, RefreshCw, X, Crosshair, GitBranch, Code2, Folder, FileCode2, ChevronRight, Layers, PanelLeftClose, PanelLeftOpen, SlidersHorizontal, Network } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import { atlasEdgeKey, type AtlasNode, type AtlasEdge } from '@/api/codeAtlas';
import { projectAtlasCards, type AtlasCardLevel } from './atlasCardLayout';
import { useAtlasExplorer } from './useAtlasExplorer';
import CodeAtlas2D from './CodeAtlas2D.vue';
import AtlasDetailDrawer from './AtlasDetailDrawer.vue';
const CodeAtlas3D = defineAsyncComponent(() => import('./CodeAtlas3D.vue'));
const { repositories, branches, data, selected, query, module, loading, error, notice, depth, limit,
  impact, pathIndex, select, clearSelection, load, overview, loadMore, expand, traceImpact, openSource } = useAtlasExplorer();
const router = useRouter();
const mode = shallowRef<'3d' | '2d'>('2d'), level = shallowRef<AtlasCardLevel>('files');
const focusFile = shallowRef(''), hasSearchResults = shallowRef(false);
const navigatorByMode = ref({ '2d': true, '3d': false });
const navigatorOpen = computed({
  get: () => navigatorByMode.value[mode.value],
  set: (open: boolean) => { navigatorByMode.value[mode.value] = open; },
});
const forceCompatibility = shallowRef(false), compatibility3d = shallowRef(false), rendererVersion = shallowRef(0);
const diagnosticReason = shallowRef(''), threeError = shallowRef(''), autoRotate = shallowRef(false);
const threeView = ref<{ home: () => void; zoomBy: (factor: number) => void }>(), flatView = ref<InstanceType<typeof CodeAtlas2D>>();
const relationKind = shallowRef(''), drawer = shallowRef<'source' | 'relations' | null>(null), sourceLine = shallowRef<number>();
const drawerNode = shallowRef<AtlasNode | null>(null);
const kinds = computed(() => data.value?.relationKinds ?? [...new Set(data.value?.edges.map(e => e.kind) ?? [])].sort());
const byId = computed(() => new Map(data.value?.nodes.map(n => [n.id, n]) ?? []));
const fileCount = computed(() => new Set(data.value?.nodes.map(n => n.filePath).filter(Boolean)).size);
const tree = computed(() => {
  const groups = new Map<string, Map<string, number>>();
  for (const node of data.value?.nodes ?? []) {
    if (!groups.has(node.module)) groups.set(node.module, new Map());
    if (node.filePath) groups.get(node.module)!.set(node.filePath, (groups.get(node.module)!.get(node.filePath) ?? 0) + 1);
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([name, files]) => ({
    name, files: [...files].sort(([a], [b]) => a.localeCompare(b)).map(([path, count]) => ({ path, count, name: path.replace(/\\/g, '/').split('/').pop() })),
  }));
});
const availablePaths = computed(() => impact.value?.paths.map((path, index) => ({ path, index })).filter(({ path }) => path.nodeIds.every(id => byId.value.has(id))) ?? []);
const activePath = computed(() => impact.value?.paths[pathIndex.value]);
const highlighted = computed(() => {
  const ids = new Set(activePath.value?.edgeIds ?? []);
  return new Set(impact.value?.edges.filter(e => ids.has(e.id)).map(e => atlasEdgeKey({ source: e.source, target: e.target, kind: e.relation, count: 1 })) ?? []);
});
const pathNodes = computed(() => activePath.value?.nodeIds.flatMap(id => byId.value.get(id) ? [byId.value.get(id)!] : []) ?? []);
const filteredEdges = computed(() => (data.value?.edges ?? []).filter(e => !relationKind.value || e.kind === relationKind.value));
const connected = computed(() => {
  if (activePath.value) return new Set(activePath.value.nodeIds);
  const ids = new Set<string>(); if (!selected.value) return ids;
  ids.add(selected.value.id);
  filteredEdges.value.forEach(e => { if (e.source === selected.value?.id) ids.add(e.target); if (e.target === selected.value?.id) ids.add(e.source); });
  return ids;
});
const canvasNodes = computed(() => {
  if (!focusFile.value || mode.value === '3d') return data.value?.nodes ?? [];
  const fileIds = new Set(data.value?.nodes.filter(n => n.filePath === focusFile.value).map(n => n.id));
  const ids = new Set([...fileIds, ...connected.value]);
  filteredEdges.value.forEach(e => { if (fileIds.has(e.source)) ids.add(e.target); if (fileIds.has(e.target)) ids.add(e.source); });
  for (const node of impact.value?.nodes ?? []) ids.add(node.id);
  return data.value?.nodes.filter(n => ids.has(n.id)) ?? [];
});
const canvasIds = computed(() => new Set(canvasNodes.value.map(n => n.id)));
const scopedEdges = computed(() => filteredEdges.value.filter(e => canvasIds.value.has(e.source) && canvasIds.value.has(e.target)));
const visibleEdges = computed(() => [...scopedEdges.value].sort((a, b) => {
  const rank = (e: AtlasEdge) => highlighted.value.has(atlasEdgeKey(e)) ? 2 : e.source === selected.value?.id || e.target === selected.value?.id ? 1 : 0;
  return rank(b) - rank(a);
}).slice(0, mode.value === '2d' ? 1200 : 3000));
const effectiveLevel = computed(() => focusFile.value || hasSearchResults.value || selected.value ? 'symbols' : level.value);
const projection = computed(() => projectAtlasCards(canvasNodes.value, visibleEdges.value, effectiveLevel.value));
const hiddenEdges = computed(() => scopedEdges.value.length - visibleEdges.value.length);
const related = computed(() => data.value?.edges.filter(e => e.source === selected.value?.id || e.target === selected.value?.id) ?? []);
const hiddenNeighbors = computed(() => selected.value?.neighborCount !== undefined ? Math.max(0, selected.value.neighborCount - new Set(related.value.flatMap(e => [e.source, e.target]).filter(id => id !== selected.value?.id)).size) : selected.value?.hiddenNeighborCount ?? 0);
const incoming = computed(() => selected.value?.incomingCount ?? related.value.filter(e => e.target === selected.value?.id).length);
const outgoing = computed(() => selected.value?.outgoingCount ?? related.value.filter(e => e.source === selected.value?.id).length);
function selectNode(node: AtlasNode) {
  select(node);
  if (drawer.value) { drawerNode.value = node; sourceLine.value = undefined; }
}
function openDrawer(tab: 'source' | 'relations', node = selected.value, line?: number) {
  if (!node) return; drawerNode.value = node; sourceLine.value = line; drawer.value = tab;
}
function showEdge(edge: AtlasEdge) {
  const node = byId.value.get(edge.source); if (!node) return;
  openDrawer(edge.sourceLines?.length ? 'source' : 'relations', node, edge.sourceLines?.[0]);
}
function openFileScope(path: string) { focusFile.value = path; level.value = 'symbols'; clearSelection(); drawer.value = null; mode.value = '2d'; }
function openModule(name: string) { focusFile.value = ''; module.value = name; query.value = ''; level.value = 'files'; drawer.value = null; void load(); }
function goOverview() { focusFile.value = ''; level.value = 'files'; drawer.value = null; overview(); }
function chooseLevel(next: AtlasCardLevel) { level.value = next; focusFile.value = ''; clearSelection(); drawer.value = null; }
function search() { focusFile.value = ''; drawer.value = null; void load(); }
function chooseMode(value: '3d' | '2d') { mode.value = value; threeError.value = ''; }
function changeRenderer(compatible: boolean) { forceCompatibility.value = compatible; compatibility3d.value = compatible; threeError.value = ''; diagnosticReason.value = ''; mode.value = '3d'; rendererVersion.value++; }
function ready3d(engine: 'webgl' | 'compatible') { compatibility3d.value = engine === 'compatible'; }
function degraded3d(reason: string) {
  diagnosticReason.value = reason;
  if (forceCompatibility.value) { compatibility3d.value = true; threeError.value = '已启用兼容 3D'; return; }
  mode.value = '2d'; threeError.value = 'WebGL 不可用，已切换到依赖画布。';
}
function fallback3d(reason = '') { diagnosticReason.value = reason; mode.value = '2d'; threeError.value = '3D 初始化失败，已切换到依赖画布。'; }
function camera() { return mode.value === '3d' ? threeView.value : flatView.value; }
function dismissSelection() { clearSelection(); drawer.value = null; }
watch([() => data.value?.repositoryId, () => data.value?.contentVersion], () => {
  drawer.value = null; relationKind.value = ''; focusFile.value = '';
  hasSearchResults.value = !!data.value && !!query.value.trim();
  if (data.value) level.value = fileCount.value <= 1 ? 'symbols' : 'files';
});
</script>
<template>
  <section class="atlas" :class="{ 'has-drawer': drawer }" aria-label="代码图谱">
    <div class="atlas-toolbar">
      <button class="tool nav-toggle" :aria-label="navigatorOpen ? '收起代码结构' : '展开代码结构'" :aria-expanded="navigatorOpen" @click="navigatorOpen = !navigatorOpen"><PanelLeftClose v-if="navigatorOpen" :size="17"/><PanelLeftOpen v-else :size="17"/></button>
      <div class="view-switch" aria-label="节点显示方式">
        <button data-view-2d :aria-pressed="mode === '2d'" @click="chooseMode('2d')"><Layers :size="14"/>依赖画布</button>
        <button data-view-3d :aria-pressed="mode === '3d'" @click="chooseMode('3d')"><Orbit :size="14"/>3D 空间</button>
      </div>
      <form class="atlas-search" @submit.prevent="search"><Search :size="15"/><input v-model="query" aria-label="搜索符号或文件" placeholder="搜索文件、函数或类…" maxlength="500"/><button type="submit" :disabled="loading">定位 <span>↵</span></button></form>
      <el-select v-model="relationKind" :empty-values="[null, undefined]" class="relation-select" aria-label="关系类型"><el-option value="" label="全部关系" /><el-option v-for="kind in kinds" :key="kind" :value="kind" :label="kind" /></el-select>
      <button class="tool" :disabled="loading" aria-label="刷新图谱" @click="load()"><RefreshCw :size="15" :class="{ spinning: loading }"/></button>
      <details class="render-diagnostics"><summary aria-label="图谱设置"><SlidersHorizontal :size="16"/></summary><div class="render-panel">
        <strong>图谱设置</strong>
        <p>卡片表示文件与 CodeGraph 符号；模块按依赖组织，箭头指向被依赖的代码。</p>
        <p>点击文件进入符号层。拖动画布平移、滚轮缩放，双击符号展开邻居，点击连线查看关联出处。</p>
        <label class="depth-control">展开深度 <el-select v-model="depth" aria-label="展开深度"><el-option :value="1" label="1 跳" /><el-option :value="2" label="2 跳" /><el-option :value="3" label="3 跳" /></el-select></label>
        <label v-if="mode === '3d'"><input v-model="autoRotate" type="checkbox"/> 自动环绕</label>
        <p v-if="mode === '3d'">当前模式：{{ compatibility3d ? '兼容 3D' : 'WebGL 2' }}</p>
        <p v-if="diagnosticReason" class="diagnostic-reason">{{ diagnosticReason }}</p>
        <div><button data-render-auto @click="changeRenderer(false)">重新检测 3D</button><button data-render-compatible @click="changeRenderer(true)">兼容 3D</button></div>
        <p v-if="data">内容版本：{{ data.contentVersion }}</p><p>关系来自静态代码解析；总览聚合当前已加载数据。</p>
        <p v-if="data?.unmappedNodes">{{ data.unmappedNodes }} 个无文件位置节点未进入画布。</p>
        <template v-if="impact"><p>影响分析：{{ impact.relationSource }} · {{ impact.cliVersion }}</p><p>映射 {{ impact.coverage.representedNodeCount }} / {{ impact.coverage.cliReportedNodeCount }} 节点；{{ impact.coverage.representedEdgeCount }} / {{ impact.coverage.cliReportedEdgeCount }} 条关系</p><p v-for="item in impact.limitations" :key="item">{{ item }}</p></template>
      </div></details>
    </div>
    <div class="atlas-caption">
      <span class="branch-chip" :title="branches.context?.branchName || '当前版本'"><GitBranch :size="13"/>{{ branches.context?.branchName || '当前版本' }}</span>
      <nav aria-label="图谱路径"><button @click="goOverview"><Layers :size="13"/>{{ repositories.selectedRepository?.name || '全部代码' }}</button><template v-if="module"><ChevronRight :size="12"/><button @click="openModule(module)">{{ module }}</button></template><template v-if="focusFile"><ChevronRight :size="12"/><span :title="focusFile">{{ focusFile.replace(/\\/g, '/').split('/').pop() }}</span></template><span v-else-if="hasSearchResults">/ 搜索结果</span></nav>
      <span v-if="threeError" class="render-notice" role="status">{{ threeError }}</span>
      <div v-if="mode === '2d' && !hasSearchResults" class="level-switch" aria-label="图谱层级"><button :aria-pressed="effectiveLevel === 'files'" @click="chooseLevel('files')">文件概览</button><button :aria-pressed="effectiveLevel === 'symbols'" @click="chooseLevel('symbols')">符号关系</button></div>
    </div>
    <div class="atlas-workbench">
      <aside v-if="navigatorOpen" class="atlas-navigator" aria-label="代码结构">
        <div class="navigator-heading"><span>代码结构</span><span>{{ fileCount }} 文件</span></div>
        <button class="repository-root" :class="{ active: !focusFile && !module }" @click="goOverview"><Layers :size="15"/><span>全部代码</span><ChevronRight :size="13"/></button>
        <div class="tree-scroll">
          <details v-for="group in tree" :key="group.name" open class="tree-module">
            <summary><ChevronRight class="tree-chevron" :size="12"/><Folder :size="14"/><span :title="group.name">{{ group.name || '根目录' }}</span><small>{{ group.files.length }}</small></summary>
            <button class="module-overview" :disabled="loading" @click="openModule(group.name)">查看模块依赖 <ChevronRight :size="11"/></button>
            <button v-for="file in group.files" :key="file.path" class="tree-file" :class="{ active: focusFile === file.path }" :title="file.path" @click="openFileScope(file.path)"><FileCode2 :size="13"/><span>{{ file.name }}</span><small>{{ file.count }}</small></button>
          </details>
          <p v-if="!tree.length" class="tree-empty">{{ loading ? '正在读取结构…' : '加载图谱后显示代码结构' }}</p>
        </div>
        <div class="navigator-note"><span class="status-dot"/>基于当前版本的代码关系</div>
      </aside>
      <div class="atlas-stage" :class="{ 'is-spatial': mode === '3d' }">
        <CodeAtlas3D v-if="mode === '3d' && data?.nodes.length" :key="rendererVersion" ref="threeView" :nodes="canvasNodes" :edges="visibleEdges" :layout-edges="data.edges" :selected-id="selected?.id" :connected="connected" :highlighted="highlighted" :auto-rotate="autoRotate" :force-compatibility="forceCompatibility" @select="selectNode" @clear-selection="dismissSelection(); drawer = null" @expand="selectNode($event); expand()" @edge="showEdge" @ready="ready3d" @degraded="degraded3d" @unavailable="fallback3d"/>
        <CodeAtlas2D v-if="mode === '2d' && data?.nodes.length" ref="flatView" :nodes="canvasNodes" :edges="visibleEdges" :selected-id="selected?.id" :connected="connected" :highlighted="highlighted" :level="effectiveLevel" :scope-key="focusFile" @select="selectNode" @expand="selectNode($event); expand()" @edge="showEdge" @file="openFileScope" @module="openModule"/>
        <div v-if="!data?.nodes.length" class="atlas-empty"><div class="empty-icon"><Network :size="30"/></div><h2>{{ loading ? '正在展开代码结构' : error ? '图谱暂不可用' : '当前范围没有代码' }}</h2><p>{{ error || '选择已构建图谱的项目，或搜索其他文件与符号。' }}</p><button v-if="!data" @click="router.push('/overview')">前往项目准备</button><button v-else @click="goOverview">返回全部代码</button></div>
        <div v-if="loading && data?.nodes.length" class="graph-notice" role="status">正在加载关联…</div>
        <div v-else-if="error && data" class="graph-notice error" role="alert">{{ error }}</div>
        <div v-else-if="notice" class="graph-notice" role="status">{{ notice }}</div>
        <div v-if="focusFile && !selected && !loading" class="scope-hint"><FileCode2 :size="13"/><span>文件内符号与直接关联</span><button aria-label="退出文件范围" @click="chooseLevel('files')"><X :size="13"/></button></div>
        <div v-if="impact?.paths.length" class="path-control"><GitBranch :size="15"/><el-select class="path-select" v-model="pathIndex" aria-label="影响传播路径"><el-option :value="-1" :label="`影响路径 · ${availablePaths.length} 条已加载`" /><el-option v-for="{ path, index } in availablePaths" :key="index" :value="index" :label="`${path.depth} 跳 → ${byId.get(path.targetNodeId)?.label || path.targetNodeId}`" /></el-select><span v-if="pathNodes.length" class="path-chain">{{ pathNodes.map(n => n.label).join(' → ') }}</span><button aria-label="关闭影响路径" @click="impact = null; pathIndex = -1"><X :size="14"/></button></div>
        <div class="canvas-hint" v-if="data?.nodes.length && !selected"><span>{{ effectiveLevel === 'files' && mode === '2d' ? '点击文件探索符号' : '选择节点查看代码与关联' }}</span><i/>{{ mode === '3d' ? '拖动旋转 · 右键平移 · 滚轮缩放' : '拖动平移 · 滚轮缩放' }}</div>
        <div class="camera-tools"><button aria-label="缩小" @click="camera()?.zoomBy(1.2)"><Minus :size="15"/></button><span v-if="mode === '2d'">{{ Math.round((flatView?.zoom ?? 1) * 100) }}%</span><button aria-label="放大" @click="camera()?.zoomBy(.8)"><Plus :size="15"/></button><i/><button aria-label="适应全部节点" @click="camera()?.home()"><Maximize :size="15"/></button><button aria-label="取消聚焦" @click="dismissSelection(); mode === '3d' && threeView?.home()"><Crosshair :size="15"/></button></div>
        <section v-if="selected" class="selection-bar" aria-label="节点详情">
          <div class="selection-symbol"><span class="kind-badge">{{ selected.kind }}</span><strong :title="selected.qualifiedName || selected.label">{{ selected.label }}</strong><button aria-label="关闭详情" @click="dismissSelection"><X :size="15"/></button><small :title="selected.filePath">{{ selected.filePath }}:{{ selected.startLine || '—' }}</small></div>
          <div class="selection-actions"><button :disabled="loading" @click="expand('in')">↙ 入向 {{ incoming }}</button><button :disabled="loading" @click="expand('out')">↗ 出向 {{ outgoing }}</button><button v-if="hiddenNeighbors" :disabled="loading" @click="expand()">+{{ hiddenNeighbors }} 邻居</button><button @click="openDrawer('relations')">关联出处</button><button :disabled="loading" @click="traceImpact"><GitBranch :size="14"/>影响路径</button><button class="source-action" :disabled="!selected.filePath" @click="openDrawer('source')"><Code2 :size="14"/>源码</button></div>
        </section>
      </div>
      <AtlasDetailDrawer v-if="drawer && drawerNode && data" :repository-id="data.repositoryId" :content-version="data.contentVersion" :context-id="branches.context?.contextId" :node="drawerNode" :nodes="data.nodes" :edges="data.edges" :tab="drawer" :line="sourceLine" @close="drawer = null" @tab="drawer = $event" @select="selectNode" @source="(node, line) => openDrawer('source', node, line)" @open-file="openSource"/>
    </div>
    <footer class="atlas-status"><span class="status-dot"/><span v-if="data">画布 {{ mode === '2d' ? projection.cards.length : canvasNodes.length }} {{ mode === '2d' ? '卡片' : '节点' }} · {{ mode === '2d' ? projection.edges.length : visibleEdges.length }} 关系</span><span v-if="data">已加载 {{ data.nodes.length }} 节点</span><span v-if="hiddenEdges" class="partial">{{ hiddenEdges }} 条关系暂未绘制</span><span v-if="data?.partial" class="partial">部分数据</span><button v-if="data?.partial && limit < 1200" :disabled="loading" @click="loadMore">加载更多</button><span v-if="data" class="version-tag" :title="data.contentVersion">CodeGraph · {{ data.contentVersion.slice(0, 8) }}</span></footer>
  </section>
</template>
<style scoped>
.atlas{--atlas-ink:#23344f;--atlas-muted:#7b899f;--atlas-line:#e5eaf2;--atlas-blue:var(--app-color-action);display:flex;flex-direction:column;min-width:0;height:100%;min-height:580px;overflow:hidden;border:1px solid var(--atlas-line);border-radius:12px;color:var(--atlas-ink);background:#fff;font-family:"Segoe UI","Microsoft YaHei",sans-serif}
.atlas button,.atlas input,.atlas select{font:inherit}.atlas button{cursor:pointer;color:inherit}.atlas button:disabled{cursor:default;opacity:.45}.atlas button:focus-visible,.atlas input:focus-visible,.atlas summary:focus-visible{outline:2px solid var(--atlas-blue);outline-offset:2px}.atlas button{transition:background .15s,color .15s}.branch-chip{display:flex;align-items:center;gap:6px;flex:none;max-width:160px;overflow:hidden;white-space:nowrap;padding:4px 8px;font:12px Consolas,monospace;background:#f8fafc;border:1px solid var(--atlas-line);border-radius:6px;color:#68788e}
.atlas-toolbar{display:flex;align-items:center;gap:12px;padding:14px 20px;background:#fff;font-size:12px;z-index:6}.view-switch{display:flex;background:#f3f5f9;padding:3px;border-radius:7px;flex:none}.view-switch button{display:flex;align-items:center;gap:6px;padding:6px 11px;border:0;border-radius:5px;white-space:nowrap;background:transparent;color:var(--atlas-muted)}.view-switch button[aria-pressed=true]{background:var(--app-selection-bg);color:var(--app-selection-text);box-shadow:inset 0 0 0 1px var(--app-selection-border)}.atlas-search{display:flex;align-items:center;gap:8px;flex:1;max-width:490px;min-width:170px;padding:4px 6px 4px 11px;background:#fafbfd;border:1px solid var(--atlas-line);border-radius:7px;color:var(--atlas-muted)}.atlas-search input{width:100%;min-width:0;border:0;background:none;color:var(--atlas-ink);outline-offset:0}.atlas-search input::placeholder{color:#96a1b3}.atlas-search button{display:flex;align-items:center;gap:6px;color:#728198;background:#fff;border:1px solid var(--atlas-line);border-radius:4px;padding:3px 7px;white-space:nowrap;font-size:12px}.atlas-search button span{color:#a6b0c0}.atlas-toolbar>.relation-select{margin-left:auto;width:160px;flex:none}.tool{display:flex;align-items:center;justify-content:center;background:none;border:0;padding:6px;border-radius:5px}.tool:hover{background:#f1f5fc}.nav-toggle{color:#8491a5!important}.render-diagnostics{position:relative}.render-diagnostics summary{list-style:none;display:flex;padding:5px;cursor:pointer;color:var(--atlas-muted)}.render-diagnostics summary::-webkit-details-marker{display:none}.render-panel{position:absolute;right:0;top:32px;width:300px;max-width:75vw;max-height:65vh;overflow:auto;padding:18px;background:#fff;border:1px solid var(--atlas-line);border-radius:var(--app-control-radius);box-shadow:var(--app-menu-shadow);line-height:1.8;overflow-wrap:anywhere}.render-panel strong{display:block;margin-bottom:10px}.render-panel p{margin:0 0 12px;color:var(--atlas-muted)}.render-panel button{padding:5px 8px;margin:0 5px 10px 0;background:#f6f8fc;border:1px solid var(--atlas-line);border-radius:5px}.render-panel label{display:flex;gap:10px;align-items:center;margin-bottom:12px}
.atlas-caption{display:flex;align-items:center;gap:12px;min-height:45px;padding:0 21px;font-size:12px;background:#fff;border-block:1px solid var(--atlas-line);flex-wrap:wrap}.atlas-caption nav{display:flex;align-items:center;gap:9px;color:#a1acbd;min-width:0;flex:1;overflow:hidden}.atlas-caption nav>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#50617b}.atlas-caption button{display:flex;align-items:center;gap:7px;background:none;border:0;color:#6e7d93;padding:0;white-space:nowrap}.atlas-caption button:hover{color:var(--atlas-blue)}.level-switch{display:flex;align-items:center;gap:17px;align-self:stretch}.level-switch button{border-bottom:2px solid transparent;padding:0 2px}.level-switch button[aria-pressed=true]{color:var(--app-selection-text);border-bottom-color:var(--app-selection-text)}.render-notice{color:#ab7721}
.atlas-workbench{display:flex;flex:1;min-height:0;overflow:hidden}.atlas-navigator{width:215px;flex:none;display:flex;flex-direction:column;border-right:1px solid var(--atlas-line);background:#fff;min-height:0}.navigator-heading{display:flex;justify-content:space-between;align-items:center;padding:19px 17px 14px;font-size:12px;color:#6e7f97}.navigator-heading span:first-child{font-weight:600;color:#51627a}.navigator-heading span:last-child{font:12px Consolas,monospace;color:#9aa6b8}.repository-root{display:flex;align-items:center;gap:8px;border:0;background:none;margin:0 10px 10px;padding:9px;border-radius:6px;font-size:12px;text-align:left}.repository-root span{flex:1}.repository-root.active{background:var(--app-selection-bg);color:var(--app-selection-text)}.tree-scroll{flex:1;overflow:auto;padding:0 10px}.tree-module{margin-bottom:8px}.tree-module summary{list-style:none;display:flex;align-items:center;gap:7px;cursor:pointer;padding:7px 4px;font-size:12px;color:#64758e}.tree-module summary::-webkit-details-marker{display:none}.tree-module summary span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600}.tree-module small{font:12px Consolas,monospace;color:#9ba7b9}.tree-module[open] .tree-chevron{transform:rotate(90deg)}.module-overview{display:flex;align-items:center;gap:4px;font-size:12px!important;color:#8c9bb1!important;border:0;background:none;padding:4px 0 7px 27px}.module-overview:hover{color:var(--atlas-blue)!important}.tree-file{width:100%;display:flex;align-items:center;gap:7px;text-align:left;border:0;border-radius:5px;padding:8px 6px 8px 25px;background:none;color:#7a899d!important;font-size:12px!important}.tree-file svg{flex:none}.tree-file span{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:Consolas,"Microsoft YaHei",monospace}.tree-file:hover{background:#f5f7fb}.tree-file.active{background:var(--app-selection-bg);color:var(--app-selection-text)!important}.tree-empty{font-size:12px;color:#8997aa;padding:10px}.navigator-note{display:flex;align-items:center;gap:6px;font-size:12px;color:#96a2b4;padding:16px;border-top:1px solid var(--atlas-line)}
.atlas-stage{position:relative;flex:1;min-width:0;min-height:310px;overflow:hidden;background:#f6f8fb}.atlas-empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:14px;color:var(--atlas-muted);padding:28px;text-align:center}.empty-icon{padding:18px;background:#edf2fa;border:1px solid #dfe7f4;border-radius:18px;color:#7b98c7}.atlas-empty h2{font-size:16px;color:#4f627d;margin:0}.atlas-empty p{font-size:12px;max-width:440px;line-height:1.8;margin:0}.atlas-empty button{border:1px solid var(--atlas-line);background:#fff;border-radius:6px;padding:8px 14px;font-size:12px}
.camera-tools{position:absolute;right:18px;bottom:17px;display:flex;align-items:center;gap:2px;padding:4px;background:#fff;border:1px solid #e0e6f0;border-radius:8px;box-shadow:0 3px 12px #23344f08;z-index:3}.camera-tools button{display:flex;align-items:center;justify-content:center;border:0;background:none;padding:6px;border-radius:4px;color:#64758c}.camera-tools button:hover{color:var(--atlas-blue);background:#edf3ff}.camera-tools>span{font:12px Consolas,monospace;color:#7c8ba1;min-width:30px;text-align:center}.camera-tools i{height:15px;width:1px;background:var(--atlas-line);margin:0 4px}.canvas-hint{position:absolute;bottom:26px;left:20px;display:flex;align-items:center;gap:9px;color:#9aa7b9;font-size:12px;pointer-events:none}.canvas-hint i{height:3px;width:3px;background:#acb7c8;border-radius:50%}.canvas-hint span{color:#8293ac}.scope-hint{position:absolute;top:15px;left:17px;display:flex;align-items:center;gap:7px;color:#7386a2;font-size:12px;background:#ffffffed;border:1px solid var(--atlas-line);border-radius:6px;padding:7px 10px}.scope-hint button{display:flex;border:0;background:none;padding:0;margin-left:5px;color:#91a0b5}
.selection-bar{position:absolute;bottom:68px;left:50%;transform:translateX(-50%);width:min(660px,calc(100% - 32px));box-sizing:border-box;display:flex;flex-direction:column;gap:10px;background:#fff;border:1px solid #dce5f2;border-radius:10px;box-shadow:0 8px 30px #23344f12;padding:13px 15px;z-index:3}.selection-symbol{display:flex;align-items:center;gap:9px;flex-wrap:wrap;min-width:0}.kind-badge{font:12px Consolas,monospace;color:#4477c4;background:#edf3ff;border-radius:4px;padding:3px 6px}.selection-symbol strong{font:600 13px Consolas,"Microsoft YaHei",monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:70%}.selection-symbol>button{margin-left:auto;padding:0;border:0;background:none;display:flex;color:#8492a5}.selection-symbol small{flex-basis:100%;font:12px Consolas,monospace;color:#8c99ad;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.selection-actions{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.selection-actions button{display:flex;align-items:center;gap:5px;font-size:12px;border:1px solid var(--atlas-line);background:#fff;padding:6px 8px;border-radius:5px;color:#6b7f99}.selection-actions .source-action{margin-left:auto;background:#3474e6;border-color:#3474e6;color:white}.selection-actions button:hover{border-color:#93b4ef}
.atlas-status{display:flex;flex-wrap:wrap;align-items:center;gap:13px;background:#fff;border-top:1px solid var(--atlas-line);padding:10px 18px;font-size:12px;color:#8e9aae}.status-dot{width:5px;height:5px;border-radius:50%;background:#71aa9b;flex:none}.atlas-status button{padding:0;background:none;border:0;color:var(--atlas-blue)}.version-tag{margin-left:auto;font-family:Consolas,monospace}.partial{color:#ac853d}.graph-notice{position:absolute;top:15px;left:17px;max-width:70%;font-size:12px;color:#53719a;background:#fff;border:1px solid var(--atlas-line);padding:8px 12px;border-radius:6px;z-index:4}.graph-notice.error{color:#b74343}.path-control{position:absolute;top:54px;left:17px;display:flex;align-items:center;gap:8px;max-width:calc(100% - 34px);background:#fff;padding:8px 12px;border:1px solid var(--atlas-line);border-radius:6px;z-index:3;font-size:12px;color:#368575}.path-control .path-select{width:240px;max-width:100%;min-width:100px}.path-chain{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:300px}.path-control button{border:0;background:none;display:flex}.spinning{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:1100px){.has-drawer .atlas-navigator{display:none}.atlas-navigator{width:185px}.atlas-toolbar{gap:7px;padding-inline:13px}.canvas-hint{display:none}.path-chain{display:none}}
@media(max-width:760px){.atlas{height:auto;min-height:650px}.atlas-toolbar{flex-wrap:wrap}.atlas-search{order:2;max-width:none;flex-basis:100%}.atlas-navigator{display:none}.atlas-caption{padding-inline:13px;gap:6px}.atlas-caption{padding-block:8px}.atlas-caption nav{flex-basis:calc(100% - 112px)}.level-switch{height:34px}.atlas-workbench{flex:none;flex-wrap:wrap;overflow:visible}.atlas-stage{flex-basis:100%;height:430px;min-height:430px}.selection-bar{padding:10px;bottom:62px}.atlas-status{gap:8px}.selection-actions button{padding:5px 6px}.branch-chip{max-width:100px;overflow:hidden}.nav-toggle{display:none}.render-panel{right:0}}
@media(prefers-reduced-motion:reduce){.atlas button{transition:none}.spinning{animation:none}}
.is-spatial{background:#070e20}.is-spatial .camera-tools{background:#111f35;border-color:#2c405d;box-shadow:0 5px 20px #03081744}.is-spatial .camera-tools button{color:#a9bedc}.is-spatial .camera-tools button:hover{background:#213853;color:#eef5ff}.is-spatial .camera-tools i{background:#314764}.is-spatial .canvas-hint,.is-spatial .canvas-hint span{color:#829ab9}.is-spatial .selection-bar{background:#111f35f5;border-color:#344d6c;box-shadow:0 12px 40px #02071066;color:#dae8fc}.is-spatial .kind-badge{background:#233958;color:#9fc7ff}.is-spatial .selection-symbol small{color:#96aac5}.is-spatial .selection-actions button{background:#182a44;border-color:#344b6b;color:#b8cce7}.is-spatial .selection-actions .source-action{background:#407bcc;border-color:#568bce;color:#fff}.is-spatial .graph-notice{top:52px;left:auto;right:17px;background:#162741;border-color:#344b6b;color:#b7cce9}.is-spatial .graph-notice.error{color:#ffb4b4}.is-spatial .path-control{top:82px;left:auto;right:17px;background:#122138;border-color:#344b6b;color:#b7d8ec}.is-spatial .scope-hint{display:none}
.depth-control .el-select { width: 110px; }
.atlas :deep(.el-select__input:focus-visible),
.atlas-search input:focus-visible { outline: none; }
.atlas-search:focus-within { border-color: var(--app-color-action); box-shadow: var(--app-focus-shadow); }
@media (max-width: 760px) {
  .atlas-toolbar { position: relative; }
  .atlas-toolbar > .relation-select { width: 140px; }
  .path-control { flex-wrap: wrap; }
  .path-control .path-select { width: 200px; max-width: calc(100vw - 100px); }
  .render-diagnostics { position: static; }
  .render-panel { right: 12px; top: calc(100% + 6px); width: min(300px, calc(100vw - 48px)); max-width: calc(100vw - 48px); }
}
</style>
