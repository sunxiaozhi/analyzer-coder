<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { DocumentAdd, Notebook, Select, View } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElSelect,
  ElScrollbar,
  ElTabPane,
  ElTabs
} from 'element-plus'
import type { KnowledgeTemplate } from '../../types'

const path = defineModel<string>('path', { required: true })
const content = defineModel<string>('content', { required: true })

const props = defineProps<{
  busy: boolean
  selectedPath: string
  templates: KnowledgeTemplate[]
}>()

const emit = defineEmits<{
  createFile: [path: string, content: string]
  saveFile: []
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(content.value || ''))

function applyTemplate(templateId: string) {
  const template = props.templates.find((item) => item.id === templateId)
  if (!template) return
  path.value = template.path
  content.value = template.content
}

function createFile() {
  emit('createFile', path.value, content.value)
}
</script>

<template>
  <ElCard class="panel kb-editor-panel" shadow="never">
    <template #header>
      <div class="kb-editor-head">
        <div class="panel-title">
          <ElIcon><Notebook /></ElIcon>
          <span>知识内容</span>
        </div>
        <strong>{{ selectedPath || path || '新建知识文档' }}</strong>
      </div>
    </template>

    <ElForm label-position="top" class="control-form kb-editor-form">
      <ElFormItem label="文档路径">
        <ElInput v-model="path" clearable placeholder="domain/user-registration.md" />
      </ElFormItem>
      <ElFormItem label="知识模版">
        <ElSelect class="control-select" placeholder="选择知识模版" @change="applyTemplate">
          <ElOption
            v-for="template in templates"
            :key="template.id"
            :label="template.name"
            :value="template.id"
          />
        </ElSelect>
      </ElFormItem>
    </ElForm>

    <ElTabs class="kb-editor-tabs">
      <ElTabPane>
        <template #label>
          <span class="tab-label"><ElIcon><DocumentAdd /></ElIcon>编辑</span>
        </template>
        <ElInput v-model="content" class="kb-editor-input" type="textarea" resize="none" />
      </ElTabPane>
      <ElTabPane>
        <template #label>
          <span class="tab-label"><ElIcon><View /></ElIcon>预览</span>
        </template>
        <ElScrollbar class="kb-preview">
          <article v-html="renderedMarkdown"></article>
        </ElScrollbar>
      </ElTabPane>
    </ElTabs>

    <div class="kb-editor-actions">
      <ElButton :disabled="busy" :icon="DocumentAdd" @click="createFile">
        新建
      </ElButton>
      <ElButton type="primary" :loading="busy" :icon="Select" @click="$emit('saveFile')">
        保存
      </ElButton>
    </div>
  </ElCard>
</template>
