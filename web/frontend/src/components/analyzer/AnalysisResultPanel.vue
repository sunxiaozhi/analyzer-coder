<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, shallowRef } from 'vue'
import { Cpu, DataAnalysis, Document, Files, Grid, Operation, Tickets } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElIcon,
  ElScrollbar,
  ElTag
} from 'element-plus'
import type { AnalyzerForm, JsonValue, OutputType } from '../../types'
import JsonViewer from './JsonViewer.vue'
import MermaidDiagram from './MermaidDiagram.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })
const mermaidView = shallowRef<'preview' | 'source'>('preview')

const props = defineProps<{
  busy: boolean
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  savedPath: string
  updatedAt: string
}>()

defineEmits<{
  analyze: []
}>()

const modeOptions = [
  { label: '报告', value: 'report', badge: 'MD', icon: Document },
  { label: '摘要', value: 'summary', badge: 'TXT', icon: Tickets },
  { label: 'JSON', value: 'json', badge: 'JSON', icon: DataAnalysis },
  { label: '切块', value: 'chunks', badge: 'JSON', icon: Grid },
  { label: 'Mermaid 图', value: 'graph', badge: 'MMD', icon: Files }
] as const

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(props.output || ''))

const modeLabel = computed(() => {
  const option = modeOptions.find((item) => item.value === form.value.mode)
  return option?.label ?? '报告'
})

const modeBadge = computed(() => {
  const option = modeOptions.find((item) => item.value === form.value.mode)
  return option?.badge ?? 'MD'
})

const outputBadge = computed(() => {
  if (!props.output) return '等待运行'
  if (props.outputType === 'json') return '结构化结果'
  if (props.outputType === 'markdown') return '报告结果'
  if (props.outputType === 'mermaid') return mermaidView.value === 'preview' ? '图谱预览' : '图谱源码'
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

const canPreviewMermaid = computed(() => props.outputType === 'mermaid' && Boolean(props.output))
</script>

<template>
  <div class="feature-page analysis-page">
    <section class="analysis-command">
      <div class="analysis-heading">
        <ElIcon><Cpu /></ElIcon>
        <div>
          <h2>代码分析</h2>
          <span>当前项目代码 · {{ modeLabel }}</span>
        </div>
      </div>

      <div class="analysis-command-actions">
        <ElTag type="info" effect="plain">{{ modeBadge }}</ElTag>
        <ElTag :type="output ? 'success' : 'info'" effect="plain">{{ outputBadge }}</ElTag>
      </div>
    </section>

    <section class="analysis-workbench">
      <aside class="analysis-config-stack">
        <ElCard class="panel analysis-config-panel" shadow="never">
          <template #header>
            <div class="panel-title">
              <ElIcon><Operation /></ElIcon>
              <span>输出模式</span>
            </div>
          </template>

          <div class="analysis-mode-grid">
            <button
              v-for="option in modeOptions"
              :key="option.value"
              class="analysis-mode-option"
              :class="{ active: form.mode === option.value }"
              type="button"
              @click="form.mode = option.value"
            >
              <ElIcon>
                <component :is="option.icon" />
              </ElIcon>
              <span>{{ option.label }}</span>
              <small>{{ option.badge }}</small>
            </button>
          </div>

          <div class="analysis-run-box">
            <dl>
              <div>
                <dt>范围</dt>
                <dd>当前项目代码</dd>
              </div>
              <div>
                <dt>模式</dt>
                <dd>{{ modeLabel }}</dd>
              </div>
              <div>
                <dt>最新结果</dt>
                <dd>{{ updatedAtLabel || '暂无' }}</dd>
              </div>
            </dl>
            <ElButton class="analysis-run-button" type="primary" :loading="busy" :icon="Cpu" @click="$emit('analyze')">
              运行分析
            </ElButton>
          </div>
        </ElCard>
      </aside>

      <ElCard class="panel analysis-output-panel" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Document /></ElIcon>
              <span>{{ outputTitle }}</span>
            </span>
            <span class="analysis-output-meta">
              <span v-if="canPreviewMermaid" class="analysis-view-toggle">
                <button
                  type="button"
                  :class="{ active: mermaidView === 'preview' }"
                  @click="mermaidView = 'preview'"
                >
                  预览
                </button>
                <button
                  type="button"
                  :class="{ active: mermaidView === 'source' }"
                  @click="mermaidView = 'source'"
                >
                  源码
                </button>
              </span>
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

        <JsonViewer v-else-if="outputType === 'json' && parsedJson" :value="parsedJson" />

        <ElScrollbar v-else-if="outputType === 'mermaid' && mermaidView === 'preview'" class="analysis-mermaid-preview">
          <MermaidDiagram :code="output" />
        </ElScrollbar>

        <ElScrollbar v-else class="analysis-output-scroll">
          <pre class="analysis-output" :class="{ 'mermaid-output': outputType === 'mermaid' }">{{ output }}</pre>
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

.analysis-workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  padding: 18px 24px 24px;
}

.analysis-config-stack {
  display: grid;
  gap: 16px;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
}

.analysis-config-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
}

.analysis-config-panel :deep(.el-card__body) {
  display: grid;
  gap: 16px;
  grid-template-rows: auto auto;
  min-height: 0;
}

.analysis-mode-grid {
  display: grid;
  gap: 10px;
}

.analysis-mode-option {
  align-items: center;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  display: grid;
  gap: 10px;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  min-height: 50px;
  padding: 9px 10px;
  text-align: left;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
  width: 100%;
}

.analysis-mode-option:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.analysis-mode-option.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent) inset, var(--shadow-sm);
}

.analysis-mode-option > .el-icon {
  align-items: center;
  background: var(--surface-muted);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  height: 30px;
  justify-content: center;
  width: 30px;
}

.analysis-mode-option span {
  font-size: 0.88rem;
  font-weight: 760;
  min-width: 0;
}

.analysis-mode-option small {
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--text-faint);
  font-size: 0.66rem;
  font-weight: 760;
  padding: 2px 6px;
}

.analysis-run-box {
  align-self: end;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 14px;
  padding: 14px;
}

.analysis-run-box dl {
  display: grid;
  gap: 10px;
  margin: 0;
}

.analysis-run-box dl > div {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.analysis-run-box dt {
  color: var(--text-faint);
  font-size: 0.74rem;
}

.analysis-run-box dd {
  color: var(--text);
  font-size: 0.82rem;
  font-weight: 760;
  margin: 0;
}

.analysis-run-button {
  width: 100%;
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
  .analysis-workbench {
    grid-template-columns: 1fr;
  }

  .analysis-config-stack {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }

  .analysis-mode-grid {
    grid-template-columns: repeat(5, minmax(92px, 1fr));
  }

  .analysis-mode-option {
    grid-template-columns: 1fr;
    justify-items: center;
    min-height: 88px;
    text-align: center;
  }

  .analysis-run-box {
    align-self: auto;
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

  .analysis-command-actions .el-button {
    width: 100%;
  }

  .analysis-workbench {
    grid-template-columns: 1fr;
    padding: 14px 16px;
  }

  .analysis-config-stack {
    grid-template-columns: 1fr;
  }

  .analysis-mode-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .analysis-mode-option {
    min-height: 82px;
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
