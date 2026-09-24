<script setup lang="ts">
import { computed, shallowRef, ref, watch, defineAsyncComponent } from 'vue';
import { Search, Orbit, ArrowLeft, Plus, Minus, Maximize, RefreshCw, X, Crosshair, GitBranch, Code2 } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import { atlasEdgeKey, type AtlasNode, type AtlasEdge } from '@/api/codeAtlas';
import { browserAtlasDisplayRecommendation } from './atlasPerformance';
import { useAtlasExplorer } from './useAtlasExplorer';
import CodeAtlas2D from './CodeAtlas2D.vue';
import AtlasDetailDrawer from './AtlasDetailDrawer.vue';
const CodeAtlas3D = defineAsyncComponent(() => import('./CodeAtlas3D.vue'));
const { repositories, branches, data, selected, query, module, loading, error, notice, depth, limit,
  impact, pathIndex, select, clearSelection, load, overview, loadMore, expand, traceImpact, openSource } = useAtlasExplorer();
const router = useRouter();
const recommendation = browserAtlasDisplayRecommendation();
const mode = shallowRef<'3d' | '2d'>(recommendation.mode), automaticModeReason = shallowRef(recommendation.reason);
const forceCompatibility = shallowRef(false), compatibility3d = shallowRef(false), rendererVersion = shallowRef(0);
const diagnosticReason = shallowRef(''), threeError = shallowRef(''), autoRotate = shallowRef(false);
const threeView = ref<{ home: () => void; zoomBy: (factor: number) => void }>(), flatView = ref<InstanceType<typeof CodeAtlas2D>>();
const relationKind = shallowRef(''), drawer = shallowRef<'source' | 'relations' | null>(null), sourceLine = shallowRef<number>();
const drawerNode = shallowRef<AtlasNode | null>(null);
const kinds = computed(() => data.value?.relationKinds ?? [...new Set(data.value?.edges.map(e => e.kind) ?? [])].sort());
const byId = computed(() => new Map(data.value?.nodes.map(n => [n.id, n]) ?? []));
const availablePaths = computed(() => impact.value?.paths.map((path, index) => ({ path, index })).filter(({ path }) => path.nodeIds.every(id => byId.value.has(id))) ?? []);
const activePath = computed(() => impact.value?.paths[pathIndex.value]);
const highlighted = computed(() => {
  const ids = new Set(activePath.value?.edgeIds ?? []);
  return new Set(impact.value?.edges.filter(e => ids.has(e.id)).map(e => atlasEdgeKey({ source: e.source, target: e.target, kind: e.relation, count: 1 })) ?? []);
});
const pathNodes = computed(() => activePath.value?.nodeIds.flatMap(id => byId.value.get(id) ? [byId.value.get(id)!] : []) ?? []);
const connected = computed(() => {
  if (activePath.value) return new Set(activePath.value.nodeIds);
  const ids = new Set<string>(); if (!selected.value) return ids;
  ids.add(selected.value.id);
  data.value?.edges.forEach(e => { if (e.source === selected.value?.id) ids.add(e.target); if (e.target === selected.value?.id) ids.add(e.source); });
  return ids;
});
const filteredEdges = computed(() => (data.value?.edges ?? []).filter(e => !relationKind.value || e.kind === relationKind.value));
const visibleEdges = computed(() => [...filteredEdges.value].sort((a, b) => {
  const rank = (e: AtlasEdge) => highlighted.value.has(atlasEdgeKey(e)) ? 2 : e.source === selected.value?.id || e.target === selected.value?.id ? 1 : 0;
  return rank(b) - rank(a);
}).slice(0, mode.value === '2d' ? 1200 : 3000));
const hiddenEdges = computed(() => filteredEdges.value.length - visibleEdges.value.length);
const related = computed(() => data.value?.edges.filter(e => e.source === selected.value?.id || e.target === selected.value?.id) ?? []);
const hiddenNeighbors = computed(() => selected.value?.neighborCount !== undefined ? Math.max(0, selected.value.neighborCount - new Set(related.value.flatMap(e => [e.source, e.target]).filter(id => id !== selected.value?.id)).size) : selected.value?.hiddenNeighborCount ?? 0);
const incoming = computed(() => selected.value?.incomingCount ?? related.value.filter(e => e.target === selected.value?.id).length);
const outgoing = computed(() => selected.value?.outgoingCount ?? related.value.filter(e => e.source === selected.value?.id).length);
function selectNode(node: AtlasNode) { select(node); drawer.value = null; }
function openDrawer(tab: 'source' | 'relations', node = selected.value, line?: number) {
  if (!node) return; drawerNode.value = node; sourceLine.value = line; drawer.value = tab;
}
function showEdge(edge: AtlasEdge) {
  const node = byId.value.get(edge.source); if (!node) return;
  openDrawer(edge.sourceLines?.length ? 'source' : 'relations', node, edge.sourceLines?.[0]);
}
function chooseMode(value: '3d' | '2d') { mode.value = value; automaticModeReason.value = ''; }
function changeRenderer(compatible: boolean) { forceCompatibility.value = compatible; compatibility3d.value = compatible; threeError.value = ''; diagnosticReason.value = ''; mode.value = '3d'; rendererVersion.value++; }
function ready3d(engine: 'webgl' | 'compatible') { compatibility3d.value = engine === 'compatible'; }
function degraded3d(reason: string) {
  diagnosticReason.value = reason;
  if (forceCompatibility.value) { compatibility3d.value = true; threeError.value = '已启用兼容 3D'; return; }
  mode.value = '2d'; automaticModeReason.value = 'WebGL 不可用'; threeError.value = 'WebGL 不可用，已切换到轻量平面。';
}
function fallback3d(reason = '') { diagnosticReason.value = reason; mode.value = '2d'; threeError.value = '3D 初始化失败，已切换到平面模式。'; }
function camera() { return mode.value === '3d' ? threeView.value : flatView.value; }
watch(() => data.value?.contentVersion, () => { drawer.value = null; relationKind.value = ''; });
watch(() => selected.value?.id, () => { drawer.value = null; });
</script>
<template>
  <section class="atlas" :class="{ 'is-lightweight': mode === '2d', 'has-drawer': drawer }">
    <header class="atlas-toolbar">
      <div class="view-switch" aria-label="节点显示方式"><button :aria-pressed="mode === '3d'" @click="chooseMode('3d')">空间视图</button><button data-view-2d :aria-pressed="mode === '2d'" @click="chooseMode('2d')">轻量平面</button></div>
      <form class="atlas-search" @submit.prevent="load()"><Search :size="16"/><input v-model="query" aria-label="搜索符号或文件" placeholder="搜索符号、限定名或文件…" maxlength="500"/><button type="submit" :disabled="loading">定位</button></form>
      <select v-model="relationKind" aria-label="关系类型"><option value="">全部关系</option><option v-for="kind in kinds" :key="kind" :value="kind">{{ kind }}</option></select>
      <label class="depth-control">深度 <select v-model="depth" aria-label="展开深度"><option :value="1">1 跳</option><option :value="2">2 跳</option><option :value="3">3 跳</option></select></label>
      <button class="tool" :disabled="loading" aria-label="刷新图谱" @click="load()"><RefreshCw :size="16"/></button>
      <details class="render-diagnostics"><summary>图例与设置</summary><div class="render-panel">
        <p>区域表示模块与文件归属。小球表示 CodeGraph 符号，箭头沿真实关系方向；继承、实现关系用虚线区分。</p>
        <p>拖动旋转／平移 · 滚轮缩放 · 双击节点展开邻居 · 点击连线查看发生位置。</p>
        <label v-if="mode === '3d'"><input v-model="autoRotate" type="checkbox"/> 自动环绕</label>
        <p>当前模式：{{ mode === '2d' ? '轻量平面' : compatibility3d ? '兼容 3D' : 'WebGL 2' }}</p>
        <p v-if="diagnosticReason" class="diagnostic-reason">{{ diagnosticReason }}</p>
        <div><button data-render-auto @click="changeRenderer(false)">重新检测 WebGL</button><button data-render-compatible @click="changeRenderer(true)">使用兼容 3D</button></div>
        <p v-if="data">内容版本：{{ data.contentVersion }}</p><p>关系来自静态代码解析。</p>
        <p v-if="data?.unmappedNodes">{{ data.unmappedNodes }} 个无文件位置节点未进入画布。</p>
        <template v-if="impact"><p>影响分析：{{ impact.relationSource }} · {{ impact.cliVersion }}</p><p>映射 {{ impact.coverage.representedNodeCount }} / {{ impact.coverage.cliReportedNodeCount }} 节点；{{ impact.coverage.representedEdgeCount }} / {{ impact.coverage.cliReportedEdgeCount }} 条关系</p><p v-for="item in impact.limitations" :key="item">{{ item }}</p></template>
      </div></details>
    </header>
    <div class="atlas-caption"><button v-if="module || query" @click="overview"><ArrowLeft :size="14"/> 全部代码</button><strong>{{ repositories.selectedRepository?.name || '代码图谱' }}</strong><span>{{ module || (query ? '定位与邻域' : '模块 / 文件 / 符号') }}</span><span v-if="automaticModeReason && mode === '2d'" class="performance-note">已自动启用轻量平面 · {{ automaticModeReason }}</span><span v-if="threeError" class="render-notice" role="status">{{ threeError }}</span></div>
    <div class="atlas-stage">
      <CodeAtlas3D v-if="mode === '3d' && data?.nodes.length" :key="rendererVersion" ref="threeView" :nodes="data.nodes" :edges="visibleEdges" :selected-id="selected?.id" :connected="connected" :highlighted="highlighted" :auto-rotate="autoRotate" :force-compatibility="forceCompatibility" @select="selectNode" @expand="selectNode($event); expand()" @edge="showEdge" @ready="ready3d" @degraded="degraded3d" @unavailable="fallback3d"/>
      <CodeAtlas2D v-if="mode === '2d' && data?.nodes.length" ref="flatView" :nodes="data.nodes" :edges="visibleEdges" :selected-id="selected?.id" :connected="connected" :highlighted="highlighted" @select="selectNode" @expand="selectNode($event); expand()" @edge="showEdge"/>
      <div v-if="!data?.nodes.length" class="atlas-empty"><Orbit :size="40"/><h2>{{ loading ? '正在读取代码图谱' : error ? '图谱暂不可用' : '当前范围没有符号' }}</h2><p>{{ error || '选择已发布 CodeGraph 的项目，或调整搜索范围。' }}</p><button v-if="!data" @click="router.push('/overview')">前往项目准备</button><button v-else @click="overview">返回全部代码</button></div>
      <div v-if="loading && data?.nodes.length" class="graph-notice" role="status">正在加载关联…</div>
      <div v-else-if="error && data" class="graph-notice error" role="alert">{{ error }}</div>
      <div v-else-if="notice" class="graph-notice" role="status">{{ notice }}</div>
      <div v-if="impact?.paths.length" class="path-control"><GitBranch :size="15"/><select v-model="pathIndex" aria-label="影响传播路径"><option :value="-1">影响路径 · {{ availablePaths.length }} 条已加载</option><option v-for="{ path, index } in availablePaths" :key="index" :value="index">{{ path.depth }} 跳 → {{ byId.get(path.targetNodeId)?.label || path.targetNodeId }}</option></select><span v-if="pathNodes.length" class="path-chain">{{ pathNodes.map(n => n.label).join(' → ') }}</span><button aria-label="关闭影响路径" @click="impact = null; pathIndex = -1"><X :size="14"/></button></div>
      <div class="camera-tools"><button aria-label="缩小" @click="camera()?.zoomBy(1.2)"><Minus :size="16"/></button><button aria-label="放大" @click="camera()?.zoomBy(.8)"><Plus :size="16"/></button><button aria-label="适应全部节点" @click="camera()?.home()"><Maximize :size="16"/></button><button aria-label="取消聚焦" @click="clearSelection"><Crosshair :size="16"/></button></div>
      <section v-if="selected" class="selection-bar" aria-label="节点详情">
        <div class="selection-symbol"><span class="kind-badge">{{ selected.kind }}</span><strong :title="selected.qualifiedName || selected.label">{{ selected.label }}</strong><button aria-label="关闭详情" @click="clearSelection"><X :size="15"/></button><small :title="selected.filePath">{{ selected.filePath }}:{{ selected.startLine || '—' }}</small></div>
        <div class="selection-actions"><button :disabled="loading" @click="expand('in')">↙ 入向 {{ incoming }}</button><button :disabled="loading" @click="expand('out')">↗ 出向 {{ outgoing }}</button><button v-if="hiddenNeighbors" :disabled="loading" @click="expand()">+{{ hiddenNeighbors }} 邻居</button><button @click="openDrawer('relations')">关联出处</button><button :disabled="loading" @click="traceImpact"><GitBranch :size="14"/> 影响路径</button><button class="source-action" @click="openDrawer('source')"><Code2 :size="14"/> 源码</button></div>
      </section>
    </div>
    <AtlasDetailDrawer v-if="drawer && drawerNode && data" :repository-id="data.repositoryId" :content-version="data.contentVersion" :context-id="branches.context?.contextId" :node="drawerNode" :nodes="data.nodes" :edges="data.edges" :tab="drawer" :line="sourceLine" @close="drawer = null" @tab="drawer = $event" @select="selectNode" @source="(node, line) => openDrawer('source', node, line)" @open-file="openSource"/>
    <footer class="atlas-status"><span v-if="data">可见 {{ data.nodes.length }} 节点 · {{ visibleEdges.length }} 关系</span><span v-if="data?.repositoryNodes !== undefined">仓库 {{ data.repositoryNodes }} 节点 / {{ data.repositoryEdges }} 关系</span><span v-if="hiddenEdges" class="partial">{{ hiddenEdges }} 条已加载关系暂未绘制，聚焦可优先查看</span><span v-if="data?.partial" class="partial">范围内仍有数据未加载</span><button v-if="data?.partial && limit < 1200" :disabled="loading" @click="loadMore">加载更多</button><span v-if="data" class="version-tag" :title="data.contentVersion">CodeGraph · {{ data.contentVersion.slice(0, 8) }}</span></footer>
  </section>
