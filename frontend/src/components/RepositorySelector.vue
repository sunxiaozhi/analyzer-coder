<script setup lang="ts">
import { RefreshCw } from 'lucide-vue-next';
import { computed } from 'vue';
import { useRepositoryStore } from '@/stores/repositoryStore';

const repositoryStore = useRepositoryStore();

const model = computed({
  get: () => repositoryStore.selectedRepositoryId ?? '',
  set: (value: string) => repositoryStore.selectRepository(value || null),
});
</script>

<template>
  <div class="repo-selector">
    <label class="field-label" for="repository-selector">仓库</label>
    <select id="repository-selector" v-model="model" class="select-control">
      <option value="">未选择</option>
      <option v-for="repository in repositoryStore.repositories" :key="repository.id" :value="repository.id">
        {{ repository.name }}
      </option>
    </select>
    <button class="icon-button" type="button" title="刷新仓库" @click="repositoryStore.loadRepositories">
      <RefreshCw :size="17" />
    </button>
  </div>
</template>
