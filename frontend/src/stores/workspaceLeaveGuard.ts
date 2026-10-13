import { defineStore } from 'pinia';

/** Check active editors before navigation or an action that replaces their context. */
export const useWorkspaceLeaveGuardStore = defineStore('workspace-leave-guard', () => {
  const guards = new Map<symbol, () => Promise<boolean>>();
  let pending = false;

  function register(guard: () => Promise<boolean>) {
    const key = Symbol('editor');
    guards.set(key, guard);
    return () => { guards.delete(key); };
  }

  async function requestLeave() {
    // A second click must not run another navigation after the same confirmation.
    if (pending) return false;
    pending = true;
    try {
      for (const guard of guards.values()) {
        if (!await guard()) return false;
      }
      return true;
    } finally { pending = false; }
  }

  return { register, requestLeave };
});
