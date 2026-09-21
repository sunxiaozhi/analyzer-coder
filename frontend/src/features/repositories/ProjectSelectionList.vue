<script setup lang="ts">
import { FolderGit2, Archive, GitBranch } from 'lucide-vue-next';
import type { Repository } from '@/types/api';
import ProjectActionsMenu from './ProjectActionsMenu.vue';
withDefaults(defineProps<{ rows: Repository[]; selectedId: string | null; loading: boolean; disabled: boolean; readingBranchName?: string | null }>(), { readingBranchName: null });
const emit = defineEmits<{ select: [repository: Repository]; settings: [repository: Repository]; govern: [repository: Repository]; remove: [repository: Repository] }>();
const sources = { LOCAL_GIT: '本地 Git', REMOTE_GIT: '远程 Git', GITLAB: 'GitLab', ZIP: 'ZIP 导入' };
</script>
<template>
  <nav class="project-selection" aria-label="项目列表" :aria-busy="loading">
    <p v-if="loading" class="project-note" role="status">正在读取项目…</p>
    <p v-else-if="!rows.length" class="project-note">没有匹配项目，请调整搜索条件或导入项目。</p>
    <div v-for="project in rows" :key="project.id" class="project-item" :class="{ 'project-item-active': project.id === selectedId }">
      <button type="button" class="project-choice" :aria-pressed="project.id === selectedId" :disabled="disabled || loading" @click="emit('select', project)">
        <span class="project-icon"><Archive v-if="project.sourceType === 'ZIP'" :size="18" /><FolderGit2 v-else :size="18" /></span>
        <span class="project-copy">
          <strong class="project-name">{{ project.name }}</strong>
          <span class="project-source">{{ sources[project.sourceType] }}</span>
          <span class="project-branch"><GitBranch :size="12" />{{ project.id === selectedId ? '当前阅读' : '默认分支' }} · {{ project.id === selectedId ? readingBranchName || '尚未选择' : project.branch || 'WORKSPACE' }}</span>
        </span>
      </button>
      <ProjectActionsMenu class="project-item-menu" :repository="project" compact :disabled="disabled || loading" @settings="emit('settings', project)" @govern="emit('govern', project)" @remove="emit('remove', project)" />
    </div>
  </nav>
</template>
<style scoped>
.project-selection { display: grid; align-content: start; gap: 8px; padding: 12px; min-height: 0; }
.project-item { position: relative; display: flex; align-items: flex-start; border: 1px solid transparent; border-radius: 9px; background: var(--app-surface); transition: background .15s; }
.project-item:hover { background: var(--app-surface-subtle); }
.project-item-active { border-color: #c2d9ef; background: var(--app-color-action-soft); box-shadow: inset 3px 0 var(--app-color-action); }
.project-item-active:hover { background: var(--app-color-action-soft); }
.project-choice { display: flex; align-items: flex-start; gap: 12px; flex: 1; min-width: 0; padding: 18px 42px 18px 15px; text-align: left; border: 0; border-radius: 8px; background: transparent; color: var(--app-text-primary); font: inherit; }
.project-icon { display: grid; place-items: center; flex: none; width: 33px; height: 33px; border: 1px solid var(--app-border); border-radius: 7px; background: var(--app-surface); color: var(--app-text-muted); }
.project-item-active .project-icon { color: var(--app-color-action); border-color: #c2d9ef; }
.project-copy { display: grid; min-width: 0; gap: 7px; }
.project-name { font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
.project-source { color: var(--app-text-muted); font-size: 12px; }
.project-branch { display: flex; align-items: baseline; gap: 5px; color: var(--app-text-muted); font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.project-branch svg { flex: none; align-self: center; }
.project-item-menu { position: absolute; top: 13px; right: 8px; }
.project-choice:disabled { cursor: wait; opacity: .65; }
.project-note { margin: 12px 8px; color: var(--app-text-muted); font-size: 13px; line-height: 1.8; }
@media (prefers-reduced-motion: reduce) { .project-item { transition: none; } }
</style>
