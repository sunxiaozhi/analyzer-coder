<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { X, ArrowUpRight } from 'lucide-vue-next';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';
import { getRepositoryFile } from '@/api/repositories';
const props = defineProps<{ repositoryId: string; contentVersion: string; contextId?: string; node: AtlasNode; nodes: AtlasNode[]; edges: AtlasEdge[]; tab: 'source' | 'relations'; line?: number }>();
const emit = defineEmits<{ close: []; select: [node: AtlasNode]; source: [node: AtlasNode, line?: number]; openFile: [node: AtlasNode, line?: number]; tab: [tab: 'source' | 'relations'] }>();
const source = ref(''), error = ref(''), loading = ref(false);
const contentPane = ref<HTMLElement>();
watch(() => [props.node.id, props.line, props.tab], () => { if (contentPane.value) contentPane.value.scrollTop = 0; }, { flush: 'post' });
const nodeById = computed(() => new Map(props.nodes.map(n => [n.id, n])));
const relations = computed(() => props.edges.filter(e => e.source === props.node.id || e.target === props.node.id).map(e => ({ ...e,
  neighbor: nodeById.value.get(e.source === props.node.id ? e.target : e.source), sourceNode: nodeById.value.get(e.source) })));
const excerpt = computed(() => {
  const start = Math.max(1, (props.line || props.node.startLine) - 4);
  return source.value.split('\n').slice(start - 1, Math.min(start + 100, Math.max(start + 20, props.line ? start + 20 : props.node.endLine + 3)))
    .map((text, i) => ({ text, line: start + i }));
});
watch([() => props.repositoryId, () => props.contentVersion, () => props.contextId, () => props.node.filePath, () => props.tab], async (_, __, onCleanup) => {
  let canceled = false; onCleanup(() => { canceled = true; });
  source.value = ''; error.value = ''; loading.value = false;
  if (props.tab !== 'source' || !props.node.filePath) return;
  const version = props.contentVersion; loading.value = true;
  try {
    const file = await getRepositoryFile(props.repositoryId, props.node.filePath, props.contextId);
    if (canceled) return;
    if (file.contentVersion !== version) throw new Error('源码版本已更新，请刷新图谱后查看。');
    source.value = file.content;
  } catch (e) { if (!canceled) error.value = e instanceof Error ? e.message : '源码读取失败'; }
  finally { if (!canceled) loading.value = false; }
}, { immediate: true });
</script>
<template>
  <section class="atlas-drawer" aria-label="代码详情抽屉" @keydown.esc="emit('close')">
    <div class="inspector-title"><span>代码详情</span><button class="close-drawer" aria-label="关闭抽屉" @click="emit('close')"><X :size="16"/></button></div>
    <div class="inspector-symbol"><code>{{ node.kind }}</code><strong :title="node.qualifiedName || node.label">{{ node.label }}</strong></div>
    <header class="drawer-heading">
      <div class="drawer-tabs"><button :aria-pressed="tab === 'source'" @click="emit('tab', 'source')">源码</button><button :aria-pressed="tab === 'relations'" @click="emit('tab', 'relations')">关联 · {{ relations.length }}</button></div>
      <button class="open-file" :disabled="!node.filePath" @click="emit('openFile', node, line)">打开文件 <ArrowUpRight :size="14"/></button>
    </header>
    <div class="drawer-path" :title="node.filePath">{{ node.filePath }}<b v-if="line">:{{ line }}</b></div>
    <div ref="contentPane" class="drawer-content">
      <template v-if="tab === 'source'"><p v-if="loading">正在加载源码…</p><p v-else-if="error" role="alert">{{ error }}</p><p v-else-if="!node.filePath">此节点没有可用的源码位置。</p><pre v-else class="atlas-source" aria-label="源码摘录，长行自动换行"><span v-for="row in excerpt" :key="row.line" :class="{ marked: line ? row.line === line : row.line >= node.startLine && row.line <= node.endLine }"><i aria-hidden="true">{{ row.line }}</i><code>{{ row.text || ' ' }}</code></span></pre></template>
      <div v-else class="relations-list">
        <div v-for="edge in relations" :key="JSON.stringify([edge.source, edge.target, edge.kind])" class="relation-row">
          <span class="edge-direction">{{ edge.source === node.id ? '出向 ↗' : '入向 ↙' }}</span>
          <button v-if="edge.neighbor" class="relation-name" @click="emit('select', edge.neighbor)">{{ edge.neighbor.label }}</button>
          <code>{{ edge.kind }}</code><span>{{ edge.count }} 处</span>
          <span class="line-links"><button v-for="at in edge.sourceLines || []" :key="at" @click="edge.sourceNode && emit('source', edge.sourceNode, at)">L{{ at }}</button><small v-if="edge.sourceLinesTruncated">更多位置见源码</small><small v-if="!edge.sourceLines?.length">无位置记录</small></span>
        </div>
        <p v-if="!relations.length">当前已加载范围内没有关联；可展开上下游继续查看。</p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.atlas-drawer{width:360px;max-width:40%;flex:none;min-width:0;display:flex;flex-direction:column;background:#fff;border-left:1px solid #e5eaf2;color:#344661}.inspector-title{display:flex;align-items:center;justify-content:space-between;padding:19px 18px 14px;font-size:12px;color:#8191a7}.inspector-symbol{display:flex;align-items:center;gap:8px;padding:0 18px 18px;min-width:0}.inspector-symbol code{padding:3px 5px;border-radius:4px;background:#edf3ff;color:#507ac0;font:12px Consolas,monospace}.inspector-symbol strong{font:600 13px Consolas,"Microsoft YaHei",monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.drawer-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;height:42px;flex:none;padding:0 18px;border-block:1px solid #e9eef5;font-size:12px}.drawer-tabs{display:flex;height:100%;gap:20px}.drawer-tabs button{border:0;border-bottom:2px solid transparent;background:none;color:#8c99ad;padding:0;white-space:nowrap}.drawer-tabs button[aria-pressed=true]{color:var(--app-selection-text);border-bottom-color:var(--app-selection-text)}.drawer-path{padding:12px 18px;background:#fafbfd;border-bottom:1px solid #edf1f6;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font:12px Consolas,monospace;color:#8c9bb0;flex:none}.open-file,.close-drawer{display:flex;align-items:center;gap:4px;white-space:nowrap;border:0;background:none;color:#6381af;padding:0}.close-drawer{color:#91a0b5}.drawer-content{overflow:auto;flex:1;min-height:0;background:#fcfdff}.drawer-content>p,.relations-list>p{padding:18px;font-size:12px;color:#8091a8;line-height:1.8}.atlas-source{margin:0;padding:16px 0;font:12px/1.95 Consolas,"Microsoft YaHei",monospace;white-space:pre-wrap}.atlas-source>span{display:grid;grid-template-columns:44px minmax(0,1fr)}.atlas-source i{font-style:normal;text-align:right;padding-right:12px;color:#a8b4c5;user-select:none}.atlas-source code{font:inherit;overflow-wrap:anywhere;padding-right:14px;color:#486384}.atlas-source .marked{background:#edf3ff;box-shadow:inset 2px 0 #6a98eb}.atlas-source .marked code{color:#305a9b}.relation-row{display:grid;grid-template-columns:52px minmax(0,1fr);gap:8px;align-items:center;border-bottom:1px solid #e9eef5;padding:14px 17px;font-size:12px}.edge-direction{color:#7592ba;font-size:12px}.relation-name{background:none;border:0;text-align:left;color:#385579;overflow-wrap:anywhere;font-family:Consolas,monospace!important;padding:0}.relation-name:hover{color:var(--app-color-action)}.relation-row>code{grid-column:2;font:12px Consolas,monospace;color:#8b9ab1}.relation-row>span:nth-of-type(2){grid-column:1;grid-row:2;font-size:12px;color:#9ca9bb}.line-links{grid-column:2;display:flex;gap:8px;flex-wrap:wrap}.line-links button{background:none;border:0;color:#4477c4;padding:0;font:12px Consolas,monospace}.line-links small{color:#93a2b6}button{cursor:pointer;font:inherit}button:focus-visible{outline:2px solid var(--app-color-action);outline-offset:2px}
@media(max-width:1100px){.atlas-drawer{width:320px;max-width:42%}}
@media(max-width:760px){.atlas-drawer{width:100%;max-width:none;height:310px;border-left:0;border-top:1px solid #e5eaf2}.inspector-title{padding:12px 16px 8px}.inspector-symbol{padding:0 16px 10px}}
</style>
