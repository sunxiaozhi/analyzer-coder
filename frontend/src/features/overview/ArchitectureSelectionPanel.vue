<script setup lang="ts">
import { computed } from 'vue';
import { Braces, FileCode2, Focus, RotateCcw, ScanSearch, ShieldAlert } from 'lucide-vue-next';
import type {
  ProjectArchitectureEdge,
  ProjectArchitectureNode,
  ProjectArchitectureRisk,
} from '@/api/repositories';

interface Props {
  node: ProjectArchitectureNode | null;
  edges: ProjectArchitectureEdge[];
  risks: ProjectArchitectureRisk[];
  focused: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  selectNode: [nodeId: string];
  focusNode: [nodeId: string];
  resetFocus: [];
  openModule: [path: string];
  openSymbols: [module: string];
  openFile: [path: string];
}>();

const evidencePaths = computed(() =>
  [...new Set(props.edges.flatMap(edge => edge.samples))].slice(0, 8),
);

const nodeKindLabel = computed(() => {
  if (props.node?.kind === 'PROJECT') return '项目根';
  if (props.node?.kind === 'RESOURCE') return '运行资源';
  return '当前模块';
});

const nodeTitle = computed(() => {
  if (!props.node) return '';
  if (props.node.id === '$project') return '当前项目';
  return props.node.kind === 'RESOURCE' ? props.node.label : props.node.id;
});

const nodeDetail = computed(() => {
  if (!props.node) return '';
  if (props.node.kind === 'RESOURCE') return props.node.resourceType ?? '外部依赖';
  return `${props.node.codeFileCount} 个代码文件`;
});

function connectedNode(edge: ProjectArchitectureEdge) {
  if (!props.node) return '';
  return edge.source === props.node.id ? edge.target : edge.source;
}

function connectionDirection(edge: ProjectArchitectureEdge) {
  if (!props.node) return '';
  if (edge.relation === 'CONNECTS_TO') {
    return edge.source === props.node.id ? '连接' : '被使用';
  }
  return edge.source === props.node.id ? '调用' : '被调用';
}
</script>

<template>
  <aside class="selection-panel">
    <div v-if="node" class="selection-title">
      <span>{{ nodeKindLabel }}</span>
      <strong>{{ nodeTitle }}</strong>
      <small>{{ nodeDetail }}</small>
    </div>

    <div class="selection-section">
      <h3>一跳连接</h3>
      <div v-if="edges.length" class="connection-list">
        <button
          v-for="edge in edges"
          :key="`${edge.relation}:${edge.source}:${edge.target}`"
          type="button"
          @click="emit('selectNode', connectedNode(edge))"
        >
          <span>{{ connectionDirection(edge) }}</span>
          <strong>{{ connectedNode(edge) }}</strong>
          <b>{{ edge.weight }}</b>
        </button>
      </div>
      <span v-else class="empty-note">未发现跨模块或运行资源连接</span>
    </div>

    <div class="selection-section evidence">
      <h3>关系证据</h3>
      <button
        v-for="path in evidencePaths"
        :key="path"
        type="button"
        :title="path"
        @click="emit('openFile', path)"
      >
        <FileCode2 :size="12" />
        <span>{{ path }}</span>
      </button>
      <span v-if="!evidencePaths.length" class="empty-note">当前节点没有可定位的来源文件</span>
    </div>

    <div class="selection-section risks">
      <h3>架构提醒</h3>
      <article v-for="risk in risks" :key="risk.id" :data-severity="risk.severity">
        <ShieldAlert :size="13" />
        <div><strong>{{ risk.title }}</strong><small>{{ risk.detail }}</small></div>
      </article>
      <span v-if="!risks.length" class="empty-note">当前节点没有边界或循环依赖提醒</span>
    </div>

    <div v-if="node && node.kind !== 'PROJECT'" class="selection-actions">
      <button v-if="!focused" type="button" class="focus-action" @click="emit('focusNode', node.id)">
        <Focus :size="13" />聚焦一跳关系
      </button>
      <button v-else type="button" class="focus-action active" @click="emit('resetFocus')">
        <RotateCcw :size="13" />返回完整地图
      </button>
      <button
        v-if="node.kind === 'MODULE'"
        type="button"
        class="symbol-action"
        @click="emit('openSymbols', node.id)"
      >
        <Braces :size="13" />浏览代码符号
      </button>

      <button
        v-if="node.kind === 'MODULE'"
        type="button"
        class="module-action"
        @click="emit('openModule', node.path)"
      >
        <ScanSearch :size="13" />检索模块资产
      </button>
    </div>
  </aside>
