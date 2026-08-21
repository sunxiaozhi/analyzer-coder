import { shallowReadonly, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  generateProjectContextPack,
  getRepositoryProfile,
  getProjectArchitectureMap,
  listRepositoryFiles,
  type ProjectArchitectureMap,
  type ProjectContextPack,
  type RepositoryPreparation,
} from '@/api/repositories';
import { useRepositoryStore } from '@/stores/repositoryStore';
import type { RepositorySnapshotFiles } from '@/types/api';

export function useProjectOverview() {
  const repositories = useRepositoryStore();
  const preparation = shallowRef<RepositoryPreparation | null>(null);
  const snapshot = shallowRef<RepositorySnapshotFiles | null>(null);
  const architecture = shallowRef<ProjectArchitectureMap | null>(null);
  const contextPack = shallowRef<ProjectContextPack | null>(null);
  const loading = shallowRef(false);
  const contextLoading = shallowRef(false);
  const error = shallowRef<string | null>(null);
  let loadVersion = 0;

  async function load(repositoryId: string | null) {
    const version = ++loadVersion;
    preparation.value = null;
    snapshot.value = null;
    architecture.value = null;
    contextPack.value = null;
    error.value = null;
    if (!repositoryId) return;
    loading.value = true;
    try {
      const [profile, files, architectureMap] = await Promise.all([
        getRepositoryProfile(repositoryId),
        listRepositoryFiles(repositoryId),
        getProjectArchitectureMap(repositoryId).catch(() => null),
      ]);
      if (version !== loadVersion) return;
      preparation.value = profile;
      snapshot.value = files;
      architecture.value = architectureMap;
    } catch (exception) {
      if (version === loadVersion) {
        error.value = exception instanceof Error ? exception.message : '项目总览加载失败';
      }
    } finally {
      if (version === loadVersion) loading.value = false;
    }
  }

  async function generateContext(task: string) {
    const repositoryId = repositories.selectedRepositoryId;
    if (!repositoryId) return;
    contextLoading.value = true;
    try {
      contextPack.value = await generateProjectContextPack(repositoryId, {
        task,
        maxItems: 12,
        maxChars: 14_000,
      });
    } catch (exception) {
      ElMessage.error(exception instanceof Error ? exception.message : '上下文包生成失败');
    } finally {
      contextLoading.value = false;
    }
  }

  async function copyContext() {
    if (!contextPack.value) return;
    try {
      await navigator.clipboard.writeText(contextPack.value.markdown);
      ElMessage.success('Agent 上下文已复制');
    } catch {
      ElMessage.error('复制失败，请检查浏览器剪贴板权限');
    }
  }

  function downloadContext() {
    if (!contextPack.value) return;
    const blob = new Blob([contextPack.value.markdown], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${contextPack.value.repositoryName}-context-pack.md`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  watch(() => repositories.selectedRepositoryId, load, { immediate: true });

  return {
    preparation: shallowReadonly(preparation),
    snapshot: shallowReadonly(snapshot),
    architecture: shallowReadonly(architecture),
    contextPack: shallowReadonly(contextPack),
    loading: shallowReadonly(loading),
    contextLoading: shallowReadonly(contextLoading),
    error: shallowReadonly(error),
    reload: () => load(repositories.selectedRepositoryId),
    generateContext,
    copyContext,
    downloadContext,
  };
}
