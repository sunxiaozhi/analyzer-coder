<script setup lang="ts">
import { Collection, Connection, Cpu, Files, FolderOpened, Search, SwitchButton, UserFilled } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElMenu, ElMenuItem, ElScrollbar } from 'element-plus'
import type { AuthUser, ConsoleSection, ProjectRecord } from '../../types'
import BrandLogo from './BrandLogo.vue'

const activeSection = defineModel<ConsoleSection>('activeSection', { required: true })

defineProps<{
  currentUser: AuthUser
  selectedProject: ProjectRecord | null
}>()

defineEmits<{
  checkHealth: []
  logout: []
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

      <div v-if="selectedProject" class="selected-project-chip">
        <span>当前项目</span>
        <strong>{{ selectedProject.name }}</strong>
      </div>

      <div class="sidebar-user-card">
        <div>
          <span>当前账号</span>
          <strong>{{ currentUser.displayName }}</strong>
        </div>
        <b class="sidebar-user-role" :class="{ admin: currentUser.isAdmin }">
          {{ currentUser.isAdmin ? '管理员' : '项目成员' }}
        </b>
      </div>

      <ElMenu :default-active="activeSection" class="main-menu" @select="activeSection = $event as ConsoleSection">
        <ElMenuItem index="projects">
          <ElIcon><FolderOpened /></ElIcon>
          <span>项目</span>
        </ElMenuItem>
        <ElMenuItem v-if="currentUser.isAdmin" index="accounts">
          <ElIcon><UserFilled /></ElIcon>
          <span>账号</span>
        </ElMenuItem>
        <ElMenuItem index="analysis">
          <ElIcon><Cpu /></ElIcon>
          <span>分析</span>
        </ElMenuItem>
        <ElMenuItem index="knowledge">
          <ElIcon><Collection /></ElIcon>
          <span>知识库</span>
        </ElMenuItem>
        <ElMenuItem index="vectors">
          <ElIcon><Files /></ElIcon>
          <span>索引</span>
        </ElMenuItem>
        <ElMenuItem index="search">
          <ElIcon><Search /></ElIcon>
          <span>检索</span>
        </ElMenuItem>
      </ElMenu>

      <ElButton class="health-button" :icon="Connection" @click="$emit('checkHealth')">
        检查后端
      </ElButton>
      <ElButton class="logout-button" :icon="SwitchButton" @click="$emit('logout')">
        退出登录
      </ElButton>
    </aside>
  </ElScrollbar>
</template>
