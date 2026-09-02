<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { CheckCircle2, ClipboardCheck, GitCommitHorizontal, Plus, Trash2 } from 'lucide-vue-next';
import {
  listTaskOutcomes,
  reportTaskOutcome,
  type KnowledgeUpdateAssessment,
  type OutcomeApprovalStatus,
  type OutcomeFeedbackTarget,
  type OutcomeTestStatus,
  type TaskOutcome,
  type TaskOutcomeFeedbackInput,
  type TaskReviewResult,
} from '@/api/taskReviews';

const props = defineProps<{ repositoryId: string; review: TaskReviewResult }>();

type StatusDraft<T extends string> = { key: string; status: T | ''; evidenceUrl: string };
type FeedbackDraft = {
  kind: 'FALSE_POSITIVE' | 'FALSE_NEGATIVE';
  targetToken: string;
  targetType: OutcomeFeedbackTarget;
  targetKey: string;
  comment: string;
};
type KnowledgeDraft = {
  knowledgeId: string;
  title: string;
  assessment: KnowledgeUpdateAssessment | '';
  comment: string;
};

const outcomes = shallowRef<TaskOutcome[]>([]);
const loading = shallowRef(false);
const saving = shallowRef(false);
const editing = shallowRef(false);
const form = reactive({
  finalCommit: '',
  summary: '',
  tests: [] as StatusDraft<OutcomeTestStatus>[],
  approvals: [] as StatusDraft<OutcomeApprovalStatus>[],
  feedback: [] as FeedbackDraft[],
  knowledge: [] as KnowledgeDraft[],
});

const falsePositiveTargets = computed(() => {
  const targets: { token: string; type: OutcomeFeedbackTarget; key: string; label: string }[] = [];
  props.review.applicableKnowledge.forEach(item => targets.push({
    token: `knowledge:${item.knowledgeId}`, type: 'KNOWLEDGE', key: item.knowledgeId,
    label: `适用知识 · ${item.title}`,
  }));
  props.review.requiredTests.forEach(item => targets.push({
    token: `test:${item.key}`, type: 'REQUIRED_TEST', key: item.key,
    label: `必须测试 · ${item.title || item.key}`,
  }));
  props.review.requiredApprovals.forEach(item => targets.push({
    token: `approval:${item.key}`, type: 'REQUIRED_APPROVAL', key: item.key,
    label: `必要审批 · ${item.title || item.key}`,
  }));
  props.review.staleKnowledge.forEach(item => targets.push({
    token: `stale:${item.knowledgeId}`, type: 'STALE_KNOWLEDGE', key: item.knowledgeId,
    label: `知识失效 · ${item.title}`,
  }));
  props.review.unknowns.forEach(item => targets.push({
    token: `unknown:${item.key}`, type: 'UNKNOWN', key: item.key,
    label: `未知项 · ${item.title || item.key}`,
  }));
  (props.review.change?.changes ?? []).forEach(item => {
    const key = item.newPath ?? item.oldPath;
    if (key) targets.push({ token: `file:${key}`, type: 'FILE', key, label: `变化文件 · ${key}` });
  });
  props.review.changedSymbols.forEach(item => targets.push({
    token: `symbol:${item.name}`, type: 'SYMBOL', key: item.name, label: `变化符号 · ${item.name}`,
  }));
  return targets;
});

const valid = computed(() => /^[0-9a-f]{40,64}$/i.test(form.finalCommit.trim())
  && Boolean(form.summary.trim())
  && form.feedback.every(item => Boolean(
    item.comment.trim()
      && (item.kind === 'FALSE_POSITIVE' ? item.targetToken : item.targetKey.trim()),
  ))
  && form.knowledge.filter(item => item.assessment).every(item => item.comment.trim()));

watch(() => [props.repositoryId, props.review.reviewId] as const, () => {
  editing.value = false;
  resetForm();
  void load();
}, { immediate: true });

