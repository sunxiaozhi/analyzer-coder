<script setup lang="ts">
import type { IndexJobStatus } from '@/types/api';

defineProps<{
  status: IndexJobStatus;
}>();
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">{{ status }}</span>
</template>
