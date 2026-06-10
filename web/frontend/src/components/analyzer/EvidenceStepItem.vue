<script setup lang="ts">
import type { Component } from 'vue'
import { ElButton, ElIcon } from 'element-plus'

defineProps<{
  busy: boolean
  description: string
  icon: Component
  index: string
  metrics: Array<{
    label: string
    value: string | number
  }>
  primaryIcon: Component
  primaryLabel: string
  secondaryIcon: Component
  secondaryLabel: string
  title: string
}>()

defineEmits<{
  primary: []
  secondary: []
}>()
</script>

<template>
  <article class="evidence-step">
    <div class="evidence-step-marker">
      <span>{{ index }}</span>
    </div>

    <div class="evidence-step-main">
      <div class="evidence-step-head">
        <ElIcon><component :is="icon" /></ElIcon>
        <div>
          <h3>{{ title }}</h3>
          <p>{{ description }}</p>
        </div>
      </div>

      <dl class="evidence-step-metrics">
        <div v-for="metric in metrics" :key="metric.label">
          <dt>{{ metric.label }}</dt>
          <dd>{{ metric.value }}</dd>
        </div>
      </dl>
    </div>

    <div class="evidence-step-actions">
      <ElButton :icon="secondaryIcon" @click="$emit('secondary')">{{ secondaryLabel }}</ElButton>
      <ElButton type="primary" :icon="primaryIcon" :loading="busy" @click="$emit('primary')">{{ primaryLabel }}</ElButton>
    </div>
  </article>
</template>

<style scoped>
.evidence-step {
  align-items: stretch;
  display: grid;
  gap: 18px;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  padding: 18px 20px;
  position: relative;
}

.evidence-step + .evidence-step {
  border-top: 1px solid var(--line);
}

.evidence-step-marker {
  align-items: flex-start;
  display: flex;
  justify-content: center;
  position: relative;
}

.evidence-step-marker::before {
  background: linear-gradient(180deg, var(--evidence, #a45c25), rgba(164, 92, 37, 0.2));
  content: "";
  inset: 34px auto -20px 50%;
  position: absolute;
  transform: translateX(-50%);
  width: 1px;
}

.evidence-step:last-child .evidence-step-marker::before {
  display: none;
}

.evidence-step-marker span {
  align-items: center;
  background: var(--archive-navy, #13231f);
  border: 1px solid rgba(216, 161, 95, 0.45);
  border-radius: 6px;
  color: #fff8e7;
  display: inline-flex;
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  font-size: 0.76rem;
  font-weight: 760;
  height: 30px;
  justify-content: center;
  width: 30px;
}

.evidence-step-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.evidence-step-head {
  align-items: flex-start;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.evidence-step-head > .el-icon {
  align-items: center;
  background: var(--trust-soft, #e6f4ee);
  border-radius: 6px;
  color: var(--trust, #1f7a68);
  display: inline-flex;
  flex: 0 0 34px;
  height: 34px;
  justify-content: center;
  margin-top: 1px;
  width: 34px;
}

.evidence-step-head h3 {
  color: var(--text);
  font-size: 0.96rem;
  font-weight: 760;
  margin: 0;
}

.evidence-step-head p {
  color: var(--text-muted);
  font-size: 0.82rem;
  line-height: 1.55;
  margin: 5px 0 0;
}

.evidence-step-metrics {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
}

.evidence-step-metrics div {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid rgba(217, 223, 209, 0.82);
  border-radius: 6px;
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 9px 10px;
}

.evidence-step-metrics dt {
  color: var(--text-faint);
  font-size: 0.72rem;
  font-weight: 700;
}

.evidence-step-metrics dd {
  color: var(--text);
  font-size: 0.84rem;
  font-weight: 720;
  margin: 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-step-actions {
  align-content: center;
  display: grid;
  gap: 8px;
  justify-items: stretch;
  min-width: 120px;
}

.evidence-step-actions :deep(.el-button) {
  justify-content: flex-start;
  margin-left: 0;
}

@media (max-width: 980px) {
  .evidence-step {
    grid-template-columns: 34px minmax(0, 1fr);
  }

  .evidence-step-actions {
    grid-column: 2;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .evidence-step-metrics,
  .evidence-step-actions {
    grid-template-columns: 1fr;
  }
}
</style>
