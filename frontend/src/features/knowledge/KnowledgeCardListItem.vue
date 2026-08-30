<script setup lang="ts">
import type { TagProps } from 'element-plus';
import type { KnowledgeCard } from '@/api/intelligence';
import { statusLabel } from '@/utils/displayLabels';

defineProps<{
  card: KnowledgeCard;
  canManage: boolean;
}>();

const emit = defineEmits<{
  view: [card: KnowledgeCard];
  edit: [card: KnowledgeCard];
  history: [card: KnowledgeCard];
  review: [card: KnowledgeCard, status: 'APPROVED' | 'CHANGES_REQUESTED'];
  publish: [card: KnowledgeCard, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'];
}>();

function statusType(status: string): TagProps['type'] {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'NEEDS_REVIEW') return 'warning';
  return 'info';
}

function formatDate(value: string) {
  return new Date(value).toLocaleString();
}

const kindLabels: Record<string, string> = {
  REFERENCE: '参考资料', BUSINESS_RULE: '业务规则', ARCH_DECISION: '架构决策',
  API_CONTRACT: '接口契约', DATA_CONSTRAINT: '数据约束', TEST_OBLIGATION: '测试义务',
  SECURITY_POLICY: '安全策略', RUNBOOK: '运行手册', INCIDENT_LESSON: '事故经验',
  OWNERSHIP: '责任归属', TECH_DEBT: '技术债',
};
const enforcementLabels: Record<string, string> = {
  REFERENCE: '仅参考', ADVISORY: '建议执行', REQUIRED: '必须执行',
};
</script>

<template>
  <article class="knowledge-card">
    <header class="card-header">
      <el-tag effect="plain" size="small">{{ kindLabels[card.knowledgeKind] }}</el-tag>
      <el-tag :type="card.enforcement === 'REQUIRED' ? 'danger' : card.enforcement === 'ADVISORY' ? 'warning' : 'info'" size="small">
        {{ enforcementLabels[card.enforcement] }}
      </el-tag>
      <el-tag :type="statusType(card.publicationStatus)" size="small">{{ statusLabel(card.publicationStatus) }}</el-tag>
    </header>

    <h3 class="card-title">{{ card.title }}</h3>
    <el-alert
      v-if="card.sourceVersionStatus === 'STALE' || card.sourceVersionStatus === 'SUSPECT'"
      :title="card.sourceVersionStatus === 'SUSPECT' ? '代码已变化，知识可能失效，需负责人复核' : '来源版本已过期；发布状态不代表内容仍适用于当前代码'"
      type="warning"
      :closable="false"
      show-icon
    />

    <div class="card-tags" aria-label="标签">
      <span v-if="!card.tags.length" class="tag-placeholder">暂无标签</span>
      <template v-else>
        <span v-for="tag in card.tags" :key="tag"># {{ tag }}</span>
      </template>
    </div>

    <dl class="card-meta">
      <div>
        <dt>最近更新</dt>
        <dd>{{ formatDate(card.updatedAt) }}</dd>
      </div>
      <div>
        <dt>人工评审</dt>
        <dd>{{ statusLabel(card.reviewStatus) }}</dd>
      </div>
      <div>
        <dt>来源版本</dt>
        <dd>{{ statusLabel(card.sourceVersionStatus) }}</dd>
      </div>
    </dl>

    <footer class="card-actions">
      <el-button link type="primary" @click="emit('view', card)">查看</el-button>
      <el-button link @click="emit('edit', card)">编辑</el-button>
      <el-button link @click="emit('history', card)">历史</el-button>
      <template v-if="canManage">
        <el-button v-if="card.reviewStatus !== 'APPROVED'" link type="primary" @click="emit('review', card, 'APPROVED')">通过评审</el-button>
        <el-button v-else link @click="emit('review', card, 'CHANGES_REQUESTED')">要求修改</el-button>
        <el-button v-if="card.publicationStatus !== 'PUBLISHED' && card.reviewStatus === 'APPROVED' && !['SUSPECT', 'STALE'].includes(card.sourceVersionStatus)" link type="success" @click="emit('publish', card, 'PUBLISHED')">发布</el-button>
        <el-button v-if="card.publicationStatus === 'PUBLISHED'" link @click="emit('publish', card, 'DRAFT')">撤回</el-button>
      </template>
    </footer>
  </article>
</template>

<style scoped>
.knowledge-card {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title {
  min-height: 42px;
  margin: 0;
  overflow: hidden;
  font-size: 15px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.card-tags {
  display: flex;
  min-height: 24px;
  flex-wrap: wrap;
  gap: 6px;
}

.card-tags span {
  padding: 3px 7px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #5e6670;
  font-size: 11px;
}

.card-tags .tag-placeholder {
  color: var(--app-text-muted);
  background: #f7f7f8;
}

.card-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 14px;
  margin: 0;
  padding: 12px 0;
  border-top: 1px solid #eeeeef;
  border-bottom: 1px solid #eeeeef;
}

.card-meta div {
  min-width: 0;
}

.card-meta dt {
  margin-bottom: 4px;
  color: var(--app-text-muted);
  font-size: 11px;
}

.card-meta dd {
  margin: 0;
  overflow: hidden;
  color: #4a4a4f;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin-top: auto;
}
</style>
