import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import {
  deleteRepository,
  listRepositories,
  registerRepository,
  startIndex,
} from '@/api/repositories';
import type { IndexJob, IndexJobType, RegisterRepositoryPayload, Repository } from '@/types/api';

export const useRepositoryStore = defineStore('repository', () => {
  const repositories = ref<Repository[]>([]);
  const selectedRepositoryId = ref<string | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const lastStartedJob = ref<IndexJob | null>(null);

  const selectedRepository = computed(() => {
    return repositories.value.find((repository) => repository.id === selectedRepositoryId.value) ?? null;
  });

  async function loadRepositories() {
    loading.value = true;
    error.value = null;
    try {
      repositories.value = await listRepositories();
      if (!selectedRepositoryId.value && repositories.value.length > 0) {
        selectedRepositoryId.value = repositories.value[0].id;
      }
      if (selectedRepositoryId.value && !repositories.value.some((item) => item.id === selectedRepositoryId.value)) {
        selectedRepositoryId.value = repositories.value[0]?.id ?? null;
      }
    } catch (exception) {
      error.value = exception instanceof Error ? exception.message : '加载仓库失败';
    } finally {
      loading.value = false;
    }
  }

  async function createRepository(payload: RegisterRepositoryPayload) {
    error.value = null;
    const repository = await registerRepository(payload);
    repositories.value = [...repositories.value, repository];
    selectedRepositoryId.value = repository.id;
    return repository;
  }

  async function removeRepository(repositoryId: string) {
    error.value = null;
    await deleteRepository(repositoryId);
    repositories.value = repositories.value.filter((repository) => repository.id !== repositoryId);
    if (selectedRepositoryId.value === repositoryId) {
      selectedRepositoryId.value = repositories.value[0]?.id ?? null;
    }
  }

  async function createIndexJob(repositoryId: string, type: IndexJobType) {
    error.value = null;
    lastStartedJob.value = await startIndex(repositoryId, type);
    return lastStartedJob.value;
  }

  function selectRepository(repositoryId: string | null) {
    selectedRepositoryId.value = repositoryId;
  }

  return {
    repositories,
    selectedRepositoryId,
    selectedRepository,
    loading,
    error,
    lastStartedJob,
    loadRepositories,
    createRepository,
    removeRepository,
    createIndexJob,
    selectRepository,
  };
});
