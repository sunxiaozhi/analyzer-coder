import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useWorkspaceLeaveGuardStore } from '@/stores/workspaceLeaveGuard';

interface Options {
  active: () => boolean;
  busy: () => boolean;
  snapshot: () => string;
  close: () => void;
}

export function useKnowledgeEditorLeaveGuard(options: Options) {
  const baseline = shallowRef('');
  const discarded = shallowRef(false);
  const dirty = computed(() => options.active() && !discarded.value && options.snapshot() !== baseline.value);
  let confirming = false;
  let generation = 0;

  watch(options.active, () => { generation++; discarded.value = false; }, { flush: 'sync' });
  function markPristine() { baseline.value = options.snapshot(); discarded.value = false; }

  async function requestClose() {
    if (!options.active() || discarded.value) return true;
    if (options.busy()) {
      ElMessage.warning('正在保存或上传附件，请完成后再离开。');
      return false;
    }
    if (confirming) return false;
    if (dirty.value) {
      const current = generation;
      confirming = true;
      let discard = false;
      try {
        await ElMessageBox.confirm('知识内容尚未保存。请返回编辑器保存草稿，或放弃本次修改。', '保留未保存的修改', {
          type: 'warning', confirmButtonText: '继续编辑', cancelButtonText: '放弃修改',
          distinguishCancelAndClose: true, closeOnClickModal: false,
        });
      } catch (action) { discard = action === 'cancel'; }
      finally { confirming = false; }
      if (!discard || current !== generation || options.busy()) return false;
    }
    discarded.value = true;
    options.close();
    return true;
  }

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!options.active() || discarded.value || (!dirty.value && !options.busy())) return;
    event.preventDefault();
    event.returnValue = '';
  }
  const unregister = useWorkspaceLeaveGuardStore().register(requestClose,
    () => options.active() && !discarded.value && (dirty.value || options.busy()));
  window.addEventListener('beforeunload', beforeUnload);
  onScopeDispose(() => {
    generation++;
    unregister();
    window.removeEventListener('beforeunload', beforeUnload);
  });
  return { dirty, markPristine, requestClose };
}
