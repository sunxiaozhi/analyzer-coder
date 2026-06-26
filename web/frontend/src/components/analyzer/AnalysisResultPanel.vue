<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { Cpu, Document } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElIcon,
  ElScrollbar,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, OutputType } from '../../types'

defineModel<AnalyzerForm>('form', { required: true })

const props = defineProps<{
  busy: boolean
  output: string
  outputTitle: string
  outputType: OutputType
  savedPath: string
  updatedAt: string
}>()

defineEmits<{
  analyze: []
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(props.output || ''))

const outputBadge = computed(() => {
  if (!props.output) return '等待运行'
  if (props.outputType === 'markdown') return '报告结果'
  return '文本结果'
})

const updatedAtLabel = computed(() => {
  if (!props.updatedAt) return ''
  const date = new Date(props.updatedAt)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(date)
})

const modeLabel = '报告'
</script>

<template>
  <div class="feature-page analysis-page console-page">
    <section class="analysis-command console-command">
      <div class="analysis-heading console-heading">
        <ElIcon><Cpu /></ElIcon>
        <div>
          <h2>代码分析</h2>
          <span>当前项目代码 · 报告</span>
        </div>
      </div>

      <div class="analysis-command-actions console-command-actions">
        <div class="analysis-run-meta" aria-label="分析状态">
          <span>
            <strong>范围</strong>
            当前项目代码
          </span>
          <span>
            <strong>模式</strong>
            {{ modeLabel }}
          </span>
          <span>
            <strong>最新结果</strong>
            {{ updatedAtLabel || '暂无' }}
          </span>
        </div>
        <ElTag type="info" effect="plain">MD</ElTag>
        <ElTag :type="output ? 'success' : 'info'" effect="plain">{{ outputBadge }}</ElTag>
        <ElButton class="analysis-run-button" type="primary" :loading="busy" :icon="Cpu" @click="$emit('analyze')">
          运行分析
        </ElButton>
      </div>
    </section>

    <section class="analysis-workbench console-workbench console-workbench-detail">
      <ElCard class="panel analysis-output-panel console-card" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Document /></ElIcon>
              <span>{{ outputTitle }}</span>
            </span>
            <span class="analysis-output-meta">
              <ElTag type="info" effect="plain">{{ outputType }}</ElTag>
              <ElTag v-if="savedPath" type="success" effect="plain">已保存</ElTag>
              <ElTag v-if="updatedAtLabel" type="info" effect="plain">{{ updatedAtLabel }}</ElTag>
            </span>
          </div>
        </template>

        <ElEmpty v-if="!output" description="运行分析后显示结果。" />

        <ElScrollbar v-else-if="outputType === 'markdown'" class="analysis-markdown">
          <article v-html="renderedMarkdown"></article>
        </ElScrollbar>

        <ElScrollbar v-else class="analysis-output-scroll">
          <pre class="analysis-output">{{ output }}</pre>
        </ElScrollbar>

        <div v-if="savedPath" class="analysis-saved-path">
          <span>保存位置</span>
          <code>{{ savedPath }}</code>
        </div>
      </ElCard>
    </section>
  </div>
</template>

<style scoped>
.analysis-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
}

.analysis-command {
  align-items: center;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbfd 100%);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.analysis-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.analysis-heading > .el-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.analysis-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.analysis-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.analysis-command-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.analysis-command-actions .el-button {
  min-width: 112px;
}

.analysis-run-meta {
  align-items: center;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: flex;
  gap: 12px;
  min-width: 0;
  padding: 7px 10px;
}

.analysis-run-meta span {
  color: var(--text);
  display: inline-flex;
  font-size: 0.72rem;
  gap: 5px;
  min-width: 0;
  white-space: nowrap;
}

.analysis-run-meta strong {
  color: var(--text-faint);
  font-weight: 760;
}

.analysis-workbench {
  min-height: 0;
  overflow: hidden;
  padding: 14px 18px 18px;
}

.analysis-run-button {
  flex: 0 0 auto;
}

