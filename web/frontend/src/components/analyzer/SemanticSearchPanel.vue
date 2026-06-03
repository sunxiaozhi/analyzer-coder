<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
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
  queryStore: []
}>()
</script>

<template>
  <div class="feature-page">
    <ElCard class="panel feature-controls" shadow="never">
      <template #header>
        <div class="panel-title">
          <ElIcon><Search /></ElIcon>
          <span>语义检索</span>
        </div>
      </template>

      <ElForm label-position="top" class="control-form horizontal-form">
        <ElFormItem label="查询文本">
          <ElInput v-model="form.query" type="textarea" :rows="3" resize="vertical" />
        </ElFormItem>
        <ElFormItem label="过滤来源">
          <ElSelect v-model="form.filterSource" class="control-select">
            <ElOption label="全部" value="" />
            <ElOption label="仅代码" value="code" />
            <ElOption label="仅知识库" value="kb" />
          </ElSelect>
        </ElFormItem>
      </ElForm>

      <ElButton type="primary" :loading="busy" :icon="Search" @click="$emit('queryStore')">
        检索
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
