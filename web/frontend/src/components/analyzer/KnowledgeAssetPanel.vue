<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, shallowRef } from 'vue'
import { CircleCheck, Delete, Edit, Filter, Memo, Plus, Refresh, Search, View, Warning } from '@element-plus/icons-vue'
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
  ElPagination,
  ElScrollbar,
  ElSelect,
  ElTabPane,
  ElTabs,
  ElTable,
  ElTableColumn,
  ElTag,
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
        <ElButton type="primary" :icon="Plus" @click="openCreateDialog">新建知识资产</ElButton>
      </div>
    </section>

    <section class="asset-list-layout">
      <aside class="asset-filter-tree-panel" aria-label="知识资产筛选">
        <div class="asset-filter-tree-heading">
          <span>
            <ElIcon><Filter /></ElIcon>
            <span>筛选树</span>
          </span>
          <ElTag type="info" effect="plain">{{ activeFilterLabel }}</ElTag>
        </div>
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

      <div class="asset-list-main">
        <ElCard class="panel asset-list-panel" shadow="never">
          <div class="asset-toolbar">
            <ElInput
              v-model="filters.query"
              class="asset-search"
              clearable
              :prefix-icon="Search"
              placeholder="搜索标题、正文、证据"
              @change="$emit('applyFilters')"
              @clear="$emit('applyFilters')"
            />
            <ElButton :icon="Refresh" :loading="busy" @click="$emit('applyFilters')">应用筛选</ElButton>
          </div>

          <div class="asset-table-frame">
            <ElEmpty v-if="!assets.length" description="暂无知识资产。" />
            <ElTable v-else :data="assets" class="asset-table" height="100%" row-key="id" stripe>
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
          </div>

        </ElCard>

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
      </div>
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
              <ElInput v-model="assetForm.reviewDueAt" clearable />
            </ElFormItem>
          </div>

          <ElFormItem label="标题">
            <ElInput v-model="assetForm.title" clearable />
          </ElFormItem>
          <ElFormItem label="摘要">
            <ElInput v-model="assetForm.summary" clearable />
          </ElFormItem>
          <ElFormItem label="标签">
            <ElInput v-model="assetForm.tagsText" clearable />
          </ElFormItem>
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
  flex: 1 1 auto;
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  width: 100%;
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
  grid-template-columns: 236px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 18px 26px 26px;
}

.asset-filter-tree-panel {
  background: rgba(255, 253, 247, 0.78);
  border: 1px solid var(--line);
  border-radius: 6px;
  display: grid;
  gap: 10px;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  padding: 12px;
}

.asset-filter-tree-heading {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.asset-filter-tree-heading > span {
  align-items: center;
  color: var(--text-soft);
  display: inline-flex;
  font-size: 0.82rem;
  font-weight: 760;
  gap: 7px;
  min-width: 0;
}

.asset-filter-tree-heading :deep(.el-tag) {
  max-width: 116px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-filter-tree {
  background: transparent;
  min-height: 0;
  overflow: auto;
}

.asset-filter-tree :deep(.el-tree-node__content) {
  border-radius: 5px;
  color: var(--text-soft);
  font-size: 0.82rem;
  height: 34px;
}

.asset-filter-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: rgba(31, 122, 104, 0.12);
  color: var(--accent);
  font-weight: 760;
}

.asset-list-main {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.asset-list-panel {
  display: grid;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.asset-list-panel :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 0;
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
  height: 100%;
  min-height: 0;
}

.asset-pagination {
  align-items: center;
  background: var(--archive-paper, #fffdf7);
  border: 1px solid var(--line);
  border-top: 0;
  border-bottom-left-radius: 6px;
  border-bottom-right-radius: 6px;
  color: var(--text-faint);
  display: flex;
  flex: 0 0 auto;
  font-size: 0.78rem;
  justify-content: flex-end;
  min-height: 54px;
  min-width: 0;
  padding: 10px 14px;
  position: relative;
  z-index: 1;
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
  gap: 10px;
}

.asset-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.asset-form-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 2px;
}

.asset-form :deep(.el-form-item__label) {
  line-height: 1.35;
  margin-bottom: 7px;
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
  background: var(--archive-paper, #fffdf7);
  border: 1px solid var(--line);
  border-radius: 6px;
  height: min(34vh, 360px);
  min-width: 0;
  overflow: hidden;
}

.asset-markdown-body {
  color: var(--text);
  font-size: 0.84rem;
  line-height: 1.6;
  padding: 12px 14px;
  word-break: break-word;
}

.asset-markdown-body :deep(h1),
.asset-markdown-body :deep(h2),
.asset-markdown-body :deep(h3) {
  color: var(--text);
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
  background: rgba(19, 35, 31, 0.08);
  border-radius: 4px;
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  padding: 2px 5px;
}

.asset-markdown-body :deep(pre) {
  background: #13231f;
  border-radius: 6px;
  color: #f8faf7;
  overflow: auto;
  padding: 10px 12px;
}

.asset-markdown-body :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.asset-markdown-body :deep(blockquote) {
  border-left: 3px solid var(--accent);
  color: var(--text-muted);
  margin: 0 0 10px;
  padding-left: 10px;
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
  .asset-list-layout,
  .asset-toolbar,
  .asset-form-grid {
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
