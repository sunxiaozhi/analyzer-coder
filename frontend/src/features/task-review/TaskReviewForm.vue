<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ChevronDown, GitBranch, GitCommitHorizontal, MonitorDot, Play, ShieldCheck } from 'lucide-vue-next';
import type { AskModel } from '@/api/intelligence';
import type { ChangeSource } from '@/api/taskReviews';

export interface TaskReviewDraft {
  task: string | null;
  changeSource: ChangeSource;
  baseRef: string | null;
  headRef: string | null;
  modelConfigId: string | null;
}

const props = defineProps<{
  loading: boolean;
  disabled?: boolean;
  initialDraft?: Partial<TaskReviewDraft>;
  models?: AskModel[];
  modelsLoading?: boolean;
  repositoryName?: string;
  repositoryCommit?: string | null;
  snapshotId?: string | null;
}>();
const emit = defineEmits<{ submit: [draft: TaskReviewDraft] }>();

const task = shallowRef(props.initialDraft?.task ?? '');
const source = shallowRef<ChangeSource>(props.initialDraft?.changeSource ?? 'WORKTREE');
const baseRef = shallowRef(props.initialDraft?.baseRef ?? (source.value === 'COMMIT_RANGE' ? 'HEAD~1' : source.value === 'WORKTREE' ? 'HEAD' : ''));
const headRef = shallowRef(props.initialDraft?.headRef ?? (source.value === 'WORKTREE' ? '' : 'HEAD'));
const modelConfigId = shallowRef(props.initialDraft?.modelConfigId ?? '');

const sourceOptions: { value: ChangeSource; label: string; detail: string; icon: typeof MonitorDot }[] = [
  { value: 'WORKTREE', label: '检查未提交改动', detail: '暂存、未暂存和未跟踪文件', icon: MonitorDot },
  { value: 'SINGLE_COMMIT', label: '检查一次提交', detail: '默认检查最近一次提交', icon: GitCommitHorizontal },
  { value: 'COMMIT_RANGE', label: '比较两个版本', detail: '适合功能分支或发布范围', icon: GitBranch },
];

const canSubmit = computed(() => {
  if (props.loading || props.disabled) return false;
  if (source.value === 'SINGLE_COMMIT') return Boolean(headRef.value.trim());
  if (source.value === 'COMMIT_RANGE') return Boolean(baseRef.value.trim() && headRef.value.trim());
  return true;
});
const rangeLabel = computed(() => {
  if (source.value === 'WORKTREE') return `${props.repositoryCommit?.slice(0, 8) || baseRef.value || 'HEAD'} → 当前工作区`;
  if (source.value === 'SINGLE_COMMIT') return `${headRef.value || '待选择'} 的单次提交`;
  return `${baseRef.value || '待选择'} → ${headRef.value || '待选择'}`;
});
const actionLabel = computed(() => ({
  WORKTREE: '审查未提交改动',
  SINGLE_COMMIT: '审查这次提交',
  COMMIT_RANGE: '审查这个版本范围',
}[source.value]));

watch(source, (value) => {
  if (value === 'WORKTREE') {
    baseRef.value = 'HEAD';
    headRef.value = '';
  } else if (value === 'SINGLE_COMMIT') {
    baseRef.value = '';
    headRef.value = 'HEAD';
  } else {
    baseRef.value = 'HEAD~1';
    headRef.value = 'HEAD';
  }
});

function submit() {
  if (!canSubmit.value) return;
  emit('submit', {
    task: task.value.trim() || null,
    changeSource: source.value,
    baseRef: baseRef.value.trim() || null,
    headRef: headRef.value.trim() || null,
    modelConfigId: modelConfigId.value || null,
  });
}
</script>

