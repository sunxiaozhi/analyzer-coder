<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { CircleCheck, Delete, Edit, Memo, Plus, Refresh, Search, Warning } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElScrollbar,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus'
import type {
  AuthUser,
  KnowledgeAsset,
  KnowledgeAssetFilters,
  KnowledgeAssetForm,
  KnowledgeAssetStatus,
  KnowledgeAssetType,
  KnowledgeEvidence
} from '../../types'

const assetForm = defineModel<KnowledgeAssetForm>('assetForm', { required: true })
const filters = defineModel<KnowledgeAssetFilters>('filters', { required: true })

const props = defineProps<{
  assets: KnowledgeAsset[]
  busy: boolean
  currentUser: AuthUser
  message: string
  selectedAssetId: string
  users: AuthUser[]
}>()

const emit = defineEmits<{
  confirmAsset: [assetId: string]
  createAsset: []
  deleteAsset: [assetId: string]
  editAsset: [asset: KnowledgeAsset]
  markStale: [assetId: string]
  refreshAssets: []
  resetAsset: []
  updateAsset: []
}>()

const typeOptions: Array<{ label: string; value: KnowledgeAssetType }> = [
  { label: '业务规则', value: 'business_rule' },
  { label: '架构决策', value: 'adr' },
  { label: '故障案例', value: 'incident' },
  { label: '接口说明', value: 'api_doc' },
  { label: '开发规范', value: 'standard' },
  { label: '领域术语', value: 'glossary' },
  { label: '模块说明', value: 'module_note' }
]

const statusOptions: Array<{ label: string; value: KnowledgeAssetStatus; tag: 'info' | 'warning' | 'success' | 'danger' }> = [
  { label: '草稿', value: 'draft', tag: 'info' },
  { label: '待确认', value: 'pending_review', tag: 'warning' },
  { label: '已确认', value: 'confirmed', tag: 'success' },
  { label: '待复审', value: 'stale', tag: 'danger' },
  { label: '已归档', value: 'archived', tag: 'info' }
]

const editorVisible = shallowRef(false)

const ownerOptions = computed(() => {
  const known = props.users.length ? props.users : [props.currentUser]
  return known.some((user) => user.id === props.currentUser.id) ? known : [props.currentUser, ...known]
})

const selectedAsset = computed(() => props.assets.find((asset) => asset.id === props.selectedAssetId) ?? null)
const confirmedCount = computed(() => props.assets.filter((asset) => asset.status === 'confirmed').length)
const reviewCount = computed(() => props.assets.filter((asset) => asset.status === 'stale' || asset.status === 'pending_review').length)
const evidenceCount = computed(() => props.assets.reduce((total, asset) => total + asset.evidence.length, 0))
const editingExisting = computed(() => Boolean(assetForm.value.id))
const assetStats = computed(() => [
  { label: '全部资产', value: props.assets.length, tone: 'neutral' },
  { label: '已确认', value: confirmedCount.value, tone: 'trust' },
  { label: '待处理', value: reviewCount.value, tone: reviewCount.value ? 'evidence' : 'neutral' },
  { label: '代码证据', value: evidenceCount.value, tone: 'evidence' }
])

function typeLabel(value: string) {
  return typeOptions.find((option) => option.value === value)?.label ?? value
}

function statusMeta(value: string) {
  return statusOptions.find((option) => option.value === value) ?? statusOptions[0]
}

function ownerLabel(value: string) {
  return ownerOptions.value.find((user) => user.id === value)?.displayName ?? '-'
}

