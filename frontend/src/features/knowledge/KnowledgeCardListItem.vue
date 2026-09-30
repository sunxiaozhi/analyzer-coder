<script setup lang="ts">
import { computed, ref } from 'vue';
import type { KnowledgeCard } from '@/api/intelligence';
import type { BranchContext, BranchValidationState } from '@/api/branches';
import { knowledgeExcerpt, knowledgeStatus } from './knowledgePresentation';
const props = defineProps<{
  card: KnowledgeCard; canManage: boolean; canMaintain: boolean; scopeLabel: string;
  validationLabel?: string; validationState?: BranchValidationState;
  branchContext?: BranchContext | null; selected?: boolean;
}>();
const emit = defineEmits<{
  view: [card: KnowledgeCard]; edit: [card: KnowledgeCard]; history: [card: KnowledgeCard];
  review: [card: KnowledgeCard, status: 'APPROVED' | 'CHANGES_REQUESTED'];
  publish: [card: KnowledgeCard, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'];
}>();
const expanded = ref(false);
const status = computed(() => knowledgeStatus(props.card, props.branchContext, props.validationState));
const excerpt = computed(() => knowledgeExcerpt(props.card.content || ''));
</script>

<template>
  <article class="knowledge-card" :class="{ selected }" :data-card-id="card.id">
    <header class="card-header">
      <h3><button type="button" class="card-title" @click="emit('view', card)">{{ card.title }}</button></h3>
      <el-tag :type="status.type" size="small">{{ status.label }}</el-tag>
      <span v-if="selected" class="selected-label">当前阅读</span>
    </header>
    <p v-if="card.publicationStatus === 'DRAFT'" class="muted publication-note">尚未用于检索和问答</p>
    <p class="card-excerpt">{{ excerpt || '暂无正文摘要' }}</p>
    <p v-if="['STALE', 'SUSPECT'].includes(card.sourceVersionStatus)" class="source-warning">来源代码已变化，请查看维护信息与复核。</p>
    <div class="card-meta">
      <span class="branch-scope" :title="card.branchScope?.mode === 'ALL_BRANCHES' ? '包括后续新增分支' : scopeLabel">{{ scopeLabel }}</span>
      <span v-if="!card.tags.length" class="muted">暂无标签</span>
      <span v-for="tag in (expanded ? card.tags : card.tags.slice(0, 3))" :key="tag" class="card-tag"># {{ tag }}</span>
      <button v-if="card.tags.length > 3" type="button" class="tags-toggle" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起标签' : `+${card.tags.length - 3}` }}</button>
      <time :datetime="card.updatedAt" :title="new Date(card.updatedAt).toLocaleString()">更新于 {{ new Date(card.updatedAt).toLocaleDateString() }}</time>
    </div>
    <footer class="card-actions">
      <el-button type="primary" link @click="emit('view', card)">查看</el-button>
      <el-button v-if="canMaintain" link @click="emit('edit', card)">编辑</el-button>
      <el-button v-if="canManage && card.publicationStatus !== 'PUBLISHED'" type="primary" @click="emit('publish', card, 'PUBLISHED')">确认并发布</el-button>
      <el-dropdown v-if="canMaintain || canManage" trigger="click">
        <el-button text aria-label="更多知识操作">更多</el-button>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item v-if="canMaintain" @click="emit('history', card)">修订历史</el-dropdown-item>
          <el-dropdown-item v-if="canManage && card.publicationStatus === 'PUBLISHED'" @click="emit('publish', card, 'DRAFT')">撤回为草稿</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
    </footer>
  </article>
</template>

<style scoped>
.knowledge-card { display: flex; flex-direction: column; gap: 12px; padding: 16px 20px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-surface); }
.knowledge-card.selected { border-color: var(--app-color-action); box-shadow: inset 3px 0 var(--app-color-action); }
.card-header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.card-header h3 { flex: 1 1 300px; min-width: 0; margin: 0; }
.card-title { padding: 0; border: 0; background: transparent; color: var(--app-text-primary); font-size: 17px; font-weight: 650; line-height: 1.5; text-align: left; overflow-wrap: anywhere; }
.card-title:hover { color: var(--app-color-action); }
.publication-note { margin: 0; font-size: 12px; }
.card-excerpt { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin: 0; color: var(--app-text-regular); font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
.card-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; color: var(--app-text-muted); font-size: 12px; }
.branch-scope { overflow-wrap: anywhere; }
.card-meta time { margin-left: auto; }
.card-tag { padding: 2px 6px; border-radius: 4px; background: var(--app-surface-subtle); overflow-wrap: anywhere; }
.tags-toggle { border: 0; background: transparent; color: var(--app-color-action); }
.card-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 8px; }
.source-warning, .selected-label { margin: 0; font-size: 12px; color: var(--app-text-muted); }
.source-warning { color: #986012; }
@media (max-width: 760px) { .knowledge-card { padding: 16px; } .card-meta time { margin-left: 0; } }
</style>