function newRequestId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const random = Math.floor(Math.random() * 16);
    return (character === 'x' ? random : (random & 0x3) | 0x8).toString(16);
  });
}
function resetForm() {
  form.finalCommit = props.review.change?.headCommit ?? '';
  form.summary = '';
  form.tests = props.review.requiredTests.map(item => ({ key: item.key, status: '', evidenceUrl: '' }));
  form.approvals = props.review.requiredApprovals.map(item => ({ key: item.key, status: '', evidenceUrl: '' }));
  form.feedback = [];
  const knowledge = [...props.review.applicableKnowledge, ...props.review.staleKnowledge]
    .filter((item, index, values) => values.findIndex(value => value.knowledgeId === item.knowledgeId) === index);
  form.knowledge = knowledge.map(item => ({
    knowledgeId: item.knowledgeId, title: item.title, assessment: '' as const, comment: '',
  }));
}
async function load() {
  loading.value = true;
  try { outcomes.value = await listTaskOutcomes(props.repositoryId, props.review.reviewId); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '开发结果加载失败'); }
  finally { loading.value = false; }
}
function addTest() { form.tests.push({ key: '', status: '', evidenceUrl: '' }); }
function addApproval() { form.approvals.push({ key: '', status: '', evidenceUrl: '' }); }
function addFeedback(kind: FeedbackDraft['kind']) {
  form.feedback.push({
    kind,
    targetToken: kind === 'FALSE_POSITIVE' ? falsePositiveTargets.value[0]?.token ?? '' : '',
    targetType: kind === 'FALSE_POSITIVE' ? falsePositiveTargets.value[0]?.type ?? 'OTHER' : 'OTHER',
    targetKey: kind === 'FALSE_POSITIVE' ? falsePositiveTargets.value[0]?.key ?? '' : '',
    comment: '',
  });
}
function updateFalsePositive(item: FeedbackDraft) {
  const target = falsePositiveTargets.value.find(candidate => candidate.token === item.targetToken);
  if (!target) return;
  item.targetType = target.type;
  item.targetKey = target.key;
}
function feedbackPayload(): TaskOutcomeFeedbackInput[] {
  const human = form.feedback.map(item => ({
    kind: item.kind,
    targetType: item.targetType,
    targetKey: item.targetKey.trim(),
    knowledgeId: null,
    knowledgeUpdateAssessment: null,
    comment: item.comment.trim(),
    evidenceUrls: [],
  } satisfies TaskOutcomeFeedbackInput));
  const knowledge = form.knowledge.filter(item => item.assessment).map(item => ({
    kind: 'KNOWLEDGE_UPDATE' as const,
    targetType: 'KNOWLEDGE' as const,
    targetKey: item.knowledgeId,
    knowledgeId: item.knowledgeId,
    knowledgeUpdateAssessment: item.assessment || null,
    comment: item.comment.trim(),
    evidenceUrls: [],
  }));
  return [...human, ...knowledge];
}
async function save() {
  if (!valid.value) return ElMessage.warning('请填写完整 Commit、结果摘要和反馈说明');
  saving.value = true;
  try {
    const outcome = await reportTaskOutcome(props.repositoryId, props.review.reviewId, {
      clientRequestId: newRequestId(),
      finalCommit: form.finalCommit.trim().toLowerCase(),
      summary: form.summary.trim(),
      tests: form.tests.filter(item => item.key.trim() && item.status).map(item => ({
        key: item.key.trim(), status: item.status as OutcomeTestStatus,
        evidenceUrl: item.evidenceUrl.trim() || null,
      })),
      approvals: form.approvals.filter(item => item.key.trim() && item.status).map(item => ({
        accountId: item.key.trim(), status: item.status as OutcomeApprovalStatus,
        evidenceUrl: item.evidenceUrl.trim() || null,
      })),
      feedback: feedbackPayload(),
    });
    outcomes.value = [outcome, ...outcomes.value.filter(item => item.id !== outcome.id)];
    editing.value = false;
    resetForm();
    ElMessage.success('开发结果已作为不可变记录保存');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '结果回报失败'); }
  finally { saving.value = false; }
}
function date(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}
</script>

