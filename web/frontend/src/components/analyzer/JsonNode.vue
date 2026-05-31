<script setup lang="ts">
import { ArrowRight, ArrowDown } from '@element-plus/icons-vue'
import { ElButton } from 'element-plus'
import { computed, shallowRef } from 'vue'
import type { JsonValue } from '../../types'

defineOptions({
  name: 'JsonNode'
})

const props = defineProps<{
  value: JsonValue
  label: string | number
  depth: number
}>()

const open = shallowRef(props.depth < 2)

const expandable = computed(() => props.value !== null && typeof props.value === 'object')

const entries = computed<[string | number, JsonValue][]>(() => {
  if (Array.isArray(props.value)) {
    return props.value.map((item, index) => [index, item])
  }
  if (expandable.value && props.value !== null && !Array.isArray(props.value)) {
    return Object.entries(props.value)
  }
  return []
})

const preview = computed(() => {
  if (Array.isArray(props.value)) return `Array(${props.value.length})`
  if (props.value !== null && typeof props.value === 'object') {
    return `Object(${Object.keys(props.value).length})`
  }
  if (typeof props.value === 'string') return `"${props.value}"`
  if (props.value === null) return 'null'
  return String(props.value)
})

const valueClass = computed(() => {
  if (props.value === null) return 'json-null'
  if (typeof props.value === 'number') return 'json-number'
  if (typeof props.value === 'boolean') return 'json-boolean'
  if (typeof props.value === 'string') return 'json-string'
  return 'json-object'
})

const nodeStyle = computed(() => ({
  '--depth': props.depth
}))

function toggle() {
  if (expandable.value) open.value = !open.value
}
</script>

<template>
  <div class="json-node" :style="nodeStyle">
    <div class="json-line">
      <ElButton
        v-if="expandable"
        class="json-toggle"
        text
        :icon="open ? ArrowDown : ArrowRight"
        :aria-label="open ? '折叠' : '展开'"
        @click="toggle"
      />
      <span v-else class="json-spacer"></span>
      <span class="json-key">{{ label }}</span>
      <span class="json-separator">:</span>
      <span :class="valueClass">{{ preview }}</span>
    </div>
    <div v-if="expandable && open" class="json-children">
      <JsonNode
        v-for="[childKey, childValue] in entries"
        :key="childKey"
        :label="childKey"
        :value="childValue"
        :depth="depth + 1"
      />
    </div>
  </div>
</template>
