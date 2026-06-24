<script setup lang="ts">
import { computed } from 'vue'
import { Collection, Connection, Document, FolderChecked, Link, Search, Tickets } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMessage,
  ElOption,
  ElScrollbar,
  ElSelect,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, QueryEvidence, QueryResult, RagCitation, RagFlow, RagRisk, RagStep } from '../../types'

const form = defineModel<AnalyzerForm>('form', { required: true })

const props = defineProps<{
  busy: boolean
  queryEvidence: QueryEvidence | null
  queryResults: QueryResult[]
  ragFlow: RagFlow | null
  savedPath: string
}>()

defineEmits<{
  queryStore: []
}>()

const sourceOptions = [
  { label: '全部来源', value: '' },
  { label: '可信知识资产', value: 'knowledge_asset' },
  { label: '知识文档', value: 'kb' },
  { label: '仅代码', value: 'code' },
] as const

const orderedResults = computed(() =>
  [...props.queryResults].sort((left, right) => resultWeight(right) - resultWeight(left))
)

const resultSummary = computed(() => ({
  direct: props.queryResults.length,
  relatedCode: props.queryEvidence?.relatedCode.length ?? 0,
  relatedKnowledge: props.queryEvidence?.relatedKnowledge.length ?? 0,
  relations: props.queryEvidence?.relations.length ?? 0
}))

const hasEvidence = computed(
  () => resultSummary.value.relatedCode > 0 || resultSummary.value.relatedKnowledge > 0 || resultSummary.value.relations > 0
)

const ragSummary = computed(() => ({
  steps: props.ragFlow?.steps.length ?? 0,
  citations: props.ragFlow?.citations.length ?? 0,
  risks: props.ragFlow?.risks.length ?? 0
}))

const filterLabel = computed(() => {
  const option = sourceOptions.find((item) => item.value === form.value.filterSource)
  return option?.label ?? '全部来源'
})

function sourceLabel(item: QueryResult) {
  if (item.metadata.source_type === 'knowledge_asset') return '知识资产'
  if (item.metadata.source_type === 'kb') return '知识库'
  if (item.metadata.source_type === 'code') return '代码'
  return item.metadata.source_type || '未知'
}

function resultWeight(item: QueryResult) {
  if (item.metadata.source_type === 'knowledge_asset' && item.metadata.status === 'confirmed') return 40
  if (item.metadata.source_type === 'knowledge_asset') return 30
  if (item.metadata.source_type === 'kb') return 20
  if (item.metadata.source_type === 'code') return 10
  return 0
}

function sourceTagType(item: QueryResult) {
  if (item.metadata.source_type === 'knowledge_asset') {
    return item.metadata.status === 'confirmed' ? 'success' : 'warning'
  }
  if (item.metadata.source_type === 'kb') return 'warning'
  return 'primary'
}

function ragStepType(step: RagStep) {
  return step.status === 'done' ? 'success' : 'warning'
}

function riskType(risk: RagRisk) {
  if (risk.level === 'high') return 'danger'
  if (risk.level === 'medium') return 'warning'
  return 'info'
}

function citationType(item: RagCitation) {
  if (item.sourceType === 'knowledge_asset') return item.status === 'confirmed' ? 'success' : 'warning'
  if (item.sourceType === 'kb') return 'warning'
  return 'primary'
}

function citationSourceLabel(item: RagCitation) {
  if (item.sourceType === 'knowledge_asset') return '知识资产'
  if (item.sourceType === 'kb') return '知识文档'
  if (item.sourceType === 'code') return '代码'
  return item.sourceType || '未知'
}

function itemTitle(item: QueryResult) {
  const metadata = item.metadata
  return String(metadata.title || metadata.symbol_name || metadata.doc_name || metadata.section || metadata.kind || '检索片段')
}

function locationLabel(item: QueryResult) {
  const path = item.metadata.file_path || '未知文件'
  const line = item.metadata.start_line ? `:${item.metadata.start_line}` : ''
  return `${path}${line}`
}

function scoreLabel(score: number) {
  return score.toFixed(4)
}

function trustLabel(item: QueryResult) {
  if (item.metadata.source_type !== 'knowledge_asset') return ''
  if (item.metadata.status === 'confirmed') return '已确认'
  if (item.metadata.status === 'pending_review' || item.metadata.status === 'stale') return '待复审'
  if (item.metadata.status === 'archived') return '已归档'
  return '草稿'
}

async function copyContextPackage() {
  if (!props.ragFlow?.contextPackage) return
  await navigator.clipboard.writeText(props.ragFlow.contextPackage)
  ElMessage.success('上下文包已复制')
}
</script>

