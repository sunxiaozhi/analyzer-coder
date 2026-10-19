<script setup lang="ts">
import { computed } from 'vue';
import { taskProgress, type ProgressTask, type StepState } from './taskProgress';

const props = defineProps<{ task: ProgressTask }>();
const progress = computed(() => taskProgress(props.task));
const states: Record<StepState, string> = {
  waiting: '等待执行', running: '执行中', completed: '已完成', failed: '失败',
  canceled: '已取消', blocked: '未执行', unknown: '未记录',
};
const icons: Partial<Record<StepState, string>> = { completed: '✓', failed: '!', canceled: '−', blocked: '−', unknown: '?' };
</script>

<template>
  <section class="task-progress" aria-label="执行进度" aria-live="polite">
    <div class="progress-heading"><strong>执行进度</strong><span>{{ progress.current }}</span></div>
    <ol class="progress-steps">
      <li v-for="(step, index) in progress.steps" :key="step.label" class="progress-step" :data-state="step.state" :aria-current="step.state === 'running' ? 'step' : undefined">
        <span class="step-marker" aria-hidden="true">{{ icons[step.state] ?? index + 1 }}</span>
        <div class="step-copy"><span class="step-name">{{ step.label }}</span><small class="step-state">{{ states[step.state] }}</small></div>
      </li>
    </ol>
    <p v-if="progress.note" class="progress-note">{{ progress.note }}</p>
  </section>
</template>

<style scoped>
.task-progress { width: 100%; min-width: 0; flex-basis: 100%; margin: 0 0 20px; }
.progress-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 14px; margin-bottom: 12px; font-size: 13px; }
.progress-heading strong { font-weight: 600; color: var(--app-text); }
.progress-heading > span, .progress-note { color: var(--app-text-muted); overflow-wrap: anywhere; }
.progress-steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; margin: 0; padding: 0; list-style: none; }
.progress-step { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 12px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-surface-subtle); }
.step-marker { display: grid; place-items: center; flex: 0 0 26px; height: 26px; border: 1px solid currentColor; border-radius: 50%; color: var(--app-text-muted); font-size: 12px; }
.step-copy { display: grid; min-width: 0; gap: 5px; }
.step-name { color: var(--app-text); font-size: 13px; overflow-wrap: anywhere; }
.step-state { color: var(--app-text-muted); font-size: 12px; }
.progress-step[data-state='running'] { border-color: var(--app-color-action); }
.progress-step[data-state='running'] .step-marker, .progress-step[data-state='running'] .step-state { color: var(--app-color-action); }
.progress-step[data-state='completed'] .step-marker, .progress-step[data-state='completed'] .step-state { color: #275f4a; }
.progress-step[data-state='failed'] .step-marker, .progress-step[data-state='failed'] .step-state { color: var(--app-color-danger, #b44242); }
.progress-note { margin: 10px 0 0; font-size: 12px; line-height: 1.6; }
</style>