<template>
  <section class="outcome-panel" v-loading="loading">
    <header class="outcome-header">
      <div class="outcome-title"><ClipboardCheck :size="18" /><span><small>Delivery evidence</small><strong>开发结果与人工反馈</strong></span></div>
      <p>追加事实，不改写原审查；反馈只进入评测与规则改进，不会自动修改正式知识。</p>
      <el-button type="primary" plain @click="editing = !editing">{{ editing ? '取消回报' : '回报开发结果' }}</el-button>
    </header>

    <div v-if="outcomes.length" class="outcome-ledger">
      <article v-for="outcome in outcomes" :key="outcome.id">
        <div class="outcome-meta">
          <CheckCircle2 :size="15" /><b>{{ outcome.reporterDisplayName || outcome.reportedBy }}</b>
          <span>{{ date(outcome.createdAt) }}</span>
          <el-tag size="small" :type="outcome.commitBinding === 'EXACT_REVIEW_HEAD' ? 'success' : 'warning'">
            {{ outcome.commitBinding === 'EXACT_REVIEW_HEAD' ? '精确绑定审查 Head' : '报告人声明的最终 Commit' }}
          </el-tag>
        </div>
        <p>{{ outcome.summary }}</p>
        <footer>
          <code>{{ outcome.finalCommit.slice(0, 12) }}</code>
          <span>{{ outcome.tests.length }} 测试 · {{ outcome.approvals.length }} 审批 · {{ outcome.feedback.length }} 反馈</span>
          <em v-if="outcome.coverage.missingRequiredTests.length || outcome.coverage.missingRequiredApprovals.length">
            尚有 {{ outcome.coverage.missingRequiredTests.length + outcome.coverage.missingRequiredApprovals.length }} 项审查义务未回报
          </em>
        </footer>
        <ul v-if="outcome.feedback.length">
          <li v-for="item in outcome.feedback" :key="item.id">
            {{ item.kind === 'FALSE_POSITIVE' ? '误报' : item.kind === 'FALSE_NEGATIVE' ? '漏报' : '知识更新判断' }}
            · {{ item.targetKey }} — {{ item.comment }}
          </li>
        </ul>
      </article>
    </div>
    <p v-else-if="!loading" class="outcome-empty">尚无开发结果。审查结论不会因为缺少回报被自动当作正确。</p>

    <form v-if="editing" class="outcome-form" @submit.prevent="save">
      <div class="form-lead">
        <label><span>最终 Commit</span><el-input v-model="form.finalCommit" maxlength="64" placeholder="完整 40–64 位 Git 对象 ID"><template #prefix><GitCommitHorizontal :size="14" /></template></el-input></label>
        <label><span>结果摘要</span><el-input v-model="form.summary" maxlength="4000" type="textarea" :rows="2" placeholder="实际完成了什么、还保留哪些限制" /></label>
      </div>

      <div class="result-grid">
        <section>
          <header><b>执行测试</b><el-button link :icon="Plus" @click="addTest">添加</el-button></header>
          <div v-for="(item, index) in form.tests" :key="index" class="result-row">
            <el-input v-model="item.key" placeholder="测试标识或命令" />
            <el-select v-model="item.status" placeholder="实际状态"><el-option label="通过" value="PASSED" /><el-option label="失败" value="FAILED" /><el-option label="跳过" value="SKIPPED" /></el-select>
            <el-input v-model="item.evidenceUrl" placeholder="证据 URL（可选）" />
            <el-button link :icon="Trash2" @click="form.tests.splice(index, 1)" />
          </div>
        </section>
        <section>
          <header><b>审批结果</b><el-button link :icon="Plus" @click="addApproval">添加</el-button></header>
          <div v-for="(item, index) in form.approvals" :key="index" class="result-row">
            <el-input v-model="item.key" placeholder="审批账号 UUID" />
            <el-select v-model="item.status" placeholder="实际状态"><el-option label="已批准" value="APPROVED" /><el-option label="已拒绝" value="REJECTED" /></el-select>
            <el-input v-model="item.evidenceUrl" placeholder="证据 URL（可选）" />
            <el-button link :icon="Trash2" @click="form.approvals.splice(index, 1)" />
          </div>
        </section>
      </div>

      <section class="feedback-editor">
        <header><div><b>人工确认的误报 / 漏报</b><small>误报只能选择本次审查已有对象；漏报必须明确填写缺失对象。</small></div><span><el-button link @click="addFeedback('FALSE_POSITIVE')">添加误报</el-button><el-button link @click="addFeedback('FALSE_NEGATIVE')">添加漏报</el-button></span></header>
        <div v-for="(item, index) in form.feedback" :key="index" class="feedback-row" :class="{ 'is-missed': item.kind === 'FALSE_NEGATIVE' }">
          <el-tag :type="item.kind === 'FALSE_POSITIVE' ? 'warning' : 'danger'">{{ item.kind === 'FALSE_POSITIVE' ? '误报' : '漏报' }}</el-tag>
          <el-select v-if="item.kind === 'FALSE_POSITIVE'" v-model="item.targetToken" placeholder="选择审查对象" @change="updateFalsePositive(item)"><el-option v-for="target in falsePositiveTargets" :key="target.token" :label="target.label" :value="target.token" /></el-select>
          <template v-else><el-select v-model="item.targetType"><el-option label="知识" value="KNOWLEDGE" /><el-option label="测试" value="REQUIRED_TEST" /><el-option label="审批" value="REQUIRED_APPROVAL" /><el-option label="文件" value="FILE" /><el-option label="符号" value="SYMBOL" /><el-option label="其他" value="OTHER" /></el-select><el-input v-model="item.targetKey" placeholder="缺失对象标识" /></template>
          <el-input v-model="item.comment" placeholder="人工核对依据" />
          <el-button link :icon="Trash2" @click="form.feedback.splice(index, 1)" />
        </div>
      </section>

      <section v-if="form.knowledge.length" class="knowledge-assessment">
        <header><b>知识是否实际需要更新</b><small>留空表示不作判断；保存不会改变知识卡状态。</small></header>
        <div v-for="item in form.knowledge" :key="item.knowledgeId">
          <span>{{ item.title }}</span>
          <el-select v-model="item.assessment" clearable placeholder="不作判断"><el-option label="需要更新" value="NEEDED" /><el-option label="不需要更新" value="NOT_NEEDED" /><el-option label="仍不确定" value="UNKNOWN" /></el-select>
          <el-input v-model="item.comment" :disabled="!item.assessment" placeholder="说明人工判断依据" />
        </div>
      </section>

      <footer class="form-actions"><span>保存后不可编辑或覆盖；修正请提交新的具名回报。</span><el-button type="primary" native-type="submit" :disabled="!valid" :loading="saving">保存不可变结果</el-button></footer>
    </form>
  </section>