<template>
  <div class="feature-page search-page console-page">
    <section class="search-command console-command">
      <div class="search-heading console-heading">
        <ElIcon><Search /></ElIcon>
        <div>
          <h2>知识问答</h2>
          <span>{{ filterLabel }} · 已确认知识优先，代码片段作为证据</span>
        </div>
      </div>

      <div class="search-command-actions console-command-actions">
        <ElTag type="info" effect="plain">命中 {{ resultSummary.direct }}</ElTag>
        <ElTag :type="hasEvidence ? 'success' : 'info'" effect="plain">
          {{ hasEvidence ? '有关联证据' : '无关联证据' }}
        </ElTag>
        <ElTag v-if="savedPath" class="saved search-saved" type="success" effect="plain">
          <ElIcon><FolderChecked /></ElIcon>
          <span>{{ savedPath }}</span>
        </ElTag>
      </div>
    </section>

    <section class="search-query-strip console-strip">
      <ElForm label-position="top" class="search-form">
        <ElFormItem label="查询文本">
          <ElInput
            v-model="form.query"
            class="search-query-input"
            type="textarea"
            :rows="2"
            resize="none"
            @keyup.ctrl.enter="$emit('queryStore')"
          />
        </ElFormItem>
        <ElFormItem label="检索范围">
          <ElSelect v-model="form.filterSource" class="control-select">
            <ElOption
              v-for="option in sourceOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem class="search-submit-item">
          <template #label>&nbsp;</template>
        <ElButton type="primary" :loading="busy" :icon="Search" @click="$emit('queryStore')">
          检索
        </ElButton>
        </ElFormItem>
      </ElForm>
    </section>

    <section class="rag-workbench">
      <ElCard class="panel rag-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Collection /></ElIcon>
              <span>RAG 搜索流程</span>
            </span>
            <div class="rag-header-tags">
              <ElTag type="info" effect="plain">步骤 {{ ragSummary.steps }}</ElTag>
              <ElTag type="success" effect="plain">引用 {{ ragSummary.citations }}</ElTag>
              <ElTag :type="ragSummary.risks ? 'warning' : 'success'" effect="plain">
                风险 {{ ragSummary.risks }}
              </ElTag>
            </div>
          </div>
        </template>

        <ElEmpty v-if="!ragFlow" description="执行检索后生成 RAG 流程。" />
        <div v-else class="rag-grid">
          <section class="rag-steps">
            <article v-for="step in ragFlow.steps" :key="step.key" class="rag-step">
              <div class="rag-step-head">
                <strong>{{ step.title }}</strong>
                <ElTag :type="ragStepType(step)" effect="plain">{{ step.status === 'done' ? '完成' : '需关注' }}</ElTag>
              </div>
              <p>{{ step.summary }}</p>
            </article>
          </section>

          <section class="rag-answer">
            <div class="rag-section-title">
              <strong>答案草稿</strong>
              <ElButton size="small" type="primary" :icon="Document" @click="copyContextPackage">
                复制上下文包
              </ElButton>
            </div>
            <pre>{{ ragFlow.answerDraft }}</pre>
          </section>

          <section class="rag-citations">
            <div class="rag-section-title">
              <strong>引用来源</strong>
              <ElTag type="info" effect="plain">{{ ragFlow.citations.length }}</ElTag>
            </div>
            <div class="rag-citation-list">
              <article v-for="item in ragFlow.citations" :key="item.id" class="rag-citation">
                <div class="rag-citation-head">
                  <strong>{{ item.title }}</strong>
                  <ElTag :type="citationType(item)" effect="plain">
                    {{ citationSourceLabel(item) }}
                  </ElTag>
                </div>
                <span>{{ item.location || '未知位置' }}</span>
                <p>{{ item.excerpt }}</p>
              </article>
            </div>
          </section>

          <section class="rag-risks">
            <div class="rag-section-title">
              <strong>风险提示</strong>
              <ElTag :type="ragFlow.risks.length ? 'warning' : 'success'" effect="plain">
                {{ ragFlow.risks.length ? '需复核' : '无明显风险' }}
              </ElTag>
            </div>
            <ElEmpty v-if="!ragFlow.risks.length" description="未发现明显召回风险。" />
            <div v-else class="rag-risk-list">
              <ElTag v-for="risk in ragFlow.risks" :key="risk.message" :type="riskType(risk)" effect="plain">
                {{ risk.message }}
              </ElTag>
            </div>
          </section>
        </div>
      </ElCard>
    </section>

    <section class="search-workbench console-workbench console-workbench-detail">
      <ElCard class="panel search-results-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Tickets /></ElIcon>
              <span>命中结果</span>
            </span>
            <ElTag type="info" effect="plain">{{ resultSummary.direct }}</ElTag>
          </div>
        </template>

        <ElScrollbar class="search-results-scroll">
          <ElEmpty v-if="!queryResults.length" description="输入问题并执行知识问答后显示结果。" />
          <div v-else class="search-result-list">
            <article v-for="item in orderedResults" :key="item.id" class="search-result-item">
              <div class="search-result-head">
                <div class="search-result-title">
                  <ElIcon><Document /></ElIcon>
                  <strong>{{ itemTitle(item) }}</strong>
                </div>
                <ElTag :type="sourceTagType(item)" effect="plain">
                  {{ sourceLabel(item) }} / {{ item.metadata.kind }}
                </ElTag>
              </div>
              <div class="search-result-meta">
                <span>{{ locationLabel(item) }}</span>
                <span v-if="trustLabel(item)">{{ trustLabel(item) }}</span>
                <span>score {{ scoreLabel(item.score) }}</span>
              </div>
              <pre>{{ item.text }}</pre>
            </article>
          </div>
        </ElScrollbar>
      </ElCard>

      <aside class="search-evidence-stack">
        <ElCard class="panel search-stats-panel console-card" shadow="never">
          <template #header>
            <div class="panel-title">
              <ElIcon><Connection /></ElIcon>
              <span>关联概览</span>
            </div>
          </template>

          <div class="search-stat-grid">
            <div class="search-stat">
              <span>关联代码</span>
              <strong>{{ resultSummary.relatedCode }}</strong>
            </div>
            <div class="search-stat">
              <span>关联知识</span>
              <strong>{{ resultSummary.relatedKnowledge }}</strong>
            </div>
            <div class="search-stat">
              <span>关系链路</span>
              <strong>{{ resultSummary.relations }}</strong>
            </div>
          </div>
        </ElCard>

        <ElCard class="panel search-evidence-panel console-card" shadow="never">
          <template #header>
            <div class="panel-title split-title">
              <span>
                <ElIcon><Link /></ElIcon>
                <span>证据片段</span>
              </span>
              <ElTag :type="hasEvidence ? 'success' : 'info'" effect="plain">
                {{ hasEvidence ? '已生成' : '无关联' }}
              </ElTag>
            </div>
          </template>

          <ElScrollbar class="search-evidence-scroll">
            <ElEmpty v-if="!queryEvidence" description="暂无证据数据。" />
            <div v-else class="evidence-list">
              <section v-if="queryEvidence.relatedCode.length" class="evidence-group">
                <h3>关联代码</h3>
                <article v-for="item in queryEvidence.relatedCode" :key="item.id" class="evidence-item code">
                  <strong>{{ itemTitle(item) }}</strong>
                  <span>{{ locationLabel(item) }}</span>
                </article>
              </section>

              <section v-if="queryEvidence.relatedKnowledge.length" class="evidence-group">
                <h3>关联知识库</h3>
                <article v-for="item in queryEvidence.relatedKnowledge" :key="item.id" class="evidence-item kb">
                  <strong>{{ itemTitle(item) }}</strong>
                  <span>{{ locationLabel(item) }}</span>
                </article>
              </section>

              <section v-if="queryEvidence.relations.length" class="evidence-group">
                <h3>关系链路</h3>
                <article v-for="relation in queryEvidence.relations" :key="`${relation.from}-${relation.to}`" class="relation-item">
                  <strong>{{ relation.reason }}</strong>
                  <span>{{ relation.terms.join(' / ') }}</span>
                </article>
              </section>

              <ElEmpty v-if="!hasEvidence" description="未发现可展开的关联证据。" />
            </div>
          </ElScrollbar>
        </ElCard>
      </aside>
    </section>

  </div>
