<script setup lang="ts">
import { computed, onActivated, onDeactivated, onScopeDispose, shallowRef, watch } from 'vue';
import { taskDuration, type DurationMode, type TaskTiming } from './taskDuration';

const props = withDefaults(defineProps<{ task: TaskTiming; mode?: DurationMode }>(), { mode: 'execution' });
const now = shallowRef(Date.now());
const active = shallowRef(true);
const duration = computed(() => taskDuration(props.task, props.mode, now.value));
let timer: ReturnType<typeof setInterval> | undefined;
function stop() { clearInterval(timer); timer = undefined; }
watch([() => duration.value.live, active], ([live, visible]) => {
  stop();
  if (live && visible) {
    now.value = Date.now();
    timer = setInterval(() => { now.value = Date.now(); }, 1000);
  }
}, { immediate: true });
watch(() => [props.task, props.mode], () => { now.value = Date.now(); });
onActivated(() => { now.value = Date.now(); active.value = true; });
onDeactivated(() => { active.value = false; stop(); });
onScopeDispose(stop);
</script>

<template>
  <span class="task-duration" :title="duration.title">{{ duration.text }}</span>
</template>

<style scoped>
.task-duration { white-space: nowrap; font-variant-numeric: tabular-nums; }
</style>
