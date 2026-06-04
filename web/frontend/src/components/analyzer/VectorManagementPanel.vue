<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import {
  ArrowDown,
  ArrowRight,
  Document,
  Files,
  FolderChecked,
  Operation,
  Refresh
} from '@element-plus/icons-vue'
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
import type { AnalyzerForm, IndexStatus, JsonValue, OutputType } from '../../types'
import type { IndexRecord, IndexRecordFilters } from '../../types'

const form = defineModel<AnalyzerForm>('form', { required: true })
const recordFilters = defineModel<IndexRecordFilters>('recordFilters', { required: true })
const recordPage = defineModel<number>('recordPage', { required: true })
const recordPageSize = defineModel<number>('recordPageSize', { required: true })
const advancedOpen = shallowRef(false)

const props = defineProps<{
  busy: boolean
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  savedPath: string
  status: IndexStatus | null
  records: IndexRecord[]
  recordTotal: number
}>()

const emit = defineEmits<{
  indexProject: []
  refreshStatus: []
  refreshRecords: []
}>()

const sourceOptions = [
  { label: '代码', value: 'code', description: '为当前代码目录建立可检索索引' },
  { label: '知识库', value: 'kb', description: '为项目文档和知识库内容建立索引' },
  { label: '代码 + 知识库', value: 'mixed', description: '同时重建代码与知识库的融合索引' }
] as const

const sourceLabel = computed(() => {
  const option = sourceOptions.find((item) => item.value === form.value.source)
  return option?.label ?? '代码'
})

const sourceDescription = computed(() => {
  const option = sourceOptions.find((item) => item.value === form.value.source)
  return option?.description ?? '为当前代码目录建立可检索索引'
})

const outputBadge = computed(() => {
  if (props.busy) return '索引中'
  if (props.output) return '已完成'
  if (props.savedPath) return '已保存'
  return '等待建立'
})

const outputTagType = computed(() => {
  if (props.busy) return 'warning'
  if (props.output) return 'success'
  return 'info'
})

const statusBadge = computed(() => {
  if (!props.status) return '未加载'
  if (!props.status.exists) return '未建立'
  return '已建立'
})

const statusTagType = computed(() => {
  if (!props.status) return 'info'
  return props.status.exists ? 'success' : 'warning'
})

const codeCount = computed(() => props.status?.sources.code ?? 0)
const kbCount = computed(() => props.status?.sources.kb ?? 0)

const indexSize = computed(() => {
  const size = props.status?.size ?? 0
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(2)} MB`
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${size} B`
})

const indexUpdatedAt = computed(() => {
  if (!props.status?.updatedAt) return '-'
  const date = new Date(props.status.updatedAt)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(date)
})

const kindOptions = computed(() => Object.keys(props.status?.kinds ?? {}).sort())

const sourceBars = computed(() => [
  { key: 'code', label: '代码', count: codeCount.value },
  { key: 'kb', label: '知识库', count: kbCount.value }
])

const kindBars = computed(() => {
  const kinds = props.status?.kinds ?? {}
  return Object.entries(kinds)
    .sort((left, right) => right[1] - left[1])
    .slice(0, 8)
    .map(([key, count]) => ({ key, label: key, count }))
})

const totalChunks = computed(() => Math.max(props.status?.total ?? 0, 1))

const pageCount = computed(() => Math.max(1, Math.ceil(props.recordTotal / recordPageSize.value)))

function barWidth(count: number) {
  return `${Math.max(4, Math.round((count / totalChunks.value) * 100))}%`
}

function metadataValue(record: IndexRecord, key: string) {
  return record.metadata[key]
}

function recordSourceLabel(record: IndexRecord) {
  const sourceType = String(metadataValue(record, 'source_type') ?? '')
  if (sourceType === 'code') return '代码'
  if (sourceType === 'kb') return '知识库'
  return sourceType || '未知'
}

function recordKind(record: IndexRecord) {
  return String(metadataValue(record, 'kind') ?? '-')
}

function recordSymbol(record: IndexRecord) {
  return String(metadataValue(record, 'symbol_name') ?? metadataValue(record, 'section') ?? '-')
}

function recordFile(record: IndexRecord) {
  return String(metadataValue(record, 'file_path') ?? '-')
}

function recordLine(record: IndexRecord) {
  const line = metadataValue(record, 'start_line')
  return typeof line === 'number' ? line : Number(line) || '-'
}

