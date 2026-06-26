<script setup lang="ts">
import { Refresh, Share } from '@element-plus/icons-vue'
import { ElButton, ElIcon } from 'element-plus'
import GraphChainCanvas from './GraphChainCanvas.vue'
import GraphChainList from './GraphChainList.vue'
import type { GraphChainDetail, GraphChainFilters, GraphChainSummary } from '../../types'

const selectedChainId = defineModel<string>('selectedChainId', { required: true })
const chainFilters = defineModel<GraphChainFilters>('chainFilters', { required: true })

const props = defineProps<{
  busy: boolean
  chains: GraphChainSummary[]
  selectedChain: GraphChainDetail | null
}>()

defineEmits<{
  refreshAll: []
  selectChain: [chainId: string]
  applyFilters: []
}>()
</script>

<template>
  <div class="feature-page graph-page console-page">
    <section class="graph-command console-command">
      <div class="graph-heading console-heading">
        <ElIcon><Share /></ElIcon>
        <div>
          <h2>图谱关系</h2>
          <span>选择链路查看对应图谱</span>
        </div>
      </div>

      <div class="graph-command-actions console-command-actions">
        <ElButton :icon="Refresh" :loading="busy" @click="$emit('refreshAll')">
          刷新图谱
        </ElButton>
      </div>
    </section>

    <section class="graph-workbench">
      <GraphChainList
        v-model:selected-chain-id="selectedChainId"
        v-model:filters="chainFilters"
        :busy="busy"
        :chains="chains"
        @apply-filters="$emit('applyFilters')"
        @refresh="$emit('refreshAll')"
        @select="$emit('selectChain', $event)"
      />
      <GraphChainCanvas :busy="busy" :detail="selectedChain" />
    </section>
  </div>
</template>

<style scoped>
.graph-page {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-height: 0;
}

.graph-command {
  align-items: center;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbfd 100%);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 16px 24px;
}

.graph-heading {
  align-items: center;
  display: flex;
  gap: 12px;
  min-width: 0;
}

.graph-heading > .el-icon {
  align-items: center;
  background: #e9f7f2;
  border-radius: 8px;
  color: #08745f;
  display: inline-flex;
  flex: 0 0 40px;
  height: 40px;
  justify-content: center;
  width: 40px;
}

.graph-heading h2 {
  color: var(--text);
  font-size: 1.08rem;
  margin: 0;
}

.graph-heading span {
  color: var(--text-faint);
  display: block;
  font-size: 0.78rem;
  margin-top: 3px;
}

.graph-command-actions {
  align-items: center;
  display: flex;
  gap: 10px;
}

.graph-workbench {
  display: grid;
  flex: 1 1 auto;
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

@media (max-width: 860px) {
  .graph-workbench {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(320px, 42vh) minmax(420px, 1fr);
  }
}

@media (max-width: 760px) {
  .graph-command {
    align-items: stretch;
    flex-direction: column;
    padding: 14px 16px;
  }

  .graph-command-actions {
    align-items: stretch;
    display: grid;
  }

}
</style>
