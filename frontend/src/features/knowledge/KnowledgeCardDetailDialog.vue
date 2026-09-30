<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { BranchContext, BranchValidationState } from '@/api/branches';
import KnowledgeBranchValidationPanel from './KnowledgeBranchValidationPanel.vue';
import type { CodeReference, KnowledgeCard, KnowledgeDriftEvent } from '@/api/intelligence';
import { statusLabel as localizeStatus } from '@/utils/displayLabels';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/authStore';
import { knowledgeStatus } from './knowledgePresentation';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';
import KnowledgeDriftPanel from './KnowledgeDriftPanel.vue';

const props = defineProps<{
  card: KnowledgeCard | null;
  driftEvent: KnowledgeDriftEvent | null;
  driftLoading: boolean;
  canMaintain: boolean;
  sourceReviewLoading: boolean;
  branchContext?: BranchContext | null;
  scopeLabel?: string;
  canManage?: boolean;
  validationState?: BranchValidationState;
}>();
const emit = defineEmits<{
  branchValidated: [];
  closed: []; edit: [card: KnowledgeCard];
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
  openDrift: [event: KnowledgeDriftEvent];
  sourceReview: [action: 'CONFIRM_CURRENT' | 'MARK_STALE'];
}>();

const visible = defineModel<boolean>({ required: true });

const auth = useAuthStore();
const status = computed(() => props.card ? knowledgeStatus(props.card, props.branchContext, props.validationState) : null);
const maintenanceOpen = ref(false);
const needsValidation = computed(() => Boolean(props.card?.publicationStatus === 'PUBLISHED'
  && ['UNVERIFIED', 'REVIEW_REQUIRED', 'INVALID'].includes(props.validationState ?? '')
  && !(props.validationState === 'UNVERIFIED' && props.card.enforcement === 'REFERENCE' && !props.card.codeReferences.length)));
watch(() => [visible.value, props.card?.id], () => {
  maintenanceOpen.value = visible.value && needsValidation.value;
});
const ownerLabel = computed(() => props.card?.ownerAccountId === auth.account?.id
  ? auth.account?.displayName || auth.account?.username : null);
async function copyOwner() {
  try { await navigator.clipboard.writeText(props.card?.ownerAccountId || ''); ElMessage.success('负责人标识已复制'); }
  catch { ElMessage.error('复制失败，可选择标识手动复制'); }
}
const kindLabels: Record<string, string> = {
  REFERENCE: '参考资料', BUSINESS_RULE: '业务规则', ARCH_DECISION: '架构决策',
  API_CONTRACT: '接口契约', DATA_CONSTRAINT: '数据约束', TEST_OBLIGATION: '测试义务',
  SECURITY_POLICY: '安全策略', RUNBOOK: '运行手册', INCIDENT_LESSON: '事故经验',
  OWNERSHIP: '责任归属', TECH_DEBT: '技术债',
};
const enforcementLabels: Record<string, string> = {
  REFERENCE: '参考', ADVISORY: '重点提醒', REQUIRED: '强约束',
};
const hasScope = computed(() => Boolean(props.card && (
  props.card.scope.pathPatterns.length || props.card.scope.symbols.length
)));
</script>

