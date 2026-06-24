<script setup lang="ts">
import { computed } from 'vue'
import { Collection, Connection, Cpu, FolderOpened, Memo, Search, Setting, Tickets, UserFilled } from '@element-plus/icons-vue'
import { ElIcon, ElMenu, ElMenuItem, ElOption, ElScrollbar, ElSelect, ElSubMenu } from 'element-plus'
import type { AuthUser, ConsoleSection, ProjectRecord } from '../../types'
import BrandLogo from './BrandLogo.vue'

const activeSection = defineModel<ConsoleSection>('activeSection', { required: true })
const projectId = defineModel<string>('projectId', { required: true })

defineProps<{
  currentUser: AuthUser
  projects: ProjectRecord[]
}>()

const emit = defineEmits<{
  refreshSection: [section: ConsoleSection]
}>()

const selectableSections: readonly ConsoleSection[] = ['search', 'assets', 'analysis', 'api-map', 'vectors', 'projects', 'accounts', 'knowledge']

const visibleActiveSection = computed(() => (activeSection.value === 'evidence' ? 'analysis' : activeSection.value))

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

      <ElMenu :default-active="visibleActiveSection" :default-openeds="['settings']" class="main-menu" @select="selectSection">
        <ElMenuItem index="search">
          <ElIcon><Search /></ElIcon>
          <span>知识问答</span>
        </ElMenuItem>
        <ElMenuItem index="assets">
          <ElIcon><Memo /></ElIcon>
          <span>知识资产</span>
        </ElMenuItem>
        <ElMenuItem index="analysis">
          <ElIcon><Cpu /></ElIcon>
          <span>代码依据</span>
        </ElMenuItem>
        <ElMenuItem index="api-map">
          <ElIcon><Connection /></ElIcon>
          <span>接口依据</span>
        </ElMenuItem>
        <ElMenuItem index="vectors">
          <ElIcon><Tickets /></ElIcon>
          <span>索引依据</span>
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
    </aside>
  </ElScrollbar>
</template>
