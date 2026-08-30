import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';
import {
  getCurrentRepositoryPreference,
  updateCurrentRepositoryPreference,
} from '@/api/accountPreferences';
import {
  deleteRepository,
  listRepositories,
  registerRepository,
  rescanRepository as requestRepositoryRescan,
  startIndex,
} from '@/api/repositories';
import type { IndexJob, IndexJobType, RegisterRepositoryPayload, Repository } from '@/types/api';

export const useRepositoryStore = defineStore('repository', () => {
  const repositories = ref<Repository[]>([]);
  const selectedRepositoryId = shallowRef<string | null>(null);
  const loading = shallowRef(false);
  const initialized = shallowRef(false);
  const error = shallowRef<string | null>(null);
  const lastStartedJob = shallowRef<IndexJob | null>(null);

  const selectedRepository = computed(() =>
    repositories.value.find((repository) => repository.id === selectedRepositoryId.value) ?? null,
  );

  async function loadRepositories() {
    loading.value = true;
    error.value = null;
    try {
      const [repositoryResult, preferenceResult] = await Promise.allSettled([
        listRepositories(),
        getCurrentRepositoryPreference(),
      ]);
      if (repositoryResult.status === 'rejected') throw repositoryResult.reason;
      repositories.value = repositoryResult.value;
      const preferredRepositoryId = preferenceResult.status === 'fulfilled'
        ? preferenceResult.value.repositoryId
        : null;
      selectedRepositoryId.value = preferredRepositoryId
        && repositories.value.some((item) => item.id === preferredRepositoryId)
        ? preferredRepositoryId
        : repositories.value[0]?.id ?? null;
      if (
        preferenceResult.status === 'fulfilled'
        && selectedRepositoryId.value !== preferredRepositoryId
      ) {
        await updateCurrentRepositoryPreference(selectedRepositoryId.value);
      }
    } catch (exception) {
      error.value = exception instanceof Error ? exception.message : '加载仓库失败';
    } finally {
      loading.value = false;
      initialized.value = true;
    }
  }

  async function createRepository(payload: RegisterRepositoryPayload) {
    error.value = null;
    const repository = await registerRepository(payload);
    repositories.value = [...repositories.value, repository];
    selectedRepositoryId.value = repository.id;
    await updateCurrentRepositoryPreference(repository.id);
    return repository;
  }

  async function rescanRepository(repositoryId: string) {
    error.value = null;
    const result = await requestRepositoryRescan(repositoryId);
    repositories.value = repositories.value.map((item) =>
      item.id === repositoryId ? result.repository : item,
    );
    return result;
  }

  async function removeRepository(repositoryId: string) {
    error.value = null;
    await deleteRepository(repositoryId);
    repositories.value = repositories.value.filter((repository) => repository.id !== repositoryId);
    if (selectedRepositoryId.value === repositoryId) {
      selectedRepositoryId.value = repositories.value[0]?.id ?? null;
      await updateCurrentRepositoryPreference(selectedRepositoryId.value);
    }
  }

  async function createIndexJob(repositoryId: string, type: IndexJobType) {
    error.value = null;
    lastStartedJob.value = await startIndex(repositoryId, type);
    return lastStartedJob.value;
  }

  async function selectRepository(repositoryId: string | null) {
    if (repositoryId && !repositories.value.some((repository) => repository.id === repositoryId)) {
      throw new Error('当前账号无权访问该仓库');
    }
    selectedRepositoryId.value = repositoryId;
    await updateCurrentRepositoryPreference(repositoryId);
  }

  return {
    repositories,
    selectedRepositoryId,
    selectedRepository,
    loading,
    initialized,
    error,
    lastStartedJob,
    loadRepositories,
    createRepository,
    rescanRepository,
    removeRepository,
    createIndexJob,
    selectRepository,
  };
});
