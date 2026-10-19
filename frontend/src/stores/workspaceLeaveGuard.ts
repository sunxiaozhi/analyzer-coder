import { defineStore } from 'pinia';

/** Check active editors before navigation or an action that replaces their context. */
export const useWorkspaceLeaveGuardStore = defineStore('workspace-leave-guard', () => {
  const guards = new Map<symbol, { leave: () => Promise<boolean>; blocksRefresh: () => boolean }>();
  let pending = false;

  function register(guard: () => Promise<boolean>, blocksRefresh: () => boolean = () => false) {
    const key = Symbol('editor');
    guards.set(key, { leave: guard, blocksRefresh });
    return () => { guards.delete(key); };
  }

  async function requestLeave() {
    // A second click must not run another navigation after the same confirmation.
    if (pending) return false;
    pending = true;
    try {
      for (const guard of guards.values()) {
        if (!await guard.leave()) return false;
      }
      return true;
    } finally { pending = false; }
  }

  function canRefreshAutomatically() {
    return !pending && [...guards.values()].every(guard => !guard.blocksRefresh());
  }

  return { register, requestLeave, canRefreshAutomatically };
});