</template>

<style scoped>
.search-page {
  gap: 0;
  grid-template-rows: auto auto auto minmax(0, 1fr);
}

.search-command {
  align-items: center;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbfd 100%);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.search-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.search-heading > .el-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.search-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.search-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.search-command-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.search-query-strip {
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  padding: 14px 24px;
}

.search-form {
  align-items: end;
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 180px auto;
}

.search-form .el-form-item {
  margin-bottom: 0;
}

.search-query-input :deep(.el-textarea__inner) {
  min-height: 64px !important;
}

.search-submit-item .el-button {
  height: 38px;
  min-width: 112px;
}

.search-saved.el-tag {
  justify-content: flex-end;
  max-width: 280px;
}

.search-workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
  min-height: 0;
  overflow: hidden;
  padding: 0 24px 24px;
}

.rag-workbench {
  padding: 16px 24px;
}

.rag-panel.el-card {
  border-color: rgba(38, 56, 70, 0.14);
  box-shadow: none;
}

.rag-panel :deep(.el-card__body) {
  padding: 12px;
}

.rag-header-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rag-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(240px, 0.78fr) minmax(0, 1.22fr);
}

.rag-steps,
.rag-answer,
.rag-citations,
.rag-risks {
  background: #fbfdff;
  border: 1px solid rgba(38, 56, 70, 0.1);
  border-radius: 8px;
  min-width: 0;
}

