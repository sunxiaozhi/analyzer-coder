<script setup lang="ts">
import type { Repository } from '@/types/api';
defineProps<{ rows: Repository[]; selectedId: string | null; loading: boolean; disabled: boolean }>();
const emit = defineEmits<{ select: [repository: Repository] }>();
</script>

<template>
  <nav class="project-selection" aria-label="项目列表" :aria-busy="loading">
    <p v-if="loading" class="project-note" role="status">正在读取项目…</p>
    <p v-else-if="!rows.length" class="project-note">没有匹配项目，请调整搜索条件或接入项目。</p>
    <button v-for="project in rows" :key="project.id" type="button" class="project-choice"
      :class="{ 'project-choice-active': project.id === selectedId }"
      :aria-pressed="project.id === selectedId" :disabled="disabled || loading" @click="emit('select', project)">
      <strong class="project-name">{{ project.name }}</strong>
      <span class="project-source">{{ project.sourceType }}</span>
      <span v-if="project.description" class="project-description">{{ project.description }}</span>
    </button>
  </nav>
</template>

<style scoped>
.project-selection { display: flex; flex-direction: column; gap: 6px; padding: 10px; min-height: 0; overflow: auto; }
.project-choice { display: grid; gap: 5px; text-align: left; padding: 12px; border: 1px solid #dbe3ec; border-left: 3px solid transparent; border-radius: 5px; background: #fff; color: #334155; cursor: pointer; font: inherit; }
.project-choice:hover { background: #f5f7fa; }
.project-choice-active { border-left-color: #2563eb; background: #eff6ff; }
.project-choice:focus-visible { outline: 2px solid #2563eb; outline-offset: 1px; }
.project-choice:disabled { cursor: wait; opacity: .65; }
.project-name { font-size: 14px; overflow-wrap: anywhere; }
.project-source, .project-description, .project-note { font-size: 12px; color: #68778a; line-height: 1.6; overflow-wrap: anywhere; }
.project-note { margin: 8px; }
</style>
