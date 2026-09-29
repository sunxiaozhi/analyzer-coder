import { defineStore } from 'pinia';

/** UI positions only. Never cache API responses or authorization decisions. */
export const usePageMemoryStore = defineStore('page-memory', () => {
  const entries = new Map<string, unknown>();
  function read<T>(key: string): T | undefined { return entries.get(key) as T | undefined; }
  function write<T>(key: string, state: T) {
    entries.delete(key);
    entries.set(key, state);
    if (entries.size > 60) entries.delete(entries.keys().next().value!);
  }
  function clear() { entries.clear(); }
  return { read, write, clear };
});
