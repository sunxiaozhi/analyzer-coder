<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { Document, FolderChecked } from '@element-plus/icons-vue'
import {
  ElCard,
  ElDivider,
  ElEmpty,
  ElIcon,
  ElMain,
  ElScrollbar,
  ElSpace,
  ElTag
} from 'element-plus'
import type { ActiveView, JsonValue, OutputType, QueryEvidence, QueryResult } from '../../types'
import JsonViewer from './JsonViewer.vue'

const props = defineProps<{
  activeView: ActiveView
  output: string
  outputTitle: string
  outputType: OutputType
  parsedJson: JsonValue | null
  queryEvidence: QueryEvidence | null
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
        <section class="evidence-section">
          <div class="section-title">
            <span>直接命中</span>
            <ElTag type="info" effect="plain">{{ queryResults.length }}</ElTag>
          </div>
        </section>

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

        <template v-if="queryEvidence">
          <ElDivider />

          <section v-if="queryEvidence.relatedCode.length" class="evidence-section">
            <div class="section-title">
              <span>关联代码</span>
              <ElTag type="success" effect="plain">{{ queryEvidence.relatedCode.length }}</ElTag>
            </div>
            <ElCard
              v-for="item in queryEvidence.relatedCode"
              :key="item.id"
              class="result-item related-item"
              shadow="never"
            >
              <template #header>
                <div class="result-meta">
                  <ElSpace>
                    <ElIcon><Document /></ElIcon>
                    <strong>{{ item.metadata.kind }} / {{ item.metadata.symbol_name }}</strong>
                  </ElSpace>
                  <ElTag type="success" effect="plain">关联 {{ item.score }}</ElTag>
                </div>
              </template>
              <p>{{ item.metadata.file_path }}:{{ item.metadata.start_line }}</p>
              <pre>{{ item.text }}</pre>
            </ElCard>
          </section>

          <section v-if="queryEvidence.relatedKnowledge.length" class="evidence-section">
            <div class="section-title">
              <span>关联知识库</span>
              <ElTag type="warning" effect="plain">{{ queryEvidence.relatedKnowledge.length }}</ElTag>
            </div>
            <ElCard
              v-for="item in queryEvidence.relatedKnowledge"
              :key="item.id"
              class="result-item related-item"
              shadow="never"
            >
              <template #header>
                <div class="result-meta">
                  <ElSpace>
                    <ElIcon><Document /></ElIcon>
                    <strong>{{ item.metadata.doc_name || item.metadata.file_path }}</strong>
                  </ElSpace>
                  <ElTag type="warning" effect="plain">关联 {{ item.score }}</ElTag>
                </div>
              </template>
              <p>{{ item.metadata.file_path }}:{{ item.metadata.start_line }}</p>
              <pre>{{ item.text }}</pre>
            </ElCard>
          </section>
        </template>
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
