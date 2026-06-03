<script setup lang="ts">
import { computed } from 'vue'
import { Connection, Document, FolderChecked, Link, Search, Tickets } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElScrollbar,
  ElSelect,
  ElTag
} from 'element-plus'
import type { ActiveView, AnalyzerForm, JsonValue, OutputType, QueryEvidence, QueryResult } from '../../types'

const form = defineModel<AnalyzerForm>('form', { required: true })

const props = defineProps<{
  activeView: ActiveView
  busy: boolean
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  queryEvidence: QueryEvidence | null
  queryResults: QueryResult[]
  savedPath: string
}>()

defineEmits<{
  queryStore: []
}>()

const sourceOptions = [
  { label: '全部来源', value: '' },
  { label: '仅代码', value: 'code' },
  { label: '仅知识库', value: 'kb' }
] as const

const resultSummary = computed(() => ({
  direct: props.queryResults.length,
  relatedCode: props.queryEvidence?.relatedCode.length ?? 0,
  relatedKnowledge: props.queryEvidence?.relatedKnowledge.length ?? 0,
  relations: props.queryEvidence?.relations.length ?? 0
}))

const hasEvidence = computed(
  () => resultSummary.value.relatedCode > 0 || resultSummary.value.relatedKnowledge > 0 || resultSummary.value.relations > 0
)

const filterLabel = computed(() => {
  const option = sourceOptions.find((item) => item.value === form.value.filterSource)
  return option?.label ?? '全部来源'
})

function sourceLabel(item: QueryResult) {
  if (item.metadata.source_type === 'kb') return '知识库'
  if (item.metadata.source_type === 'code') return '代码'
  return item.metadata.source_type || '未知'
}

function itemTitle(item: QueryResult) {
  const metadata = item.metadata
  return String(metadata.symbol_name || metadata.doc_name || metadata.section || metadata.kind || '检索片段')
}

function locationLabel(item: QueryResult) {
  const path = item.metadata.file_path || '未知文件'
  const line = item.metadata.start_line ? `:${item.metadata.start_line}` : ''
  return `${path}${line}`
}

function scoreLabel(score: number) {
  return score.toFixed(4)
}
</script>

<template>
  <div class="feature-page search-page">
    <section class="search-command">
      <div class="search-command-main">
        <div class="search-heading">
          <ElIcon><Search /></ElIcon>
          <div>
            <h2>知识检索</h2>
            <span>{{ filterLabel }} · Top 5</span>
          </div>
        </div>

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
        </ElForm>
      </div>

      <div class="search-command-side">
        <ElButton type="primary" :loading="busy" :icon="Search" @click="$emit('queryStore')">
          检索
        </ElButton>
        <ElTag v-if="savedPath" class="saved search-saved" type="success" effect="plain">
          <ElIcon><FolderChecked /></ElIcon>
          <span>{{ savedPath }}</span>
        </ElTag>
      </div>
    </section>

    <section class="search-workbench">
      <ElCard class="panel search-results-panel" shadow="never">
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
          <ElEmpty v-if="!queryResults.length" description="输入问题并执行检索后显示结果。" />
          <div v-else class="search-result-list">
            <article v-for="item in queryResults" :key="item.id" class="search-result-item">
              <div class="search-result-head">
                <div class="search-result-title">
                  <ElIcon><Document /></ElIcon>
                  <strong>{{ itemTitle(item) }}</strong>
                </div>
                <ElTag :type="item.metadata.source_type === 'kb' ? 'warning' : 'primary'" effect="plain">
                  {{ sourceLabel(item) }} / {{ item.metadata.kind }}
                </ElTag>
              </div>
              <div class="search-result-meta">
                <span>{{ locationLabel(item) }}</span>
                <span>score {{ scoreLabel(item.score) }}</span>
              </div>
              <pre>{{ item.text }}</pre>
            </article>
          </div>
        </ElScrollbar>
      </ElCard>

      <aside class="search-evidence-stack">
        <ElCard class="panel search-stats-panel" shadow="never">
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

        <ElCard class="panel search-evidence-panel" shadow="never">
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
  grid-template-rows: auto minmax(0, 1fr);
}

.search-command {
  align-items: stretch;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(0, 1fr) auto;
  padding: 16px 24px;
}

.search-command-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.search-heading {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.search-heading > .el-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 36px;
  height: 36px;
  justify-content: center;
  width: 36px;
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

.search-form {
  align-items: end;
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 180px;
}

.search-form .el-form-item {
  margin-bottom: 0;
}

.search-query-input :deep(.el-textarea__inner) {
  min-height: 64px !important;
}

.search-command-side {
  align-items: end;
  display: grid;
  gap: 10px;
  justify-items: end;
  min-width: 180px;
}

.search-command-side .el-button {
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
  padding: 18px 24px 24px;
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
  border-left-color: #d97706;
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
  .search-workbench {
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
  .search-workbench {
    padding: 14px 16px;
  }

  .search-form,
  .search-stat-grid {
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
