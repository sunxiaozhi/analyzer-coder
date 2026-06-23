<script setup lang="ts">
import { computed } from 'vue'
import { Collection, Connection, FolderOpened, Memo, Search, Setting, Share, UserFilled } from '@element-plus/icons-vue'
import { ElButton, ElIcon, ElMenu, ElMenuItem, ElOption, ElScrollbar, ElSelect, ElSubMenu } from 'element-plus'
import type { AuthUser, ConsoleSection, ProjectRecord } from '../../types'
import BrandLogo from './BrandLogo.vue'

const activeSection = defineModel<ConsoleSection>('activeSection', { required: true })
const projectId = defineModel<string>('projectId', { required: true })

defineProps<{
  currentUser: AuthUser
  projects: ProjectRecord[]
}>()

const emit = defineEmits<{
  checkHealth: []
  refreshSection: [section: ConsoleSection]
}>()

const selectableSections: readonly ConsoleSection[] = ['assets', 'evidence', 'search', 'projects', 'accounts', 'knowledge']

const visibleActiveSection = computed(() => {
  if (['analysis', 'api-map', 'vectors'].includes(activeSection.value)) return 'evidence'
  return activeSection.value
})

function selectSection(value: string) {
  if (!selectableSections.includes(value as ConsoleSection)) return
  const section = value as ConsoleSection
  if (activeSection.value === section) {
    emit('refreshSection', section)
    return
  }
  activeSection.value = section
}
</script>

<template>
  <ElScrollbar class="sidebar-scrollbar">
    <aside class="sidebar nav-sidebar">
      <div class="brand-block">
        <BrandLogo />
        <div class="brand-copy">
          <h1>知识资产</h1>
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

      <ElMenu :default-active="visibleActiveSection" :default-openeds="['settings']" class="main-menu" @select="selectSection">
        <ElMenuItem index="assets">
          <ElIcon><Memo /></ElIcon>
          <span>知识资产</span>
        </ElMenuItem>
        <ElMenuItem index="evidence">
          <ElIcon><Share /></ElIcon>
          <span>依据提取</span>
        </ElMenuItem>
        <ElMenuItem index="search">
          <ElIcon><Search /></ElIcon>
          <span>知识问答</span>
        </ElMenuItem>
        <ElSubMenu index="settings">
          <template #title>
            <ElIcon><Setting /></ElIcon>
            <span>项目设置</span>
          </template>
          <ElMenuItem index="projects">
            <ElIcon><FolderOpened /></ElIcon>
            <span>项目管理</span>
          </ElMenuItem>
          <ElMenuItem v-if="currentUser.isAdmin" index="accounts">
            <ElIcon><UserFilled /></ElIcon>
            <span>账号权限</span>
          </ElMenuItem>
          <ElMenuItem index="knowledge">
            <ElIcon><Collection /></ElIcon>
            <span>文档库</span>
          </ElMenuItem>
        </ElSubMenu>
      </ElMenu>

      <ElButton class="health-button" :icon="Connection" @click="$emit('checkHealth')">
        检查后端
      </ElButton>
    </aside>
  </ElScrollbar>
</template>
