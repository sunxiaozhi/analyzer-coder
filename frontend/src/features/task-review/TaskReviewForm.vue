<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { GitBranch, GitCommitHorizontal, MonitorDot, Play } from 'lucide-vue-next';
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
}>();
const emit = defineEmits<{
  submit: [draft: TaskReviewDraft];
}>();

const task = shallowRef(props.initialDraft?.task ?? '');
const source = shallowRef<ChangeSource>(props.initialDraft?.changeSource ?? 'WORKTREE');
const baseRef = shallowRef(props.initialDraft?.baseRef ?? (source.value === 'COMMIT_RANGE' ? 'HEAD~1' : source.value === 'WORKTREE' ? 'HEAD' : ''));
const headRef = shallowRef(props.initialDraft?.headRef ?? (source.value === 'WORKTREE' ? '' : 'HEAD'));
const modelConfigId = shallowRef(props.initialDraft?.modelConfigId ?? '');

const sourceOptions: { value: ChangeSource; label: string; detail: string; icon: typeof MonitorDot }[] = [
  { value: 'WORKTREE', label: '工作区', detail: '暂存、未暂存和未跟踪文件', icon: MonitorDot },
  { value: 'SINGLE_COMMIT', label: '单 Commit', detail: '核对一个已提交版本', icon: GitCommitHorizontal },
  { value: 'COMMIT_RANGE', label: 'Commit Range', detail: '比较两个提交之间的变化', icon: GitBranch },
];

const canSubmit = computed(() => {
  if (props.loading || props.disabled) return false;
  if (source.value === 'SINGLE_COMMIT') return Boolean(headRef.value.trim());
  if (source.value === 'COMMIT_RANGE') return Boolean(baseRef.value.trim() && headRef.value.trim());
  return true;
});

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
    <header>
      <div>
        <span>新建审查</span>
        <h2 id="review-form-title">选择真实代码范围</h2>
      </div>
      <p>任务描述只用于召回参考知识，正式结论始终来自 Git、代码和已审核规则。</p>
    </header>

    <label class="task-field">
      <span>任务描述 <small>可选</small></span>
      <textarea
        v-model="task"
        rows="2"
        maxlength="2000"
        placeholder="例如：增加退款人工审批，并核对必须执行的测试和负责人"
        @keydown.ctrl.enter="submit"
      />
    </label>

    <label class="model-field">
      <span>引用总结 <small>可选，不改变确定性结论</small></span>
      <select v-model="modelConfigId" :disabled="modelsLoading">
        <option value="">不使用模型总结（更快）</option>
        <option v-for="item in models ?? []" :key="item.id" :value="item.id" :disabled="!item.available">
          {{ item.name }} / {{ item.model }}{{ item.available ? '' : '（不可用）' }}
        </option>
      </select>
      <small v-if="modelsLoading">正在读取可用模型…</small>
      <small v-else>模型只能总结已完成审查，并且每条建议必须引用现有证据 ID。</small>
    </label>

    <fieldset class="source-field">
      <legend>审查范围</legend>
      <button
        v-for="item in sourceOptions"
        :key="item.value"
        type="button"
        :class="{ active: source === item.value }"
        :aria-pressed="source === item.value"
        @click="source = item.value"
      >
        <component :is="item.icon" :size="16" />
        <span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span>
      </button>
    </fieldset>

    <div v-if="source !== 'WORKTREE'" class="ref-fields">
      <label v-if="source === 'COMMIT_RANGE'">
        <span>Base ref</span>
        <input v-model="baseRef" class="mono" maxlength="200" placeholder="HEAD~1" />
      </label>
      <span v-if="source === 'COMMIT_RANGE'" class="ref-arrow">→</span>
      <label>
        <span>{{ source === 'SINGLE_COMMIT' ? 'Commit ref' : 'Head ref' }}</span>
        <input v-model="headRef" class="mono" maxlength="200" placeholder="HEAD" />
      </label>
    </div>

    <div v-if="source === 'WORKTREE'" class="worktree-note" role="note">
      <MonitorDot :size="16" />
      <span><strong>分析期间不要修改文件</strong>系统会在前后核对工作区摘要，版本变化时本次审查会失败，不会混合数据。</span>
    </div>

    <footer>
      <span>Ctrl + Enter</span>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="submit">
        <Play :size="14" />开始审查
      </el-button>
    </footer>
  </section>
</template>

<style scoped>
.review-form { display: grid; gap: 16px; padding: 20px; border: 1px solid #dbe2e7; border-radius: 10px; background: #fff; }
.review-form > header { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 14px; border-bottom: 1px solid #e7ecef; }
.review-form > header span, legend, label > span { color: #687782; font-size: 13px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }
.review-form h2 { margin: 4px 0 0; color: #1f2a33; font-size: 18px; font-weight: 680; }
.review-form > header p { max-width: 490px; margin: 0; color: #687782; font-size: 14px; line-height: 1.55; text-align: right; }
.task-field { display: grid; gap: 7px; }
.task-field small { margin-left: 5px; color: #98a3ab; font-weight: 500; text-transform: none; }
textarea, input, select { width: 100%; color: #1f2a33; border: 1px solid #cfd8de; border-radius: 7px; outline: none; background: #fbfcfd; transition: border-color .16s, box-shadow .16s; }
textarea { min-height: 68px; padding: 11px 12px; resize: vertical; line-height: 1.55; }
input, select { height: 38px; padding: 0 10px; }
textarea:focus, input:focus, select:focus { border-color: var(--app-color-action); box-shadow: 0 0 0 3px var(--app-focus-ring); }
.model-field { display: grid; gap: 7px; }
.model-field > small { color: #7b8790; font-size: 13px; line-height: 1.45; }
.model-field span small { margin-left: 5px; color: #98a3ab; font-weight: 500; text-transform: none; }
.source-field { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 0; padding: 19px 0 0; border: 0; border-top: 1px solid #edf0f2; }
.source-field legend { position: relative; top: 8px; padding: 0; }
.source-field button { display: grid; grid-template-columns: 22px minmax(0, 1fr); gap: 8px; min-height: 60px; padding: 10px; color: #687782; text-align: left; border: 1px solid #d9e0e5; border-radius: 7px; background: #fff; }
.source-field button span { display: grid; gap: 3px; }
.source-field button strong { color: #2d3942; font-size: 14px; }
.source-field button small { font-size: 13px; line-height: 1.35; }
.source-field button.active { color: var(--app-color-action); border-color: var(--app-color-action); box-shadow: inset 3px 0 var(--app-color-action); background: #f4f8fc; }
.source-field button:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.ref-fields { display: grid; grid-template-columns: minmax(0, 1fr) 30px minmax(0, 1fr); align-items: end; gap: 8px; }
.ref-fields label { display: grid; gap: 7px; }
.ref-arrow { padding-bottom: 10px; color: #84919a; text-align: center; }
.worktree-note { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 8px; padding: 10px 12px; color: #7b541f; border-left: 3px solid var(--app-color-warning); background: #fff8ee; font-size: 13px; line-height: 1.5; }
.worktree-note span { display: grid; gap: 1px; }
.review-form > footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.review-form > footer > span { color: #929da5; font: 13px "SFMono-Regular", Consolas, monospace; }
@media (max-width: 760px) {
  .review-form { padding: 15px; }
  .review-form > header { display: grid; gap: 6px; }
  .review-form > header p { text-align: left; }
  .source-field { grid-template-columns: 1fr; }
  .ref-fields { grid-template-columns: 1fr; }
  .ref-arrow { display: none; }
}
</style>
