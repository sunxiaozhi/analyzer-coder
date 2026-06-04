<script setup lang="ts">
import { FolderOpened } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElIcon } from 'element-plus'
import type { ProjectRecord } from '../../types'

defineProps<{
  project?: ProjectRecord
  selected: boolean
}>()

defineEmits<{
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
    </div>

    <div class="project-card-actions">
      <ElButton class="project-select-button" size="small" type="primary" plain @click.stop="$emit('select')">
        {{ selected ? '当前' : '选择' }}
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
