<script setup lang="ts">
import { Collection, Connection, Cpu, Files, FolderOpened, Search, UserFilled } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElMenu, ElMenuItem, ElOption, ElScrollbar, ElSelect } from 'element-plus'
import type { AuthUser, ConsoleSection, ProjectRecord } from '../../types'
import BrandLogo from './BrandLogo.vue'

const activeSection = defineModel<ConsoleSection>('activeSection', { required: true })
const projectId = defineModel<string>('projectId', { required: true })

defineProps<{
  currentUser: AuthUser
  projects: ProjectRecord[]
}>()

defineEmits<{
  checkHealth: []
}>()
</script>

<template>
  <ElScrollbar class="sidebar-scrollbar">
    <aside class="sidebar nav-sidebar">
      <div class="brand-block">
        <BrandLogo />
        <div class="brand-copy">
          <p class="eyebrow">Analyzer Console</p>
          <h1>代码智库</h1>
        </div>
      </div>

      <div class="sidebar-project-select">
        <span>当前项目</span>
        <ElSelect
          v-model="projectId"
          class="sidebar-project-control"
          :disabled="projects.length === 0"
          filterable
          placeholder="选择项目"
        >
          <ElOption v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
        </ElSelect>
      </div>

      <ElMenu :default-active="activeSection" class="main-menu" @select="activeSection = $event as ConsoleSection">
        <ElMenuItem index="search">
          <ElIcon><Search /></ElIcon>
          <span>知识检索</span>
        </ElMenuItem>
        <ElMenuItem index="knowledge">
          <ElIcon><Collection /></ElIcon>
          <span>知识库维护</span>
        </ElMenuItem>
        <ElMenuItem index="projects">
          <ElIcon><FolderOpened /></ElIcon>
          <span>项目管理</span>
        </ElMenuItem>
        <ElMenuItem v-if="currentUser.isAdmin" index="accounts">
          <ElIcon><UserFilled /></ElIcon>
          <span>账号管理</span>
        </ElMenuItem>
        <ElMenuItem index="analysis">
          <ElIcon><Cpu /></ElIcon>
          <span>分析</span>
        </ElMenuItem>
        <ElMenuItem index="vectors">
          <ElIcon><Files /></ElIcon>
          <span>索引</span>
        </ElMenuItem>
      </ElMenu>

      <ElButton class="health-button" :icon="Connection" @click="$emit('checkHealth')">
        检查后端
      </ElButton>
    </aside>
  </ElScrollbar>
</template>
