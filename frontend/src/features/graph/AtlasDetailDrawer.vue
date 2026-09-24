<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { X, ArrowUpRight } from 'lucide-vue-next';
import type { AtlasEdge, AtlasNode } from '@/api/codeAtlas';
import { getRepositoryFile } from '@/api/repositories';
const props = defineProps<{ repositoryId: string; contentVersion: string; contextId?: string; node: AtlasNode; nodes: AtlasNode[]; edges: AtlasEdge[]; tab: 'source' | 'relations'; line?: number }>();
const emit = defineEmits<{ close: []; select: [node: AtlasNode]; source: [node: AtlasNode, line?: number]; openFile: [node: AtlasNode, line?: number]; tab: [tab: 'source' | 'relations'] }>();
const source = ref(''), error = ref(''), loading = ref(false);
const nodeById = computed(() => new Map(props.nodes.map(n => [n.id, n])));
const relations = computed(() => props.edges.filter(e => e.source === props.node.id || e.target === props.node.id).map(e => ({ ...e,
  neighbor: nodeById.value.get(e.source === props.node.id ? e.target : e.source), sourceNode: nodeById.value.get(e.source) })));
const excerpt = computed(() => {
  const start = Math.max(1, (props.line || props.node.startLine) - 4);
  return source.value.split('\n').slice(start - 1, Math.min(start + 100, Math.max(start + 20, props.line ? start + 20 : props.node.endLine + 3)))
    .map((text, i) => ({ text, line: start + i }));
});
watch(() => [props.repositoryId, props.contentVersion, props.contextId, props.node.filePath, props.tab], async (_, __, onCleanup) => {
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
    <header class="drawer-heading">
      <div class="drawer-tabs"><button :aria-pressed="tab === 'source'" @click="emit('tab', 'source')">源码</button><button :aria-pressed="tab === 'relations'" @click="emit('tab', 'relations')">关联 · {{ relations.length }}</button></div>
      <span class="drawer-path" :title="node.qualifiedName">{{ node.filePath }}<b v-if="line">:{{ line }}</b></span>
      <button class="open-file" @click="emit('openFile', node, line)">打开文件 <ArrowUpRight :size="14"/></button>
      <button class="close-drawer" aria-label="关闭抽屉" @click="emit('close')"><X :size="17"/></button>
    </header>
    <div class="drawer-content">
      <template v-if="tab === 'source'"><p v-if="loading">正在加载源码…</p><p v-else-if="error" role="alert">{{ error }}</p><pre v-else class="atlas-source" aria-label="源码摘录，长行自动换行"><span v-for="row in excerpt" :key="row.line" :class="{ marked: line ? row.line === line : row.line >= node.startLine && row.line <= node.endLine }"><i aria-hidden="true">{{ row.line }}</i><code>{{ row.text || ' ' }}</code></span></pre></template>
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
.atlas-drawer{height:270px;min-width:0;display:flex;flex-direction:column;background:var(--app-surface,#fff);border-top:1px solid var(--app-border,#d8e2e8)}.drawer-heading{display:flex;align-items:center;gap:14px;height:44px;flex:none;padding:0 16px;border-bottom:1px solid var(--app-border,#d8e2e8);font-size:12px}.drawer-tabs{display:flex;height:100%;gap:16px}.drawer-tabs button{border:0;border-bottom:2px solid transparent;background:none;color:var(--app-text-muted);padding:0 3px;white-space:nowrap}.drawer-tabs button[aria-pressed=true]{color:var(--app-color-action);border-bottom-color:var(--app-color-action)}.drawer-path{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:Consolas,monospace;color:var(--app-text-muted)}.open-file,.close-drawer{display:flex;align-items:center;gap:5px;white-space:nowrap;border:0;background:none;color:var(--app-color-action)}.drawer-content{overflow:auto;flex:1;min-height:0}.drawer-content>p,.relations-list>p{padding:15px;color:var(--app-text-muted)}.atlas-source{margin:0;padding:12px 0;font:12px/1.8 Consolas,"Microsoft YaHei",monospace;white-space:pre-wrap}.atlas-source>span{display:grid;grid-template-columns:60px minmax(0,1fr)}.atlas-source i{font-style:normal;text-align:right;padding-right:14px;color:#7d93a5;user-select:none}.atlas-source code{font:inherit;overflow-wrap:anywhere;padding-right:20px}.atlas-source .marked{background:var(--app-color-action-soft,#eaf3fe)}.relation-row{display:grid;grid-template-columns:60px minmax(130px,1fr) 120px 55px minmax(130px,1fr);gap:12px;align-items:center;border-bottom:1px solid var(--app-border);padding:10px 18px;font-size:12px}.edge-direction{color:#168fa3}.relation-name{background:none;border:0;text-align:left;color:var(--app-text-primary);overflow-wrap:anywhere}.line-links{display:flex;gap:8px;flex-wrap:wrap}.line-links button{background:none;border:0;color:var(--app-color-action);padding:0;font:12px Consolas,monospace}.line-links small{color:var(--app-text-muted)}button{cursor:pointer;font:inherit}button:focus-visible{outline:2px solid var(--app-color-action);outline-offset:3px}@media(max-width:700px){.relation-row{grid-template-columns:50px 1fr 80px}.line-links{grid-column:2/-1}.open-file{display:none}}
</style>