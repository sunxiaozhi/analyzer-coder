<script setup lang="ts">
import { computed } from 'vue';
import type { CodeReference, KnowledgeCard, KnowledgeDriftEvent } from '@/api/intelligence';
import { statusLabel as localizeStatus } from '@/utils/displayLabels';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';
import KnowledgeDriftPanel from './KnowledgeDriftPanel.vue';

const props = defineProps<{
  card: KnowledgeCard | null;
  driftEvent: KnowledgeDriftEvent | null;
  driftLoading: boolean;
  canMaintain: boolean;
  sourceReviewLoading: boolean;
}>();
const emit = defineEmits<{
  openCode: [reference: CodeReference];
  openGraph: [reference: CodeReference];
  openDrift: [event: KnowledgeDriftEvent];
  sourceReview: [action: 'CONFIRM_CURRENT' | 'MARK_STALE'];
}>();

const visible = defineModel<boolean>({ required: true });

const statusLabel = computed(() => props.card ? localizeStatus(props.card.publicationStatus) : '');
const kindLabels: Record<string, string> = {
  REFERENCE: '参考资料', BUSINESS_RULE: '业务规则', ARCH_DECISION: '架构决策',
  API_CONTRACT: '接口契约', DATA_CONSTRAINT: '数据约束', TEST_OBLIGATION: '测试义务',
  SECURITY_POLICY: '安全策略', RUNBOOK: '运行手册', INCIDENT_LESSON: '事故经验',
  OWNERSHIP: '责任归属', TECH_DEBT: '技术债',
};
const enforcementLabels: Record<string, string> = {
  REFERENCE: '仅参考', ADVISORY: '建议执行', REQUIRED: '必须执行',
};
const severityLabels: Record<string, string> = { INFO: '提示', WARNING: '警告', CRITICAL: '严重' };
const hasScope = computed(() => Boolean(props.card && (
  props.card.scope.pathPatterns.length || props.card.scope.symbols.length || props.card.scope.modules.length
  || props.card.scope.repositoryIds?.length || props.card.scope.serviceNames?.length
  || props.card.scope.contractIds?.length
)));
const hasObligations = computed(() => Boolean(props.card && (
  props.card.obligations.requiredTests.length
  || props.card.obligations.requiredApproverAccountIds.length
  || props.card.obligations.instructions.length
  || props.card.obligations.prohibitedPathPatterns?.length
  || props.card.obligations.knowledgeUpdateRequired
)));
</script>