<template>
  <section class="review-form" aria-labelledby="review-form-title">
    <header class="form-heading">
      <span class="step-badge">第 1 步</span>
      <div>
        <h2 id="review-form-title">你要审查哪一段改动？</h2>
        <p>选择你当前正在处理的开发场景。系统只读取对应的真实 Git 改动。</p>
      </div>
    </header>

    <fieldset class="source-field">
      <legend class="sr-only">选择审查场景</legend>
      <button v-for="item in sourceOptions" :key="item.value" type="button" :class="{ active: source === item.value }" :aria-pressed="source === item.value" @click="source = item.value">
        <component :is="item.icon" :size="19" />
        <span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span>
        <i aria-hidden="true" />
      </button>
    </fieldset>

    <div v-if="source !== 'WORKTREE'" class="ref-fields">
      <label v-if="source === 'COMMIT_RANGE'">
        <span>从哪个版本开始</span>
        <input v-model="baseRef" class="mono" maxlength="200" placeholder="例如 main 或 HEAD~1" />
      </label>
      <span v-if="source === 'COMMIT_RANGE'" class="ref-arrow">→</span>
      <label>
        <span>{{ source === 'SINGLE_COMMIT' ? '要检查的提交' : '检查到哪个版本' }}</span>
        <input v-model="headRef" class="mono" maxlength="200" placeholder="例如 HEAD 或提交 SHA" />
      </label>
    </div>

    <section class="scope-confirmation" aria-labelledby="scope-confirmation-title">
      <span class="step-badge">第 2 步</span>
      <div class="scope-copy">
        <h3 id="scope-confirmation-title">确认本次审查范围</h3>
        <p>开始后会读取 Git 差异，并将改动与当前已发布知识基线进行匹配。</p>
      </div>
      <dl>
        <div><dt>项目</dt><dd>{{ repositoryName || '当前项目' }}</dd></div>
        <div><dt>代码范围</dt><dd class="mono">{{ rangeLabel }}</dd></div>
        <div><dt>知识基线</dt><dd class="mono">{{ snapshotId?.slice(0, 8) || '未发布' }}</dd></div>
      </dl>
    </section>

    <label class="task-field">
      <span>这次改动是做什么的？ <small>可选，用于查找参考知识</small></span>
      <textarea v-model="task" rows="2" maxlength="2000" placeholder="例如：增加退款人工审批，并核对必须执行的测试和负责人" @keydown.ctrl.enter="submit" />
    </label>

    <details class="advanced-settings">
      <summary><ChevronDown :size="15" />高级设置 <small>模型只总结已有证据</small></summary>
      <label class="model-field">
        <span>证据总结模型</span>
        <select v-model="modelConfigId" :disabled="modelsLoading">
          <option value="">不使用模型总结（更快）</option>
          <option v-for="item in models ?? []" :key="item.id" :value="item.id" :disabled="!item.available">{{ item.name }} / {{ item.model }}{{ item.available ? '' : '（不可用）' }}</option>
        </select>
        <small v-if="modelsLoading">正在读取可用模型…</small>
        <small v-else>每条模型建议都必须引用已有证据；模型不会产生测试或审批要求。</small>
      </label>
    </details>

    <div v-if="source === 'WORKTREE'" class="worktree-note" role="note">
      <ShieldCheck :size="17" />
      <span><strong>审查期间请保持文件不变</strong>系统会核对前后的工作区摘要；版本变化时会停止本次审查。</span>
    </div>

    <footer>
      <span>Ctrl + Enter 也可开始</span>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="submit"><Play :size="14" />{{ actionLabel }}</el-button>
    </footer>
  </section>
</template>

