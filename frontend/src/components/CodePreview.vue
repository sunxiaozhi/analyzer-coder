<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue';
import type { CodeChunk } from '@/types/api';

defineProps<{
  chunk: CodeChunk | null;
}>();
</script>

<template>
  <section class="code-preview">
    <div v-if="chunk" class="code-preview-header">
      <div>
        <h2>{{ chunk.filePath }}</h2>
        <p>{{ chunk.language ?? 'text' }} · {{ chunk.startLine ?? 1 }}-{{ chunk.endLine ?? 1 }}</p>
      </div>
      <span class="mono hash">{{ chunk.contentHash.slice(0, 12) }}</span>
    </div>
    <pre v-if="chunk" class="code-block"><code>{{ chunk.content }}</code></pre>
    <EmptyState v-else title="未选择代码片段" />
  </section>
</template>
