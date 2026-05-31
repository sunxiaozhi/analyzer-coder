<script setup lang="ts">
import { Connection, Cpu, Files, FolderOpened, Search } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElMenu, ElMenuItem, ElScrollbar } from 'element-plus'
import type { ConsoleSection, ProjectRecord } from '../../types'

const activeSection = defineModel<ConsoleSection>('activeSection', { required: true })

defineProps<{
  selectedProject: ProjectRecord | null
}>()

defineEmits<{
  checkHealth: []
}>()
</script>

<template>
  <ElScrollbar class="sidebar-scrollbar">
    <aside class="sidebar nav-sidebar">
      <div class="brand-block">
        <p class="eyebrow">Analyzer Console</p>
        <h1>Java 代码知识分析</h1>
      </div>

      <div class="current-project">
        <span>当前项目</span>
        <strong>{{ selectedProject?.name || '当前工作区' }}</strong>
      </div>

      <ElMenu :default-active="activeSection" class="main-menu" @select="activeSection = $event as ConsoleSection">
        <ElMenuItem index="projects">
          <ElIcon><FolderOpened /></ElIcon>
          <span>项目管理</span>
        </ElMenuItem>
        <ElMenuItem index="analysis">
          <ElIcon><Cpu /></ElIcon>
          <span>代码分析结果</span>
        </ElMenuItem>
        <ElMenuItem index="vectors">
          <ElIcon><Files /></ElIcon>
          <span>向量管理</span>
        </ElMenuItem>
        <ElMenuItem index="search">
          <ElIcon><Search /></ElIcon>
          <span>语义检索</span>
        </ElMenuItem>
      </ElMenu>

      <ElButton class="health-button" :icon="Connection" @click="$emit('checkHealth')">
        检查后端
      </ElButton>
    </aside>
  </ElScrollbar>
</template>
