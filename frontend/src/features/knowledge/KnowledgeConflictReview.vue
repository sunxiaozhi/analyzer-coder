<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue';
import { intelligenceApi, type CardInput, type KnowledgeCard } from '@/api/intelligence';
import { knowledgeCardInput } from './useKnowledgeCardEditor';
import { compareKnowledgeDraft, mergeKnowledgeDraft, type KnowledgeField } from './knowledgeConflict';
const props = defineProps<{ repositoryId: string; contextId?: string | null; base: KnowledgeCard; local: CardInput; disabled: boolean }>();
const emit = defineEmits<{ resolve: [input: CardInput, latest: KnowledgeCard] }>();
const latest = shallowRef<KnowledgeCard | null>(null);
const loading = shallowRef(false), error = shallowRef('');
const choices = shallowRef<Partial<Record<KnowledgeField, 'local' | 'latest'>>>({});
const labels: Record<KnowledgeField, string> = { title: '标题', content: '正文', tags: '标签', cardType: '卡片类型', knowledgeKind: '知识类型', severity: '严重级别', enforcement: '约束级别', ownerAccountId: '负责人', scope: '约束范围', obligations: '约束规则', branchScope: '共享分支', attachmentIds: '附件', codeReferences: '关联代码' };
const baseInput = computed(() => knowledgeCardInput(props.base));
const latestInput = computed(() => latest.value ? knowledgeCardInput(latest.value) : null);
const fields = computed(() => latestInput.value ? compareKnowledgeDraft(baseInput.value, props.local, latestInput.value).filter(field => field.changed) : []);
const unresolved = computed(() => fields.value.some(field => field.conflict && !choices.value[field.key]));
let revision = 0;
watch(() => [props.repositoryId, props.contextId, props.base.id], () => { revision++; latest.value = null; choices.value = {}; loading.value = false; error.value = ''; }, { flush: 'sync' });
onScopeDispose(() => { revision++; });
watch(() => props.local, () => { choices.value = {}; });
function show(value: unknown) { return typeof value === 'string' ? value : JSON.stringify(value ?? null, null, 2); }
async function loadLatest() {
  const version = ++revision;
  loading.value = true; error.value = ''; latest.value = null; choices.value = {};
  try {
    const cards = await intelligenceApi.cards(props.repositoryId, props.contextId);
    if (version !== revision) return;
    const card = cards.find(item => item.id === props.base.id);
    if (!card) throw new Error('知识已被删除或不再可见，当前输入仍保留。');
    latest.value = card;
  } catch (cause) { if (version === revision) error.value = cause instanceof Error ? cause.message : '最新修订读取失败'; }
  finally { if (version === revision) loading.value = false; }
}
function resolve() {
  if (!latest.value || !latestInput.value || props.disabled) return;
  const input = mergeKnowledgeDraft(baseInput.value, props.local, latestInput.value, choices.value);
  if (input) emit('resolve', input, latest.value);
}
</script>

<template>
  <section class="conflict-review" aria-label="编辑冲突对照">
    <el-button :loading="loading" :disabled="disabled" @click="loadLatest">{{ latest ? '重新读取最新修订' : '查看最新修订并对照' }}</el-button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="latest && latestInput">
      <p>编辑起点 v{{ base.revision }} · 最新 v{{ latest.revision }}。不同字段自动保留各自修改；同一字段双方都修改时，请选择要保留的内容。</p>
      <div class="conflict-fields">
        <div v-for="field in fields" :key="field.key" class="conflict-field">
          <strong>{{ labels[field.key] }}{{ field.conflict ? ' · 需要选择' : '' }}</strong>
          <div class="version-pair"><div><b>当前输入</b><pre>{{ show(local[field.key]) }}</pre></div><div><b>最新修订</b><pre>{{ show(latestInput[field.key]) }}</pre></div></div>
          <div v-if="field.conflict" class="field-choices">
            <label><input type="radio" :name="`conflict-${field.key}`" :checked="choices[field.key] === 'local'" :disabled="disabled" @change="choices = { ...choices, [field.key]: 'local' }" />保留当前输入</label>
            <label><input type="radio" :name="`conflict-${field.key}`" :checked="choices[field.key] === 'latest'" :disabled="disabled" @change="choices = { ...choices, [field.key]: 'latest' }" />采用最新修订</label>
          </div>
          <small v-else>将保留{{ field.preferred === 'local' ? '当前输入' : '最新修订' }}的修改。</small>
        </div>
      </div>
      <el-button type="primary" :disabled="disabled || unresolved" @click="resolve">应用对照结果并继续编辑</el-button>
      <small>应用后仍需保存为草稿；保存前发生的新冲突会再次拦截。</small>
    </template>
  </section>
</template>

<style scoped>
.conflict-review { margin:12px 0; text-align:left; font-size:13px; }.conflict-review p { line-height:1.7; }.conflict-fields { max-height:35vh; overflow:auto; }.conflict-field { padding:12px 0; border-top:1px solid var(--app-border); }.version-pair { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin:8px 0; }.version-pair pre { max-height:180px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; padding:8px; background:var(--app-color-bg-soft,#f4f6f8); font:12px/1.6 monospace; }.field-choices { display:flex; flex-wrap:wrap; gap:12px; }.conflict-review small { display:block; color:var(--app-text-muted); margin-top:6px; }@media(max-width:760px) { .version-pair { grid-template-columns:1fr; } }
</style>
