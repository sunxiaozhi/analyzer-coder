<script setup lang="ts">
import { Check, FolderOpened, Refresh, House } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElIcon, ElTag } from 'element-plus'
import type { ProjectRecord } from '../../types'

defineProps<{
  busy?: boolean
  project?: ProjectRecord
  selected: boolean
  workspace?: boolean
}>()

defineEmits<{
  pull: []
  select: []
}>()
</script>

<template>
  <ElCard
    class="project-card"
    :class="{ selected }"
    shadow="never"
    @click="$emit('select')"
  >
    <div class="project-card-header">
      <div class="project-card-icon">
        <ElIcon>
          <House v-if="workspace" />
          <FolderOpened v-else />
        </ElIcon>
      </div>
      <ElTag v-if="selected" type="success" effect="plain">
        <ElIcon><Check /></ElIcon>
        <span>当前</span>
      </ElTag>
      <ElTag v-else effect="plain">可选</ElTag>
    </div>

    <div class="project-card-main">
      <h3>{{ workspace ? '当前工作区' : project?.name }}</h3>
      <p>{{ workspace ? '使用 analyzer-coder 当前目录中的源码。' : project?.gitUrl }}</p>
    </div>

    <div class="project-card-meta">
      <span>{{ workspace ? 'workspace' : project?.id }}</span>
      <span>{{ workspace ? '本地目录' : project?.path }}</span>
      <span v-if="project">更新：{{ project.updatedAt }}</span>
    </div>

    <div class="project-card-actions">
      <ElButton size="small" type="primary" plain @click.stop="$emit('select')">
        选择
      </ElButton>
      <ElButton
        v-if="project"
        size="small"
        :disabled="busy"
        :icon="Refresh"
        @click.stop="$emit('pull')"
      >
        更新
      </ElButton>
    </div>
  </ElCard>
</template>
