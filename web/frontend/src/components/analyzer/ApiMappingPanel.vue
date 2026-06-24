<script setup lang="ts">
import { computed } from 'vue'
import { Connection, Link, Refresh, Warning } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElScrollbar,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, ApiMapping, ApiMappingResult } from '../../types'

const form = defineModel<AnalyzerForm>('form', { required: true })

const props = defineProps<{
  busy: boolean
  message: string
  result: ApiMappingResult | null
  savedPath: string
}>()

defineEmits<{
  buildMapping: []
}>()

const summary = computed(() => props.result?.summary)

const matchedCount = computed(() => summary.value?.matched ?? 0)
const mismatchCount = computed(() => summary.value?.methodMismatches ?? 0)
const unmatchedCount = computed(() => summary.value?.unmatched ?? 0)

function statusLabel(status: ApiMapping['status']) {
  if (status === 'matched') return '已匹配'
  if (status === 'method_mismatch') return '方法不一致'
  return '未匹配'
}

function statusType(status: ApiMapping['status']) {
  if (status === 'matched') return 'success'
  if (status === 'method_mismatch') return 'warning'
  return 'danger'
}

function backendLabel(item: ApiMapping) {
  if (!item.backend) return '未找到后端路由'
  return `${item.backend.methods.join('|')} ${item.backend.path}`
}

function backendLocation(item: ApiMapping) {
  if (!item.backend) return '-'
  return `${item.backend.file_path}:${item.backend.line} · ${item.backend.controller}.${item.backend.handler}`
}
</script>

<template>
  <div class="feature-page api-map-page console-page">
    <section class="api-map-command console-command">
      <div class="api-map-heading console-heading">
        <ElIcon><Link /></ElIcon>
        <div>
          <h2>接口映射</h2>
          <span>前端 API 调用 · 后端 Spring 路由</span>
        </div>
      </div>

      <div class="api-map-actions console-command-actions">
        <ElTag v-if="message" :type="result ? 'success' : 'danger'" effect="plain">{{ message }}</ElTag>
        <ElTag type="success" effect="plain">匹配 {{ matchedCount }}</ElTag>
        <ElTag :type="mismatchCount ? 'warning' : 'info'" effect="plain">方法不一致 {{ mismatchCount }}</ElTag>
        <ElTag :type="unmatchedCount ? 'danger' : 'info'" effect="plain">未匹配 {{ unmatchedCount }}</ElTag>
        <ElButton type="primary" :loading="busy" :icon="Refresh" @click="$emit('buildMapping')">
          生成映射
        </ElButton>
      </div>
    </section>

    <section class="api-map-config console-strip">
      <ElForm label-position="top" class="api-map-form">
        <ElFormItem label="前端源码路径">
          <ElInput v-model="form.frontendPath" clearable />
        </ElFormItem>
        <ElFormItem label="后端源码路径">
          <ElInput v-model="form.backendPath" clearable />
        </ElFormItem>
      </ElForm>
    </section>

    <section class="api-map-workbench console-workbench console-workbench-detail">
      <ElCard class="panel api-map-summary-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title">
            <ElIcon><Connection /></ElIcon>
            <span>扫描结果</span>
          </div>
        </template>

        <dl class="api-map-summary">
          <div>
            <dt>前端调用</dt>
            <dd>{{ summary?.frontendCalls ?? 0 }}</dd>
          </div>
          <div>
            <dt>后端路由</dt>
            <dd>{{ summary?.backendEndpoints ?? 0 }}</dd>
          </div>
          <div>
            <dt>结果文件</dt>
            <dd>{{ savedPath || '运行后生成' }}</dd>
          </div>
        </dl>
      </ElCard>

      <ElCard class="panel api-map-list-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Warning /></ElIcon>
              <span>映射明细</span>
            </span>
            <ElTag type="info" effect="plain">{{ result?.mappings.length ?? 0 }}</ElTag>
          </div>
        </template>

        <ElScrollbar class="api-map-scroll">
          <ElEmpty v-if="!result" description="点击生成映射后显示结果。" />
          <ElEmpty v-else-if="!result.mappings.length" description="没有发现前端 API 调用。" />
          <div v-else class="api-map-list">
            <article v-for="item in result.mappings" :key="`${item.frontend.file_path}:${item.frontend.line}:${item.frontend.path}`" class="api-map-item">
              <div class="api-map-item-head">
                <strong>{{ item.frontend.method }} {{ item.frontend.path }}</strong>
                <ElTag :type="statusType(item.status)" effect="plain">{{ statusLabel(item.status) }}</ElTag>
              </div>
              <div class="api-map-columns">
                <div>
                  <span>前端</span>
                  <code>{{ item.frontend.file_path }}:{{ item.frontend.line }}</code>
                  <small>{{ item.frontend.callee }}</small>
                </div>
                <div>
                  <span>后端</span>
                  <code>{{ backendLabel(item) }}</code>
                  <small>{{ backendLocation(item) }}</small>
                </div>
              </div>
              <p>{{ item.reason }} · {{ item.match_strategy }}</p>
            </article>
          </div>
        </ElScrollbar>
      </ElCard>
    </section>
  </div>
</template>

<style scoped>
.api-map-page {
  gap: 0;
  grid-template-rows: auto auto minmax(0, 1fr);
}

.api-map-command,
.api-map-config {
  background: #ffffff;
  border-bottom: 1px solid var(--line);
  padding: 16px 24px;
}

.api-map-command {
  align-items: center;
  display: flex;
  gap: 16px;
  justify-content: space-between;
}

.api-map-heading,
.api-map-actions,
.api-map-item-head,
.panel-title > span {
  align-items: center;
  display: flex;
  gap: 10px;
}

.api-map-heading > .el-icon {
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  height: 40px;
  padding: 10px;
  width: 40px;
}

.api-map-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.api-map-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.api-map-form {
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.api-map-workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  padding: 18px 24px 24px;
}

.api-map-summary-panel.el-card,
.api-map-list-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
}

.api-map-summary-panel :deep(.el-card__body),
.api-map-list-panel :deep(.el-card__body) {
  min-height: 0;
}

.api-map-summary {
  display: grid;
  gap: 12px;
  margin: 0;
}

.api-map-summary div {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
}

.api-map-summary dt {
  color: var(--text-faint);
  font-size: 0.74rem;
}

.api-map-summary dd {
  color: var(--text);
  font-weight: 780;
  margin: 5px 0 0;
  overflow-wrap: anywhere;
}

.api-map-scroll {
  height: 100%;
}

.api-map-list {
  display: grid;
  gap: 12px;
  padding: 12px;
}

.api-map-item {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 12px;
  padding: 14px;
}

.api-map-item-head {
  justify-content: space-between;
}

.api-map-item-head strong {
  color: var(--text);
  overflow-wrap: anywhere;
}

.api-map-columns {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr 1fr;
}

.api-map-columns > div {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px;
}

.api-map-columns span {
  color: var(--text-faint);
  font-size: 0.72rem;
  font-weight: 760;
}

.api-map-columns code,
.api-map-columns small {
  color: var(--text);
  overflow-wrap: anywhere;
}

.api-map-columns small {
  color: var(--text-faint);
}

.api-map-item p {
  color: var(--text-faint);
  font-size: 0.78rem;
  margin: 0;
}

@media (max-width: 980px) {
  .api-map-command,
  .api-map-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .api-map-form,
  .api-map-workbench,
  .api-map-columns {
    grid-template-columns: 1fr;
  }
}
</style>