function formatDate(value: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function addEvidence() {
  const item: KnowledgeEvidence = {
    type: 'file',
    filePath: '',
    symbolName: '',
    startLine: 0,
    endLine: 0,
    note: ''
  }
  assetForm.value.evidence = [...assetForm.value.evidence, item]
}

function removeEvidence(index: number) {
  assetForm.value.evidence = assetForm.value.evidence.filter((_, itemIndex) => itemIndex !== index)
}

function openCreateDialog() {
  emit('resetAsset')
  editorVisible.value = true
}

function openEditDialog(asset: KnowledgeAsset) {
  emit('editAsset', asset)
  editorVisible.value = true
}

function submitAsset() {
  if (editingExisting.value) {
    emit('updateAsset')
    editorVisible.value = false
    return
  }
  emit('createAsset')
  editorVisible.value = false
}
</script>

<template>
  <div class="feature-page asset-page">
    <section class="asset-command">
      <div class="asset-heading">
        <ElIcon><Memo /></ElIcon>
        <div>
          <h2>知识资产</h2>
          <span>人工确认的业务规则、决策、故障经验和代码证据</span>
        </div>
      </div>

      <div class="asset-command-actions">
        <ElButton :icon="Refresh" :loading="busy" @click="$emit('refreshAssets')">刷新</ElButton>
        <ElButton type="primary" :icon="Plus" @click="openCreateDialog">新建资产</ElButton>
      </div>
    </section>

    <section class="asset-list-layout">
      <div class="asset-metrics" aria-label="知识资产概览">
        <article v-for="stat in assetStats" :key="stat.label" class="asset-metric" :class="`tone-${stat.tone}`">
          <span>{{ stat.label }}</span>
          <strong>{{ stat.value }}</strong>
        </article>
      </div>

      <ElCard class="panel asset-list-panel" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Memo /></ElIcon>
              <span>资产台账</span>
            </span>
            <ElTag type="info" effect="plain">{{ assets.length }} 条</ElTag>
          </div>
        </template>

        <div class="asset-toolbar">
          <ElInput v-model="filters.query" class="asset-search" clearable :prefix-icon="Search" placeholder="搜索标题、正文、证据" @change="$emit('refreshAssets')" />
          <div class="asset-filter-bar">
            <ElSelect v-model="filters.type" clearable placeholder="类型" @change="$emit('refreshAssets')">
              <ElOption v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
            </ElSelect>
            <ElSelect v-model="filters.status" clearable placeholder="状态" @change="$emit('refreshAssets')">
              <ElOption v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
            </ElSelect>
          </div>
        </div>

        <ElEmpty v-if="!assets.length" description="暂无知识资产。" />
        <ElTable v-else :data="assets" class="asset-table" row-key="id" stripe>
          <ElTableColumn label="知识资产" min-width="420">
            <template #default="{ row }">
              <div class="asset-table-main">
                <strong>{{ row.title }}</strong>
                <p>{{ row.summary || row.content || '暂无摘要' }}</p>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="标签" min-width="160">
            <template #default="{ row }">
              <div v-if="row.tags.length" class="asset-tag-list">
                <ElTag v-for="tag in row.tags" :key="tag" size="small" effect="plain">{{ tag }}</ElTag>
              </div>
              <span v-else class="asset-empty-cell">-</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="治理" width="150">
            <template #default="{ row }">
              <div class="asset-governance">
                <span class="trust-stamp" :class="`status-${row.status}`">{{ statusMeta(row.status).label }}</span>
                <small>{{ typeLabel(row.type) }}</small>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="责任与证据" width="150">
            <template #default="{ row }">
              <div class="asset-owner-cell">
                <strong>{{ ownerLabel(row.ownerUserId) }}</strong>
                <span>{{ row.evidence.length }} 条证据</span>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="更新" width="126">
            <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" fixed="right" width="260">
            <template #default="{ row }">
              <div class="asset-table-actions">
                <ElButton size="small" :icon="Edit" @click="openEditDialog(row)">编辑</ElButton>
                <ElButton size="small" type="success" :icon="CircleCheck" :loading="busy" @click="$emit('confirmAsset', row.id)">确认</ElButton>
                <ElButton size="small" :icon="Warning" :loading="busy" @click="$emit('markStale', row.id)">复审</ElButton>
                <ElButton size="small" type="danger" plain :icon="Delete" :loading="busy" @click="$emit('deleteAsset', row.id)">删除</ElButton>
              </div>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>
    </section>

    <ElDialog
      v-model="editorVisible"
      class="asset-dialog"
      :close-on-click-modal="false"
      :title="editingExisting ? '编辑知识资产' : '新建知识资产'"
      width="min(920px, 92vw)"
    >
      <ElScrollbar class="asset-dialog-scroll">
        <ElForm label-position="top" class="control-form asset-form">
          <div class="asset-form-grid">
            <ElFormItem label="知识类型">
              <ElSelect v-model="assetForm.type">
                <ElOption v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="治理状态">
              <ElSelect v-model="assetForm.status">
                <ElOption v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="负责人">
              <ElSelect v-model="assetForm.ownerUserId" filterable>
                <ElOption v-for="user in ownerOptions" :key="user.id" :label="user.displayName" :value="user.id" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="复审日期">
              <ElInput v-model="assetForm.reviewDueAt" clearable placeholder="2026-07-01" />
            </ElFormItem>
          </div>

          <ElFormItem label="标题">
            <ElInput v-model="assetForm.title" clearable placeholder="例如 手机号必须唯一" />
          </ElFormItem>
          <ElFormItem label="摘要">
            <ElInput v-model="assetForm.summary" clearable placeholder="一句话说明这条知识的结论" />
          </ElFormItem>
          <ElFormItem label="标签">
            <ElInput v-model="assetForm.tagsText" clearable placeholder="注册, 用户, 风控" />
          </ElFormItem>
          <ElFormItem label="正文">
            <ElInput v-model="assetForm.content" type="textarea" :rows="8" resize="none" />
          </ElFormItem>
          <ElFormItem label="来源文档">
            <ElInput v-model="assetForm.sourcePath" clearable placeholder="docs/domain/user-registration.md" />
          </ElFormItem>

          <section class="evidence-editor">
            <div class="evidence-heading">
              <strong>代码证据</strong>
              <ElButton size="small" :icon="Plus" @click="addEvidence">添加证据</ElButton>
            </div>
            <ElEmpty v-if="!assetForm.evidence.length" description="还没有关联代码、接口或文件证据。" />
            <article v-for="(evidence, index) in assetForm.evidence" :key="index" class="evidence-row">
              <ElInput v-model="evidence.type" placeholder="类型" />
              <ElInput v-model="evidence.filePath" placeholder="文件路径" />
              <ElInput v-model="evidence.symbolName" placeholder="方法/接口/类名" />
              <ElInput v-model="evidence.note" placeholder="说明" />
              <ElButton :icon="Delete" title="删除证据" @click="removeEvidence(index)" />
            </article>
          </section>
        </ElForm>
      </ElScrollbar>

      <template #footer>
        <footer class="asset-editor-actions">
          <div class="asset-selected-note">
            <template v-if="selectedAsset">
              <span>{{ typeLabel(selectedAsset.type) }}</span>
              <span class="trust-stamp" :class="`status-${selectedAsset.status}`">{{ statusMeta(selectedAsset.status).label }}</span>
            </template>
            <ElTag v-if="message" type="success" effect="plain">{{ message }}</ElTag>
          </div>
          <div class="asset-action-buttons">
            <ElButton :disabled="busy" @click="editorVisible = false">取消</ElButton>
            <ElButton v-if="editingExisting" :icon="Warning" :loading="busy" @click="$emit('markStale', assetForm.id)">标记复审</ElButton>
            <ElButton v-if="editingExisting" :icon="CircleCheck" type="success" :loading="busy" @click="$emit('confirmAsset', assetForm.id)">确认</ElButton>
            <ElButton type="primary" :loading="busy" @click="submitAsset">{{ editingExisting ? '保存资产' : '创建资产' }}</ElButton>
            <ElButton v-if="editingExisting" type="danger" plain :icon="Delete" :loading="busy" @click="$emit('deleteAsset', assetForm.id)">删除</ElButton>
          </div>
        </footer>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.asset-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
}

