<script setup lang="ts">
import { computed } from 'vue'
import { Connection, Cpu, Files, Link, Refresh, Search } from '@element-plus/icons-vue'
import { ElIcon, ElMain, ElTag } from 'element-plus'
import EvidenceStepItem from './EvidenceStepItem.vue'
import type { ApiMappingResult, IndexStatus } from '../../types'

const props = defineProps<{
  apiMapping: ApiMappingResult | null
  apiMappingMessage: string
  apiMappingSavedPath: string
  analysisSavedPath: string
  busy: boolean
  indexStatus: IndexStatus | null
}>()

defineEmits<{
  analyze: []
  buildApiMapping: []
  indexProject: []
  openAnalysis: []
  openApiMap: []
  openVectors: []
}>()

const mappingSummary = computed(() => props.apiMapping?.summary)
const indexTotal = computed(() => props.indexStatus?.total ?? 0)
const knowledgeAssetCount = computed(() => props.indexStatus?.sources.knowledge_asset ?? 0)
const codeCount = computed(() => props.indexStatus?.sources.code ?? 0)
const pendingMappingCount = computed(() => (mappingSummary.value?.methodMismatches ?? 0) + (mappingSummary.value?.unmatched ?? 0))
</script>

<template>
  <ElMain class="page-surface evidence-page">
    <section class="evidence-command">
      <div class="evidence-heading">
        <ElIcon><Search /></ElIcon>
        <div>
          <h2>证据发现</h2>
          <span>从代码、接口和索引记录中发现可挂载到知识资产的证据</span>
        </div>
      </div>
      <div class="evidence-command-actions">
        <ElTag effect="plain">代码 {{ codeCount }}</ElTag>
        <ElTag type="success" effect="plain">知识资产 {{ knowledgeAssetCount }}</ElTag>
        <ElTag :type="indexTotal ? 'success' : 'warning'" effect="plain">索引 {{ indexTotal }}</ElTag>
      </div>
    </section>

    <section class="evidence-workbench">
      <div class="evidence-ledger-head">
        <span>发现流水线</span>
        <strong>代码结构 -> 接口关系 -> 检索索引</strong>
      </div>

      <EvidenceStepItem
        index="01"
        title="代码结构证据"
        description="提取 Java 类型、方法、调用点、Spring 组件、HTTP 接口和 SQL 注解，作为知识资产的代码依据。"
        :busy="busy"
        :icon="Cpu"
        :primary-icon="Refresh"
        primary-label="运行分析"
        :secondary-icon="Connection"
        secondary-label="查看详情"
        :metrics="[
          { label: '最近结果', value: analysisSavedPath || '尚未生成' },
          { label: '代码片段', value: codeCount },
          { label: '用途', value: '挂载证据' }
        ]"
        @primary="$emit('analyze')"
        @secondary="$emit('openAnalysis')"
      />

      <EvidenceStepItem
        index="02"
        title="接口证据"
        description="匹配前端 API 调用和后端 Spring 路由，帮助生成接口说明和业务规则证据。"
        :busy="busy"
        :icon="Link"
        :primary-icon="Refresh"
        primary-label="生成映射"
        :secondary-icon="Connection"
        secondary-label="查看详情"
        :metrics="[
          { label: '匹配', value: mappingSummary?.matched ?? 0 },
          { label: '待处理', value: pendingMappingCount },
          { label: '结果', value: apiMappingMessage || apiMappingSavedPath || '尚未生成' }
        ]"
        @primary="$emit('buildApiMapping')"
        @secondary="$emit('openApiMap')"
      />

      <EvidenceStepItem
        index="03"
        title="索引证据"
        description="统一索引代码、知识文档和知识资产，为可信检索和 AI 上下文提供召回基础。"
        :busy="busy"
        :icon="Files"
        :primary-icon="Refresh"
        primary-label="重建索引"
        :secondary-icon="Connection"
        secondary-label="查看详情"
        :metrics="[
          { label: '总片段', value: indexTotal },
          { label: '状态', value: indexStatus?.exists ? '已建立' : '未建立' },
          { label: '知识资产', value: knowledgeAssetCount }
        ]"
        @primary="$emit('indexProject')"
        @secondary="$emit('openVectors')"
      />
    </section>
  </ElMain>
</template>

<style scoped>
.evidence-page {
  gap: 0;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 0;
}

.evidence-command {
  align-items: center;
  background:
    linear-gradient(90deg, rgba(164, 92, 37, 0.1), transparent 48%),
    linear-gradient(180deg, var(--archive-paper, #fffdf7), var(--archive-panel, #f8faf7));
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 18px 26px 16px;
}

.evidence-heading,
.evidence-command-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.evidence-heading > .el-icon {
  align-items: center;
  background: var(--archive-navy, #13231f);
  border-radius: 6px;
  color: #fff8e7;
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.evidence-heading h2 {
  color: var(--text);
  font-size: 1.14rem;
  font-weight: 780;
  margin: 0;
}

.evidence-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.evidence-workbench {
  background: rgba(255, 253, 247, 0.74);
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(23, 32, 29, 0.05);
  display: grid;
  margin: 18px 26px 26px;
  overflow: hidden;
}

.evidence-ledger-head {
  align-items: center;
  background: var(--archive-panel, #f8faf7);
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 12px;
  justify-content: space-between;
  padding: 13px 20px;
}

.evidence-ledger-head span {
  color: var(--text-faint);
  font-size: 0.78rem;
  font-weight: 760;
}

.evidence-ledger-head strong {
  color: var(--text);
  font-size: 0.88rem;
  font-weight: 760;
}

@media (max-width: 760px) {
  .evidence-command {
    align-items: stretch;
    flex-direction: column;
  }

  .evidence-command-actions,
  .evidence-ledger-head {
    flex-wrap: wrap;
    justify-content: stretch;
  }

  .evidence-workbench {
    margin: 14px 16px 18px;
  }
}
</style>