</template>

<style scoped>
.outcome-panel { display: grid; gap: 12px; margin-top: 14px; padding: 16px 18px; border: 1px solid #d8e0e5; border-top: 3px solid #287d68; border-radius: 9px; background: #fff; }
.outcome-header { display: grid; grid-template-columns: minmax(220px, auto) minmax(0, 1fr) auto; align-items: center; gap: 14px; }
.outcome-title { display: flex; align-items: center; gap: 8px; color: #236d5c; }
.outcome-title span { display: grid; }
.outcome-title small { color: #6f857e; font: 700 12px Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.outcome-title strong { color: #203832; font-size: 14px; }
.outcome-header > p { margin: 0; color: #657770; font-size: 13px; }
.outcome-ledger { display: grid; gap: 7px; }
.outcome-ledger article { padding: 11px 12px; border: 1px solid #dce7e3; border-radius: 6px; background: #fbfdfc; }
.outcome-meta { display: flex; align-items: center; gap: 7px; color: #60736d; font-size: 13px; }
.outcome-meta b { color: #2b443d; }
.outcome-ledger article > p { margin: 8px 0; color: #344740; font-size: 14px; }
.outcome-ledger footer { display: flex; flex-wrap: wrap; gap: 10px; color: #71817c; font-size: 13px; }
.outcome-ledger footer em { color: #a45a35; font-style: normal; }
.outcome-ledger ul { display: grid; gap: 3px; margin: 8px 0 0; padding: 8px 0 0 18px; color: #5e6e69; font-size: 13px; border-top: 1px solid #e2eae7; }
.outcome-empty { margin: 0; padding: 12px; color: #73817d; font-size: 13px; text-align: center; border: 1px dashed #cfdbd7; }
.outcome-form { display: grid; gap: 16px; padding-top: 14px; border-top: 1px solid #dce6e2; }
.form-lead { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(0, 1.2fr); gap: 10px; }
.form-lead label { display: grid; gap: 5px; color: #52655f; font-size: 13px; }
.result-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.result-grid section, .feedback-editor, .knowledge-assessment { display: grid; align-content: start; gap: 7px; padding: 10px; border: 1px solid #dde6e3; border-radius: 6px; }
.result-grid header, .feedback-editor header, .knowledge-assessment header { display: flex; align-items: center; justify-content: space-between; color: #354c45; font-size: 13px; }
.feedback-editor header > div, .knowledge-assessment header { gap: 3px; }
.feedback-editor header > div { display: grid; }
.feedback-editor small, .knowledge-assessment small { color: #75847f; font-size: 12px; font-weight: 400; }
.result-row { display: grid; grid-template-columns: minmax(120px, 1fr) 100px minmax(130px, 1fr) 28px; gap: 6px; }
.feedback-row { display: grid; grid-template-columns: auto minmax(150px, .8fr) minmax(160px, 1fr) 28px; align-items: center; gap: 7px; }
.feedback-row.is-missed { grid-template-columns: auto 120px minmax(140px, .8fr) minmax(160px, 1fr) 28px; }
.knowledge-assessment > div { display: grid; grid-template-columns: minmax(150px, .7fr) 140px minmax(180px, 1.3fr); align-items: center; gap: 7px; color: #475c55; font-size: 13px; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #71817c; font-size: 13px; }
@media (max-width: 900px) {
  .outcome-header, .form-lead, .result-grid { grid-template-columns: 1fr; }
  .result-row, .feedback-row, .knowledge-assessment > div { grid-template-columns: 1fr; }
}
</style>
