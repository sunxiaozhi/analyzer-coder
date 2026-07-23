<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { renderMarkdown } from './markdown';

const props = defineProps<{ modelValue: string; repositoryId: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const tab = shallowRef<'write' | 'preview'>('write');
const html = computed(() => renderMarkdown(props.modelValue, props.repositoryId));
</script>

<template>
  <div class="markdown-editor">
    <div class="editor-tabs">
      <button type="button" :class="{ active: tab === 'write' }" @click="tab='write'">编写</button>
      <button type="button" :class="{ active: tab === 'preview' }" @click="tab='preview'">预览</button>
      <span>支持标题、列表、引用、代码块、链接与图片</span>
    </div>
    <el-input v-if="tab==='write'" :model-value="modelValue" type="textarea" :rows="14"
              placeholder="使用 Markdown 编写知识正文…" @update:model-value="emit('update:modelValue', $event)" />
    <div v-else class="markdown-preview" v-html="html" />
  </div>
</template>

<style scoped>
.markdown-editor{border:1px solid var(--el-border-color);border-radius:10px;overflow:hidden}
.editor-tabs{display:flex;align-items:center;gap:4px;padding:7px 10px;background:var(--el-fill-color-light);border-bottom:1px solid var(--el-border-color)}
.editor-tabs button{border:0;background:transparent;padding:6px 12px;border-radius:7px;cursor:pointer;color:var(--el-text-color-secondary)}
.editor-tabs button.active{background:var(--el-bg-color);color:var(--el-color-primary);box-shadow:0 1px 3px rgba(0,0,0,.08)}
.editor-tabs span{margin-left:auto;font-size:12px;color:var(--el-text-color-placeholder)}
.markdown-editor :deep(.el-textarea__inner){border:0;box-shadow:none;border-radius:0;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}
.markdown-preview{min-height:304px;max-height:440px;overflow:auto;padding:16px 20px;line-height:1.7;background:var(--el-bg-color)}
.markdown-preview :deep(img){max-width:100%;border-radius:8px}
.markdown-preview :deep(pre){overflow:auto;padding:12px;border-radius:8px;background:#18212f;color:#e6edf3}
.markdown-preview :deep(blockquote){margin:10px 0;padding-left:14px;border-left:3px solid var(--el-color-primary);color:var(--el-text-color-secondary)}
</style>
