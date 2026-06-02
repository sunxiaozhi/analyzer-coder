<script setup lang="ts">
import { Files } from '@element-plus/icons-vue'
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
  indexProject: []
}>()
</script>

<template>
  <div class="feature-page">
    <ElCard class="panel feature-controls" shadow="never">
      <template #header>
        <div class="panel-title">
          <ElIcon><Files /></ElIcon>
          <span>向量管理</span>
        </div>
      </template>

      <ElForm label-position="top" class="control-form horizontal-form">
        <ElFormItem v-if="form.source !== 'mixed'" label="索引路径">
          <ElInput v-model="form.path" clearable />
        </ElFormItem>
        <ElFormItem v-if="form.source === 'mixed'" label="代码路径">
          <ElInput v-model="form.codePath" clearable />
        </ElFormItem>
        <ElFormItem v-if="form.source === 'mixed'" label="知识库路径">
          <ElInput v-model="form.kbPath" clearable />
        </ElFormItem>
        <ElFormItem label="数据来源">
          <ElSelect v-model="form.source" class="control-select">
            <ElOption label="代码" value="code" />
            <ElOption label="知识库" value="kb" />
            <ElOption label="代码 + 知识库" value="mixed" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="向量库文件">
          <ElInput
            v-model="form.store"
            clearable
            :placeholder="form.projectId ? '默认使用项目独立向量库' : '.vector_store/web-project.jsonl'"
          />
        </ElFormItem>
      </ElForm>

      <ElButton type="primary" :loading="busy" :icon="Files" @click="$emit('indexProject')">
        建立索引
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
