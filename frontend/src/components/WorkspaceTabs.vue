<script setup lang="ts">
import {
  Clipboard,
  Copy,
  Layers3,
  MoreHorizontal,
  PanelLeftClose,
  PanelRightClose,
  RefreshCw,
  X,
} from 'lucide-vue-next';
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue';
import type { WorkspaceTab } from '@/stores/workspaceTabs';

const props = defineProps<{ tabs: WorkspaceTab[]; activeName: string }>();
const emit = defineEmits<{
  activate: [tab: WorkspaceTab];
  refresh: [tab: WorkspaceTab];
  close: [tab: WorkspaceTab];
  closeOthers: [tab?: WorkspaceTab];
  closeLeft: [tab: WorkspaceTab];
  closeRight: [tab: WorkspaceTab];
  closeAll: [];
  copyLink: [tab: WorkspaceTab];
}>();

const contextMenu = reactive({ visible: false, x: 0, y: 0, tab: null as WorkspaceTab | null });
const contextMenuElement = ref<HTMLElement | null>(null);
const contextTabIndex = computed(() => props.tabs.findIndex(tab => tab.name === contextMenu.tab?.name));
const canCloseLeft = computed(() => contextTabIndex.value > 0);
const canCloseRight = computed(() => contextTabIndex.value >= 0 && contextTabIndex.value < props.tabs.length - 1);

async function openContextMenu(event: MouseEvent, tab: WorkspaceTab) {
  event.preventDefault();
  contextMenu.tab = tab;
  contextMenu.visible = true;
  contextMenu.x = event.clientX;
  contextMenu.y = event.clientY;
  await nextTick();
  const menu = contextMenuElement.value;
  if (!menu) return;
  const { width, height } = menu.getBoundingClientRect();
  contextMenu.x = Math.max(8, Math.min(event.clientX, window.innerWidth - width - 8));
  contextMenu.y = Math.max(8, Math.min(event.clientY, window.innerHeight - height - 8));
  menu.focus();
}

function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.tab = null;
}

function run(action: (tab: WorkspaceTab) => void) {
  const tab = contextMenu.tab;
  if (!tab) return;
  closeContextMenu();
  action(tab);
}

function onGlobalPointer(event: MouseEvent) {
  if (!contextMenu.visible || contextMenuElement.value?.contains(event.target as Node)) return;
  closeContextMenu();
}

function onGlobalKey(event: KeyboardEvent) {
  if (event.key === 'Escape') closeContextMenu();
}

window.addEventListener('mousedown', onGlobalPointer);
window.addEventListener('blur', closeContextMenu);
window.addEventListener('keydown', onGlobalKey);
onBeforeUnmount(() => {
  window.removeEventListener('mousedown', onGlobalPointer);
  window.removeEventListener('blur', closeContextMenu);
  window.removeEventListener('keydown', onGlobalKey);
});
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
        @contextmenu="openContextMenu($event, tab)"
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

    <Teleport to="body">
      <div
        v-if="contextMenu.visible && contextMenu.tab"
        ref="contextMenuElement"
        class="tab-context-menu"
        role="menu"
        tabindex="-1"
        :aria-label="`${contextMenu.tab.title}页签操作`"
        :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        @contextmenu.prevent
      >
        <button role="menuitem" @click="run(tab => emit('refresh', tab))">
          <RefreshCw :size="14" /><span>刷新当前页签</span>
        </button>
        <button role="menuitem" @click="run(tab => emit('copyLink', tab))">
          <Clipboard :size="14" /><span>复制页面链接</span>
        </button>
        <div class="menu-divider" role="separator"></div>
        <button role="menuitem" @click="run(tab => emit('close', tab))">
          <X :size="14" /><span>关闭当前页签</span>
        </button>
        <button :disabled="tabs.length <= 1" role="menuitem" @click="run(tab => emit('closeOthers', tab))">
          <Copy :size="14" /><span>关闭其他页签</span>
        </button>
        <button :disabled="!canCloseLeft" role="menuitem" @click="run(tab => emit('closeLeft', tab))">
          <PanelLeftClose :size="14" /><span>关闭左侧页签</span>
        </button>
        <button :disabled="!canCloseRight" role="menuitem" @click="run(tab => emit('closeRight', tab))">
          <PanelRightClose :size="14" /><span>关闭右侧页签</span>
        </button>
        <button role="menuitem" @click="closeContextMenu(); emit('closeAll')">
          <Layers3 :size="14" /><span>关闭全部页签</span>
        </button>
      </div>
    </Teleport>
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
.workspace-tabs-label svg { color: #0066cc; }
.tab-track { display: flex; gap: 4px; min-width: 0; padding-left: 6px; overflow-x: auto; overflow-y: hidden; scrollbar-width: thin; }
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
.workspace-tab > span { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-tab i { display: grid; flex: none; width: 20px; height: 20px; place-items: center; color: #969ba1; border-radius: 4px; font-style: normal; }
.workspace-tab:hover { color: #263746; border-color: #dbe5ef; background: #f6f9fc; }
.workspace-tab i:hover,
.workspace-tab i:focus-visible { color: #263746; background: #e7eaed; outline: none; }
.workspace-tab.active { color: #fff; border-color: #0066cc; background: #0066cc; box-shadow: 0 2px 5px #0066cc2b; font-weight: 650; }
.workspace-tab.active i { color: #dceeff; }
.workspace-tab.active i:hover,
.workspace-tab.active i:focus-visible { color: #fff; background: #ffffff26; }
.tab-menu { display: grid; width: 34px; height: 34px; place-items: center; color: #69727a; border: 1px solid transparent; border-radius: 5px; background: transparent; }
.tab-menu:hover { color: #005eb8; border-color: #d3e1ee; background: #f3f8fc; }
@media (max-width: 760px) {
  .workspace-tabs { position: sticky; top: 96px; z-index: 8; margin-bottom: 10px; }
  .workspace-tabs-label span { display: none; }
  .workspace-tabs-label { padding: 0 9px; }
}
</style>

<style>
.tab-context-menu {
  position: fixed;
  z-index: 4000;
  display: grid;
  width: 190px;
  padding: 6px;
  border: 1px solid #d8e0e7;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 12px 28px #1f34491f, 0 2px 8px #1f344914;
  outline: none;
}
.tab-context-menu button {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 7px;
  align-items: center;
  width: 100%;
  height: 32px;
  padding: 0 8px;
  color: #384550;
  text-align: left;
  border: 0;
  border-radius: 5px;
  background: transparent;
  font-size: 11px;
}
.tab-context-menu button svg { color: #687886; }
.tab-context-menu button:hover,
.tab-context-menu button:focus-visible { color: #005eb8; background: #edf5fd; outline: none; }
.tab-context-menu button:hover svg,
.tab-context-menu button:focus-visible svg { color: #0066cc; }
.tab-context-menu button:disabled { color: #a6adb4; background: transparent; cursor: not-allowed; }
.tab-context-menu button:disabled svg { color: #b9bfc5; }
.tab-context-menu .menu-divider { height: 1px; margin: 5px 3px; background: #e7eaed; }
</style>
