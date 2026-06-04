<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, shallowRef, watch } from 'vue'
import { Delete, Document, Edit, Notebook, Plus, Refresh, Select, View } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElMain,
  ElScrollbar,
  ElTabPane,
  ElTabs,
  ElTag
} from 'element-plus'
import type { KnowledgeTemplate, KnowledgeTemplateForm } from '../../types'

const templateForm = defineModel<KnowledgeTemplateForm>('templateForm', { required: true })

const props = defineProps<{
  busy: boolean
  message: string
  templates: KnowledgeTemplate[]
}>()

const emit = defineEmits<{
  createTemplate: []
  deleteTemplate: [templateId: string]
  refreshTemplates: []
  updateTemplate: []
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const dialogOpen = shallowRef(false)
const pendingAction = shallowRef<'create' | 'update' | ''>('')

const renderedMarkdown = computed(() => markdown.render(templateForm.value.content || ''))

const editingExisting = computed(() => Boolean(templateForm.value.id))

function resetForm() {
  templateForm.value.id = ''
  templateForm.value.name = ''
  templateForm.value.path = ''
  templateForm.value.content = ''
}

function openCreateDialog() {
  resetForm()
  dialogOpen.value = true
}

function openEditDialog(template: KnowledgeTemplate) {
  templateForm.value.id = template.id
  templateForm.value.name = template.name
  templateForm.value.path = template.path
  templateForm.value.content = template.content
  dialogOpen.value = true
}

function submitTemplate() {
  pendingAction.value = editingExisting.value ? 'update' : 'create'
  if (editingExisting.value) {
    emit('updateTemplate')
    return
  }
  emit('createTemplate')
}

function formatDate(value: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

watch(
  () => props.templates,
  () => {
    if (!pendingAction.value) return
    dialogOpen.value = false
    pendingAction.value = ''
  }
)
</script>

<template>
  <ElMain class="page-surface template-page">
    <div class="page-header">
      <div>
        <h2>知识库模板</h2>
      </div>
      <div class="page-actions">
        <ElTag type="info" effect="plain">{{ templates.length }} 个模板</ElTag>
        <ElButton :icon="Refresh" :loading="busy" @click="$emit('refreshTemplates')">
          刷新
        </ElButton>
        <ElButton type="primary" :icon="Plus" @click="openCreateDialog">
          新建模板
        </ElButton>
      </div>
    </div>

    <ElCard class="panel template-list-panel" shadow="never">
      <template #header>
        <div class="panel-title split-title">
          <span>
            <ElIcon><Notebook /></ElIcon>
            <span>模板列表</span>
          </span>
          <span v-if="message" class="template-message">{{ message }}</span>
        </div>
      </template>

      <ElScrollbar class="template-list-scroll">
        <ElEmpty v-if="!templates.length" description="暂无模板。" />
        <div v-else class="template-list">
          <div class="template-list-head">
            <span>模板名称</span>
            <span>默认文档路径</span>
            <span>更新时间</span>
            <span>操作</span>
          </div>
          <article v-for="template in templates" :key="template.id" class="template-row">
            <div class="template-name-cell">
              <span class="template-icon">
                <ElIcon><Document /></ElIcon>
              </span>
              <span class="template-copy">
                <strong>{{ template.name }}</strong>
                <small>{{ template.id }}</small>
              </span>
            </div>
            <div class="template-path-cell">{{ template.path }}</div>
            <div class="template-date-cell">{{ formatDate(template.updatedAt) }}</div>
            <div class="template-actions">
              <ElButton size="small" :icon="Edit" @click="openEditDialog(template)">
                编辑
              </ElButton>
              <ElButton size="small" type="danger" plain :icon="Delete" @click="$emit('deleteTemplate', template.id)">
                删除
              </ElButton>
            </div>
          </article>
        </div>
      </ElScrollbar>
    </ElCard>

    <ElDialog
      v-model="dialogOpen"
      class="template-dialog"
      :title="editingExisting ? '编辑知识库模板' : '新建知识库模板'"
      width="720px"
    >
      <ElForm label-position="top" class="control-form template-form">
        <ElFormItem label="模板名称">
          <ElInput v-model="templateForm.name" clearable placeholder="例如 接口说明" />
        </ElFormItem>
        <ElFormItem label="默认文档路径">
          <ElInput v-model="templateForm.path" clearable placeholder="api/user-api.md" />
        </ElFormItem>
      </ElForm>

      <ElTabs class="template-editor-tabs">
        <ElTabPane>
          <template #label>
            <span class="tab-label"><ElIcon><Document /></ElIcon>内容</span>
          </template>
          <ElInput v-model="templateForm.content" class="template-content-input" type="textarea" resize="none" />
        </ElTabPane>
        <ElTabPane>
          <template #label>
            <span class="tab-label"><ElIcon><View /></ElIcon>预览</span>
          </template>
          <ElScrollbar class="template-preview">
            <article v-html="renderedMarkdown"></article>
          </ElScrollbar>
        </ElTabPane>
      </ElTabs>

      <template #footer>
        <div class="dialog-actions">
          <ElButton :disabled="busy" @click="dialogOpen = false">取消</ElButton>
          <ElButton type="primary" :loading="busy" :icon="Select" @click="submitTemplate">
            {{ editingExisting ? '保存模板' : '创建模板' }}
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </ElMain>
</template>

<style scoped>
.template-page {
  gap: 14px;
}

.template-list-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.template-list-panel :deep(.el-card__body) {
  min-height: 0;
  padding: 12px;
}

.split-title {
  justify-content: space-between;
}

.split-title > span {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  min-width: 0;
}

.template-message {
  color: var(--accent);
  font-size: 0.82rem;
  font-weight: 700;
}

.template-list-scroll {
  height: 100%;
  min-height: 0;
}

.template-list {
  display: grid;
  gap: 8px;
  min-width: 780px;
}

.template-list-head,
.template-row {
  align-items: center;
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(180px, 1fr) minmax(260px, 1.5fr) 150px 180px;
}

.template-list-head {
  color: var(--text-faint);
  font-size: 0.76rem;
  font-weight: 760;
  padding: 0 12px;
}

.template-row {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  min-height: 66px;
  padding: 12px;
}

.template-name-cell {
  align-items: center;
  display: flex;
  gap: 9px;
  min-width: 0;
}

.template-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 7px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 32px;
  height: 32px;
  justify-content: center;
  width: 32px;
}

.template-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.template-copy strong,
.template-copy small,
.template-path-cell,
.template-date-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-copy strong {
  color: var(--text);
  font-size: 0.9rem;
}

.template-copy small,
.template-date-cell {
  color: var(--text-faint);
  font-size: 0.76rem;
}

.template-path-cell {
  color: var(--text-soft);
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.84rem;
}

.template-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.template-form {
  grid-template-columns: minmax(160px, 220px) minmax(0, 1fr);
}

.template-editor-tabs {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  height: 430px;
  min-height: 0;
  overflow: hidden;
}

.template-editor-tabs :deep(.el-tabs__content),
.template-editor-tabs :deep(.el-tab-pane) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.template-content-input {
  display: block;
  height: 100%;
  min-height: 0;
}

.template-content-input :deep(.el-textarea__inner) {
  background: #fbfdfe;
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.9rem;
  height: 100%;
  line-height: 1.5;
  min-height: 0 !important;
  overflow: auto;
}

.template-preview {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: #263846;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.template-preview :deep(.el-scrollbar__view) {
  min-height: 100%;
  padding: 18px;
}

@media (max-width: 900px) {
  .template-list {
    min-width: 0;
  }

  .template-list-head {
    display: none;
  }

  .template-row {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .template-path-cell,
  .template-date-cell {
    white-space: normal;
    overflow-wrap: anywhere;
  }

  .template-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 760px) {
  .template-form {
    grid-template-columns: 1fr;
  }

  .template-editor-tabs {
    height: 360px;
  }

  .template-dialog :deep(.el-dialog) {
    width: calc(100% - 24px) !important;
  }
}
</style>