function recordPreview(record: IndexRecord) {
  return record.text.replace(/\s+/g, ' ').slice(0, 160)
}

function refreshAll() {
  emit('refreshStatus')
}

function applyRecordFilters() {
  recordPage.value = 1
  emit('refreshRecords')
}

function changeRecordPage(nextPage: number) {
  recordPage.value = Math.min(Math.max(nextPage, 1), pageCount.value)
  emit('refreshRecords')
}
</script>

<template>
  <div class="feature-page vector-page">
    <section class="vector-command">
      <div class="vector-heading">
        <ElIcon><Files /></ElIcon>
        <div>
          <h2>索引运维</h2>
          <span>{{ sourceLabel }} · {{ sourceDescription }}</span>
        </div>
      </div>

      <div class="vector-command-actions">
        <ElTag :type="statusTagType" effect="plain">{{ statusBadge }}</ElTag>
        <ElTag :type="outputTagType" effect="plain">{{ outputBadge }}</ElTag>
        <ElButton :icon="Refresh" @click="refreshAll">
          刷新状态
        </ElButton>
        <ElButton type="primary" :loading="busy" :icon="Files" @click="$emit('indexProject')">
          重建索引
        </ElButton>
      </div>
    </section>

    <section class="vector-workbench">
      <aside class="vector-config-stack">
        <ElCard class="panel vector-config-panel" shadow="never">
          <template #header>
            <div class="panel-title">
              <ElIcon><Operation /></ElIcon>
              <span>索引参数</span>
            </div>
          </template>

          <ElForm label-position="top" class="vector-form">
            <ElFormItem label="数据来源">
              <ElSelect v-model="form.source" class="control-select">
                <ElOption
                  v-for="option in sourceOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
            </ElFormItem>

            <ElFormItem label="基础路径">
              <ElInput v-model="form.path" clearable />
            </ElFormItem>

            <div class="vector-advanced">
              <button class="vector-advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
                <ElIcon>
                  <ArrowDown v-if="advancedOpen" />
                  <ArrowRight v-else />
                </ElIcon>
                <span>高级路径</span>
              </button>

              <div v-if="advancedOpen" class="vector-advanced-fields">
                <ElFormItem label="代码路径">
                  <ElInput v-model="form.codePath" clearable />
                </ElFormItem>
                <ElFormItem label="知识库路径">
                  <ElInput v-model="form.kbPath" clearable />
                </ElFormItem>
              </div>
            </div>
          </ElForm>
        </ElCard>

        <ElCard class="panel vector-summary-panel" shadow="never">
          <template #header>
            <div class="panel-title">
              <ElIcon><FolderChecked /></ElIcon>
              <span>索引状态</span>
            </div>
          </template>

          <dl class="vector-summary">
            <div class="vector-summary-item">
              <dt>状态</dt>
              <dd>{{ statusBadge }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>总数</dt>
              <dd>{{ status?.total ?? 0 }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>代码</dt>
              <dd>{{ codeCount }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>知识库</dt>
              <dd>{{ kbCount }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>大小</dt>
              <dd>{{ indexSize }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>更新时间</dt>
              <dd>{{ indexUpdatedAt }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>来源</dt>
              <dd>{{ sourceLabel }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>基础路径</dt>
              <dd>{{ form.path || '-' }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>代码路径</dt>
              <dd>{{ form.codePath || '-' }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>知识库路径</dt>
              <dd>{{ form.kbPath || '-' }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>索引文件</dt>
              <dd>{{ status?.store || '选择项目后加载' }}</dd>
            </div>
            <div class="vector-summary-item">
              <dt>结果文件</dt>
              <dd>{{ savedPath || '运行后生成' }}</dd>
            </div>
          </dl>
        </ElCard>
      </aside>

      <ElCard class="panel vector-output-panel" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Document /></ElIcon>
              <span>索引明细</span>
            </span>
            <ElTag type="info" effect="plain">{{ recordTotal }}</ElTag>
          </div>
        </template>

        <div class="index-visualization">
          <section class="index-distribution">
            <div class="index-distribution-group">
              <h3>来源分布</h3>
              <div class="index-bars">
                <div v-for="item in sourceBars" :key="item.key" class="index-bar-row">
                  <span>{{ item.label }}</span>
                  <div class="index-bar-track">
                    <i :style="{ width: barWidth(item.count) }"></i>
                  </div>
                  <strong>{{ item.count }}</strong>
                </div>
              </div>
            </div>

            <div class="index-distribution-group">
              <h3>类型分布</h3>
              <div class="index-bars">
                <div v-for="item in kindBars" :key="item.key" class="index-bar-row">
                  <span>{{ item.label }}</span>
                  <div class="index-bar-track">
                    <i :style="{ width: barWidth(item.count) }"></i>
                  </div>
                  <strong>{{ item.count }}</strong>
                </div>
              </div>
            </div>
          </section>

          <section class="index-record-tools">
            <ElInput
              v-model="recordFilters.query"
              clearable
              placeholder="搜索符号、文件、文本"
              @keyup.enter="applyRecordFilters"
              @clear="applyRecordFilters"
            />
            <ElSelect v-model="recordFilters.source" clearable placeholder="来源" @change="applyRecordFilters">
              <ElOption label="代码" value="code" />
              <ElOption label="知识库" value="kb" />
            </ElSelect>
            <ElSelect v-model="recordFilters.kind" clearable placeholder="类型" @change="applyRecordFilters">
              <ElOption v-for="kind in kindOptions" :key="kind" :label="kind" :value="kind" />
            </ElSelect>
            <ElButton :icon="Refresh" @click="applyRecordFilters">查询</ElButton>
          </section>

          <ElEmpty v-if="!status?.exists" description="当前项目还没有索引，先重建索引。" />

          <ElScrollbar v-else class="index-record-scroll">
            <div class="index-record-table">
              <div class="index-record-head">
                <span>来源</span>
                <span>类型</span>
                <span>符号</span>
                <span>文件</span>
                <span>行</span>
                <span>文本预览</span>
              </div>
              <article v-for="record in records" :key="record.id" class="index-record-row">
                <ElTag :type="recordSourceLabel(record) === '知识库' ? 'warning' : 'primary'" effect="plain">
                  {{ recordSourceLabel(record) }}
                </ElTag>
                <span>{{ recordKind(record) }}</span>
                <strong>{{ recordSymbol(record) }}</strong>
                <code>{{ recordFile(record) }}</code>
                <span>{{ recordLine(record) }}</span>
                <p>{{ recordPreview(record) }}</p>
              </article>
              <ElEmpty v-if="!records.length" description="没有匹配的索引记录。" />
            </div>
          </ElScrollbar>

          <footer class="index-record-footer">
            <span>第 {{ recordPage }} / {{ pageCount }} 页</span>
            <div>
              <ElButton :disabled="recordPage <= 1" @click="changeRecordPage(recordPage - 1)">上一页</ElButton>
              <ElButton :disabled="recordPage >= pageCount" @click="changeRecordPage(recordPage + 1)">下一页</ElButton>
            </div>
          </footer>

          <section v-if="output" class="index-operation-result">
            <span>最近操作</span>
            <code>{{ output }}</code>
          </section>
        </div>
      </ElCard>
    </section>
  </div>
</template>

<style scoped>
.vector-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
}

.vector-command {
  align-items: center;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.vector-heading {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.vector-heading > .el-icon {
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

.vector-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.vector-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vector-command-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.vector-command-actions .el-button {
  min-width: 112px;
}

.vector-workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  padding: 18px 24px 24px;
}

.vector-config-stack {
  display: grid;
  gap: 16px;
  grid-template-rows: auto auto;
  min-height: 0;
}

.vector-form {
  display: grid;
  gap: 12px;
}

.vector-form .el-form-item {
  margin-bottom: 0;
}

.vector-advanced {
  border-top: 1px solid var(--line);
  display: grid;
  gap: 10px;
  padding-top: 2px;
}

.vector-advanced-toggle {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--accent);
  cursor: pointer;
  display: inline-flex;
  font-weight: 760;
  gap: 6px;
  justify-self: start;
  padding: 4px 0;
}

.vector-advanced-toggle:hover,
.vector-advanced-toggle:focus {
  color: var(--accent-strong);
}

.vector-advanced-fields {
  display: grid;
  gap: 12px;
}

.vector-summary {
  display: grid;
  gap: 0;
  margin: 0;
}

.vector-summary-item {
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 6px;
  grid-template-columns: 74px minmax(0, 1fr);
  padding: 10px 0;
}

.vector-summary-item:first-child {
  padding-top: 0;
}

.vector-summary-item:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.vector-summary-item dt {
  color: var(--text-faint);
  font-size: 0.72rem;
  margin: 0;
}

.vector-summary-item dd {
  color: var(--text);
  font-size: 0.84rem;
  font-weight: 700;
  margin: 0;
  overflow-wrap: anywhere;
}

.vector-output-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.vector-output-panel :deep(.el-card__body) {
  display: grid;
  min-height: 0;
  padding: 12px;
}

.split-title > span {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  min-width: 0;
}

.vector-markdown,
.vector-output-scroll {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.vector-markdown :deep(.el-scrollbar__view) {
  color: #263846;
  line-height: 1.6;
  min-height: 100%;
  padding: 20px 24px;
}

.vector-output {
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.9rem;
  line-height: 1.5;
  margin: 0;
  min-height: 100%;
  padding: 18px;
  white-space: pre-wrap;
}

.index-visualization {
  display: grid;
  gap: 12px;
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
  min-height: 0;
}

.index-distribution {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.index-distribution-group {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 12px;
}

.index-distribution-group h3 {
  color: var(--text);
  font-size: 0.84rem;
  margin: 0;
}

.index-bars {
  display: grid;
  gap: 8px;
}

.index-bar-row {
  align-items: center;
  display: grid;
  gap: 8px;
  grid-template-columns: 68px minmax(0, 1fr) 44px;
}

.index-bar-row span,
.index-bar-row strong {
  color: var(--text-faint);
  font-size: 0.74rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.index-bar-row strong {
  color: var(--text);
  text-align: right;
}

.index-bar-track {
  background: #e8eef2;
  border-radius: 999px;
  height: 8px;
  overflow: hidden;
}

.index-bar-track i {
  background: var(--accent);
  border-radius: inherit;
  display: block;
  height: 100%;
}

.index-record-tools {
  display: grid;
  gap: 10px;
  grid-template-columns: minmax(180px, 1fr) 120px 130px auto;
}

.index-record-scroll {
  border: 1px solid var(--line);
  border-radius: 8px;
  min-height: 0;
  overflow: hidden;
}

.index-record-table {
  display: grid;
  min-width: 980px;
}

.index-record-head,
.index-record-row {
  align-items: center;
  display: grid;
  gap: 10px;
  grid-template-columns: 74px 84px minmax(120px, 0.8fr) minmax(180px, 1fr) 48px minmax(240px, 1.4fr);
  padding: 10px 12px;
}

.index-record-head {
  background: var(--surface-muted);
  border-bottom: 1px solid var(--line);
  color: var(--text-faint);
  font-size: 0.72rem;
  font-weight: 760;
  position: sticky;
  top: 0;
  z-index: 1;
}

.index-record-row {
  border-bottom: 1px solid var(--line);
  color: var(--text);
  font-size: 0.78rem;
}

.index-record-row:last-child {
  border-bottom: 0;
}

.index-record-row > span,
.index-record-row strong,
.index-record-row code,
.index-record-row p {
  margin: 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.index-record-row code {
  color: #385166;
  font-family: "Cascadia Mono", Consolas, monospace;
}

.index-record-footer {
  align-items: center;
  color: var(--text-faint);
  display: flex;
  font-size: 0.78rem;
  justify-content: space-between;
}

.index-record-footer > div {
  display: flex;
  gap: 8px;
}

.index-operation-result {
  align-items: center;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 8px;
  grid-template-columns: 70px minmax(0, 1fr);
  padding: 10px 12px;
}

.index-operation-result span {
  color: var(--text-faint);
  font-size: 0.74rem;
  font-weight: 760;
}

.index-operation-result code {
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .vector-workbench {
    grid-template-columns: 1fr;
  }

  .vector-config-stack {
    grid-template-columns: minmax(0, 1fr) minmax(240px, 300px);
    grid-template-rows: auto;
  }

  .index-distribution,
  .index-record-tools {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .vector-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .vector-command-actions {
    align-items: stretch;
    display: grid;
  }

  .vector-command-actions .el-button {
    width: 100%;
  }

  .vector-workbench {
    grid-template-columns: 1fr;
    padding: 14px 16px;
  }

  .vector-config-stack {
    grid-template-columns: 1fr;
  }

  .vector-markdown,
  .vector-output-scroll {
    min-height: 320px;
  }

  .index-record-footer,
  .index-operation-result {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .index-record-footer {
    flex-direction: column;
    gap: 10px;
  }
}
</style>