.asset-command {
  align-items: center;
  background:
    linear-gradient(90deg, rgba(31, 122, 104, 0.1), transparent 46%),
    linear-gradient(180deg, var(--archive-paper, #fffdf7) 0%, var(--archive-panel, #f8faf7) 100%);
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 18px 26px 16px;
}

.asset-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.asset-heading > .el-icon {
  align-items: center;
  background: var(--archive-navy, #13231f);
  border-radius: 6px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.asset-heading h2 {
  color: var(--text);
  font-size: 1.14rem;
  font-weight: 780;
  margin: 0;
}

.asset-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.asset-command-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.asset-list-layout {
  display: grid;
  gap: 14px;
  min-height: 0;
  padding: 18px 26px 26px;
}

.asset-metrics {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.asset-metric {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid var(--line);
  border-radius: 6px;
  display: grid;
  gap: 6px;
  min-height: 76px;
  padding: 13px 14px;
  position: relative;
}

.asset-metric::before {
  background: var(--line-strong);
  border-radius: 999px;
  content: "";
  height: 3px;
  left: 14px;
  position: absolute;
  right: 14px;
  top: 0;
}

.asset-metric.tone-trust::before {
  background: var(--trust);
}

.asset-metric.tone-evidence::before {
  background: var(--evidence);
}

.asset-metric span {
  color: var(--text-faint);
  font-size: 0.76rem;
  font-weight: 720;
}

.asset-metric strong {
  color: var(--text);
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  font-size: 1.52rem;
  line-height: 1;
}

.asset-list-panel {
  display: grid;
  min-height: 0;
}

.asset-list-panel :deep(.el-card__header) {
  background: var(--archive-paper, #fffdf7);
  border-bottom-color: var(--line);
  padding: 13px 16px;
}

.asset-list-panel :deep(.el-card__body) {
  display: grid;
  min-height: 0;
  padding: 0;
  grid-template-rows: auto minmax(0, 1fr);
}

.asset-toolbar {
  align-items: center;
  background: var(--archive-panel, #f8faf7);
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 10px;
  grid-template-columns: minmax(260px, 1fr) auto;
  padding: 12px 14px;
}

.asset-search {
  max-width: 520px;
}

.asset-filter-bar {
  display: grid;
  gap: 8px;
  grid-template-columns: 160px 160px;
}

.asset-table {
  min-height: 0;
}

.asset-table :deep(.el-table) {
  background: transparent;
}

.asset-table :deep(.el-table__cell) {
  border-bottom-color: rgba(217, 223, 209, 0.82);
}

.asset-table :deep(.el-table__header th) {
  background: #eef4ec;
  color: var(--text-soft);
  font-size: 0.78rem;
  font-weight: 760;
}

.asset-table :deep(.el-table__row td) {
  padding: 14px 0;
}

.asset-table-main {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.asset-table-main strong {
  color: var(--text);
  font-size: 0.94rem;
  font-weight: 760;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-table-main p {
  color: var(--text-muted);
  display: -webkit-box;
  font-size: 0.8rem;
  line-height: 1.52;
  margin: 0;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.asset-tag-list,
.asset-table-actions,
.evidence-heading,
.asset-editor-actions,
.asset-action-buttons {
  align-items: center;
  display: flex;
  gap: 8px;
}

.asset-tag-list,
.asset-table-actions {
  flex-wrap: wrap;
}

.asset-table-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-start;
}

.asset-table-actions :deep(.el-button) {
  justify-content: flex-start;
  margin-left: 0;
  padding-left: 9px;
  padding-right: 9px;
}

.asset-empty-cell {
  color: var(--text-faint);
}

.asset-governance,
.asset-owner-cell {
  display: grid;
  gap: 7px;
}

.asset-governance small,
.asset-owner-cell span {
  color: var(--text-faint);
  font-size: 0.76rem;
}

.asset-owner-cell strong {
  color: var(--text-soft);
  font-size: 0.86rem;
  font-weight: 720;
}

.asset-dialog :deep(.el-dialog__body) {
  padding: 10px 20px;
}

.asset-dialog :deep(.el-dialog__footer) {
  border-top: 1px solid var(--line);
  padding: 14px 20px 18px;
}

.asset-dialog-scroll {
  max-height: min(66vh, 640px);
}

.asset-form {
  display: grid;
  gap: 2px;
}

.asset-form-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.evidence-editor {
  border-top: 1px solid var(--line);
  display: grid;
  gap: 10px;
  margin-top: 8px;
  padding-top: 14px;
}

.evidence-heading {
  justify-content: space-between;
}

.evidence-row {
  display: grid;
  gap: 8px;
  grid-template-columns: 92px minmax(120px, 1.2fr) minmax(120px, 1fr) minmax(120px, 1fr) 36px;
}

.asset-editor-actions {
  border-top: 1px solid var(--line);
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
}

.asset-selected-note {
  align-items: center;
  color: var(--text-faint);
  display: flex;
  gap: 10px;
  font-size: 0.8rem;
  min-height: 30px;
}

.trust-stamp {
  border: 1px solid var(--accent);
  border-radius: 4px;
  color: var(--accent);
  display: inline-flex;
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  font-size: 0.72rem;
  font-weight: 700;
  justify-content: center;
  letter-spacing: 0;
  line-height: 1;
  padding: 7px 9px 6px;
  transform: rotate(-1deg);
  white-space: nowrap;
}

.trust-stamp.status-pending_review {
  border-color: var(--evidence, #a45c25);
  color: var(--evidence, #a45c25);
}

.trust-stamp.status-stale {
  border-color: #b54708;
  color: #b54708;
}

.trust-stamp.status-archived,
.trust-stamp.status-draft {
  border-color: var(--text-faint);
  color: var(--text-faint);
}

.asset-action-buttons {
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 980px) {
  .asset-metrics,
  .asset-toolbar,
  .asset-filter-bar,
  .asset-form-grid,
  .evidence-row {
    grid-template-columns: 1fr;
  }

  .asset-command,
  .asset-editor-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .asset-command-actions,
  .asset-action-buttons {
    justify-content: stretch;
  }
}
</style>