<template>
  <el-dialog v-model="visible" :title="card?.title ?? '知识卡片详情'" width="760">
    <template v-if="card">
      <div class="detail-meta">
        <el-tag effect="plain">{{ kindLabels[card.knowledgeKind] }}</el-tag>
        <el-tag :type="card.enforcement === 'REQUIRED' ? 'danger' : card.enforcement === 'ADVISORY' ? 'warning' : 'info'">
          {{ enforcementLabels[card.enforcement] }}
        </el-tag>
        <el-tag :type="card.severity === 'CRITICAL' ? 'danger' : card.severity === 'WARNING' ? 'warning' : 'info'">
          {{ severityLabels[card.severity] }}
        </el-tag>
        <el-tag :type="card.publicationStatus === 'PUBLISHED' ? 'success' : 'info'">
          {{ statusLabel }}
        </el-tag>
        <el-tag :type="card.reviewStatus === 'APPROVED' ? 'success' : card.reviewStatus === 'CHANGES_REQUESTED' ? 'warning' : 'info'">
          人工评审：{{ localizeStatus(card.reviewStatus) }}
        </el-tag>
        <el-tag :type="['SUSPECT', 'STALE'].includes(card.sourceVersionStatus) ? 'warning' : card.sourceVersionStatus === 'CURRENT' ? 'success' : 'info'">
          来源版本：{{ localizeStatus(card.sourceVersionStatus) }}
        </el-tag>
        <span>修订 v{{ card.revision }}</span>
        <time>{{ new Date(card.updatedAt).toLocaleString() }}</time>
      </div>
      <dl class="engineering-facts">
        <div><dt>原有分类</dt><dd>{{ card.cardType || '未分类' }}</dd></div>
        <div><dt>负责人</dt><dd class="mono">{{ card.ownerAccountId || '未指定' }}</dd></div>
        <div><dt>最近验证快照</dt><dd class="mono">{{ card.lastVerifiedSnapshotId || '尚未验证' }}</dd></div>
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
      <div class="detail-content" v-html="card.renderedContent" />
      <div v-if="card.tags.length" class="detail-tags">
        <span v-for="tag in card.tags" :key="tag"># {{ tag }}</span>
      </div>
      <KnowledgeAttachmentList :items="card.attachments" :repository-id="card.repositoryId" />
      <section v-if="hasScope" class="engineering-detail">
        <h3>适用范围</h3>
        <div v-if="card.scope.pathPatterns.length"><b>路径</b><code v-for="item in card.scope.pathPatterns" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.symbols.length"><b>符号</b><code v-for="item in card.scope.symbols" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.modules.length"><b>模块</b><code v-for="item in card.scope.modules" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.repositoryIds?.length"><b>跨仓库</b><code v-for="item in card.scope.repositoryIds" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.serviceNames?.length"><b>服务</b><code v-for="item in card.scope.serviceNames" :key="item">{{ item }}</code></div>
        <div v-if="card.scope.contractIds?.length"><b>契约</b><code v-for="item in card.scope.contractIds" :key="item">{{ item }}</code></div>
      </section>
      <section v-if="hasObligations" class="engineering-detail">
        <h3>开发要求</h3>
        <div v-if="card.obligations.requiredTests.length"><b>测试</b><code v-for="item in card.obligations.requiredTests" :key="item">{{ item }}</code></div>
        <div v-if="card.obligations.requiredApproverAccountIds.length"><b>审批人</b><code v-for="item in card.obligations.requiredApproverAccountIds" :key="item">{{ item }}</code></div>
        <div v-if="card.obligations.instructions.length"><b>要求</b><span v-for="item in card.obligations.instructions" :key="item">{{ item }}</span></div>
        <div v-if="card.obligations.prohibitedPathPatterns?.length"><b>CI 禁止路径</b><code v-for="item in card.obligations.prohibitedPathPatterns" :key="item">{{ item }}</code></div>
        <div v-if="card.obligations.knowledgeUpdateRequired"><b>CI 知识同步</b><span>命中代码变化时，必须发布更高修订且保持 CURRENT</span></div>
      </section>
    </template>
      <section v-if="card?.codeReferences.length" class="detail-code-links">
        <h3>关联代码</h3>
        <article v-for="reference in card?.codeReferences ?? []" :key="reference.chunkId ?? reference.filePath">
          <div>
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <span class="mono">{{ reference.filePath }} · L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</span>
          </div>
          <el-tag v-if="reference.stale" type="warning" size="small">代码已变化</el-tag>
          <el-button link type="primary" @click="emit('openCode', reference)">查看源码</el-button>
          <el-button link @click="emit('openGraph', reference)">调用图谱</el-button>
        </article>
      </section>
  </el-dialog>
</template>

<style scoped>
.detail-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.detail-content {
  line-height: 1.7;
  color: var(--el-text-color-primary);
}
.engineering-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0 0 20px; border-top: 1px solid #dce4ea; border-bottom: 1px solid #dce4ea; }
.engineering-facts > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 8px; padding: 9px 4px; }
.engineering-facts dt { color: #71808b; font-size: 12px; }
.engineering-facts dd { min-width: 0; margin: 0; overflow: hidden; color: #283640; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.engineering-detail { display: grid; gap: 8px; margin-top: 18px; padding: 14px 0 2px; border-top: 1px solid #dce4ea; }
.engineering-detail h3 { margin: 0 0 2px; color: #283640; font-size: 14px; }
.engineering-detail > div { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 6px; }
.engineering-detail b { width: 54px; color: #71808b; font-size: 12px; }
.engineering-detail code, .engineering-detail span { padding: 3px 6px; color: #31566d; border-radius: 4px; background: #eef5f8; font-size: 12px; }

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
  font-size: 13px;
}
.detail-code-links {
  display: grid;
  gap: 8px;
  margin: 20px 0 12px;
  padding-top: 14px;
  border-top: 1px solid #eceef1;
}

.detail-code-links h3 { margin: 0 0 2px; font-size: 13px; }

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
.detail-code-links span { color: #71717a; font-size: 11px; }

@media (max-width: 760px) {
  .detail-code-links article { grid-template-columns: minmax(0, 1fr) auto; }
  .engineering-facts { grid-template-columns: 1fr; }
}
</style>