<template>
  <el-drawer v-model="visible" :title="card?.title ?? '知识卡片详情'" size="min(760px, 100vw)"
    class="knowledge-reader" :modal="false" :lock-scroll="false" @closed="emit('closed')">
    <template #header>
      <div class="reader-heading"><h2>{{ card?.title ?? '知识卡片详情' }}</h2>
        <el-button v-if="card && canMaintain" @click="emit('edit', card)">编辑</el-button>
      </div>
    </template>
    <template v-if="card">
      <div class="detail-meta">
        <el-tag v-if="status" :type="status.type">{{ status.label }}</el-tag>
        <span>{{ scopeLabel }}</span>
        <span v-if="card.branchScope?.mode === 'ALL_BRANCHES'">包括后续新增分支</span>
        <span>{{ kindLabels[card.knowledgeKind] }} · {{ enforcementLabels[card.enforcement] }}</span>
        <span>修订 v{{ card.revision }}</span><time :datetime="card.updatedAt">{{ new Date(card.updatedAt).toLocaleString() }}</time>
      </div>
      <div class="detail-content" v-html="card.renderedContent" />
      <div v-if="card.tags.length" class="detail-tags">
        <span v-for="tag in card.tags" :key="tag"># {{ tag }}</span>
      </div>
      <KnowledgeAttachmentList :items="card.attachments" :repository-id="card.repositoryId" />
      <section v-if="card?.codeReferences.length" class="detail-code-links">
        <h3>关联代码</h3>
        <article v-for="reference in card?.codeReferences ?? []" :key="reference.chunkId ?? reference.filePath">
          <div>
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <span>{{ reference.branchName || reference.branchId || '来源分支未提供' }}{{ reference.branchId && reference.branchId !== branchContext?.branchId ? ' · 其他分支' : '' }}</span>
            <span class="mono" :title="reference.filePath">{{ reference.filePath }} · L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</span>
          </div>
          <el-tag v-if="reference.stale" type="warning" size="small">代码已变化</el-tag>
          <el-button link type="primary" @click="emit('openCode', reference)">查看源码</el-button>
          <el-button link @click="emit('openGraph', reference)">调用图谱</el-button>
        </article>
      </section>
      <details class="maintenance-details" :open="maintenanceOpen" @toggle="maintenanceOpen = ($event.target as HTMLDetailsElement).open">
        <summary>维护信息与复核</summary>
      <dl class="engineering-facts">
        <div><dt>负责人</dt><dd :title="card.ownerAccountId || undefined">{{ ownerLabel || card.ownerAccountId || '未指定' }} <el-button v-if="card.ownerAccountId && !ownerLabel" link @click="copyOwner">复制标识</el-button></dd></div>
        <div><dt>人工评审</dt><dd>{{ localizeStatus(card.reviewStatus) }}</dd></div>
        <div><dt>来源版本</dt><dd>{{ localizeStatus(card.sourceVersionStatus) }}</dd></div>
        <div><dt>最近验证内容版本</dt><dd class="mono">{{ card.lastVerifiedContentVersion || '尚未验证' }}</dd></div>
        <div><dt>验证说明</dt><dd>{{ card.verificationNote || '暂无' }}</dd></div>
      </dl>
      <KnowledgeDriftPanel
        v-if="card.sourceVersionStatus !== 'UNVERIFIED' || driftEvent || driftLoading"
        :card="card"
        :event="driftEvent"
        :loading="driftLoading"
        :can-maintain="canMaintain"
        :reviewing="sourceReviewLoading"
        @open-diff="emit('openDrift', $event)"
        @review="emit('sourceReview', $event)"
      />
      <KnowledgeBranchValidationPanel v-if="visible && branchContext && branchContext.repositoryId === card.repositoryId"
        :context="branchContext" :initially-open="maintenanceOpen" :card-id="card.id" :card-revision="card.revision" :can-manage="canManage ?? false"
        @saved="emit('branchValidated')" />
      </details>
      <section v-if="hasScope" class="engineering-detail">
        <h3>适用范围</h3>
        <div v-if="card.scope.pathPatterns.length"><b>路径</b><code v-for="item in card.scope.pathPatterns" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.symbols.length"><b>符号</b><code v-for="item in card.scope.symbols" :key="item">{{ item }}</code></div>
      </section>
    </template>
  </el-drawer>
</template>

<style scoped>
.reader-heading { display: flex; gap: 12px; align-items: start; min-width: 0; }
.reader-heading h2 { flex: 1; margin: 0; font-size: 20px; line-height: 1.5; overflow-wrap: anywhere; }
:global(.knowledge-reader .el-drawer__header) { margin-bottom: 0; padding: 16px 20px; border-bottom: 1px solid var(--app-border); }
:global(.knowledge-reader .el-drawer__body) { padding: 20px; }
.detail-content { overflow-wrap: anywhere; }
.detail-content :deep(table) { display: block; max-width: 100%; overflow: auto; }

.maintenance-details { margin-top: 16px; }
.maintenance-details > summary { cursor: pointer; padding: 10px 0; color: #50647a; }
.detail-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 20px;
  color: var(--el-text-color-secondary);
  font-size: 15px;
}

.detail-content {
  line-height: 1.7;
  color: var(--el-text-color-primary);
}
.engineering-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0 0 20px; border-top: 1px solid #dce4ea; border-bottom: 1px solid #dce4ea; }
.engineering-facts > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 8px; padding: 9px 4px; }
.engineering-facts dt { color: #71808b; font-size: 14px; }
.engineering-facts dd { min-width: 0; margin: 0; overflow: hidden; color: #283640; font-size: 14px; text-overflow: ellipsis; overflow-wrap: anywhere; }
.engineering-detail { display: grid; gap: 8px; margin-top: 18px; padding: 14px 0 2px; border-top: 1px solid #dce4ea; }
.engineering-detail h3 { margin: 0 0 2px; color: #283640; font-size: 14px; }
.engineering-detail > div { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 6px; }
.engineering-detail b { width: 54px; color: #71808b; font-size: 14px; }
.engineering-detail code, .engineering-detail span { padding: 3px 6px; color: #31566d; border-radius: 4px; background: #eef5f8; font-size: 14px; }

.detail-content :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}

.detail-content :deep(pre) {
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: #18212f;
  color: #e6edf3;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 20px 0 12px;
  color: var(--el-color-primary);
  font-size: 15px;
}
.detail-code-links {
  display: grid;
  gap: 8px;
  margin: 20px 0 12px;
  padding-top: 14px;
  border-top: 1px solid #eceef1;
}

.detail-code-links h3 { margin: 0 0 2px; font-size: 15px; }

.detail-code-links article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto auto;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #d9e5f1;
  border-radius: 6px;
  background: #f6faff;
}

.detail-code-links article > div { display: grid; min-width: 0; }
.detail-code-links b,
.detail-code-links span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-code-links span { color: #71717a; font-size: 13px; }

@media (max-width: 760px) {
  .detail-code-links article { grid-template-columns: minmax(0, 1fr) auto; }
  .engineering-facts { grid-template-columns: 1fr; }
}
</style>
