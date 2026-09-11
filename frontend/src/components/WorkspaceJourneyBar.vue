<script setup lang="ts">
import { computed } from 'vue';
import { ChevronRight, CircleAlert } from 'lucide-vue-next';

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
}

const steps = computed<JourneyStep[]>(() => [
  {
    key: 'project',
    label: '选择项目',
    detail: props.hasRepository ? '代码源已接入' : '先接入代码源',
    path: props.canManageProjects ? '/repositories' : '/overview',
    routes: ['repositories'],
    available: true,
  },
  {
    key: 'prepare',
    label: '准备证据',
    detail: props.hasSnapshot ? '已有快照，查看索引状态' : '扫描并建立索引',
    path: '/overview',
    routes: ['overview'],
    available: props.hasRepository,
  },
  {
    key: 'code',
    label: '联合检索',
    detail: '同时查代码与知识',
    path: props.hasSnapshot ? '/search' : '/overview',
    routes: ['search', 'graph'],
    available: props.hasSnapshot,
  },
  {
    key: 'ask',
    label: '问项目',
    detail: '带引用理解项目',
    path: props.hasSnapshot ? '/ask' : '/overview',
    routes: ['ask'],
    available: props.hasSnapshot,
  },
  ...(props.canMaintainKnowledge ? [{
    key: 'knowledge',
    label: '维护知识',
    detail: '补充说明并绑定代码',
    path: props.hasSnapshot ? '/knowledge' : '/overview',
    routes: ['knowledge'],
    available: props.hasSnapshot,
  }] : []),
]);

function stepTitle(step: JourneyStep) {
  if (step.available) return `${step.label}：${step.detail}`;
  return step.key === 'prepare' ? '请先选择或接入项目' : '请先在项目总览完成证据准备';
}
</script>

<template>
  <nav class="journey-bar" aria-label="工作区导航">
    <span class="journey-label">功能导航</span>
    <template v-for="(step, index) in steps" :key="step.key">
      <button
        type="button"
        :class="{
          active: step.routes.includes(activeRoute),
          blocked: !step.available,
        }"
        :title="stepTitle(step)"
        @click="emit('navigate', step.path)"
      >
        <span class="step-mark">
          <CircleAlert v-if="!step.available" :size="11" />
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