<style scoped>
.review-form { display: grid; gap: 18px; padding: 22px; border: 1px solid #d7e0e5; border-radius: 10px; background: #fff; }
.form-heading { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: 11px; }
.form-heading div, .scope-copy { display: grid; gap: 3px; }
.form-heading h2, .scope-copy h3 { margin: 0; color: #20303a; font-size: 19px; }
.form-heading p, .scope-copy p { margin: 0; color: #6f7e88; font-size: 13px; line-height: 1.5; }
.step-badge { padding: 4px 7px; color: #315e7c; border: 1px solid #bfd1dd; border-radius: 4px; background: #eef5f9; font: 700 12px "SFMono-Regular", Consolas, monospace; white-space: nowrap; }
.source-field { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; margin: 0; padding: 0; border: 0; }
.source-field button { position: relative; display: grid; grid-template-columns: 24px minmax(0, 1fr) 13px; align-items: center; gap: 9px; min-height: 72px; padding: 12px; color: #647681; text-align: left; border: 1px solid #d8e1e6; border-radius: 8px; background: #fbfcfd; }
.source-field button span { display: grid; gap: 4px; }
.source-field button strong { color: #2d3d47; font-size: 14px; }
.source-field button small { font-size: 12px; line-height: 1.35; }
.source-field button i { width: 11px; height: 11px; border: 2px solid #b3c0c8; border-radius: 50%; }
.source-field button.active { color: var(--app-color-action); border-color: #7ea9c7; box-shadow: inset 0 -3px #4d85ac; background: #f2f8fc; }
.source-field button.active i { border: 3px solid #fff; box-shadow: 0 0 0 2px var(--app-color-action); background: var(--app-color-action); }
.source-field button:focus-visible, summary:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.ref-fields { display: grid; grid-template-columns: minmax(0, 1fr) 32px minmax(0, 1fr); align-items: end; gap: 8px; }
label { display: grid; gap: 7px; }
label > span { color: #5f707b; font-size: 13px; font-weight: 700; }
label small { color: #89969e; font-weight: 500; }
.ref-arrow { padding-bottom: 10px; color: #84919a; text-align: center; }
textarea, input, select { width: 100%; color: #1f2a33; border: 1px solid #cad5dc; border-radius: 7px; outline: none; background: #fbfcfd; }
textarea { min-height: 68px; padding: 11px 12px; resize: vertical; line-height: 1.55; }
input, select { height: 40px; padding: 0 10px; }
textarea:focus, input:focus, select:focus { border-color: var(--app-color-action); box-shadow: 0 0 0 3px var(--app-focus-ring); }
.scope-confirmation { display: grid; grid-template-columns: auto minmax(180px, .8fr) minmax(420px, 1.2fr); align-items: start; gap: 11px; padding: 14px; border: 1px solid #dce5e9; border-left: 4px solid var(--app-color-action); border-radius: 7px; background: #f7fafc; }
.scope-copy h3 { font-size: 15px; }
.scope-confirmation dl { display: grid; grid-template-columns: .8fr 1.3fr .7fr; margin: 0; border-left: 1px solid #dbe4e8; }
.scope-confirmation dl div { min-width: 0; padding: 0 11px; }
.scope-confirmation dt { color: #7d8b94; font-size: 11px; }
.scope-confirmation dd { overflow: hidden; margin: 4px 0 0; color: #34444e; font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.advanced-settings { border-top: 1px solid #e7ecef; border-bottom: 1px solid #e7ecef; }
.advanced-settings summary { display: flex; align-items: center; gap: 6px; padding: 11px 2px; color: #566872; font-size: 13px; font-weight: 700; cursor: pointer; list-style: none; }
.advanced-settings summary::-webkit-details-marker { display: none; }
.advanced-settings summary svg { transition: transform .18s ease; }
.advanced-settings[open] summary svg { transform: rotate(180deg); }
.advanced-settings summary small { color: #8a969e; font-weight: 500; }
.model-field { padding: 0 2px 13px; }
.model-field > small { color: #788790; font-size: 12px; line-height: 1.45; }
.worktree-note { display: grid; grid-template-columns: 21px minmax(0, 1fr); gap: 8px; padding: 10px 12px; color: #72501f; border-left: 3px solid var(--app-color-warning); background: #fff8ee; font-size: 13px; line-height: 1.5; }
.worktree-note span { display: grid; gap: 1px; }
.review-form > footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.review-form > footer > span { color: #929da5; font: 12px "SFMono-Regular", Consolas, monospace; }
.sr-only { position: absolute; overflow: hidden; width: 1px; height: 1px; padding: 0; margin: -1px; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.mono { font-family: "SFMono-Regular", Consolas, monospace; }
@media (prefers-reduced-motion: reduce) { .advanced-settings summary svg { transition: none; } }
@media (max-width: 980px) { .scope-confirmation { grid-template-columns: auto 1fr; } .scope-confirmation dl { grid-column: 2; border-top: 1px solid #dbe4e8; border-left: 0; padding-top: 10px; } }
@media (max-width: 760px) { .review-form { padding: 15px; } .source-field, .ref-fields { grid-template-columns: 1fr; } .ref-arrow { display: none; } .scope-confirmation dl { grid-template-columns: 1fr; gap: 8px; } .scope-confirmation dl div { padding: 0; } .review-form > footer { align-items: stretch; flex-direction: column; } .review-form > footer > span { text-align: right; } }
</style>
