<script setup lang="ts">
import { Connection, Files, Refresh, Search, Share } from '@element-plus/icons-vue'
import { ElButton, ElEmpty, ElIcon, ElInput, ElOption, ElScrollbar, ElSelect, ElTag } from 'element-plus'
import type { GraphChainFilters, GraphChainSummary, GraphChainType } from '../../types'

const selectedChainId = defineModel<string>('selectedChainId', { required: true })
const filters = defineModel<GraphChainFilters>('filters', { required: true })

const props = defineProps<{
  busy: boolean
  chains: GraphChainSummary[]
}>()

const emit = defineEmits<{
  applyFilters: []
  refresh: []
  select: [chainId: string]
}>()

const chainTypeOptions: Array<{ label: string; value: GraphChainType }> = [
  { label: '全部链路', value: '' },
  { label: '接口链路', value: 'endpoint' },
  { label: '方法链路', value: 'method' },
  { label: '文件链路', value: 'file' }
]

function selectChain(chain: GraphChainSummary) {
  selectedChainId.value = chain.id
  emit('select', chain.id)
}

function chainTypeLabel(type: string) {
  if (type === 'endpoint') return '接口'
  if (type === 'method') return '方法'
  if (type === 'file') return '文件'
  return type || '链路'
}

function compactLine(value: string) {
  return value || '-'
}
</script>

<template>
  <aside class="chain-panel">
    <header class="chain-panel-header">
      <div class="chain-title">
        <ElIcon><Share /></ElIcon>
        <div>
          <h3>链路</h3>
          <span>链路列表</span>
        </div>
      </div>
      <ElButton :icon="Refresh" :loading="busy" aria-label="刷新链路" title="刷新链路" @click="$emit('refresh')" />
    </header>

    <section class="chain-filters">
      <ElSelect v-model="filters.type" placeholder="链路类型" @change="$emit('applyFilters')">
        <ElOption v-for="option in chainTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </ElSelect>
      <ElInput
        v-model="filters.query"
        clearable
        placeholder="搜索链路、文件或符号"
        :prefix-icon="Search"
        @keyup.enter="$emit('applyFilters')"
        @clear="$emit('applyFilters')"
      />
      <ElButton :icon="Search" @click="$emit('applyFilters')">查询</ElButton>
    </section>

    <ElScrollbar v-if="chains.length" class="chain-scroll">
      <div class="chain-list">
        <button
          v-for="chain in chains"
          :key="chain.id"
          class="chain-item"
          :class="{ active: chain.id === selectedChainId }"
          type="button"
          @click="selectChain(chain)"
        >
          <span class="chain-icon" :class="chain.type">
            <ElIcon v-if="chain.type === 'file'"><Files /></ElIcon>
            <ElIcon v-else><Connection /></ElIcon>
          </span>
          <span class="chain-content">
            <span class="chain-row">
              <strong>{{ compactLine(chain.title) }}</strong>
              <ElTag size="small" effect="plain">{{ chainTypeLabel(chain.type) }}</ElTag>
            </span>
            <span class="chain-subtitle">{{ compactLine(chain.subtitle) }}</span>
            <span v-if="chain.startLine" class="chain-meta">L{{ chain.startLine }}</span>
          </span>
        </button>
      </div>
    </ElScrollbar>
    <ElEmpty v-else class="chain-empty" description="暂无可展示链路。" />
  </aside>
</template>

<style scoped>
.chain-panel {
  background: #ffffff;
  border-right: 1px solid var(--line);
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  min-height: 0;
}

.chain-panel-header {
  align-items: center;
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 10px;
  justify-content: space-between;
  padding: 14px 16px;
}

.chain-title {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.chain-title > .el-icon {
  align-items: center;
  background: #e8f5f1;
  border-radius: 8px;
  color: #08745f;
  display: inline-flex;
  flex: 0 0 34px;
  height: 34px;
  justify-content: center;
  width: 34px;
}

.chain-title h3 {
  color: var(--text);
  font-size: 0.9rem;
  line-height: 1.2;
  margin: 0;
}

.chain-title span {
  color: var(--text-faint);
  display: block;
  font-size: 0.72rem;
  margin-top: 2px;
}

.chain-filters {
  display: grid;
  gap: 8px;
  padding: 12px 16px;
}

.chain-scroll {
  min-height: 0;
}

.chain-list {
  display: grid;
  gap: 8px;
  padding: 0 12px 16px;
}

.chain-item {
  align-items: flex-start;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: inherit;
  cursor: pointer;
  display: grid;
  gap: 10px;
  grid-template-columns: 32px minmax(0, 1fr);
  padding: 10px;
  text-align: left;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  width: 100%;
}

.chain-item:hover,
.chain-item.active {
  background: #f7fbfa;
  border-color: #83b8aa;
  box-shadow: 0 6px 16px rgba(18, 46, 40, 0.08);
}

.chain-icon {
  align-items: center;
  background: #eef5fb;
  border-radius: 8px;
  color: #315f8a;
  display: inline-flex;
  height: 32px;
  justify-content: center;
  width: 32px;
}

.chain-icon.endpoint {
  background: #e8f5f1;
  color: #08745f;
}

.chain-icon.file {
  background: #fff5e5;
  color: #9a5f00;
}

.chain-content,
.chain-row,
.chain-meta {
  min-width: 0;
}

.chain-content {
  display: grid;
  gap: 5px;
}

.chain-row {
  align-items: center;
  display: flex;
  gap: 6px;
}

.chain-row strong {
  color: var(--text);
  flex: 1 1 auto;
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chain-subtitle {
  color: var(--text-faint);
  display: block;
  font-size: 0.7rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chain-meta {
  color: #52616b;
  font-size: 0.68rem;
}

.chain-empty {
  align-self: start;
  padding-top: 40px;
}
</style>
