<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { ElEmpty } from 'element-plus'

const props = defineProps<{
  code: string
}>()

const svg = shallowRef('')
const errorMessage = shallowRef('')
const rendering = shallowRef(false)

let renderSequence = 0
let mermaidInitialized = false

watch(
  () => props.code,
  async (code) => {
    if (!code.trim()) {
      svg.value = ''
      errorMessage.value = ''
      rendering.value = false
      return
    }

    const renderId = ++renderSequence
    rendering.value = true
    errorMessage.value = ''

    try {
      const { default: mermaid } = await import('mermaid')
      if (!mermaidInitialized) {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          theme: 'base',
          flowchart: {
            curve: 'basis',
            htmlLabels: false,
            useMaxWidth: true
          }
        })
        mermaidInitialized = true
      }

      const result = await mermaid.render(`analyzer-mermaid-${renderId}`, code)
      if (renderId === renderSequence) {
        svg.value = result.svg
      }
    } catch (error) {
      if (renderId === renderSequence) {
        svg.value = ''
        errorMessage.value = error instanceof Error ? error.message : 'Mermaid 图渲染失败'
      }
    } finally {
      if (renderId === renderSequence) {
        rendering.value = false
      }
    }
  },
  { immediate: true }
)
</script>

<template>
  <ElEmpty v-if="rendering" description="正在渲染 Mermaid 图。" />
  <ElEmpty v-else-if="errorMessage" :description="errorMessage" />
  <div v-else class="mermaid-diagram" v-html="svg"></div>
</template>

<style scoped>
.mermaid-diagram {
  align-items: center;
  display: flex;
  justify-content: center;
  min-height: 100%;
  min-width: 100%;
  overflow: auto;
}

.mermaid-diagram :deep(svg) {
  height: auto;
  max-width: 100%;
}
</style>
