<script setup lang="ts">
import CodeAtlasExplorer from '@/features/graph/CodeAtlasExplorer.vue';
import KnowledgeProjectSummary from '@/features/knowledge/KnowledgeProjectSummary.vue';
import { useRepositoryStore } from '@/stores/repositoryStore';
const repositories = useRepositoryStore();
</script>
<template><KnowledgeProjectSummary v-if="repositories.selectedRepository?.sourceType === 'KNOWLEDGE'" /><CodeAtlasExplorer v-else /></template>