.analysis-output-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.analysis-output-panel :deep(.el-card__body) {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 10px;
  min-height: 0;
  padding: 12px;
}

.split-title > span {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  min-width: 0;
}

.analysis-output-meta {
  align-items: center;
  display: inline-flex;
  gap: 8px;
}

.analysis-view-toggle {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: inline-grid;
  grid-template-columns: 1fr 1fr;
  overflow: hidden;
}

.analysis-view-toggle button {
  background: transparent;
  border: 0;
  color: var(--text-faint);
  cursor: pointer;
  font-size: 0.74rem;
  font-weight: 760;
  line-height: 1;
  min-width: 44px;
  padding: 7px 9px;
}

.analysis-view-toggle button.active {
  background: #ffffff;
  color: var(--accent);
  box-shadow: 0 0 0 1px var(--line) inset;
}

.analysis-output-panel :deep(.el-empty) {
  align-self: center;
  justify-self: center;
}

.analysis-markdown,
.analysis-mermaid-preview,
.analysis-output-scroll {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.analysis-mermaid-preview :deep(.el-scrollbar__view) {
  align-items: center;
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
}

.analysis-markdown :deep(.el-scrollbar__view) {
  color: #263846;
  line-height: 1.6;
  min-height: 100%;
  padding: 20px 24px;
}

.analysis-markdown :deep(h1),
.analysis-markdown :deep(h2),
.analysis-markdown :deep(h3) {
  margin: 20px 0 10px;
}

.analysis-markdown :deep(h1:first-child),
.analysis-markdown :deep(h2:first-child),
.analysis-markdown :deep(h3:first-child) {
  margin-top: 0;
}

.analysis-markdown :deep(table) {
  border-collapse: collapse;
  margin: 14px 0;
  width: 100%;
}

.analysis-markdown :deep(th),
.analysis-markdown :deep(td) {
  border: 1px solid var(--line);
  padding: 8px 10px;
  text-align: left;
}

.analysis-markdown :deep(th) {
  background: var(--surface-muted);
}

.analysis-markdown :deep(code) {
  background: #eef3f5;
  border-radius: 4px;
  padding: 2px 5px;
}

.analysis-markdown :deep(pre) {
  background: #f3f7f9;
  border-radius: 6px;
  overflow: auto;
  padding: 12px;
}

.analysis-output {
  color: #263846;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 0.9rem;
  line-height: 1.5;
  margin: 0;
  min-height: 100%;
  padding: 18px;
  white-space: pre-wrap;
}

.analysis-saved-path {
  align-items: center;
  border-top: 1px solid var(--line);
  color: var(--text-faint);
  display: flex;
  gap: 10px;
  min-width: 0;
  padding: 10px 2px 0;
}

.analysis-saved-path span {
  flex: 0 0 auto;
  font-size: 0.74rem;
  font-weight: 760;
}

.analysis-saved-path code {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 6px;
  color: #263846;
  display: block;
  flex: 1;
  font-size: 0.76rem;
  min-width: 0;
  overflow: hidden;
  padding: 5px 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .analysis-run-meta {
    flex: 1 1 100%;
    order: 1;
  }

  .analysis-command-actions .el-tag,
  .analysis-command-actions .analysis-run-button {
    order: 2;
  }
}

@media (max-width: 760px) {
  .analysis-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .analysis-command-actions {
    align-items: stretch;
    display: grid;
  }

  .analysis-run-meta {
    align-items: stretch;
    display: grid;
    gap: 7px;
    order: 0;
  }

  .analysis-run-meta span {
    justify-content: space-between;
    white-space: normal;
  }

  .analysis-command-actions .el-button {
    width: 100%;
  }

  .analysis-workbench {
    grid-template-columns: 1fr;
    padding: 14px 16px;
  }

  .analysis-markdown,
  .analysis-mermaid-preview,
  .analysis-output-scroll {
    min-height: 320px;
  }

  .analysis-saved-path {
    align-items: stretch;
    flex-direction: column;
    gap: 6px;
  }
}
</style>
