<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ArrowUpRight, FileCode2, SearchCode } from 'lucide-vue-next';
import type {
  ProjectArchitectureModuleSymbols,
  ProjectArchitectureSymbol,
} from '@/api/repositories';

interface Props {
  visible: boolean;
  loading: boolean;
  error: string | null;
  data: ProjectArchitectureModuleSymbols | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  close: [];
  openGraph: [symbol: ProjectArchitectureSymbol];
  openFile: [symbol: ProjectArchitectureSymbol];
}>();

const filter = shallowRef('');
const filteredSymbols = computed(() => {
  const keyword = filter.value.trim().toLowerCase();
  if (!keyword) return props.data?.symbols ?? [];
  return (props.data?.symbols ?? []).filter(symbol =>
    [symbol.symbolName, symbol.symbolKind, symbol.filePath, symbol.language]
      .filter(Boolean)
      .some(value => value!.toLowerCase().includes(keyword)),
  );
});

watch(
  () => props.data?.module,
  () => {
    filter.value = '';
  },
);

function kindLabel(kind: string | null) {
  return kind?.trim() || 'SYMBOL';
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    class="module-symbol-drawer"
    size="520px"
    :show-close="false"
    destroy-on-close
    @update:model-value="(value: boolean) => { if (!value) emit('close'); }"
  >
    <template #header>
      <div class="drawer-heading">
        <span>MODULE SYMBOLS</span>
        <strong>{{ data?.module ?? '模块代码符号' }}</strong>
        <small>选择当前快照中的真实符号，再进入确定性调用分析</small>
      </div>
    </template>

    <div class="symbol-tools">
      <el-input
        v-model="filter"
        clearable
        :prefix-icon="SearchCode"
        placeholder="筛选类、函数、方法或文件"
      />
      <span v-if="data">
        {{ filteredSymbols.length }}/{{ data.symbols.length }} 个符号
        <em v-if="data.truncated">最多展示 80 个</em>
      </span>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" />

    <div v-loading="loading" class="symbol-list">
      <el-empty
        v-if="!loading && !error && !filteredSymbols.length"
        :image-size="58"
        description="当前模块尚未索引出可下钻的代码符号"
      />
      <article
        v-for="symbol in filteredSymbols"
        :key="`${symbol.filePath}:${symbol.startLine}:${symbol.symbolName}`"
        class="symbol-card"
      >
        <button type="button" class="symbol-source" @click="emit('openFile', symbol)">
          <span class="symbol-kind">{{ kindLabel(symbol.symbolKind) }}</span>
          <strong>{{ symbol.symbolName }}</strong>
          <small>
            <FileCode2 :size="11" />
            {{ symbol.filePath }}{{ symbol.startLine ? `:${symbol.startLine}` : '' }}
          </small>
        </button>
        <button
          type="button"
          class="graph-action"
          title="分析该符号的调用关系"
          @click="emit('openGraph', symbol)"
        >
          调用图 <ArrowUpRight :size="12" />
        </button>
      </article>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-heading { display: grid; gap: 4px; }
.drawer-heading span { color: var(--app-text-muted); font-size: 11px; font-weight: 650; letter-spacing: .04em; }
.drawer-heading strong { color: #263b48; font: 650 16px Consolas, monospace; }
.drawer-heading small { color: var(--app-text-muted); font-size: 11px; }
.symbol-tools { display: grid; gap: 7px; padding-bottom: 12px; border-bottom: 1px solid #e3e9ed; }
.symbol-tools > span { color: var(--app-text-muted); font-size: 11px; }
.symbol-tools em { margin-left: 6px; color: #9a672e; font-style: normal; }
.symbol-list { display: grid; align-content: start; gap: 7px; min-height: 220px; padding-top: 12px; }
.symbol-card { display: grid; grid-template-columns: minmax(0, 1fr) 68px; align-items: stretch; overflow: hidden; border: 1px solid #dce4e8; border-radius: 5px; background: #fff; }
.symbol-card:hover { border-color: #99b5c5; box-shadow: 0 4px 12px rgb(42 72 88 / 7%); }
.symbol-source { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 5px 8px; min-width: 0; padding: 10px 11px; text-align: left; border: 0; background: transparent; }
.symbol-source:focus-visible, .graph-action:focus-visible { outline: 2px solid rgb(0 102 204 / 28%); outline-offset: -2px; }
.symbol-kind { padding: 2px 5px; color: #0066cc; border-radius: 3px; background: #eaf3fd; font: 700 11px Consolas, monospace; }
.symbol-source strong { overflow: hidden; color: #2d414e; font: 600 12px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.symbol-source small { grid-column: 1 / -1; display: flex; align-items: center; gap: 4px; overflow: hidden; color: var(--app-text-muted); font: 500 11px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.graph-action { display: flex; align-items: center; justify-content: center; gap: 3px; color: #0066cc; border: 0; border-left: 1px solid #dedee3; background: #f3f8fc; font-size: 11px; }
.graph-action:hover { color: #0066cc; background: #edf5fa; }
@media (max-width: 600px) {
  .symbol-card { grid-template-columns: 1fr; }
  .graph-action { min-height: 34px; border-top: 1px solid #e0e7e9; border-left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .symbol-card { transition: none; }
}
</style>
