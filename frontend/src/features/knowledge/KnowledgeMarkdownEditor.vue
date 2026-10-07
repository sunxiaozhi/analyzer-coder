<script setup lang="ts">
import { shallowRef } from 'vue';
import MarkdownPreview from '@/components/MarkdownPreview.vue';

const props = defineProps<{ modelValue: string; repositoryId: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const tab = shallowRef<'write' | 'preview'>('write');
</script>

<template>
  <div class="markdown-editor">
    <div class="editor-tabs" role="group" aria-label="正文编辑模式">
      <button type="button" :class="{ active: tab === 'write' }" :aria-pressed="tab === 'write'" @click="tab='write'">编写</button>
      <button type="button" :class="{ active: tab === 'preview' }" :aria-pressed="tab === 'preview'" @click="tab='preview'">预览</button>
      <span>支持标题、列表、引用、代码块、链接与图片</span>
    </div>
    <el-input v-if="tab==='write'" :model-value="modelValue" type="textarea" :rows="20" aria-label="知识正文"
              placeholder="使用 Markdown 编写知识正文…" @update:model-value="emit('update:modelValue', $event)" />
    <MarkdownPreview v-else class="markdown-preview" :content="modelValue" :repository-id="repositoryId" embedded />
  </div>
</template>

<style scoped>
.markdown-editor{border:1px solid var(--el-border-color);border-radius:10px;overflow:hidden}
.editor-tabs{display:flex;align-items:center;gap:4px;padding:7px 10px;background:var(--el-fill-color-light);border-bottom:1px solid var(--el-border-color)}
.editor-tabs button{border:0;background:transparent;padding:6px 12px;border-radius:7px;cursor:pointer;color:var(--el-text-color-secondary)}
.editor-tabs button.active{background:var(--app-selection-bg);color:var(--el-color-primary);box-shadow:inset 0 0 0 1px var(--app-selection-border)}
.editor-tabs span{margin-left:auto;font-size:14px;color:var(--el-text-color-placeholder)}
.markdown-editor :deep(.el-textarea__inner){border:0;box-shadow:none;border-radius:0;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}
.markdown-preview{min-height:440px;padding:16px 20px;line-height:1.7;background:var(--el-bg-color)}
@media (max-width: 760px) { .editor-tabs span { display: none; } }
</style>