</template>
<style scoped>
.atlas{display:flex;flex-direction:column;min-width:0;height:100%;min-height:520px;overflow:hidden;border:1px solid var(--app-border);border-radius:10px;color:var(--app-text-primary);background:var(--app-canvas);font-family:"Segoe UI","Microsoft YaHei",sans-serif}.atlas button,.atlas input,.atlas select{font:inherit}.atlas button{cursor:pointer;color:inherit}.atlas button:disabled{cursor:default;opacity:.45}.atlas button:focus-visible,.atlas input:focus-visible,.atlas select:focus-visible{outline:2px solid var(--app-color-action);outline-offset:3px}.atlas-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:11px 16px;border-bottom:1px solid var(--app-border);background:var(--app-surface);font-size:12px;z-index:5}.view-switch{display:flex;background:var(--app-surface-subtle);border:1px solid var(--app-border);padding:3px;border-radius:6px;flex:none}.view-switch button{padding:5px 10px;border:0;border-radius:3px;white-space:nowrap;background:transparent;color:var(--app-text-muted)}.view-switch button[aria-pressed=true]{background:var(--app-surface);color:var(--app-color-action);box-shadow:0 1px 4px #17324d18}.atlas-search{display:flex;align-items:center;gap:8px;flex:1;min-width:180px;padding:4px 5px 4px 10px;background:var(--app-canvas);border:1px solid var(--app-border);border-radius:6px}.atlas-search input{width:100%;min-width:0;border:0;background:none;color:var(--app-text-primary)}.atlas-search button{background:var(--app-color-action);color:white;border:0;border-radius:4px;padding:5px 10px;white-space:nowrap}.atlas-toolbar select{max-width:140px;border:0;background:transparent;color:var(--app-text-muted);padding:5px 0}.depth-control{display:flex;gap:4px;align-items:center;color:var(--app-text-muted)}.tool{display:flex;background:none;border:0;padding:5px}.render-diagnostics{position:relative}.render-diagnostics summary{cursor:pointer;color:var(--app-text-muted)}.render-panel{position:absolute;right:0;top:30px;width:340px;max-height:60vh;overflow:auto;padding:16px;background:var(--app-surface);border:1px solid var(--app-border);border-radius:8px;box-shadow:0 14px 36px #17324d26;line-height:1.7;overflow-wrap:anywhere}.render-panel p{margin:0 0 12px}.render-panel button{padding:6px 8px;margin:0 6px 10px 0;background:var(--app-surface-subtle);border:1px solid var(--app-border);border-radius:4px}.atlas-caption{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:9px 17px;color:var(--app-text-muted);font-size:11px;background:var(--app-surface);border-bottom:1px solid var(--app-border)}.atlas-caption strong{font-size:13px;color:var(--app-text-primary)}.atlas-caption button{display:flex;align-items:center;gap:5px;background:none;border:0;color:var(--app-color-action);padding:0}.performance-note{margin-left:auto}.render-notice{color:var(--app-color-warning)}.atlas-stage{position:relative;flex:1;min-height:330px;overflow:hidden;background:radial-gradient(ellipse at 50% 35%,#fff 0,#f0f5f9 80%)}.atlas-empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:var(--app-text-muted);padding:24px;text-align:center}.atlas-empty h2{font-size:18px;margin:0}.atlas-empty p{font-size:13px;max-width:560px;margin:0}.atlas-empty button{border:1px solid var(--app-border);background:var(--app-surface);border-radius:6px;padding:8px 14px}.camera-tools{position:absolute;right:16px;top:16px;display:flex;gap:2px;padding:4px;background:var(--app-surface);border:1px solid var(--app-border);border-radius:7px;box-shadow:0 4px 16px #17324d0d;z-index:3}.camera-tools button{display:flex;align-items:center;justify-content:center;border:0;background:none;padding:7px}.camera-tools button:hover{color:var(--app-color-action);background:var(--app-color-action-soft)}.selection-bar{position:absolute;bottom:18px;left:50%;transform:translateX(-50%);width:min(780px,calc(100% - 40px));box-sizing:border-box;display:flex;flex-direction:column;gap:10px;background:var(--app-surface);border:1px solid var(--app-border);border-radius:10px;box-shadow:0 8px 32px #17324d18;padding:12px 16px;z-index:3}.selection-symbol{display:flex;align-items:center;gap:9px;flex-wrap:wrap;min-width:0}.kind-badge{font:11px Consolas,monospace;color:var(--app-color-evidence,#168fa3);background:#e9f4f5;border-radius:4px;padding:3px 6px}.selection-symbol strong{font:600 14px Consolas,"Microsoft YaHei",monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:75%}.selection-symbol>button{margin-left:auto;padding:0;border:0;background:none;display:flex}.selection-symbol small{flex-basis:100%;font:11px Consolas,monospace;color:var(--app-text-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.selection-actions{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.selection-actions button{display:flex;align-items:center;gap:5px;font-size:12px;border:1px solid var(--app-border);background:var(--app-surface);padding:6px 9px;border-radius:5px}.selection-actions .source-action{margin-left:auto;background:var(--app-color-action);border-color:var(--app-color-action);color:white}.selection-actions button:hover{border-color:var(--app-color-action)}.atlas-status{display:flex;flex-wrap:wrap;align-items:center;gap:14px;background:var(--app-surface);border-top:1px solid var(--app-border);padding:9px 16px;font-size:11px;color:var(--app-text-muted)}.atlas-status button{padding:0;background:none;border:0;color:var(--app-color-action)}.version-tag{margin-left:auto;font-family:Consolas,monospace}.partial{color:var(--app-color-warning)}.graph-notice{position:absolute;top:12px;left:16px;max-width:60%;font-size:12px;color:#35607b;background:#fff;border:1px solid var(--app-border);padding:8px 12px;border-radius:5px;z-index:3}.graph-notice.error{color:var(--app-color-danger,#b74343)}.path-control{position:absolute;top:58px;left:16px;display:flex;align-items:center;gap:8px;max-width:calc(100% - 32px);background:var(--app-surface);padding:8px 12px;border:1px solid var(--app-border);border-radius:6px;z-index:3;font-size:12px;color:#168fa3}.path-control select{min-width:100px;max-width:260px;background:transparent;border:0;color:inherit}.path-chain{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:460px}.path-control button{border:0;background:none;display:flex}@media(max-width:900px){.atlas-toolbar{gap:8px;padding:10px}.atlas-search{order:2;flex-basis:100%}.selection-bar{bottom:10px;padding:10px 12px}.path-chain{display:none}.atlas-status{gap:8px}.selection-actions button{padding:5px 7px}}
</style>