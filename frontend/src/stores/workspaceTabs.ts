import { defineStore } from 'pinia';
import { shallowRef } from 'vue';

export interface WorkspaceTab {
  name: string;
  title: string;
  fullPath: string;
}

const storageKey = 'analyzer-coder.workspace-tabs.v1';

function restoreTabs(): WorkspaceTab[] {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) ?? '[]');
    if (!Array.isArray(value)) return [];
    return value.filter((tab): tab is WorkspaceTab =>
      typeof tab?.name === 'string'
      && typeof tab?.title === 'string'
      && typeof tab?.fullPath === 'string');
  } catch {
    return [];
  }
}

export const useWorkspaceTabsStore = defineStore('workspace-tabs', () => {
  const tabs = shallowRef<WorkspaceTab[]>(restoreTabs());

  function persist() {
    localStorage.setItem(storageKey, JSON.stringify(tabs.value));
  }

  function open(tab: WorkspaceTab) {
    const index = tabs.value.findIndex(item => item.name === tab.name);
    tabs.value = index < 0
      ? [...tabs.value, tab]
      : tabs.value.map((item, itemIndex) => itemIndex === index ? tab : item);
    persist();
  }

  function close(name: string) {
    const index = tabs.value.findIndex(tab => tab.name === name);
    if (index < 0) return null;
    const next = tabs.value[index + 1] ?? tabs.value[index - 1] ?? null;
    tabs.value = tabs.value.filter(tab => tab.name !== name);
    persist();
    return next;
  }

  function closeOthers(name: string) {
    tabs.value = tabs.value.filter(tab => tab.name === name);
    persist();
  }

  function closeLeft(name: string) {
    const index = tabs.value.findIndex(tab => tab.name === name);
    if (index <= 0) return [];
    const closed = tabs.value.slice(0, index);
    tabs.value = tabs.value.slice(index);
    persist();
    return closed;
  }

  function closeRight(name: string) {
    const index = tabs.value.findIndex(tab => tab.name === name);
    if (index < 0 || index === tabs.value.length - 1) return [];
    const closed = tabs.value.slice(index + 1);
    tabs.value = tabs.value.slice(0, index + 1);
    persist();
    return closed;
  }

  function closeAll() {
    tabs.value = [];
    persist();
  }

  function retain(names: Set<string>) {
    tabs.value = tabs.value.filter(tab => names.has(tab.name));
    persist();
  }

  return { tabs, open, close, closeOthers, closeLeft, closeRight, closeAll, retain };
});
