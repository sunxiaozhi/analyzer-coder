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
    <div class="evidence-step-main">
      <div class="evidence-step-head">
        <span class="evidence-step-index">{{ index }}</span>
        <ElIcon class="evidence-step-icon"><component :is="icon" /></ElIcon>
        <div class="evidence-step-copy">
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

      <div class="evidence-step-actions">
        <ElButton :icon="secondaryIcon" @click="$emit('secondary')">{{ secondaryLabel }}</ElButton>
        <ElButton type="primary" :icon="primaryIcon" :loading="busy" @click="$emit('primary')">{{ primaryLabel }}</ElButton>
      </div>
    </div>
  </article>
</template>

<style scoped>
.evidence-step {
  display: block;
  padding: 16px 18px;
}

.evidence-step + .evidence-step {
  border-top: 1px solid var(--line);
}

.evidence-step-main {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.evidence-step-head {
  align-items: center;
  display: grid;
  gap: 10px;
  grid-template-columns: 32px 34px minmax(0, 1fr);
  min-width: 0;
}

.evidence-step-index {
  align-items: center;
  background: var(--archive-navy, #071833);
  border: 1px solid rgba(147, 197, 253, 0.45);
  border-radius: 6px;
  color: #dbeafe;
  display: inline-flex;
  font-family: var(--mono-font, "Cascadia Mono", Consolas, monospace);
  font-size: 0.72rem;
  font-weight: 760;
  height: 30px;
  justify-content: center;
  width: 30px;
}

.evidence-step-icon {
  align-items: center;
  background: var(--trust-soft, #eaf2ff);
  border-radius: 6px;
  color: var(--trust, #2563eb);
  display: inline-flex;
  height: 34px;
  justify-content: center;
  width: 34px;
}

.evidence-step-copy {
  min-width: 0;
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
  line-height: 1.45;
  margin: 5px 0 0;
}

.evidence-step-metrics {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
}

.evidence-step-metrics div {
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(214, 228, 245, 0.9);
  border-radius: 6px;
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
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
  display: flex;
  gap: 7px;
  justify-content: flex-end;
  min-width: 0;
}

.evidence-step-actions :deep(.el-button) {
  justify-content: center;
  margin-left: 0;
  min-width: 104px;
}

@media (max-width: 980px) {
  .evidence-step-actions {
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .evidence-step-head {
    align-items: flex-start;
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .evidence-step-icon {
    display: none;
  }

  .evidence-step-copy {
    min-width: 0;
  }

  .evidence-step-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .evidence-step-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
