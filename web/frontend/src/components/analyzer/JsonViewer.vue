<script setup lang="ts">
import { ElScrollbar } from 'element-plus'
import type { JsonValue } from '../../types'
import JsonNode from './JsonNode.vue'

defineProps<{
  value: JsonValue
}>()
</script>

<template>
  <ElScrollbar class="json-viewer">
    <JsonNode :value="value" label="root" :depth="0" />
  </ElScrollbar>
</template>
