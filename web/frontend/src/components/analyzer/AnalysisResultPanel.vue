<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, shallowRef } from 'vue'
import { ArrowDown, ArrowRight, Cpu, Document, Operation } from '@element-plus/icons-vue'
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElScrollbar,
  ElSelect,
  ElTag
} from 'element-plus'
import type { ActiveView, AnalyzerForm, JsonValue, OutputType, QueryEvidence, QueryResult } from '../../types'
import JsonViewer from './JsonViewer.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })
const advancedOpen = shallowRef(false)

const props = defineProps<{
  activeView: ActiveView
  busy: boolean
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  queryEvidence: QueryEvidence | null
  queryResults: QueryResult[]
  savedPath: string
}>()

defineEmits<{
  analyze: []
}>()

const sourceOptions = [
  { label: '代码', value: 'code' },
  { label: '知识库', value: 'kb' },
  { label: '代码 + 知识库', value: 'mixed' }
] as const

const modeOptions = [
  { label: '报告', value: 'report' },
  { label: '摘要', value: 'summary' },
  { label: 'JSON', value: 'json' },
  { label: '切块', value: 'chunks' },
  { label: 'Mermaid 图', value: 'graph' }
] as const

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(props.output || ''))

const sourceLabel = computed(() => {
  const option = sourceOptions.find((item) => item.value === form.value.source)
  return option?.label ?? '代码'
})

const modeLabel = computed(() => {
  const option = modeOptions.find((item) => item.value === form.value.mode)
  return option?.label ?? '报告'
})

const outputBadge = computed(() => {
  if (!props.output) return '等待运行'
  if (props.outputType === 'json') return '结构化结果'
  if (props.outputType === 'markdown') return '报告结果'
  if (props.outputType === 'mermaid') return '图谱源码'
  return '文本结果'
})
</script>

<template>
  <div class="feature-page analysis-page">
    <section class="analysis-command">
      <div class="analysis-heading">
        <ElIcon><Cpu /></ElIcon>
        <div>
          <h2>代码分析</h2>
          <span>{{ sourceLabel }} · {{ modeLabel }}</span>
        </div>
      </div>

      <div class="analysis-command-actions">
        <ElTag :type="output ? 'success' : 'info'" effect="plain">{{ outputBadge }}</ElTag>
        <ElButton type="primary" :loading="busy" :icon="Cpu" @click="$emit('analyze')">
          运行分析
        </ElButton>
      </div>
    </section>

    <section class="analysis-workbench">
      <aside class="analysis-config-stack">
        <ElCard class="panel analysis-config-panel" shadow="never">
          <template #header>
            <div class="panel-title">
              <ElIcon><Operation /></ElIcon>
              <span>分析参数</span>
            </div>
          </template>

          <ElForm label-position="top" class="analysis-form">
            <ElFormItem label="数据来源">
              <ElSelect v-model="form.source" class="control-select">
                <ElOption
                  v-for="option in sourceOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="输出模式">
              <ElSelect v-model="form.mode" class="control-select">
                <ElOption
                  v-for="option in modeOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
            </ElFormItem>

            <div class="analysis-advanced">
              <button class="analysis-advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
                <ElIcon>
                  <ArrowDown v-if="advancedOpen" />
                  <ArrowRight v-else />
                </ElIcon>
                <span>高级路径</span>
              </button>

              <div v-if="advancedOpen" class="analysis-advanced-fields">
                <ElFormItem label="基础路径">
                  <ElInput v-model="form.path" clearable />
                </ElFormItem>
                <ElFormItem label="代码路径">
                  <ElInput v-model="form.codePath" clearable />
                </ElFormItem>
                <ElFormItem label="知识库路径">
                  <ElInput v-model="form.kbPath" clearable />
                </ElFormItem>
              </div>
            </div>
          </ElForm>
        </ElCard>
      </aside>

      <ElCard class="panel analysis-output-panel" shadow="never">
        <template #header>
          <div class="panel-title split-title">
            <span>
              <ElIcon><Document /></ElIcon>
              <span>{{ outputTitle }}</span>
            </span>
            <ElTag type="info" effect="plain">{{ outputType }}</ElTag>
          </div>
        </template>

        <ElEmpty v-if="!output" description="配置参数后运行分析，结果会显示在这里。" />

        <ElScrollbar v-else-if="outputType === 'markdown'" class="analysis-markdown">
          <article v-html="renderedMarkdown"></article>
        </ElScrollbar>

        <JsonViewer v-else-if="outputType === 'json' && parsedJson" :value="parsedJson" />

        <ElScrollbar v-else class="analysis-output-scroll">
          <pre class="analysis-output" :class="{ 'mermaid-output': outputType === 'mermaid' }">{{ output }}</pre>
        </ElScrollbar>
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
  background: var(--surface);
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
  gap: 10px;
  min-width: 0;
}

.analysis-heading > .el-icon {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 8px;
  color: var(--accent);
  display: inline-flex;
  flex: 0 0 36px;
  height: 36px;
  justify-content: center;
  width: 36px;
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
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  padding: 18px 24px 24px;
}

.analysis-config-stack {
  display: grid;
  gap: 16px;
  grid-template-rows: auto;
  min-height: 0;
}

.analysis-form {
  display: grid;
  gap: 12px;
}

.analysis-form .el-form-item {
  margin-bottom: 0;
}

.analysis-advanced {
  border-top: 1px solid var(--line);
  display: grid;
  gap: 10px;
  padding-top: 2px;
}

.analysis-advanced-toggle {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--accent);
  cursor: pointer;
  display: inline-flex;
  font-weight: 760;
  gap: 6px;
  justify-self: start;
  padding: 4px 0;
}

.analysis-advanced-toggle:hover,
.analysis-advanced-toggle:focus {
  color: var(--accent-strong);
}

.analysis-advanced-fields {
  display: grid;
  gap: 12px;
}

.analysis-output-panel.el-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.analysis-output-panel :deep(.el-card__body) {
  display: grid;
  min-height: 0;
  padding: 12px;
}

.split-title > span {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  min-width: 0;
}

.analysis-markdown,
.analysis-output-scroll {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
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

@media (max-width: 1100px) {
  .analysis-workbench {
    grid-template-columns: 1fr;
  }

  .analysis-config-stack {
    grid-template-columns: minmax(0, 1fr) minmax(240px, 300px);
    grid-template-rows: auto;
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

  .analysis-markdown,
  .analysis-output-scroll {
    min-height: 320px;
  }
}
</style>
