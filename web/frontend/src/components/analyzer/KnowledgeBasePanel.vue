<script setup lang="ts">
import { Collection, Files, Refresh } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElForm, ElFormItem, ElIcon, ElInput, ElMain, ElTag } from 'element-plus'
import type { AnalyzerForm, KnowledgeFile } from '../../types'
import KnowledgeEditor from './KnowledgeEditor.vue'
import KnowledgeFileList from './KnowledgeFileList.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })
const draftPath = defineModel<string>('draftPath', { required: true })
const content = defineModel<string>('content', { required: true })

defineProps<{
  busy: boolean
  files: KnowledgeFile[]
  message: string
  root: string
  selectedPath: string
}>()

const emit = defineEmits<{
  createFile: [path: string, content: string]
  deleteFile: [path: string]
  refreshFiles: []
  rebuildIndex: []
  saveFile: []
  selectFile: [path: string]
}>()

function forwardCreateFile(path: string, fileContent: string) {
  emit('createFile', path, fileContent)
}
</script>

<template>
  <ElMain class="page-surface kb-page">
    <div class="page-header">
      <div>
        <p class="eyebrow">Knowledge Base</p>
        <h2>知识库维护</h2>
      </div>
      <div class="page-actions">
        <ElTag v-if="root" type="info" effect="plain">{{ root }}</ElTag>
        <ElButton :icon="Refresh" :loading="busy" @click="$emit('refreshFiles')">
          刷新
        </ElButton>
        <ElButton type="primary" :icon="Files" :loading="busy" @click="$emit('rebuildIndex')">
          重建混合索引
        </ElButton>
      </div>
    </div>

    <ElCard class="panel kb-toolbar" shadow="never">
      <ElForm label-position="top" class="control-form horizontal-form">
        <ElFormItem label="知识库根目录">
          <ElInput v-model="form.kbPath" clearable />
        </ElFormItem>
        <ElFormItem label="代码路径">
          <ElInput v-model="form.codePath" clearable />
        </ElFormItem>
        <ElFormItem label="向量库文件">
          <ElInput
            v-model="form.store"
            clearable
            :placeholder="form.projectId ? '默认使用项目独立向量库' : '.vector_store/web-project.jsonl'"
          />
        </ElFormItem>
      </ElForm>
      <div class="kb-message">
        <ElIcon><Collection /></ElIcon>
        <span>{{ message || '维护 docs 下的知识文档，保存后可重建混合索引。' }}</span>
      </div>
    </ElCard>

    <div class="kb-workbench">
      <KnowledgeFileList
        :busy="busy"
        :files="files"
        :selected-path="selectedPath"
        @delete-file="$emit('deleteFile', $event)"
        @refresh="$emit('refreshFiles')"
        @select-file="$emit('selectFile', $event)"
      />
      <KnowledgeEditor
        v-model:path="draftPath"
        v-model:content="content"
        :busy="busy"
        :selected-path="selectedPath"
        @create-file="forwardCreateFile"
        @save-file="emit('saveFile')"
      />
    </div>
  </ElMain>
</template>
