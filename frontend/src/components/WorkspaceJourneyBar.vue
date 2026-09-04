<script setup lang="ts">
import { computed } from 'vue';
import { Check, ChevronRight, CircleAlert } from 'lucide-vue-next';

interface Props {
  activeRoute: string;
  hasRepository: boolean;
  hasSnapshot: boolean;
  canManageProjects: boolean;
  canMaintainKnowledge: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{ navigate: [path: string] }>();

interface JourneyStep {
  key: string;
  label: string;
  detail: string;
  path: string;
  routes: string[];
  available: boolean;
  complete: boolean;
}

const steps = computed<JourneyStep[]>(() => [
  {
    key: 'project',
    label: '选择项目',
    detail: props.hasRepository ? '代码源已接入' : '先接入代码源',
    path: props.canManageProjects ? '/repositories' : '/overview',
    routes: ['repositories'],
    available: true,
    complete: props.hasRepository,
  },
  {
    key: 'prepare',
    label: '准备证据',
    detail: props.hasSnapshot ? '当前快照已发布' : '扫描并建立索引',
    path: '/overview',
    routes: ['overview'],
    available: props.hasRepository,
    complete: props.hasSnapshot,
  },
  {
    key: 'code',
    label: '代码证据',
    detail: '检索、源码与关系',
    path: props.hasSnapshot ? '/search' : '/overview',
    routes: ['search', 'graph'],
    available: props.hasSnapshot,
    complete: false,
  },
  {
    key: 'ask',
    label: '问项目',
    detail: '带引用理解项目',
    path: props.hasSnapshot ? '/ask' : '/overview',
    routes: ['ask'],
    available: props.hasSnapshot,
    complete: false,
  },
  {
    key: 'review',
    label: '变更审查',
    detail: '核对改动与义务',
    path: props.hasSnapshot ? '/change-impact' : '/overview',
    routes: ['change-impact'],
    available: props.hasSnapshot,
    complete: false,
  },
  ...(props.canMaintainKnowledge ? [{
    key: 'knowledge',
    label: '知识回写',
    detail: '沉淀并持续复核',
    path: props.hasSnapshot ? '/knowledge' : '/overview',
    routes: ['knowledge'],
    available: props.hasSnapshot,
    complete: false,
  }] : []),
]);

function stepTitle(step: JourneyStep) {
  if (step.available) return `${step.label}：${step.detail}`;
  return step.key === 'prepare' ? '请先选择或接入项目' : '请先在项目总览完成证据准备';
}
</script>

<template>
  <nav class="journey-bar" aria-label="研发工作流程">
    <span class="journey-label">工作流程</span>
    <template v-for="(step, index) in steps" :key="step.key">
      <button
        type="button"
        :class="{
          active: step.routes.includes(activeRoute),
          complete: step.complete,
          blocked: !step.available,
        }"
        :title="stepTitle(step)"
        @click="emit('navigate', step.path)"
      >
        <span class="step-mark">
          <Check v-if="step.complete" :size="11" />
          <CircleAlert v-else-if="!step.available" :size="11" />
          <span v-else>{{ index + 1 }}</span>
        </span>
        <span class="step-copy"><strong>{{ step.label }}</strong><small>{{ step.detail }}</small></span>
      </button>
      <ChevronRight v-if="index < steps.length - 1" class="journey-arrow" :size="13" />
    </template>
  </nav>
</template>

<style scoped>
.journey-bar {
  display: flex;
  min-width: 0;
  min-height: 38px;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  overflow-x: auto;
  color: var(--app-text-muted);
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: #fff;
  scrollbar-width: none;
}
.journey-bar::-webkit-scrollbar { display: none; }
.journey-label { flex: none; padding: 0 8px 0 3px; color: #788791; font-size: 12px; font-weight: 700; }
.journey-bar button { display: flex; min-width: 0; flex: 1 1 118px; align-items: center; gap: 6px; padding: 3px 7px; color: #52636f; border: 0; border-radius: 4px; background: transparent; text-align: left; }
.journey-bar button:hover, .journey-bar button:focus-visible { color: var(--app-color-action); outline: none; background: var(--app-color-action-soft); }
.journey-bar button.active { color: #fff; background: var(--app-color-identity); }
.journey-bar button.blocked:not(.active) { color: #89959d; }
.step-mark { display: grid; width: 19px; height: 19px; flex: none; place-items: center; color: #6f7e88; border: 1px solid #cbd5db; border-radius: 50%; font-size: 11px; font-weight: 750; }
button.complete .step-mark { color: #fff; border-color: var(--app-color-success); background: var(--app-color-success); }
button.active .step-mark { color: var(--app-color-identity); border-color: #fff; background: #fff; }
button.blocked .step-mark { color: var(--app-color-warning); border-color: #dfc49f; }
.step-copy { display: grid; min-width: 0; line-height: 1.15; }
.step-copy strong { font-size: 12px; white-space: nowrap; }
.step-copy small { overflow: hidden; font-size: 11px; font-weight: 400; text-overflow: ellipsis; white-space: nowrap; }
.journey-arrow { flex: none; color: #a8b3ba; }
@media (max-width: 900px) {
  .journey-label, .step-copy small { display: none; }
  .journey-bar button { flex: 1 0 auto; justify-content: center; }
}
@media (max-width: 760px) {
  .journey-bar { margin-bottom: 10px; }
}
</style>
