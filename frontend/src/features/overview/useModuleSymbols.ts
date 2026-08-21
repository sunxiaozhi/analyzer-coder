import { shallowReadonly, shallowRef } from 'vue';
import {
  getProjectArchitectureModuleSymbols,
  type ProjectArchitectureModuleSymbols,
} from '@/api/repositories';

export function useModuleSymbols(repositoryId: () => string | null) {
  const visible = shallowRef(false);
  const loading = shallowRef(false);
  const error = shallowRef<string | null>(null);
  const data = shallowRef<ProjectArchitectureModuleSymbols | null>(null);
  let requestId = 0;

  async function open(module: string) {
    visible.value = true;
    loading.value = true;
    error.value = null;
    data.value = null;
    const currentRequest = ++requestId;
    const currentRepository = repositoryId();
    if (!currentRepository) {
      loading.value = false;
      error.value = '请先选择仓库';
      return;
    }
    try {
      const result = await getProjectArchitectureModuleSymbols(
        currentRepository,
        module,
      );
      if (currentRequest === requestId) data.value = result;
    } catch (cause) {
      if (currentRequest === requestId) {
        error.value = cause instanceof Error ? cause.message : '模块符号加载失败';
      }
    } finally {
      if (currentRequest === requestId) loading.value = false;
    }
  }

  function close() {
    visible.value = false;
    requestId++;
  }

  return {
    visible: shallowReadonly(visible),
    loading: shallowReadonly(loading),
    error: shallowReadonly(error),
    data: shallowReadonly(data),
    open,
    close,
  };
}
