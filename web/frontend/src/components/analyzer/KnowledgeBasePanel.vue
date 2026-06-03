<script setup lang="ts">
import { Files, Refresh } from '@element-plus/icons-vue'
import { ElButton, ElMain, ElTag } from 'element-plus'
import type { KnowledgeFile } from '../../types'
import KnowledgeEditor from './KnowledgeEditor.vue'
import KnowledgeFileList from './KnowledgeFileList.vue'

const draftPath = defineModel<string>('draftPath', { required: true })
const content = defineModel<string>('content', { required: true })

defineProps<{
  busy: boolean
  files: KnowledgeFile[]
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

    <div class="kb-workbench" aria-label="知识库文档维护">
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
