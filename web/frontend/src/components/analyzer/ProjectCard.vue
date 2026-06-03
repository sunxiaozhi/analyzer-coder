<script setup lang="ts">
import { FolderOpened, Refresh } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElIcon } from 'element-plus'
import type { ProjectRecord } from '../../types'

const props = defineProps<{
  busy?: boolean
  project?: ProjectRecord
  selected: boolean
}>()

defineEmits<{
  pull: []
  select: []
}>()

function updatedLabel() {
  if (!props.project?.updatedAt) return '-'
  const date = new Date(props.project.updatedAt)
  if (Number.isNaN(date.getTime())) return props.project.updatedAt
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}
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
          <FolderOpened />
        </ElIcon>
      </div>
    </div>

    <div class="project-card-main">
      <h3>{{ project?.name }}</h3>
      <p>{{ project?.gitUrl }}</p>
    </div>

    <div class="project-card-meta">
      <span>{{ project?.path }}</span>
      <span v-if="project">更新：{{ updatedLabel() }}</span>
    </div>

    <div class="project-card-actions">
      <ElButton class="project-select-button" size="small" type="primary" plain @click.stop="$emit('select')">
        {{ selected ? '当前' : '选择' }}
      </ElButton>
      <ElButton
        v-if="project"
        class="project-update-button"
        size="small"
        type="primary"
        :disabled="busy"
        :icon="Refresh"
        @click.stop="$emit('pull')"
      >
        <span>更新</span>
      </ElButton>
    </div>
  </ElCard>
</template>

<style scoped>
.project-card.el-card {
  border-color: var(--line);
  min-height: 0;
  transition: background 140ms ease, border-color 140ms ease;
}

.project-card.el-card:hover {
  background: #fbfdff;
  border-color: #b8c9d9;
  box-shadow: none;
  transform: none;
}

.project-card.el-card.selected {
  background: #f8fbff;
  border-color: #93c5fd;
  box-shadow: inset 3px 0 0 var(--accent);
}

.project-card :deep(.el-card__body) {
  align-items: center;
  display: grid;
  gap: 12px;
  grid-template-columns: 34px minmax(0, 1.4fr) minmax(0, 1fr) auto;
  min-height: 72px;
  padding: 10px 12px;
}

.project-card-header {
  align-items: center;
  display: flex;
  justify-content: center;
}

.project-card-main {
  display: grid;
  gap: 5px;
}

.project-card-main h3 {
  font-size: 0.92rem;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-card-main p {
  display: block;
  margin: 0;
  min-height: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-card-meta {
  border-top: 0;
  gap: 4px;
  min-width: 0;
  padding-top: 0;
}

.project-card-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-card-actions {
  display: flex;
  gap: 8px;
  grid-template-columns: none;
}

.project-card-actions .el-button {
  margin-left: 0;
  min-width: 58px;
  width: auto;
}

.project-update-button :deep(span) {
  display: inline-flex;
  min-width: max-content;
}

.project-update-button.el-button {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

.project-update-button.el-button:hover,
.project-update-button.el-button:focus {
  background: var(--accent-strong);
  border-color: var(--accent-strong);
  color: #ffffff;
}

.project-update-button.el-button.is-disabled,
.project-update-button.el-button.is-disabled:hover {
  background: #d8e0e8;
  border-color: #d8e0e8;
  color: #64748b;
}

.project-update-button :deep(.el-icon) {
  color: currentColor;
}

@media (max-width: 760px) {
  .project-card :deep(.el-card__body) {
    grid-template-columns: 34px minmax(0, 1fr);
  }

  .project-card-meta,
  .project-card-actions {
    grid-column: 2;
  }

  .project-card-actions {
    justify-content: flex-start;
  }
}
</style>
