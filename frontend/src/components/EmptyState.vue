<script setup lang="ts">
import { Inbox } from 'lucide-vue-next';

defineProps<{
  title: string;
}>();
</script>

<template>
  <div class="empty-state">
    <Inbox :size="24" />
    <span>{{ title }}</span>
  </div>
</template>
