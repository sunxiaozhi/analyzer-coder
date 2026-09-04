<script setup lang="ts">
import { computed, onMounted, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  AlertTriangle,
  Archive,
  CheckCircle2,
  Clock3,
  FileSearch,
  FlaskConical,
  GitCommitHorizontal,
  GitPullRequest,
  History,
  RefreshCw,
} from 'lucide-vue-next';
import {
  createPullRequestReview,
  createTaskReview,
  getTaskReview,
  listTaskReviews,
  type TaskReviewResult,
  type TaskReviewSummary,
  type PullRequestReviewResult,
} from '@/api/taskReviews';
import { intelligenceApi, type AskModel } from '@/api/intelligence';
import { ApiError } from '@/api/http';
import ChangeEvidenceSpine from '@/features/task-review/ChangeEvidenceSpine.vue';
import ImpactEstimatePanel from '@/features/task-review/ImpactEstimatePanel.vue';
import PullRequestReviewForm, { type PullRequestReviewDraft } from '@/features/task-review/PullRequestReviewForm.vue';
import ReviewEvidenceDrawer from '@/features/task-review/ReviewEvidenceDrawer.vue';
import TaskReviewForm, { type TaskReviewDraft } from '@/features/task-review/TaskReviewForm.vue';
import TaskOutcomePanel from '@/features/task-review/TaskOutcomePanel.vue';
import type { ReviewEvidenceSelection } from '@/features/task-review/types';
import { useRepositoryStore } from '@/stores/repositoryStore';

type WorkbenchMode = 'review' | 'estimate';
type ReviewInput = 'local' | 'provider';

const repositories = useRepositoryStore();
const router = useRouter();
const route = useRoute();
const mode = shallowRef<WorkbenchMode>('review');
const reviewInput = shallowRef<ReviewInput>('local');
const loading = shallowRef(false);
const historyLoading = shallowRef(false);
const error = shallowRef<string | null>(null);
const credentialRequired = shallowRef(false);
const result = shallowRef<TaskReviewResult | null>(null);
const providerResult = shallowRef<PullRequestReviewResult | null>(null);
const history = shallowRef<TaskReviewSummary[]>([]);
const summaryModels = shallowRef<AskModel[]>([]);
const summaryModelsLoading = shallowRef(false);
const selection = shallowRef<ReviewEvidenceSelection | null>(null);
const historyReadOnly = shallowRef(false);
const formKey = shallowRef(0);
let contextVersion = 0;

const repository = computed(() => repositories.selectedRepository);
const shortSnapshot = computed(() => repository.value?.snapshotId?.slice(0, 8) ?? '未发布');
const shortCommit = computed(() => repository.value?.commit?.slice(0, 8) ?? '无提交');
const resultState = computed(() => {
  if (!result.value) return null;
  if (result.value.status === 'FAILED') return 'failed';
  if (result.value.change?.partial || result.value.change?.limitations.length || result.value.unknowns.length) return 'degraded';
  return 'complete';
});
const resultStateLabel = computed(() => ({
  failed: '审查失败',
  degraded: '已完成，存在限制',
  complete: '证据完整',
}[resultState.value ?? 'complete']));
const initialDraft = computed<Partial<TaskReviewDraft>>(() => {
  const source = route.query.source;
  const validSource = source === 'SINGLE_COMMIT' || source === 'COMMIT_RANGE' || source === 'WORKTREE'
    ? source
    : 'WORKTREE';
  return {
    changeSource: validSource,
    baseRef: typeof route.query.baseRef === 'string' ? route.query.baseRef : null,
    headRef: typeof route.query.headRef === 'string' ? route.query.headRef : null,
    task: typeof route.query.task === 'string' ? route.query.task : null,
  };
});

function newRequestId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