</template>

<style scoped>
.selection-panel { display: flex; min-width: 0; flex-direction: column; background: #fff; }
.selection-title { display: grid; gap: 4px; padding: 16px; border-bottom: 1px solid #e6ebef; }
.selection-title span { color: #7d8991; font-size: 8px; }
.selection-title strong { overflow-wrap: anywhere; color: #2d414e; font: 600 12px Consolas, monospace; }
.selection-title small { color: #8b969d; font-size: 8px; }
.selection-section { padding: 13px 16px; border-bottom: 1px solid #e6ebef; }
.selection-section h3 { margin: 0 0 8px; color: #5a6973; font-size: 9px; }
.connection-list { display: grid; gap: 5px; }
.connection-list button { display: grid; grid-template-columns: 34px minmax(0, 1fr) 20px; gap: 5px; align-items: center; min-height: 28px; padding: 5px 6px; text-align: left; border: 0; background: #f5f8fa; }
.connection-list button:hover, .connection-list button:focus-visible { outline: 1px solid #79a0b8; background: #edf5f8; }
.connection-list span { color: #7b8992; font-size: 8px; }
.connection-list strong { overflow: hidden; color: #405560; font: 500 9px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.connection-list b { color: #08795f; font: 700 9px Consolas, monospace; text-align: right; }
.evidence { display: grid; gap: 5px; }
.evidence h3 { margin-bottom: 3px; }
.evidence button { display: grid; grid-template-columns: 14px minmax(0, 1fr); align-items: center; gap: 5px; padding: 4px 0; overflow: hidden; color: #526873; text-align: left; border: 0; background: transparent; font: 500 8px Consolas, monospace; }
.evidence button:hover, .evidence button:focus-visible { color: #0066cc; outline: none; }
.evidence button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.risks { display: grid; gap: 6px; }
.risks h3 { margin-bottom: 2px; }
.risks article { display: grid; grid-template-columns: 16px 1fr; gap: 6px; padding: 7px; color: #8d4b31; border-left: 2px solid #c4852d; background: #fbf5ec; }
.risks article[data-severity='HIGH'] { color: #9b3f31; border-color: #bd452f; background: #fbefed; }
.risks article div { display: grid; gap: 3px; }
.risks strong { font-size: 9px; }
.risks small { color: #796d68; font-size: 8px; line-height: 1.45; }
.empty-note { color: #949da3; font-size: 8px; line-height: 1.5; }
.selection-actions { display: grid; grid-template-columns: 1fr; gap: 6px; margin-top: auto; padding: 12px 16px 16px; }
.selection-actions button { display: flex; align-items: center; justify-content: center; gap: 5px; padding: 7px; border-radius: 4px; font-size: 9px; }
.focus-action { color: #08795f; border: 1px solid #9dcabc; background: #eff8f5; }
.focus-action.active { color: #315f7c; border-color: #cbd9e2; background: #f6f9fb; }
.symbol-action { color: #fff; border: 1px solid #08795f; background: #08795f; }
.module-action { color: #315f7c; border: 1px solid #cbd9e2; background: #f6f9fb; }
@media (max-width: 900px) {
  .selection-panel { min-height: 230px; }
}
@media (prefers-reduced-motion: reduce) {
  .selection-actions button { transition: none; }
}
</style>