.rag-steps {
  display: grid;
  gap: 8px;
  padding: 10px;
}

.rag-step {
  background: #ffffff;
  border-radius: 6px;
  display: grid;
  gap: 6px;
  padding: 9px 10px;
}

.rag-step-head,
.rag-section-title,
.rag-citation-head {
  align-items: center;
  display: flex;
  gap: 10px;
  justify-content: space-between;
}

.rag-step p,
.rag-citation p {
  color: var(--text-faint);
  font-size: 0.78rem;
  line-height: 1.55;
  margin: 0;
}

.rag-answer,
.rag-citations,
.rag-risks {
  padding: 12px;
}

.rag-answer pre {
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.82rem;
  line-height: 1.6;
  margin: 10px 0 0;
  min-height: 126px;
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-citation-list,
.rag-risk-list {
  display: grid;
  gap: 9px;
  margin-top: 10px;
}

.rag-citation {
  background: #ffffff;
  border-radius: 6px;
  display: grid;
  gap: 6px;
  padding: 10px;
}

.rag-citation strong {
  overflow-wrap: anywhere;
}

.rag-citation > span {
  color: var(--text-faint);
  font-size: 0.75rem;
}

.rag-risks {
  grid-column: 1 / -1;
}

.rag-risk-list {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.rag-risk-list .el-tag {
  height: auto;
  justify-content: flex-start;
  line-height: 1.4;
  min-height: 32px;
  padding: 6px 10px;
  white-space: normal;
}

.search-results-panel.el-card,
.search-evidence-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.search-results-panel :deep(.el-card__body),
.search-evidence-panel :deep(.el-card__body) {
  min-height: 0;
  padding: 12px;
}

.split-title > span,
.search-result-title {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  min-width: 0;
}

.search-results-scroll,
.search-evidence-scroll {
  height: 100%;
  min-height: 0;
}

.search-result-list {
  display: grid;
  gap: 12px;
}

.search-result-item {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 10px;
  padding: 13px;
}

.search-result-head {
  align-items: center;
  display: flex;
  gap: 10px;
  justify-content: space-between;
  min-width: 0;
}

.search-result-title {
  color: var(--text);
  font-weight: 760;
}

.search-result-title strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-result-meta {
  color: var(--text-faint);
  display: flex;
  font-size: 0.78rem;
  gap: 12px;
  justify-content: space-between;
  min-width: 0;
}

.search-result-meta span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-result-item pre {
  background: #f3f7f9;
  border-radius: 6px;
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.84rem;
  line-height: 1.5;
  margin: 0;
  max-height: 180px;
  overflow: auto;
  padding: 11px;
  white-space: pre-wrap;
}

.search-evidence-stack {
  display: grid;
  gap: 16px;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
}

.search-stat-grid {
  display: grid;
  gap: 9px;
  grid-template-columns: repeat(3, 1fr);
}

.search-stat {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 5px;
  padding: 10px;
}

.search-stat span {
  color: var(--text-faint);
  font-size: 0.72rem;
}

.search-stat strong {
  color: var(--accent);
  font-size: 1.2rem;
}

.evidence-list,
.evidence-group {
  display: grid;
  gap: 10px;
}

.evidence-group h3 {
  color: var(--text-soft);
  font-size: 0.78rem;
  margin: 2px 0 0;
}

.evidence-item,
.relation-item {
  border: 1px solid var(--line);
  border-left: 3px solid var(--blue);
  border-radius: 8px;
  display: grid;
  gap: 5px;
  padding: 10px;
}

.evidence-item.kb {
  border-left-color: #0284c7;
}

.evidence-item strong,
.relation-item strong {
  color: var(--text);
  font-size: 0.84rem;
  overflow-wrap: anywhere;
}

.evidence-item span,
.relation-item span {
  color: var(--text-faint);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

@media (max-width: 1100px) {
  .search-command,
  .search-workbench,
  .rag-grid {
    grid-template-columns: 1fr;
  }

  .search-command-side {
    align-items: center;
    display: flex;
    justify-content: space-between;
    min-width: 0;
  }

  .search-evidence-stack {
    grid-template-rows: auto auto;
  }
}

@media (max-width: 760px) {
  .search-command,
  .search-workbench,
  .rag-workbench {
    padding: 14px 16px;
  }

  .search-form,
  .search-stat-grid,
  .rag-grid {
    grid-template-columns: 1fr;
  }

  .search-command-side {
    align-items: stretch;
    display: grid;
    justify-items: stretch;
  }

  .search-command-side .el-button,
  .search-saved.el-tag {
    max-width: none;
    width: 100%;
  }

  .search-result-head,
  .search-result-meta {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
