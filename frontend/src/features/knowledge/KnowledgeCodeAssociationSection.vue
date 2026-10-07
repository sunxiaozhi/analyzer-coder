<script setup lang="ts">
import { computed } from 'vue';
import type { CodeReference } from '@/api/intelligence';
import KnowledgeCodeReferenceSelector from './KnowledgeCodeReferenceSelector.vue';

defineProps<{ repositoryId: string; contextId?: string | null }>();
const emit = defineEmits<{ openCode: [reference: CodeReference] }>();

const references = defineModel<CodeReference[]>('references', { required: true });
const referenceSummary = computed(() => references.value.length
  ? `已绑定 ${references.value.length} 处代码`
  : '尚未绑定代码');

</script>

<template>
  <section class="editor-section association-section">
    <header class="section-heading source-heading">
      <div>
        <h3>关联代码</h3>
        <p>可选。关联具体实现，方便从知识返回源码；业务经验和操作说明也可以直接录入。</p>
      </div>
      <span :data-active="references.length > 0">{{ referenceSummary }}</span>
    </header>

    <KnowledgeCodeReferenceSelector
      v-model="references"
      :repository-id="repositoryId"
      :context-id="contextId"
      @open-code="emit('openCode', $event)"
    />


  </section>
</template>

<style scoped>
.editor-section {
  padding: 18px;
  border: 1px solid #d9e2e8;
  border-radius: 8px;
  background: #fff;
}
.section-heading { margin-bottom: 16px; }
.section-heading h3 { margin: 0; color: #25313c; font-size: 17px; }
.section-heading p { margin: 4px 0 0; color: #6d7d89; font-size: 14px; line-height: 1.55; }
.source-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.source-heading > span {
  flex: none;
  padding: 5px 9px;
  color: #657480;
  border: 1px solid #cbd7df;
  border-radius: 999px;
  background: #f7f9fa;
  font-size: 13px;
}
.source-heading > span[data-active='true'] { color: var(--app-selection-text); border-color: var(--app-color-action); background: var(--app-selection-bg); }
@media (max-width: 760px) {
  .editor-section { padding: 14px; }
  .source-heading { align-items: flex-start; flex-direction: column; gap: 10px; }
}
</style>
