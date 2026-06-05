<script setup lang="ts">
import { Collection, Files, Refresh } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElMain, ElTag } from 'element-plus'
import type { KnowledgeFile, KnowledgeTemplate } from '../../types'
import KnowledgeEditor from './KnowledgeEditor.vue'
import KnowledgeFileList from './KnowledgeFileList.vue'

const draftPath = defineModel<string>('draftPath', { required: true })
const content = defineModel<string>('content', { required: true })

defineProps<{
  busy: boolean
  files: KnowledgeFile[]
  root: string
  selectedPath: string
  templates: KnowledgeTemplate[]
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
    <section class="kb-command">
      <div class="kb-heading">
        <ElIcon><Collection /></ElIcon>
        <div>
          <h2>知识维护</h2>
          <span>{{ root || '项目知识库' }} · 文档维护与索引重建</span>
        </div>
      </div>

      <div class="kb-command-actions">
        <ElTag v-if="root" type="info" effect="plain">{{ root }}</ElTag>
        <ElButton :icon="Refresh" :loading="busy" @click="$emit('refreshFiles')">
          刷新
        </ElButton>
        <ElButton type="primary" :icon="Files" :loading="busy" @click="$emit('rebuildIndex')">
          重建混合索引
        </ElButton>
      </div>
    </section>

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
        :templates="templates"
        @create-file="forwardCreateFile"
        @save-file="emit('saveFile')"
      />
    </div>
  </ElMain>
</template>

<style scoped>
.kb-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 0;
}

.kb-command {
  align-items: center;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbfd 100%);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.kb-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.kb-heading > .el-icon {
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

.kb-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.kb-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-command-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.kb-command-actions .el-button {
  min-width: 112px;
}

.kb-workbench {
  padding: 18px 24px 24px;
}

@media (max-width: 760px) {
  .kb-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .kb-command-actions {
    align-items: stretch;
    display: grid;
    justify-content: stretch;
  }

  .kb-command-actions .el-button {
    width: 100%;
  }

  .kb-workbench {
    padding: 14px 16px;
  }
}
</style>
