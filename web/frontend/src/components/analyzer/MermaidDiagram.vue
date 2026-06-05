<script setup lang="ts">
import CallChainGraph from './CallChainGraph.vue'

defineProps<{
  code: string
}>()
</script>

<template>
  <CallChainGraph :code="code" />
</template>