function failureMessage(exception: unknown, fallback: string) {
  if (exception instanceof ApiError && exception.code === 'CURRENT_SNAPSHOT_REQUIRED') {
    return '仓库还没有当前代码快照，请先在项目总览完成准备。';
  }
  if (exception instanceof ApiError && exception.code === 'PROVIDER_CREDENTIAL_REQUIRED') {
    return '当前仓库没有可用的远程访问凭据，配置后即可继续读取并评论拉取请求 / 合并请求。';
  }
  if (exception instanceof ApiError && exception.code === 'PR_HEAD_FETCH_MISMATCH') {
    return '托管平台返回的头提交与远程审查引用不一致。为避免审错版本，本次操作已停止。';
  }
  return exception instanceof Error ? exception.message : fallback;
}

async function loadHistory(repositoryId: string, version = contextVersion) {
  historyLoading.value = true;
  try {
    const records = await listTaskReviews(repositoryId, 12);
    if (version === contextVersion && repositoryId === repositories.selectedRepositoryId) {
      history.value = records;
    }
  } catch (exception) {
    if (version === contextVersion) error.value = failureMessage(exception, '审查历史加载失败');
  } finally {
    if (version === contextVersion) historyLoading.value = false;
  }
}

async function loadSummaryModels(repositoryId: string, version = contextVersion) {
  summaryModelsLoading.value = true;
  try {
    const models = await intelligenceApi.askModels(repositoryId);
    if (version === contextVersion && repositoryId === repositories.selectedRepositoryId) {
      summaryModels.value = models;
    }
  } catch {
    if (version === contextVersion) summaryModels.value = [];
  } finally {
    if (version === contextVersion) summaryModelsLoading.value = false;
  }
}

