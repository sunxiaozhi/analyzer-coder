<script setup lang="ts">
import { Delete, Document, Refresh } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElEmpty, ElIcon, ElScrollbar, ElSpace, ElTag } from 'element-plus'
import type { KnowledgeFile } from '../../types'

defineProps<{
  busy: boolean
  files: KnowledgeFile[]
  selectedPath: string
}>()

defineEmits<{
  deleteFile: [path: string]
  refresh: []
  selectFile: [path: string]
}>()
</script>

<template>
  <ElCard class="panel kb-list-panel" shadow="never">
    <template #header>
      <div class="panel-title split-title kb-list-header">
        <span>文档列表</span>
        <ElTag size="small" effect="plain">{{ files.length }} 个</ElTag>
        <ElButton text :icon="Refresh" :loading="busy" @click="$emit('refresh')" />
      </div>
    </template>

    <ElScrollbar class="kb-file-scroll">
      <ElEmpty v-if="!files.length" description="暂无知识库文档" />
      <div v-else class="kb-file-list">
        <button
          v-for="file in files"
          :key="file.path"
          class="kb-file-item"
          :class="{ selected: selectedPath === file.path }"
          type="button"
          @click="$emit('selectFile', file.path)"
        >
          <span class="kb-file-main">
            <span class="kb-file-icon">
              <ElIcon><Document /></ElIcon>
            </span>
            <span class="kb-file-copy">
              <strong>{{ file.name || file.path }}</strong>
              <small>{{ file.path }}</small>
            </span>
          </span>
          <ElSpace class="kb-file-actions">
            <ElTag size="small" effect="plain">{{ Math.max(1, Math.ceil(file.size / 1024)) }} KB</ElTag>
            <ElButton
              text
              type="danger"
              :icon="Delete"
              @click.stop="$emit('deleteFile', file.path)"
            />
          </ElSpace>
        </button>
      </div>
    </ElScrollbar>
  </ElCard>
</template>
