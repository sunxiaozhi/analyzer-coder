<script setup lang="ts">
import { ChevronRight, Code2, FileCode2, Folder } from 'lucide-vue-next';
import type { AtlasNode } from '@/api/codeAtlas';
import type { SpatialTreeEntry } from './atlasSpatialTree';

defineProps<{ entries: SpatialTreeEntry[]; expanded: Set<string>; focusedId: string | null; selectedId?: string; activeIds: Set<string> | null }>();
const emit = defineEmits<{ toggle: [id: string]; focus: [entry: SpatialTreeEntry]; select: [node: AtlasNode] }>();
const typeNames = { module: '模块', directory: '目录', file: '文件', symbol: '符号' };
</script>

<template>
  <ul class="spatial-tree">
    <li v-for="entry in entries" :key="entry.id" :style="{ '--branch-color': entry.color }">
      <div class="spatial-tree-row" :class="{ active: focusedId === entry.id || !!entry.node && entry.node.id === selectedId, ancestor: selectedId && !entry.node && entry.nodeIds.includes(selectedId), dimmed: activeIds && !entry.nodeIds.some(id => activeIds!.has(id)) }" :data-current-symbol="entry.node && entry.node.id === selectedId ? '' : undefined">
        <button v-if="entry.children.length" class="tree-disclosure" :aria-expanded="expanded.has(entry.id)" :aria-label="(expanded.has(entry.id) ? '收起 ' : '展开 ') + entry.name" @click.stop="emit('toggle', entry.id)"><ChevronRight :size="12" :class="{ expanded: expanded.has(entry.id) }"/></button>
        <span v-else class="tree-disclosure-spacer"/>
        <button class="spatial-tree-item" :data-tree-kind="entry.kind" :data-tree-path="entry.filePath" :data-tree-node="entry.node?.id" :aria-label="(entry.node ? '选中符号 ' : '聚焦' + typeNames[entry.kind] + ' ') + entry.name" :aria-pressed="focusedId === entry.id || !!entry.node && entry.node.id === selectedId" :title="entry.node ? (entry.node.qualifiedName || entry.name) + ' · ' + entry.node.kind : entry.filePath || entry.name" @click.stop="entry.node ? emit('select', entry.node) : emit('focus', entry)">
          <Code2 v-if="entry.kind === 'symbol'" :size="12"/><FileCode2 v-else-if="entry.kind === 'file'" :size="12"/><Folder v-else :size="12"/>
          <span>{{ entry.name }}</span><small v-if="!entry.node">{{ entry.nodeIds.length }}</small>
        </button>
      </div>
      <AtlasSpatialTree v-if="entry.children.length && expanded.has(entry.id)" :entries="entry.children" :expanded="expanded" :focused-id="focusedId" :selected-id="selectedId" :active-ids="activeIds" @toggle="emit('toggle', $event)" @focus="emit('focus', $event)" @select="emit('select', $event)"/>
    </li>
  </ul>
</template>

<style scoped>
.spatial-tree{list-style:none;margin:0;padding:0}.spatial-tree .spatial-tree{padding-left:12px}.spatial-tree li{min-width:0}.spatial-tree-row{display:flex;align-items:center;min-width:0;border:1px solid transparent;border-radius:4px;margin:1px 0;color:#acbed8}.spatial-tree-row:hover{background:#172944;color:#eef5ff}.spatial-tree-row.active{background:#1d3351;border-color:#456486;color:#eef5ff}.spatial-tree-row.ancestor{color:#e5efff}.spatial-tree-row.dimmed{opacity:.4}.spatial-tree-row.dimmed:hover{opacity:1}.spatial-tree-row button{display:flex;align-items:center;border:0;background:transparent;color:inherit;font:inherit;cursor:pointer}.spatial-tree-row button:focus-visible{outline:2px solid #8fbaff;outline-offset:-2px;border-radius:3px}.tree-disclosure,.tree-disclosure-spacer{width:18px;flex:none}.tree-disclosure{justify-content:center;align-self:stretch;padding:0}.tree-disclosure svg{transition:transform .15s}.tree-disclosure .expanded{transform:rotate(90deg)}.spatial-tree-item{gap:7px;padding:6px 5px 6px 0;min-width:0;flex:1;text-align:left}.spatial-tree-item>svg{color:var(--branch-color);flex:none}.spatial-tree-item>span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}.spatial-tree-item small{margin-left:auto;padding-left:4px;flex:none;font:11px Consolas,monospace;color:#8196b6}@media(prefers-reduced-motion:reduce){.tree-disclosure svg{transition:none}}
</style>