async function submit(draft: TaskReviewDraft) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const version = contextVersion;
  loading.value = true;
  error.value = null;
  credentialRequired.value = false;
  selection.value = null;
  historyReadOnly.value = false;
  try {
    const response = await createTaskReview(repositoryId, {
      clientRequestId: newRequestId(),
      task: draft.task,
      changeSource: draft.changeSource,
      baseRef: draft.baseRef,
      headRef: draft.headRef,
      modelConfigId: draft.modelConfigId,
    });
    if (version !== contextVersion || repositoryId !== repositories.selectedRepositoryId) return;
    result.value = response;
    await loadHistory(repositoryId, version);
  } catch (exception) {
    if (version === contextVersion) error.value = failureMessage(exception, '变更审查失败');
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

async function submitProvider(draft: PullRequestReviewDraft) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const version = contextVersion;
  loading.value = true;
  error.value = null;
  credentialRequired.value = false;
  selection.value = null;
  historyReadOnly.value = false;
  providerResult.value = null;
  try {
    const response = await createPullRequestReview(repositoryId, {
      clientRequestId: newRequestId(),
      ...draft,
    });
    if (version !== contextVersion || repositoryId !== repositories.selectedRepositoryId) return;
    result.value = response.review;
    providerResult.value = response;
    ElMessage.success(response.comment.action === 'UPDATED' ? '已有 PR/MR 评论已更新' : 'PR/MR 提示性评论已创建');
    await loadHistory(repositoryId, version);
  } catch (exception) {
    if (version === contextVersion) {
      credentialRequired.value = exception instanceof ApiError && exception.code === 'PROVIDER_CREDENTIAL_REQUIRED';
      error.value = failureMessage(exception, '拉取请求 / 合并请求审查同步失败');
    }
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

async function openHistory(item: TaskReviewSummary) {
  const repositoryId = repositories.selectedRepositoryId;
  if (!repositoryId) return;
  const version = contextVersion;
  loading.value = true;
  error.value = null;
  selection.value = null;
  try {
    const detail = await getTaskReview(repositoryId, item.reviewId);
    if (version !== contextVersion || repositoryId !== repositories.selectedRepositoryId) return;
    result.value = detail;
    historyReadOnly.value = true;
  } catch (exception) {
    if (version === contextVersion) error.value = failureMessage(exception, '历史审查加载失败');
  } finally {
    if (version === contextVersion) loading.value = false;
  }
}

function resetResult() {
  result.value = null;
  selection.value = null;
  historyReadOnly.value = false;
  providerResult.value = null;
  error.value = null;
  credentialRequired.value = false;
}

function configureCredential() {
  if (!repository.value) return;
  void router.push({ name: 'repositories', query: { edit: repository.value.id } });
}

function selectEvidence(item: ReviewEvidenceSelection) {
  selection.value = item;
}

function openCode(item: ReviewEvidenceSelection) {
  if (!item.filePath) return;
  if (result.value?.snapshotId !== repository.value?.snapshotId) {
    ElMessage.warning('这条历史证据属于旧快照，当前源码预览不会冒充旧版本内容。');
    return;
  }
  void router.push({
    name: 'search',
    query: {
      path: item.filePath,
      startLine: String(item.startLine ?? 1),
      endLine: String(item.endLine ?? item.startLine ?? 1),
    },
  });
}

function openKnowledge(item: ReviewEvidenceSelection) {
  if (!item.knowledgeId) return;
  void router.push({ name: 'knowledge', query: { cardId: item.knowledgeId } });
}

function shortDate(value: string | null) {
  if (!value) return '未完成';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}

function sourceLabel(source: TaskReviewSummary['changeSource']) {
  return { WORKTREE: '工作区', SINGLE_COMMIT: '单次提交', COMMIT_RANGE: '提交范围' }[source];
}

watch(
  () => repositories.selectedRepositoryId,
  repositoryId => {
    contextVersion++;
    formKey.value++;
    resetResult();
    history.value = [];
    summaryModels.value = [];
    historyLoading.value = false;
    summaryModelsLoading.value = false;
    if (repositoryId) {
      void loadHistory(repositoryId);
      void loadSummaryModels(repositoryId);
    }
  },
  { immediate: true },
);
watch(
  () => [route.query.source, route.query.baseRef, route.query.headRef, route.query.task] as const,
  values => {
    if (!values.some(value => typeof value === 'string')) return;
    mode.value = 'review';
    formKey.value++;
    resetResult();
  },
);

onMounted(async () => {
  if (!repositories.repositories.length) await repositories.loadRepositories();
});
</script>

<template>
  <main class="review-page">
    <header class="dossier-header">
      <div class="title-block">
        <small>工程变更审查</small>
        <h1>变更审查</h1>
        <p>把真实 Git 改动、适用工程知识、必须动作与未知项收在同一份审查卷宗中。</p>
      </div>
      <dl>
        <div><dt>仓库</dt><dd>{{ repository?.name ?? '未选择' }}</dd></div>
        <div><dt>知识基线快照</dt><dd class="mono">{{ shortSnapshot }}</dd></div>
        <div><dt>基线提交</dt><dd class="mono">{{ shortCommit }}</dd></div>
      </dl>
    </header>

    <nav class="mode-switch" aria-label="变更工具模式">
      <button type="button" :class="{ active: mode === 'review' }" @click="mode = 'review'">
        <FileSearch :size="15" /><span><strong>真实变更审查</strong><small>读取 Git 差异与正式知识</small></span>
      </button>
      <button type="button" :class="{ active: mode === 'estimate' }" @click="mode = 'estimate'">
        <FlaskConical :size="15" /><span><strong>需求影响预估</strong><small>未读取真实 Git 差异</small></span>
      </button>
    </nav>

    <ImpactEstimatePanel v-if="mode === 'estimate'" />

    <template v-else>
      <section v-if="!repositories.selectedRepositoryId" class="page-empty">
        <FileSearch :size="30" />
        <h2>先选择一个仓库</h2>
        <p>变更审查只能基于你有权读取的仓库和已发布快照运行。</p>
        <el-button type="primary" @click="router.push('/repositories')">前往项目管理</el-button>
      </section>

      <template v-else>
        <section v-if="!repository?.snapshotId" class="precondition-banner">
          <AlertTriangle :size="18" />
          <div><strong>当前项目还不能发起变更审查</strong><p>先生成代码快照和内容索引，系统才能把改动与知识证据绑定到同一版本。</p></div>
          <el-button type="primary" @click="router.push('/overview')">去准备项目</el-button>
        </section>

        <template v-else>
          <nav v-if="repository.capabilities.canUpdate" class="review-input-switch" aria-label="审查输入来源">
            <button type="button" :class="{ active: reviewInput === 'local' }" @click="reviewInput = 'local'; resetResult()">
              <GitCommitHorizontal :size="14" /><span><strong>本地 Git</strong><small>工作区 / 提交版本 / 版本范围</small></span>
            </button>
            <button type="button" :class="{ active: reviewInput === 'provider' }" @click="reviewInput = 'provider'; resetResult()">
              <GitPullRequest :size="14" /><span><strong>拉取请求 / 合并请求</strong><small>读取托管平台补丁并同步评论</small></span>
            </button>
          </nav>

          <TaskReviewForm
            v-if="reviewInput === 'local' || !repository.capabilities.canUpdate"
            :key="formKey"
            :loading="loading"
            :initial-draft="initialDraft"
            :models="summaryModels"
            :models-loading="summaryModelsLoading"
            @submit="submit"
          />
          <PullRequestReviewForm
            v-else
            :key="`provider-${formKey}`"
            :loading="loading"
            :default-provider="repository.sourceType === 'GITLAB' ? 'GITLAB' : 'GITHUB'"
            :models="summaryModels"
            :models-loading="summaryModelsLoading"
            @submit="submitProvider"
          />
        </template>

        <section class="history-strip" aria-labelledby="history-title">
          <header>
            <div><History :size="15" /><strong id="history-title">最近审查</strong><small>不可变、只读的版本记录</small></div>
            <RefreshCw v-if="historyLoading" class="spinning" :size="14" />
          </header>
          <div v-if="history.length" class="history-list">
            <button
              v-for="item in history"
              :key="item.reviewId"
              type="button"
              :class="{ selected: result?.reviewId === item.reviewId }"
              @click="openHistory(item)"
            >
              <span><component :is="item.status === 'COMPLETED' ? CheckCircle2 : AlertTriangle" :size="13" />{{ item.status === 'COMPLETED' ? '已完成' : '失败' }}</span>
              <strong>{{ item.task || `${sourceLabel(item.changeSource)}审查` }}</strong>
              <small>{{ item.changedFileCount }} 文件 · {{ item.unknownCount }} 未知 · {{ shortDate(item.finishedAt) }}</small>
            </button>
          </div>
          <p v-else-if="!historyLoading">还没有审查记录。提交上方表单后，结果会成为可追溯的只读版本。</p>
        </section>

        <section v-if="error" class="state-banner error-state" role="alert">
          <AlertTriangle :size="17" /><div><strong>无法完成当前操作</strong><p>{{ error }}</p></div>
          <el-button v-if="credentialRequired && repository?.capabilities.canManageCredential" type="primary" plain @click="configureCredential">去绑定凭据</el-button>
        </section>

        <section v-if="loading && !result" class="state-banner loading-state" aria-live="polite">
          <RefreshCw class="spinning" :size="17" /><div><strong>正在核对真实改动</strong><p>依次读取 Git、定位变更对象、匹配已审核知识并生成确定性义务。</p></div>
        </section>

        <template v-if="result">
          <section class="result-ledger" :data-state="resultState">
            <div class="ledger-state">
              <component :is="resultState === 'complete' ? CheckCircle2 : AlertTriangle" :size="18" />
              <span><small>{{ historyReadOnly ? '历史审查 · 只读' : '本次审查' }}</small><strong>{{ resultStateLabel }}</strong></span>
            </div>
            <p>{{ result.summary || result.error?.message || '审查已经完成，所有可确认内容均附带事实来源。' }}</p>
            <div class="ledger-version">
              <span><Archive :size="12" />知识基线 <code>{{ result.snapshotId.slice(0, 8) }}</code></span>
              <span><GitCommitHorizontal :size="12" />待审提交 {{ result.change?.headCommit?.slice(0, 8) ?? shortCommit }}</span>
              <span><Clock3 :size="12" />{{ shortDate(result.finishedAt) }}</span>
            </div>
          </section>

          <section v-if="result.change?.limitations.length" class="review-limitations">
            <AlertTriangle :size="16" />
            <div>
              <strong>本次审查的事实边界</strong>
              <ul>
                <li v-for="item in result.change.limitations" :key="`${item.code}:${item.detail}`">{{ item.detail }}</li>
              </ul>
            </div>
          </section>

          <section v-if="result.change?.source === 'WORKTREE'" class="worktree-result-note">
            工作区结果绑定摘要 <code>{{ result.change.worktreeDigest?.slice(0, 16) ?? '不可用' }}</code>；文件继续变化后，应创建新审查。
          </section>

          <section v-if="providerResult" class="provider-result-note">
            <GitPullRequest :size="15" />
            <span>
              <strong>{{ providerResult.provider }} · {{ providerResult.externalId }}</strong>
              评论已{{ providerResult.comment.action === 'UPDATED' ? '更新' : '创建' }}；本结果仅提示，不改变合并状态。
            </span>
            <a v-if="providerResult.comment.commentUrl" :href="providerResult.comment.commentUrl" target="_blank" rel="noopener noreferrer">查看评论</a>
            <a v-else-if="providerResult.webUrl" :href="providerResult.webUrl" target="_blank" rel="noopener noreferrer">查看变更</a>
          </section>

          <section v-if="result.status === 'FAILED'" class="page-empty compact">
            <AlertTriangle :size="26" /><h2>变更审查失败</h2><p>{{ result.error?.message }}</p>
          </section>

          <div v-else class="review-workbench">
            <ChangeEvidenceSpine :result="result" @select="selectEvidence" />
            <ReviewEvidenceDrawer
              :selection="selection"
              @close="selection = null"
              @open-code="openCode"
              @open-knowledge="openKnowledge"
            />
          </div>
          <TaskOutcomePanel
            v-if="result.status === 'COMPLETED'"
            :key="result.reviewId"
            :repository-id="result.repositoryId"
            :review="result"
          />
        </template>
      </template>
    </template>
  </main>
</template>

<style scoped>
.review-page { --paper: var(--app-surface); --ink: var(--app-text-primary); display: grid; gap: 16px; width: 100%; min-width: 0; min-height: 0; height: 100%; margin: 0; padding: 14px 2px 32px; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; color: var(--ink); font-family: inherit; }
.dossier-header { display: flex; align-items: end; justify-content: space-between; gap: 26px; padding: 19px 20px; border: 1px solid #d8e0e5; border-top: 3px solid var(--app-color-action); border-radius: 9px; background: var(--paper); }
.title-block { display: grid; gap: 4px; }
.title-block small { color: var(--app-color-action); font: 700 12px "SFMono-Regular", Consolas, monospace; letter-spacing: .1em; text-transform: uppercase; }
.title-block h1 { margin: 0; font-size: 24px; font-weight: 720; letter-spacing: -.02em; }
.title-block p { margin: 1px 0 0; color: #687782; font-size: 14px; }
.dossier-header dl { display: flex; margin: 0; border: 1px solid #dde4e8; border-radius: 7px; overflow: hidden; }
.dossier-header dl div { display: grid; min-width: 112px; gap: 3px; padding: 8px 11px; border-left: 1px solid #e3e8eb; }
.dossier-header dl div:first-child { border-left: 0; }
.dossier-header dt { color: #82909a; font-size: 12px; text-transform: uppercase; }
.dossier-header dd { overflow: hidden; max-width: 180px; margin: 0; color: #36444d; font-size: 13px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.mono, code { font-family: "SFMono-Regular", Consolas, monospace; }
.mode-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; padding: 5px; border: 1px solid #dce3e7; border-radius: 9px; background: #eef2f4; }
.mode-switch button { display: grid; grid-template-columns: 22px minmax(0, 1fr); align-items: center; gap: 8px; min-height: 48px; padding: 7px 12px; color: #6c7a83; text-align: left; border: 1px solid transparent; border-radius: 6px; background: transparent; }
.mode-switch button span { display: grid; gap: 2px; }
.mode-switch strong { color: #42515b; font-size: 14px; }
.mode-switch small { font-size: 13px; }
.mode-switch button.active { color: var(--app-color-action); border-color: #ccd9e2; box-shadow: 0 1px 2px #26384410; background: #fff; }
.mode-switch button.active strong { color: #263844; }
.review-input-switch { display: flex; justify-content: flex-start; gap: 5px; padding: 4px; border: 1px solid #dce3e7; border-radius: 8px; background: #f3f6f7; }
.review-input-switch button { display: grid; grid-template-columns: 18px auto; align-items: center; gap: 6px; min-width: 190px; padding: 7px 10px; color: #718089; text-align: left; border: 1px solid transparent; border-radius: 6px; background: transparent; }
.review-input-switch button span { display: grid; gap: 1px; }
.review-input-switch strong { color: #45545d; font-size: 13px; }
.review-input-switch small { font-size: 12px; }
.review-input-switch button.active { color: var(--app-color-success); border-color: #c6d8d2; background: #fff; box-shadow: 0 1px 2px #2638440d; }
.mode-switch button:focus-visible, .history-list button:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.page-empty { display: grid; place-items: center; min-height: 270px; padding: 30px; color: #71808a; border: 1px dashed #ccd6dc; border-radius: 9px; background: #fafbfc; text-align: center; }
.page-empty h2 { margin: 11px 0 2px; color: #34434d; font-size: 16px; }
.page-empty p { margin: 0; font-size: 13px; }
.page-empty .el-button { margin-top: 14px; }
.page-empty.compact { min-height: 150px; }
.precondition-banner { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 13px 15px; color: #8b5a20; border: 1px solid #ead8bd; border-left: 4px solid var(--app-color-warning); border-radius: 7px; background: var(--app-color-warning-soft); }
.precondition-banner div { display: grid; gap: 2px; }
.precondition-banner strong { color: #67451d; font-size: 14px; }
.precondition-banner p { margin: 0; color: #76624a; font-size: 13px; }
.history-strip { border: 1px solid #dbe2e6; border-radius: 9px; overflow: hidden; background: #fff; }
.history-strip > header { display: flex; align-items: center; justify-content: space-between; min-height: 38px; padding: 0 12px; color: #52616b; border-bottom: 1px solid #e6ebee; background: #f8fafb; }
.history-strip > header div { display: flex; align-items: center; gap: 7px; }
.history-strip > header strong { font-size: 13px; }
.history-strip > header small { color: #89949b; font-size: 12px; }
.history-strip > p { margin: 0; padding: 13px; color: #77858e; font-size: 13px; }
.history-list { display: flex; gap: 7px; overflow-x: auto; padding: 8px; }
.history-list button { display: grid; flex: 0 0 220px; gap: 4px; padding: 9px 10px; color: #697780; text-align: left; border: 1px solid #e0e6e9; border-radius: 6px; background: #fff; }
.history-list button:hover, .history-list button.selected { border-color: #8fb5d5; background: #f5f9fc; }
.history-list button > span { display: flex; align-items: center; gap: 4px; color: #567182; font-size: 12px; }
.history-list strong { overflow: hidden; color: #33424c; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.history-list small { color: #7c8992; font-size: 12px; }
.state-banner { display: grid; grid-template-columns: 23px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 11px 13px; border-left: 3px solid #76838f; background: #f3f5f6; }
.state-banner div { display: grid; gap: 2px; }
.state-banner strong { font-size: 13px; }
.state-banner p { margin: 0; font-size: 13px; line-height: 1.45; }
.error-state { color: #8a5030; border-color: var(--app-color-warning); background: var(--app-color-warning-soft); }
.loading-state { color: #345f7b; border-color: var(--app-color-action); background: #f1f7fb; }
.result-ledger { display: grid; grid-template-columns: auto minmax(220px, 1fr) auto; align-items: center; gap: 18px; padding: 12px 14px; border: 1px solid #d8e3de; border-left: 4px solid var(--app-color-success); border-radius: 8px; background: #f7fbf9; }
.result-ledger[data-state="degraded"], .result-ledger[data-state="failed"] { color: #80521f; border-color: #ead9c1; border-left-color: var(--app-color-warning); background: var(--app-color-warning-soft); }
.ledger-state { display: flex; align-items: center; gap: 8px; color: var(--app-color-success); }
.result-ledger[data-state="degraded"] .ledger-state, .result-ledger[data-state="failed"] .ledger-state { color: #a36019; }
.ledger-state span { display: grid; gap: 1px; }
.ledger-state small { font-size: 12px; }
.ledger-state strong { color: #2b3932; font-size: 14px; }
.result-ledger > p { margin: 0; color: #596a62; font-size: 13px; line-height: 1.5; }
.ledger-version { display: flex; gap: 10px; color: #6f7d76; font-size: 12px; }
.ledger-version span { display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.worktree-result-note { padding: 8px 11px; color: #73562f; border-left: 3px solid var(--app-color-warning); background: var(--app-color-warning-soft); font-size: 13px; }
.review-limitations { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 8px; padding: 10px 12px; color: #76552f; border-left: 3px solid var(--app-color-warning); background: var(--app-color-warning-soft); font-size: 13px; }
.review-limitations div { display: grid; gap: 4px; }
.review-limitations ul { display: grid; gap: 3px; margin: 0; padding-left: 18px; line-height: 1.45; }
.provider-result-note { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 9px 11px; color: #47655c; border-left: 3px solid var(--app-color-success); background: #f0f8f5; font-size: 13px; }
.provider-result-note span { display: grid; gap: 1px; }
.provider-result-note strong { color: #264a3f; }
.provider-result-note a { color: var(--app-color-success); font-weight: 700; text-decoration: none; }
.provider-result-note a:hover { text-decoration: underline; }
.review-workbench { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 360px); align-items: start; gap: 14px; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 980px) {
  .review-page { padding: 12px 0 28px; }
  .dossier-header { display: grid; align-items: start; }
  .dossier-header dl { width: 100%; }
  .dossier-header dl div { flex: 1; min-width: 0; }
  .review-workbench { grid-template-columns: 1fr; }
  .result-ledger { grid-template-columns: 1fr; gap: 8px; }
  .ledger-version { flex-wrap: wrap; }
}
@media (max-width: 760px) {
  .review-page { height: auto; min-height: 100%; overflow: visible; }
}
@media (max-width: 600px) {
  .dossier-header { padding: 15px; }
  .title-block h1 { font-size: 21px; }
  .dossier-header dl { display: grid; grid-template-columns: 1fr 1fr; }
  .dossier-header dl div { border-top: 1px solid #e3e8eb; border-left: 0; }
  .dossier-header dl div:first-child { grid-column: 1 / -1; border-top: 0; }
  .mode-switch { grid-template-columns: 1fr; }
  .review-input-switch { display: grid; grid-template-columns: 1fr 1fr; }
  .review-input-switch button { min-width: 0; }
  .provider-result-note { grid-template-columns: 20px minmax(0, 1fr); }
  .provider-result-note a { grid-column: 2; }
  .precondition-banner { grid-template-columns: 24px minmax(0, 1fr); }
  .precondition-banner .el-button { grid-column: 2; justify-self: start; margin-left: 0; }
}
</style>
