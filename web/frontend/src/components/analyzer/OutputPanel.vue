<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { Document, FolderChecked } from '@element-plus/icons-vue'
import {
  ElCard,
  ElEmpty,
  ElIcon,
  ElMain,
  ElScrollbar,
  ElSpace,
  ElTag
} from 'element-plus'
import type { ActiveView, JsonValue, OutputType, QueryResult } from '../../types'
import JsonViewer from './JsonViewer.vue'

const props = defineProps<{
  activeView: ActiveView
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  queryResults: QueryResult[]
  savedPath: string
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
})

const renderedMarkdown = computed(() => markdown.render(props.output || ''))
</script>

<template>
  <ElMain class="result-surface">
    <div class="result-header">
      <div>
        <p class="eyebrow">Output</p>
        <h2>{{ outputTitle }}</h2>
      </div>
      <ElTag v-if="savedPath" class="saved" type="success" effect="plain">
        <ElIcon><FolderChecked /></ElIcon>
        <span>已保存：{{ savedPath }}</span>
      </ElTag>
    </div>

    <ElScrollbar v-if="activeView === 'query'" class="results-list">
      <ElEmpty v-if="!queryResults.length" description="暂无检索结果。" />
      <template v-else>
        <ElCard v-for="item in queryResults" :key="item.id" class="result-item" shadow="never">
          <template #header>
            <div class="result-meta">
              <ElSpace>
                <ElIcon><Document /></ElIcon>
                <strong>{{ item.metadata.source_type }} / {{ item.metadata.kind }}</strong>
              </ElSpace>
              <ElTag type="info" effect="plain">score {{ item.score.toFixed(4) }}</ElTag>
            </div>
          </template>
          <p>{{ item.metadata.file_path }}:{{ item.metadata.start_line }}</p>
          <pre>{{ item.text }}</pre>
        </ElCard>
      </template>
    </ElScrollbar>

    <ElScrollbar v-else-if="outputType === 'markdown'" class="markdown-output">
      <article v-html="renderedMarkdown"></article>
    </ElScrollbar>

    <JsonViewer v-else-if="outputType === 'json' && parsedJson" :value="parsedJson" />

    <ElScrollbar v-else class="output-scroll">
      <pre class="output" :class="{ 'mermaid-output': outputType === 'mermaid' }">{{ output || '等待运行。' }}</pre>
    </ElScrollbar>
  </ElMain>
</template>
