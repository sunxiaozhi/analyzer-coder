<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { FileText } from 'lucide-vue-next';
import type { KnowledgeCard } from '@/api/intelligence';
import type { BranchContext, BranchValidationState } from '@/api/branches';
import { knowledgeExcerpt, knowledgeStatus } from './knowledgePresentation';
import { knowledgeKindLabel } from '@/utils/displayLabels';
const props = defineProps<{
  knowledgeOnly?: boolean;
  card: KnowledgeCard; canManage: boolean; canMaintain: boolean; scopeLabel: string;
  validationLabel?: string; validationState?: BranchValidationState;
  branchContext?: BranchContext | null; selected?: boolean; deleting?: boolean;
}>();
const emit = defineEmits<{
  view: [card: KnowledgeCard]; edit: [card: KnowledgeCard]; history: [card: KnowledgeCard];
  review: [card: KnowledgeCard, status: 'APPROVED' | 'CHANGES_REQUESTED'];
  delete: [card: KnowledgeCard];
  publish: [card: KnowledgeCard, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'];
}>();
const expanded = shallowRef(false);
const status = computed(() => knowledgeStatus(props.card, props.branchContext, props.validationState));
const excerpt = computed(() => knowledgeExcerpt(props.card.content || ''));
</script>

<template>
  <article class="knowledge-card" :class="{ selected }" :data-card-id="card.id">
    <header class="card-header">
      <span class="card-kind"><FileText :size="15" aria-hidden="true" />{{ knowledgeKindLabel(card.knowledgeKind) }}</span>
      <el-tag class="card-status" :type="status.type" size="small">{{ status.label }}</el-tag>
    </header>
    <h3 class="card-heading"><button type="button" class="card-title" :title="card.title" @click="emit('view', card)">{{ card.title }}</button></h3>
    <p class="card-excerpt">{{ excerpt || '暂无正文摘要' }}</p>
    <div class="card-notes">
      <span v-if="selected" class="selected-label">当前阅读</span>
      <p v-if="['STALE', 'SUSPECT'].includes(card.sourceVersionStatus)" class="source-warning">来源代码已变化，待复核</p>
      <p v-else-if="card.publicationStatus === 'DRAFT'" class="publication-note">尚未用于检索和问答</p>
    </div>
    <div class="card-tags">
      <span v-if="!card.tags.length" class="empty-tags">暂无标签</span>
      <span v-for="tag in (expanded ? card.tags : card.tags.slice(0, 3))" :key="tag" class="card-tag"># {{ tag }}</span>
      <button v-if="card.tags.length > 3" type="button" class="tags-toggle" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起标签' : `+${card.tags.length - 3}` }}</button>
    </div>
    <span class="branch-scope" :title="!knowledgeOnly && card.branchScope?.mode === 'ALL_BRANCHES' ? `${scopeLabel}，包括后续新增分支` : scopeLabel">{{ scopeLabel }}</span>
    <div class="card-meta">
      <time :datetime="card.updatedAt" :title="new Date(card.updatedAt).toLocaleString()">更新于 {{ new Date(card.updatedAt).toLocaleDateString() }}</time>
      <span class="card-revision" :aria-label="`修订 ${card.revision}`">v{{ card.revision }}</span>
    </div>
    <footer class="card-actions">
      <el-button type="primary" link @click="emit('view', card)">查看</el-button>
      <el-button v-if="canMaintain" link @click="emit('edit', card)">编辑</el-button>
      <el-button v-if="canManage && card.publicationStatus !== 'PUBLISHED'" type="primary" @click="emit('publish', card, 'PUBLISHED')">确认并发布</el-button>
      <el-dropdown v-if="canMaintain || canManage" trigger="click">
        <el-button text aria-label="更多知识操作">更多</el-button>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item v-if="canMaintain" @click="emit('history', card)">修订历史</el-dropdown-item>
          <el-dropdown-item v-if="canManage && card.publicationStatus !== 'DRAFT'" @click="emit('publish', card, 'DRAFT')">撤回为草稿</el-dropdown-item>
          <el-dropdown-item v-if="canManage && card.publicationStatus === 'DRAFT'" :disabled="deleting" divided @click="emit('delete', card)">{{ deleting ? '正在删除…' : '删除草稿' }}</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
    </footer>
  </article>
</template>

<style scoped>
.knowledge-card { display: flex; flex-direction: column; min-width: 0; gap: 8px; padding: 16px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-surface); transition: border-color 160ms, box-shadow 160ms; }
.knowledge-card:hover { border-color: var(--app-border-strong); box-shadow: 0 3px 12px rgb(23 50 77 / 6%); }
.knowledge-card:focus-within { border-color: var(--app-color-action); }
.knowledge-card.selected { border-color: var(--app-color-action); box-shadow: inset 3px 0 var(--app-color-action); background: var(--app-selection-bg); }
.card-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; min-height: 24px; }
.card-kind { display: inline-flex; flex: none; align-items: center; gap: 6px; padding-top: 2px; color: var(--app-color-evidence); font-size: 12px; font-weight: 600; }
.card-status { flex-shrink: 1; max-width: 66%; height: auto; min-height: 24px; padding: 3px 7px; text-align: right; white-space: normal; line-height: 1.4; }
.card-heading { min-width: 0; min-height: 48px; margin: 0; }
.card-title { display: -webkit-box; width: 100%; padding: 0; border: 0; background: transparent; color: var(--app-text-primary); font-family: var(--app-font-display); font-size: 16px; font-weight: 650; line-height: 1.5; text-align: left; overflow-wrap: anywhere; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-title:hover { color: var(--app-color-action); }
.card-excerpt { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; min-height: 4.95em; margin: 0; color: var(--app-text-regular); font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
.card-notes { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; min-height: 18px; font-size: 12px; }
.publication-note { margin: 0; color: var(--app-text-muted); }
.selected-label { color: var(--app-color-action); font-weight: 600; }
.source-warning { margin: 0; color: var(--app-color-warning); }
.card-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-height: 25px; }
.empty-tags { color: var(--app-text-subtle); font-size: 12px; }
.card-tag { max-width: 100%; padding: 2px 7px; border-radius: 4px; background: var(--app-surface-subtle); color: var(--app-text-muted); font-size: 12px; overflow-wrap: anywhere; }
.tags-toggle { padding: 2px 5px; border: 0; background: transparent; color: var(--app-color-action); font-size: 12px; }
.branch-scope { margin-top: auto; overflow: hidden; color: var(--app-text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.card-meta { display: flex; justify-content: space-between; gap: 8px; color: var(--app-text-subtle); font-size: 12px; }
.card-revision { font-family: var(--app-font-mono); }
.card-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 2px; padding-top: 10px; border-top: 1px solid var(--app-border); }
.card-actions :deep(.el-button + .el-button) { margin-left: 0; }
@media (max-width: 760px) {
  .card-heading, .card-excerpt { min-height: 0; }
  .card-notes:empty { display: none; }
}
@media (prefers-reduced-motion: reduce) { .knowledge-card { transition: none; } }
</style>
