<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue';
import { branchesApi, type BranchContext, type BranchValidationCard, type BranchValidationState } from '@/api/branches';

const props = defineProps<{ context: BranchContext; canManage: boolean; cardId?: string; cardRevision?: number }>();
const emit = defineEmits<{ saved: [] }>();
const cards = shallowRef<BranchValidationCard[]>([]);
const selectedId = shallowRef('');
const filter = shallowRef('');
const state = shallowRef<BranchValidationState>('UNVERIFIED');
const note = shallowRef('');
const busy = shallowRef(false);
const error = shallowRef('');
const saved = shallowRef(false);
let sequence = 0;
const labels: Record<BranchValidationState, string> = { CURRENT: '已验证', UNVERIFIED: '未验证', REVIEW_REQUIRED: '待复核', INVALID: '不适用' };
const selected = computed(() => cards.value.find(card => card.cardId === selectedId.value));
const filtered = computed(() => cards.value.filter(card => card.title.toLowerCase().includes(filter.value.trim().toLowerCase())));
watch(selected, card => { state.value = card?.state ?? 'UNVERIFIED'; note.value = card?.note ?? ''; saved.value = false; }, { flush: 'sync' });
async function load() {
  const version = ++sequence;
  busy.value = true; error.value = ''; saved.value = false;
  cards.value = []; selectedId.value = '';
  try {
    const rows = await branchesApi.validations(props.context);
    if (version === sequence) {
      cards.value = props.cardId ? rows.filter(card => card.cardId === props.cardId && card.revision === props.cardRevision) : rows;
      if (props.cardId) selectedId.value = cards.value[0]?.cardId ?? '';
    }
  } catch (cause) { if (version === sequence) error.value = cause instanceof Error ? cause.message : '加载验证记录失败'; }
  finally { if (version === sequence) busy.value = false; }
}
async function save() {
  if (!props.canManage || !selected.value || !note.value.trim() || busy.value) return;
  const version = sequence;
  const card = selected.value;
  const nextState = state.value;
  const nextNote = note.value.trim();
  busy.value = true; error.value = ''; saved.value = false;
  try {
    await branchesApi.validate(props.context, card, nextState, nextNote);
    if (version !== sequence) return;
    cards.value = cards.value.map(row => row.cardId === card.cardId ? { ...row, state: nextState, note: nextNote } : row);
    // Updating the selected row resets its editor; announce success after that watcher.
    saved.value = true;
    emit('saved');
  } catch (cause) { if (version === sequence) error.value = cause instanceof Error ? cause.message : '保存验证结果失败，请刷新知识后重试'; }
  finally { if (version === sequence) busy.value = false; }
}
watch(() => [props.context.contextId, props.cardId, props.cardRevision], load, { immediate: true });
onBeforeUnmount(() => { ++sequence; });
</script>

<template>
  <details class="validation-panel">
    <summary>知识分支验证 <span>{{ cards.length }} 条适用知识</span></summary>
    <div class="validation-body">
      <p class="validation-target">验证目标 <strong>{{ context.branchName }}</strong> <code>{{ context.commitSha.slice(0, 12) }}</code></p>
      <p class="validation-hint">对照当前版本检查知识内容后记录结论。更新知识或代码版本后需要重新验证；检索仍使用当前阅读版本，请刷新阅读版本以采用新结论。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="saved" title="验证结果已保存" type="success" :closable="false" />
      <el-input v-if="!cardId" v-model="filter" placeholder="筛选知识标题" aria-label="筛选待验证知识" clearable />
      <el-select v-if="!cardId" v-model="selectedId" placeholder="选择要验证的知识" aria-label="待验证知识" :disabled="busy" style="width: 100%">
        <el-option v-for="card in filtered" :key="card.cardId" :value="card.cardId" :label="`${card.title} · r${card.revision} · ${labels[card.state]}`" />
      </el-select>
      <el-button link :disabled="busy" @click="load">刷新知识与验证记录</el-button>
      <p v-if="!busy && !cards.length" class="validation-hint">当前分支没有匹配的适用知识修订。请刷新知识或检查适用分支。</p>
      <template v-if="selected">
        <pre class="validation-content">{{ selected.content }}</pre>
        <el-form label-position="top" :disabled="busy || !canManage" @submit.prevent="save">
          <el-form-item label="验证结论">
            <el-select v-model="state" aria-label="验证结论">
              <el-option v-for="(label, value) in labels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="验证说明（必填）">
            <el-input v-model="note" type="textarea" :rows="3" :maxlength="2000" show-word-limit aria-label="验证说明" placeholder="填写检查的代码位置、依据及需要修正的内容" />
          </el-form-item>
          <el-button v-if="canManage" native-type="submit" type="primary" :loading="busy" :disabled="!note.trim()">保存验证结果</el-button>
        </el-form>
        <p v-if="!canManage" class="validation-hint">当前权限仅可查看，保存验证结果需要仓库管理权限。</p>
      </template>
    </div>
  </details>
</template>

<style scoped>
.validation-panel { border-top: 1px solid #dbe3ec; color: #334155; }
.validation-panel summary { padding: 14px 0; cursor: pointer; font-weight: 600; }
.validation-panel summary span { color: #68778a; font-size: 12px; font-weight: 400; margin-left: 12px; }
.validation-panel summary:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.validation-body { display: flex; flex-direction: column; gap: 12px; }
.validation-target { margin: 0; border-left: 3px solid #2563eb; padding: 8px 12px; background: #eff6ff; overflow-wrap: anywhere; }
.validation-target strong { margin: 0 8px; }
.validation-target code, .validation-content { font-family: Consolas, monospace; }
.validation-hint { margin: 0; color: #68778a; font-size: 12px; line-height: 1.7; }
.validation-content { max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; padding: 12px; background: #f5f7fa; font-size: 12px; line-height: 1.7; }
</style>
