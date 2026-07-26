<script setup lang="ts">
import { Layers3, MoreHorizontal, X } from 'lucide-vue-next';
import type { WorkspaceTab } from '@/stores/workspaceTabs';

defineProps<{ tabs: WorkspaceTab[]; activeName: string }>();
const emit = defineEmits<{
  activate: [tab: WorkspaceTab];
  close: [tab: WorkspaceTab];
  closeOthers: [];
  closeAll: [];
}>();
</script>

<template>
  <div class="workspace-tabs" aria-label="已打开页面">
    <div class="workspace-tabs-label">
      <Layers3 :size="14" />
      <span>工作区</span>
    </div>
    <div class="tab-track" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.name"
        :class="['workspace-tab', { active: tab.name === activeName }]"
        type="button"
        role="tab"
        :aria-selected="tab.name === activeName"
        @click="emit('activate', tab)"
        @auxclick.middle.prevent="emit('close', tab)"
      >
        <span>{{ tab.title }}</span>
        <i
          role="button"
          tabindex="0"
          :aria-label="`关闭${tab.title}`"
          @click.stop="emit('close', tab)"
          @keydown.enter.stop="emit('close', tab)"
        >
          <X :size="12" />
        </i>
      </button>
    </div>
    <el-dropdown trigger="click" placement="bottom-end">
      <button class="tab-menu" type="button" aria-label="管理页签"><MoreHorizontal :size="16" /></button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item :disabled="tabs.length <= 1" @click="emit('closeOthers')">关闭其他页签</el-dropdown-item>
          <el-dropdown-item @click="emit('closeAll')">关闭全部页签</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.workspace-tabs {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 38px;
  min-width: 0;
  height: 44px;
  padding: 4px;
  border: 1px solid #dedee3;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 2px 7px #24384c0d;
}
.workspace-tabs-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px 0 9px;
  color: #526170;
  border-right: 1px solid #e3e7eb;
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
}
.workspace-tabs-label svg {
  color: #0066cc;
}
.tab-track {
  display: flex;
  gap: 4px;
  min-width: 0;
  padding-left: 6px;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: thin;
}
.workspace-tab {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 9px;
  min-width: 0;
  max-width: 210px;
  height: 34px;
  padding: 0 6px 0 12px;
  color: #646b72;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  font-size: 12px;
}
.workspace-tab > span {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workspace-tab i {
  display: grid;
  flex: none;
  place-items: center;
  width: 20px;
  height: 20px;
  color: #969ba1;
  border-radius: 4px;
  font-style: normal;
}
.workspace-tab:hover {
  color: #263746;
  border-color: #dbe5ef;
  background: #f6f9fc;
}
.workspace-tab i:hover,
.workspace-tab i:focus-visible { color: #263746; background: #e7eaed; outline: none; }
.workspace-tab.active {
  color: #fff;
  border-color: #0066cc;
  background: #0066cc;
  box-shadow: 0 2px 5px #0066cc2b;
  font-weight: 650;
}
.workspace-tab.active i { color: #dceeff; }
.workspace-tab.active i:hover,
.workspace-tab.active i:focus-visible {
  color: #fff;
  background: #ffffff26;
}
.tab-menu {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #69727a;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
}
.tab-menu:hover {
  color: #005eb8;
  border-color: #d3e1ee;
  background: #f3f8fc;
}
@media (max-width: 760px) {
  .workspace-tabs { position: sticky; top: 96px; z-index: 8; margin-bottom: 10px; }
  .workspace-tabs-label span { display: none; }
  .workspace-tabs-label { padding: 0 9px; }
}
</style>
