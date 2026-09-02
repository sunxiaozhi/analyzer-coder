<script setup lang="ts">
import { computed } from 'vue';
import { ArrowUpRight, Boxes, FileCode2 } from 'lucide-vue-next';
import type { ProjectProfileCount } from '@/api/repositories';

interface Props {
  repositoryName: string;
  modules: ProjectProfileCount[];
  entryPoints: string[];
}

const props = defineProps<Props>();
const emit = defineEmits<{ openFile: [path: string] }>();
const maximum = computed(() => Math.max(1, ...props.modules.map(item => item.count)));
const moduleRows = computed(() => props.modules.map((module, index) => ({
  ...module,
  width: `${Math.max(18, Math.round((module.count / maximum.value) * 100))}%`,
  order: String(index + 1).padStart(2, '0'),
})));
</script>

<template>
  <section class="structure-panel">
    <header class="panel-head">
      <div>
        <span class="eyebrow">模块视图</span>
        <h2>项目结构轨道</h2>
      </div>
      <span class="module-total">{{ modules.length }} 个核心模块</span>
    </header>

    <div class="structure-map">
      <div class="repository-root">
        <span class="root-icon"><Boxes :size="18" /></span>
        <div>
          <strong>{{ repositoryName }}</strong>
          <small>当前发布快照</small>
        </div>
      </div>

      <div v-if="moduleRows.length" class="module-rail">
        <article v-for="module in moduleRows" :key="module.name" class="module-row">
          <span class="module-order">{{ module.order }}</span>
          <div class="module-track">
            <span class="module-bar" :style="{ width: module.width }"></span>
          </div>
          <strong>{{ module.name }}</strong>
          <small>{{ module.count }} 文件</small>
        </article>
      </div>
      <el-empty v-else :image-size="48" description="当前快照没有可展示的一级模块" />
    </div>

    <footer class="entry-strip">
      <span class="entry-label"><FileCode2 :size="14" /> 约定入口</span>
      <div v-if="entryPoints.length" class="entry-list">
        <button v-for="path in entryPoints" :key="path" type="button" @click="emit('openFile', path)">
          <span>{{ path }}</span><ArrowUpRight :size="12" />
        </button>
      </div>
      <span v-else class="entry-empty">尚未识别入口文件</span>
    </footer>
  </section>
</template>

<style scoped>
.structure-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #d9e1e8;
  border-radius: 8px;
  background: #fff;
}

.panel-head {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 16px;
  border-bottom: 1px solid #e5eaef;
}

.eyebrow { color: var(--app-text-muted); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.panel-head h2 { margin: 5px 0 0; color: #23313d; font-size: 15px; }
.module-total { color: var(--app-text-muted); font-size: 13px; }

.structure-map {
  position: relative;
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 26px;
  min-height: 290px;
  padding: 28px 22px 24px;
  background-color: #f8fafb;
  background-image: linear-gradient(#e7edf1 1px, transparent 1px), linear-gradient(90deg, #e7edf1 1px, transparent 1px);
  background-size: 28px 28px;
}

.repository-root {
  position: relative;
  align-self: start;
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 58px;
  padding: 11px 13px;
  border: 1px solid #aec1d0;
  border-left: 4px solid var(--app-color-action);
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 7px 18px rgb(30 58 77 / 9%);
}

.repository-root::after {
  position: absolute;
  top: 28px;
  right: -27px;
  width: 26px;
  height: 1px;
  background: #91a6b7;
  content: '';
}

.root-icon { display: grid; width: 32px; height: 32px; place-items: center; color: #fff; border-radius: 5px; background: var(--app-color-action); }
.repository-root div { display: grid; min-width: 0; gap: 3px; }
.repository-root strong { overflow: hidden; color: #273745; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.repository-root small { color: var(--app-text-muted); font-size: 13px; }

.module-rail { position: relative; display: grid; align-content: start; gap: 9px; padding-left: 18px; }
.module-rail::before { position: absolute; top: 28px; bottom: 28px; left: 0; width: 1px; background: #91a6b7; content: ''; }
.module-row {
  position: relative;
  display: grid;
  grid-template-columns: 24px minmax(70px, 1fr) minmax(90px, 150px) 52px;
  align-items: center;
  gap: 9px;
  min-height: 42px;
  padding: 7px 10px;
  border: 1px solid #dce4ea;
  border-radius: 5px;
  background: rgb(255 255 255 / 94%);
}
.module-row::before { position: absolute; top: 50%; left: -19px; width: 18px; height: 1px; background: #91a6b7; content: ''; }
.module-order { color: var(--app-text-muted); font: 13px Consolas, monospace; }
.module-track { height: 4px; overflow: hidden; border-radius: 3px; background: #e7edf1; }
.module-bar { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--app-color-action), #90bde5); }
.module-row strong { overflow: hidden; color: #344653; font: 600 14px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.module-row small { color: var(--app-text-muted); font-size: 13px; text-align: right; }

.entry-strip { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 10px; padding: 13px 16px; border-top: 1px solid #e5eaef; }
.entry-label { display: flex; align-items: center; gap: 6px; color: #5e6d78; font-size: 14px; font-weight: 650; }
.entry-list { display: flex; min-width: 0; flex-wrap: wrap; gap: 6px; }
.entry-list button { display: flex; max-width: 220px; align-items: center; gap: 5px; padding: 5px 7px; color: #315870; border: 1px solid #dbe4ea; border-radius: 4px; background: #f8fafb; font: 13px Consolas, monospace; }
.entry-list button:hover, .entry-list button:focus-visible { color: var(--app-color-action); border-color: #9cc2e4; outline: none; background: #f0f7fd; }
.entry-list button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry-empty { color: var(--app-text-muted); font-size: 13px; }

@media (max-width: 860px) {
  .structure-map { grid-template-columns: 1fr; }
  .repository-root { max-width: 280px; }
  .repository-root::after { display: none; }
  .module-rail { padding-left: 0; }
  .module-rail::before, .module-row::before { display: none; }
}
</style>
