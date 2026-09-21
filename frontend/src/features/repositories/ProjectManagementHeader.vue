<script setup lang="ts">
import { GitBranch, FolderGit2, Archive } from 'lucide-vue-next';
import type { Repository } from '@/types/api';
import ProjectActionsMenu from './ProjectActionsMenu.vue';
defineProps<{ repository: Repository; readingBranchName: string | null }>();
const emit = defineEmits<{ settings: []; govern: []; remove: [] }>();
const sources = { LOCAL_GIT: '本地 Git', REMOTE_GIT: '远程 Git', GITLAB: 'GitLab', ZIP: 'ZIP 导入' };
</script>
<template>
  <header class="project-header">
    <div class="project-heading"><span class="heading-icon"><Archive v-if="repository.sourceType === 'ZIP'" :size="23" /><FolderGit2 v-else :size="23" /></span><div><span class="source-label">{{ sources[repository.sourceType] }}</span><h2>{{ repository.name }}</h2></div></div>
    <ProjectActionsMenu class="header-menu" :repository="repository" @settings="emit('settings')" @govern="emit('govern')" @remove="emit('remove')" />
    <p v-if="repository.description" class="project-description">{{ repository.description }}</p>
    <p class="project-location" :title="repository.path">{{ repository.path }}</p>
    <div class="project-context">
      <span class="reading-context"><GitBranch :size="14" />当前阅读 <b>{{ readingBranchName || '尚未选择' }}</b></span>
      <span class="default-context">默认分支 <b>{{ repository.branch || 'WORKSPACE' }}</b></span>
    </div>
  </header>
</template>
<style scoped>
.project-header { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px 24px; padding: 28px 30px 24px; border-bottom: 1px solid var(--app-border); background: var(--app-surface); }
.project-heading { display: flex; align-items: center; gap: 14px; min-width: 0; }
.heading-icon { display: grid; place-items: center; width: 46px; height: 46px; flex: none; color: var(--app-color-action); background: var(--app-color-action-soft); border-radius: 10px; }
.source-label { color: var(--app-text-muted); font-size: 12px; }
.project-heading h2 { margin: 2px 0 0; font-family: var(--app-font-display); font-size: 24px; font-weight: 650; line-height: 1.4; overflow-wrap: anywhere; }
.header-menu { align-self: center; }
.project-description, .project-location, .project-context { grid-column: 1 / -1; margin: 0; }
.project-description { padding-top: 5px; color: var(--app-text-regular); font-size: 13px; line-height: 1.8; }
.project-location { color: var(--app-text-muted); font-family: var(--app-font-mono); font-size: 12px; overflow-wrap: anywhere; }
.project-context { display: flex; flex-wrap: wrap; align-items: center; gap: 14px 24px; padding-top: 8px; font-size: 12px; }
.reading-context { display: flex; align-items: center; gap: 7px; padding: 6px 10px; color: var(--app-color-action); background: var(--app-color-action-soft); border-radius: 5px; }
.reading-context b, .default-context b { font-family: var(--app-font-mono); font-weight: 600; overflow-wrap: anywhere; }
.default-context { color: var(--app-text-muted); }
@media (max-width: 760px) { .project-header { padding: 22px 18px; gap: 10px 12px; } .project-heading h2 { font-size: 20px; } .heading-icon { display: none; } }
</style>
