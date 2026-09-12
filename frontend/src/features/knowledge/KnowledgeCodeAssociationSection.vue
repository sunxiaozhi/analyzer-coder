<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import type { CodeReference } from '@/api/intelligence';
import KnowledgeCodeReferenceSelector from './KnowledgeCodeReferenceSelector.vue';

defineProps<{ repositoryId: string }>();
const emit = defineEmits<{ openCode: [reference: CodeReference] }>();

const references = defineModel<CodeReference[]>('references', { required: true });
const paths = defineModel<string>('paths', { required: true });
const symbols = defineModel<string>('symbols', { required: true });
const advancedSections = shallowRef<string[]>([]);
const referenceSummary = computed(() => references.value.length
  ? `已绑定 ${references.value.length} 处代码`
  : '尚未绑定代码');

watch(
  () => [paths.value, symbols.value] as const,
  ([pathValue, symbolValue]) => {
    if ((pathValue.trim() || symbolValue.trim()) && !advancedSections.value.includes('scope')) {
      advancedSections.value = ['scope'];
    }
  },
  { immediate: true },
);
</script>

<template>
  <section class="editor-section association-section">
    <header class="section-heading source-heading">
      <div>
        <h3>关联代码</h3>
        <p>绑定具体实现后，可以从知识返回源码，并检测引用内容是否发生变化。</p>
      </div>
      <span :data-active="references.length > 0">{{ referenceSummary }}</span>
    </header>

    <KnowledgeCodeReferenceSelector
      v-model="references"
      :repository-id="repositoryId"
      @open-code="emit('openCode', $event)"
    />

    <el-collapse v-model="advancedSections" class="advanced-scope">
      <el-collapse-item name="scope">
        <template #title>
          <div class="advanced-title">
            <b>扩大适用范围</b>
            <small>可选；让知识覆盖一组路径或同名代码符号</small>
          </div>
        </template>
        <div class="scope-grid">
          <el-form-item label="路径规则">
            <el-input v-model="paths" type="textarea" :rows="4" placeholder="backend/src/**/refund/**" />
            <small>每行一项，使用仓库相对通配规则。</small>
          </el-form-item>
          <el-form-item label="符号">
            <el-input
              v-model="symbols"
              type="textarea"
              :rows="4"
              placeholder="RefundService&#10;approveRefund"
            />
            <small>每行一项，与类、函数或方法名精确匹配。</small>
          </el-form-item>
        </div>
      </el-collapse-item>
    </el-collapse>
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
.source-heading > span[data-active='true'] { color: #1f668f; border-color: #a9cce1; background: #edf7fc; }
.advanced-scope { margin-top: 18px; border-top-color: #dce4e9; border-bottom: 0; }
.advanced-title { display: grid; line-height: 1.35; }
.advanced-title b { color: #34444f; font-size: 14px; }
.advanced-title small { color: #74838e; font-size: 13px; font-weight: 400; }
.scope-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding: 4px 2px 0; }
.scope-grid small { display: block; margin-top: 5px; color: var(--el-text-color-secondary); line-height: 1.45; }
@media (max-width: 760px) {
  .editor-section { padding: 14px; }
  .source-heading { align-items: flex-start; flex-direction: column; gap: 10px; }
  .scope-grid { grid-template-columns: 1fr; gap: 0; }
}
</style>
