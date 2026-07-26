<script setup lang="ts">
import type { IndexJobStatus } from '@/types/api';
import { statusLabel } from '@/utils/displayLabels';

defineProps<{
  status: IndexJobStatus;
}>();
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">{{ statusLabel(status) }}</span>
</template>
