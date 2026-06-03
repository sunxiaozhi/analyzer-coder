<script setup lang="ts">
import { Cpu } from '@element-plus/icons-vue'
import { ElButton, ElCard, ElForm, ElFormItem, ElIcon, ElInput, ElOption, ElSelect } from 'element-plus'
import type { ActiveView, AnalyzerForm, JsonValue, OutputType, QueryEvidence, QueryResult } from '../../types'
import OutputPanel from './OutputPanel.vue'

const form = defineModel<AnalyzerForm>('form', { required: true })

defineProps<{
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
</script>

<template>
  <div class="feature-page">
    <ElCard class="panel feature-controls" shadow="never">
      <template #header>
        <div class="panel-title">
          <ElIcon><Cpu /></ElIcon>
          <span>代码分析结果</span>
        </div>
      </template>

      <ElForm label-position="top" class="control-form horizontal-form">
        <ElFormItem label="分析路径">
          <ElInput v-model="form.path" clearable />
        </ElFormItem>
        <ElFormItem label="数据来源">
          <ElSelect v-model="form.source" class="control-select">
            <ElOption label="代码" value="code" />
            <ElOption label="知识库" value="kb" />
            <ElOption label="代码 + 知识库" value="mixed" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="输出模式">
          <ElSelect v-model="form.mode" class="control-select">
            <ElOption label="报告" value="report" />
            <ElOption label="摘要" value="summary" />
            <ElOption label="JSON" value="json" />
            <ElOption label="切块" value="chunks" />
            <ElOption label="Mermaid 图" value="graph" />
          </ElSelect>
        </ElFormItem>
      </ElForm>

      <ElButton type="primary" :loading="busy" :icon="Cpu" @click="$emit('analyze')">
        运行分析
      </ElButton>
    </ElCard>

    <OutputPanel
      :active-view="activeView"
      :output="output"
      :output-title="outputTitle"
      :output-type="outputType"
      :parsed-json="parsedJson"
      :query-evidence="queryEvidence"
      :query-results="queryResults"
      :saved-path="savedPath"
    />
  </div>
</template>
