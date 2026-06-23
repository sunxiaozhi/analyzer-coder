<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, shallowRef } from 'vue'
import { CircleCheck, Delete, Edit, Filter, Plus, Refresh, Search, View, Warning } from '@element-plus/icons-vue'
import {
  ElButton,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElPagination,
  ElScrollbar,
  ElSelect,
  ElTabPane,
  ElTabs,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElTree
} from 'element-plus'
import type {
  AuthUser,
  KnowledgeAsset,
  KnowledgeAssetFilters,
  KnowledgeAssetForm,
  KnowledgeAssetPagination,
  KnowledgeAssetStatus,
  KnowledgeAssetType
} from '../../types'

const assetForm = defineModel<KnowledgeAssetForm>('assetForm', { required: true })
const filters = defineModel<KnowledgeAssetFilters>('filters', { required: true })
const pagination = defineModel<KnowledgeAssetPagination>('pagination', { required: true })

const props = defineProps<{
  assets: KnowledgeAsset[]
  busy: boolean
  currentUser: AuthUser
  message: string
  selectedAssetId: string
  users: AuthUser[]
}>()

const emit = defineEmits<{
  applyFilters: []
  changePage: [page: number]
  changePageSize: [pageSize: number]
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

type AssetTreeFilter =
  | { kind: 'all'; value: '' }
  | { kind: 'type'; value: KnowledgeAssetType }
  | { kind: 'status'; value: KnowledgeAssetStatus }
  | { kind: 'group'; value: '' }

interface AssetTreeNode {
  key: string
  label: string
  filter: AssetTreeFilter
  children?: AssetTreeNode[]
}

const editorVisible = shallowRef(false)
const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const ownerOptions = computed(() => {
  const known = props.users.length ? props.users : [props.currentUser]
  return known.some((user) => user.id === props.currentUser.id) ? known : [props.currentUser, ...known]
})

const selectedAsset = computed(() => props.assets.find((asset) => asset.id === props.selectedAssetId) ?? null)
const editingExisting = computed(() => Boolean(assetForm.value.id))
const filterTreeData = computed<AssetTreeNode[]>(() => [
  {
    key: 'all',
    label: '全部知识资产',
    filter: { kind: 'all', value: '' }
  },
  {
    key: 'type',
    label: '知识类型',
    filter: { kind: 'group', value: '' },
    children: typeOptions.map((option) => ({
      key: `type:${option.value}`,
      label: option.label,
      filter: { kind: 'type', value: option.value }
    }))
  },
  {
    key: 'status',
    label: '治理状态',
    filter: { kind: 'group', value: '' },
    children: statusOptions.map((option) => ({
      key: `status:${option.value}`,
      label: option.label,
      filter: { kind: 'status', value: option.value }
    }))
  }
])
const activeTreeKey = computed(() => {
  if (filters.value.type) return `type:${filters.value.type}`
  if (filters.value.status) return `status:${filters.value.status}`
  return 'all'
})
const activeFilterLabel = computed(() => {
  if (filters.value.type) return typeLabel(filters.value.type)
  if (filters.value.status) return statusMeta(filters.value.status).label
  return '全部知识资产'
})
const renderedAssetMarkdown = computed(() => markdown.render(assetForm.value.content || ''))

function handleTreeFilter(node: AssetTreeNode) {
  if (node.filter.kind === 'group') return
  if (node.filter.kind === 'all') {
    filters.value.type = ''
    filters.value.status = ''
  } else if (node.filter.kind === 'type') {
    filters.value.type = node.filter.value
    filters.value.status = ''
  } else {
    filters.value.status = node.filter.value
    filters.value.type = ''
  }
  emit('applyFilters')
}

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
    <section class="asset-topbar">
      <div class="asset-title-block">
        <h2>知识资产</h2>
        <p>维护团队确认过的规则、决策、接口说明和故障经验。</p>
      </div>

      <div class="asset-topbar-actions">
        <ElButton class="asset-quiet-button" :icon="Refresh" :loading="busy" @click="$emit('refreshAssets')">刷新</ElButton>
        <ElButton class="asset-primary-button" type="primary" :icon="Plus" @click="openCreateDialog">新建资产</ElButton>
      </div>
    </section>

    <section class="asset-workbench">
      <aside class="asset-filter-panel" aria-label="知识资产筛选">
        <div class="asset-filter-heading">
          <span>
            <ElIcon><Filter /></ElIcon>
            <span>筛选</span>
          </span>
          <ElTag effect="plain">{{ activeFilterLabel }}</ElTag>
        </div>

        <ElInput
          v-model="filters.query"
          class="asset-filter-search"
          clearable
          :prefix-icon="Search"
          placeholder="搜索标题、正文、证据"
          @change="$emit('applyFilters')"
          @clear="$emit('applyFilters')"
        />

        <ElTree
          class="asset-filter-tree"
          :current-node-key="activeTreeKey"
          :data="filterTreeData"
          default-expand-all
          highlight-current
          node-key="key"
          @node-click="handleTreeFilter"
        />
      </aside>

      <main class="asset-data-panel">
        <div class="asset-data-toolbar">
          <div class="asset-data-title">
            <strong>资产列表</strong>
            <span>{{ pagination.total }} 条</span>
          </div>
          <ElButton class="asset-quiet-button" size="small" :icon="Refresh" :loading="busy" @click="$emit('applyFilters')">应用筛选</ElButton>
        </div>

        <div class="asset-table-frame">
          <ElEmpty v-if="!assets.length" description="暂无知识资产。" />
          <ElTable v-else :data="assets" class="asset-table" height="100%" row-key="id">
            <ElTableColumn label="标题" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <strong class="asset-title-cell">{{ row.title }}</strong>
              </template>
            </ElTableColumn>
            <ElTableColumn label="类型" width="96">
              <template #default="{ row }">
                <span class="asset-type-cell">{{ typeLabel(row.type) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="94">
              <template #default="{ row }">
                <span class="asset-status-pill" :class="`status-${row.status}`">{{ statusMeta(row.status).label }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="负责人" width="104" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="asset-owner-name">{{ ownerLabel(row.ownerUserId) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="更新时间" width="108">
              <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="118" align="center" class-name="asset-actions-column">
              <template #default="{ row }">
                <div class="asset-table-actions">
                  <ElTooltip content="编辑" placement="top">
                    <ElButton aria-label="编辑" class="asset-icon-action" size="small" text :icon="Edit" title="编辑" @click="openEditDialog(row)" />
                  </ElTooltip>
                  <ElTooltip content="确认" placement="top">
                    <ElButton aria-label="确认" class="asset-icon-action" size="small" text type="success" :icon="CircleCheck" :loading="busy" title="确认" @click="$emit('confirmAsset', row.id)" />
                  </ElTooltip>
                  <ElTooltip content="标记复审" placement="top">
                    <ElButton aria-label="标记复审" class="asset-icon-action" size="small" text :icon="Warning" :loading="busy" title="标记复审" @click="$emit('markStale', row.id)" />
                  </ElTooltip>
                  <ElTooltip content="删除" placement="top">
                    <ElButton aria-label="删除" class="asset-icon-action danger" size="small" text type="danger" :icon="Delete" :loading="busy" title="删除" @click="$emit('deleteAsset', row.id)" />
                  </ElTooltip>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <footer class="asset-pagination">
          <span class="asset-pagination-total">共 {{ pagination.total }} 条</span>
          <ElPagination
            background
            :hide-on-single-page="false"
            layout="sizes, prev, pager, next"
            :current-page="pagination.page"
            :page-size="pagination.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="pagination.total"
            @current-change="$emit('changePage', $event)"
            @size-change="$emit('changePageSize', $event)"
          />
        </footer>
      </main>
    </section>

    <ElDialog
      v-model="editorVisible"
      class="asset-dialog"
      :close-on-click-modal="false"
      :title="editingExisting ? '编辑知识资产' : '新建知识资产'"
      width="min(1040px, 94vw)"
    >
      <ElScrollbar class="asset-dialog-scroll">
        <ElForm label-position="top" class="control-form asset-form">
          <ElFormItem label="标题">
            <ElInput v-model="assetForm.title" clearable />
          </ElFormItem>

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
              <ElInput v-model="assetForm.reviewDueAt" clearable />
            </ElFormItem>
          </div>

          <div class="asset-form-grid secondary">
            <ElFormItem label="摘要">
              <ElInput v-model="assetForm.summary" clearable />
            </ElFormItem>
            <ElFormItem label="标签">
              <ElInput v-model="assetForm.tagsText" clearable />
            </ElFormItem>
          </div>

          <ElFormItem label="正文（Markdown）">
            <ElTabs class="asset-markdown-tabs">
              <ElTabPane>
                <template #label>
                  <span class="tab-label"><ElIcon><Edit /></ElIcon>编辑</span>
                </template>
                <ElInput v-model="assetForm.content" class="asset-markdown-input" type="textarea" resize="none" />
              </ElTabPane>
              <ElTabPane>
                <template #label>
                  <span class="tab-label"><ElIcon><View /></ElIcon>预览</span>
                </template>
                <ElScrollbar class="asset-markdown-scroll">
                  <article class="asset-markdown-body" v-html="renderedAssetMarkdown"></article>
                </ElScrollbar>
              </ElTabPane>
            </ElTabs>
          </ElFormItem>
        </ElForm>
      </ElScrollbar>

      <template #footer>
        <footer class="asset-editor-actions">
          <div class="asset-selected-note">
            <template v-if="selectedAsset">
              <span>{{ typeLabel(selectedAsset.type) }}</span>
              <span class="asset-status-pill" :class="`status-${selectedAsset.status}`">{{ statusMeta(selectedAsset.status).label }}</span>
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
  --asset-accent: #2563eb;
  --asset-accent-dark: #1d4ed8;
  --asset-bg: #edf5ff;
  --asset-danger: #c2413a;
  --asset-ink: #17212b;
  --asset-line: #d6e4f5;
  --asset-muted: #647384;
  --asset-panel: #ffffff;
  --asset-soft: #eaf2ff;
  background: var(--asset-bg);
  flex: 1 1 auto;
  gap: 16px;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  padding: 20px 24px 24px;
  width: 100%;
}

.asset-topbar {
  align-items: flex-end;
  display: flex;
  gap: 18px;
  justify-content: space-between;
  min-width: 0;
}

.asset-title-block {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.asset-title-block h2 {
  color: var(--asset-ink);
  font-size: 1.36rem;
  font-weight: 760;
  line-height: 1.2;
  margin: 0;
}

.asset-title-block p {
  color: var(--asset-muted);
  font-size: 0.84rem;
  line-height: 1.5;
  margin: 0;
}

.asset-topbar-actions {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  white-space: nowrap;
}

.asset-primary-button.el-button {
  background: var(--asset-accent);
  border-color: var(--asset-accent);
}

.asset-primary-button.el-button:hover {
  background: var(--asset-accent-dark);
  border-color: var(--asset-accent-dark);
}

.asset-quiet-button.el-button {
  background: #ffffff;
  border-color: var(--asset-line);
  color: #344456;
}

.asset-workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: 228px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.asset-filter-panel,
.asset-data-panel {
  background: var(--asset-panel);
  border: 1px solid var(--asset-line);
  border-radius: 8px;
  box-shadow: 0 14px 32px rgba(27, 39, 51, 0.06);
  min-width: 0;
}

.asset-filter-panel {
  display: grid;
  gap: 12px;
  grid-template-rows: auto auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 14px;
  position: relative;
}

.asset-filter-panel::before {
  background: var(--asset-accent);
  border-radius: 8px 0 0 8px;
  content: "";
  inset: 0 auto 0 0;
  position: absolute;
  width: 3px;
}

.asset-filter-heading {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.asset-filter-heading > span {
  align-items: center;
  color: var(--asset-ink);
  display: inline-flex;
  font-size: 0.86rem;
  font-weight: 740;
  gap: 6px;
  min-width: 0;
}

.asset-filter-heading :deep(.el-tag) {
  border-color: #cddbe6;
  color: var(--asset-muted);
  max-width: 112px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-filter-search {
  min-width: 0;
}

.asset-filter-tree {
  background: transparent;
  min-height: 0;
  overflow: auto;
}

.asset-filter-tree :deep(.el-tree-node__content) {
  border-radius: 6px;
  color: #405064;
  font-size: 0.82rem;
  height: 32px;
}

.asset-filter-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: rgba(37, 99, 235, 0.11);
  color: var(--asset-accent-dark);
  font-weight: 760;
}

.asset-data-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.asset-data-toolbar {
  align-items: center;
  border-bottom: 1px solid var(--asset-line);
  display: flex;
  gap: 12px;
  justify-content: space-between;
  min-width: 0;
  padding: 13px 16px;
}

.asset-data-title {
  align-items: baseline;
  display: flex;
  gap: 8px;
  min-width: 0;
}

.asset-data-title strong {
  color: var(--asset-ink);
  font-size: 0.94rem;
}

.asset-data-title span {
  color: var(--asset-muted);
  font-size: 0.78rem;
}

.asset-table-frame {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.asset-table-frame :deep(.el-empty) {
  height: 100%;
}

.asset-table {
  --el-table-border-color: var(--asset-line);
  --el-table-header-bg-color: #f7fafc;
  height: 100%;
  min-height: 0;
}

.asset-table :deep(.el-table__cell) {
  border-bottom-color: var(--asset-line);
}

.asset-table :deep(.el-table__header th) {
  background: #f7fafc;
  color: #526276;
  font-size: 0.78rem;
  font-weight: 760;
}

.asset-table :deep(.el-table__cell .cell) {
  padding-left: 14px;
  padding-right: 14px;
}

.asset-table :deep(.asset-actions-column .cell) {
  padding-left: 6px;
  padding-right: 6px;
}

.asset-table :deep(.el-table__row td) {
  padding: 12px 0;
}

.asset-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: #f5f9ff;
}

.asset-title-cell {
  color: var(--asset-ink);
  display: block;
  font-size: 0.94rem;
  font-weight: 720;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-table-actions,
.asset-editor-actions,
.asset-action-buttons {
  align-items: center;
  display: flex;
  gap: 8px;
}

.asset-table-actions {
  flex-wrap: nowrap;
  gap: 3px;
  justify-content: center;
  min-width: max-content;
  white-space: nowrap;
}

.asset-table-actions :deep(.asset-icon-action) {
  flex: 0 0 auto;
  height: 26px;
  justify-content: center;
  margin-left: 0;
  padding: 0;
  width: 26px;
}

.asset-owner-name,
.asset-type-cell {
  color: #405064;
  font-size: 0.86rem;
  font-weight: 650;
  white-space: nowrap;
}

.asset-status-pill {
  align-items: center;
  border: 1px solid #b8c7d6;
  border-radius: 999px;
  color: #405064;
  display: inline-flex;
  font-size: 0.74rem;
  font-weight: 720;
  justify-content: center;
  line-height: 1;
  min-width: 58px;
  padding: 6px 8px;
  white-space: nowrap;
}

.asset-status-pill.status-confirmed {
  background: rgba(37, 99, 235, 0.1);
  border-color: rgba(37, 99, 235, 0.3);
  color: var(--asset-accent-dark);
}

.asset-status-pill.status-pending_review {
  background: rgba(14, 165, 233, 0.1);
  border-color: rgba(14, 165, 233, 0.28);
  color: #0369a1;
}

.asset-status-pill.status-stale {
  background: rgba(194, 65, 58, 0.1);
  border-color: rgba(194, 65, 58, 0.28);
  color: var(--asset-danger);
}

.asset-pagination {
  align-items: center;
  border-top: 1px solid var(--asset-line);
  color: var(--asset-muted);
  display: flex;
  flex: 0 0 auto;
  font-size: 0.78rem;
  justify-content: flex-end;
  min-height: 54px;
  min-width: 0;
  padding: 10px 16px;
}

.asset-pagination :deep(.el-pagination) {
  justify-content: flex-end;
  min-width: 0;
}

.asset-pagination-total {
  flex: 0 0 auto;
  margin-right: auto;
  white-space: nowrap;
}

.asset-dialog :deep(.el-dialog__header) {
  margin-right: 0;
  padding: 18px 24px 12px;
}

.asset-dialog :deep(.el-dialog__body) {
  padding: 10px 24px;
}

.asset-dialog :deep(.el-dialog__footer) {
  border-top: 1px solid var(--asset-line);
  padding: 14px 24px 18px;
}

.asset-dialog-scroll {
  max-height: min(66vh, 640px);
}

.asset-form {
  display: grid;
  gap: 14px;
}

.asset-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.asset-form-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.asset-form-grid.secondary {
  grid-template-columns: minmax(0, 1.4fr) minmax(220px, 0.8fr);
}

.asset-form :deep(.el-form-item__label) {
  color: #526276;
  font-size: 0.78rem;
  font-weight: 720;
  line-height: 1.35;
  margin-bottom: 6px;
}

.asset-markdown-tabs {
  min-width: 0;
  width: 100%;
}

.asset-markdown-tabs :deep(.el-tabs__header) {
  margin: 0 0 10px;
}

.asset-markdown-tabs :deep(.el-tabs__content) {
  min-width: 0;
}

.asset-markdown-tabs :deep(.el-tab-pane) {
  min-width: 0;
}

.asset-markdown-tabs .tab-label {
  align-items: center;
  display: inline-flex;
  gap: 6px;
}

.asset-markdown-input {
  display: block;
  min-width: 0;
}

.asset-markdown-input :deep(.el-textarea__inner) {
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  height: min(34vh, 360px);
  line-height: 1.58;
}

.asset-markdown-scroll {
  background: #fbfcfe;
  border: 1px solid var(--asset-line);
  border-radius: 6px;
  height: min(34vh, 360px);
  min-width: 0;
  overflow: hidden;
}

.asset-markdown-body {
  color: var(--asset-ink);
  font-size: 0.84rem;
  line-height: 1.6;
  padding: 12px 14px;
  word-break: break-word;
}

.asset-markdown-body :deep(h1),
.asset-markdown-body :deep(h2),
.asset-markdown-body :deep(h3) {
  color: var(--asset-ink);
  line-height: 1.25;
  margin: 12px 0 8px;
}

.asset-markdown-body :deep(h1:first-child),
.asset-markdown-body :deep(h2:first-child),
.asset-markdown-body :deep(h3:first-child),
.asset-markdown-body :deep(p:first-child) {
  margin-top: 0;
}

.asset-markdown-body :deep(p),
.asset-markdown-body :deep(ul),
.asset-markdown-body :deep(ol) {
  margin: 0 0 10px;
}

.asset-markdown-body :deep(code) {
  background: rgba(23, 33, 43, 0.08);
  border-radius: 4px;
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  padding: 2px 5px;
}

.asset-markdown-body :deep(pre) {
  background: #16212d;
  border-radius: 6px;
  color: #f7fafc;
  overflow: auto;
  padding: 10px 12px;
}

.asset-markdown-body :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.asset-markdown-body :deep(blockquote) {
  border-left: 3px solid var(--asset-accent);
  color: var(--asset-muted);
  margin: 0 0 10px;
  padding-left: 10px;
}

.asset-editor-actions {
  justify-content: space-between;
  min-width: 0;
}

.asset-selected-note {
  align-items: center;
  color: var(--asset-muted);
  display: flex;
  gap: 10px;
  font-size: 0.8rem;
  min-height: 30px;
  min-width: 0;
}

.asset-action-buttons {
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 980px) {
  .asset-page {
    padding: 16px;
  }

  .asset-workbench,
  .asset-form-grid {
    grid-template-columns: 1fr;
  }

  .asset-form-grid.secondary {
    grid-template-columns: 1fr;
  }

  .asset-topbar,
  .asset-editor-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .asset-topbar-actions,
  .asset-action-buttons {
    justify-content: stretch;
  }

  .asset-pagination {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .asset-pagination :deep(.el-pagination) {
    justify-content: flex-start;
  }
}
</style>
