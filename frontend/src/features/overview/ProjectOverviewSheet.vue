<script setup lang="ts">
import { computed } from 'vue';
import {
  AlertTriangle,
  ArrowRight,
  BookOpenCheck,
  Check,
  CircleDot,
  Code2,
  Database,
  FileCode2,
  GitBranch,
  GitCommit,
  Network,
  RefreshCw,
  ShieldCheck,
} from 'lucide-vue-next';
import type {
  ProjectCodeFacts,
  ProjectHealthIssue,
  ProjectHealthOverview,
  ProjectProfile,
  RepositoryPreparation,
} from '@/api/repositories';
import type { TaskReviewSummary } from '@/api/taskReviews';
import type { Repository } from '@/types/api';

interface Props {
  repository: Repository;
  preparation: RepositoryPreparation | null;
  profile: ProjectProfile | null;
  codeFacts: ProjectCodeFacts | null;
  health: ProjectHealthOverview | null;
  loading: boolean;
  preparing: boolean;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  refresh: [];
  prepare: [];
  retryStage: [stage: 'snapshot' | 'content' | 'vectors' | 'graph' | 'knowledge_drift'];
  startReview: [];
  openKnowledge: [];
}>();

const HEALTH_COPY = {
  READY: { label: '工程状态良好', detail: '索引与可信知识均可用于变更审查', tone: 'ready' },
  DEGRADED: { label: '可用但有缺口', detail: '可以审查，部分证据或治理状态需要处理', tone: 'warning' },
  BLOCKED: { label: '审查条件不足', detail: '先处理阻塞项，再发起可靠的变更审查', tone: 'danger' },
  PREPARING: { label: '正在准备', detail: '正在生成当前快照对应的工程证据', tone: 'running' },
} as const;

const fallbackHealthState = computed(() => {
  if (props.preparation?.state === 'PROCESSING') return 'PREPARING';
  if (props.preparation?.state === 'ACTION_REQUIRED' || props.preparation?.state === 'NOT_READY') {
    return 'BLOCKED';
  }
  if (props.preparation?.state === 'READY') return 'READY';
  return 'DEGRADED';
});
const healthState = computed(() => props.health?.state ?? fallbackHealthState.value);
const healthCopy = computed(() => HEALTH_COPY[healthState.value]);
const knowledge = computed(() => props.health?.knowledge ?? {
  total: 0,
  current: 0,
  suspect: 0,
  stale: 0,
  unverified: 0,
  trusted: 0,
  requiredWithoutOwner: 0,
  unreviewed: 0,
});
const vectorCoverage = computed(() => {
  if (!props.profile?.chunkCount) return 0;
  return Math.round(props.profile.vectorizedChunks / props.profile.chunkCount * 100);
});
const categories = computed(() => (
  props.codeFacts?.fileCategories.filter(item => item.count > 0).slice(0, 8) ?? []
));
const categoryMaximum = computed(() => Math.max(1, ...categories.value.map(item => item.count)));
const prepareLabel = computed(() => {
  if (props.preparation?.state === 'NOT_READY') return '准备项目';
  if (props.preparation?.state === 'PROCESSING') return '继续准备';
  if (props.preparation?.state === 'READY') return '检查更新';
  return '修复准备状态';
});
const reviewActionLabel = computed(() => (
  props.health?.readyForReview ? '开始变更审查' : '准备完成后审查'
));
const reviewActionTitle = computed(() => (
  props.health?.readyForReview
    ? '基于当前快照和可信知识发起变更审查'
    : '请先处理右侧准备流程和当前问题'
));

function short(value: string | null | undefined, length: number) {
  return value ? value.slice(0, length) : '—';
}

function categoryWidth(value: number) {
  return Math.max(6, Math.round(value / categoryMaximum.value * 100)) + '%';
}

function stageTone(stageState: string) {
  if (stageState === 'READY') return 'ready';
  if (stageState === 'RUNNING') return 'running';
  if (stageState === 'FAILED' || stageState === 'DEGRADED') return 'danger';
  return 'muted';
}

function reviewStatus(review: TaskReviewSummary) {
  if (review.status === 'COMPLETED') return { label: '已完成', tone: 'ready' };
  if (review.status === 'RUNNING') return { label: '进行中', tone: 'running' };
  return { label: '失败', tone: 'danger' };
}

function formatTime(value: string | null) {
  if (!value) return '尚未完成';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function issueAction(issue: ProjectHealthIssue) {
  if (issue.actionTarget === 'PREPARATION') emit('prepare');
  else emit('openKnowledge');
}

function canResolveIssue(issue: ProjectHealthIssue) {
  return issue.actionTarget === 'PREPARATION'
    ? Boolean(props.repository.capabilities?.canIndex)
    : Boolean(props.repository.capabilities?.canUpdate);
}
</script>

<template>
  <article class="overview-sheet">
    <header class="project-hero">
      <div class="identity-block">
        <span class="project-mark"><Code2 :size="22" /></span>
        <div class="identity-copy">
          <h1>{{ repository.name }}</h1>
          <p>{{ repository.description || '当前代码快照的工程知识、索引能力与审查状态。' }}</p>
        </div>
      </div>

      <div class="version-line" aria-label="项目版本">
        <span><GitBranch :size="13" />{{ repository.branch ?? '无分支' }}</span>
        <span><GitCommit :size="13" />{{ short(repository.commit, 10) }}</span>
        <span>快照 {{ short(repository.snapshotId, 8) }}</span>
        <span v-if="repository.dirty" class="dirty-flag">工作区有未发布变更</span>
      </div>

      <div class="health-callout" :data-tone="healthCopy.tone">
        <span class="health-icon">
          <ShieldCheck v-if="healthState === 'READY'" :size="19" />
          <RefreshCw v-else-if="healthState === 'PREPARING'" :size="18" class="spinning" />
          <AlertTriangle v-else :size="19" />
        </span>
        <div>
          <strong>{{ healthCopy.label }}</strong>
          <small>{{ healthCopy.detail }}</small>
        </div>
      </div>

      <div class="hero-actions">
        <button
          type="button"
          class="review-action"
          :title="reviewActionTitle"
          :disabled="loading || preparing || !health?.readyForReview"
          @click="emit('startReview')"
        >
          {{ reviewActionLabel }}
          <ArrowRight :size="15" />
        </button>
        <button
          v-if="repository.capabilities?.canIndex"
          type="button"
          class="prepare-action"
          :disabled="loading || preparing"
          @click="emit('prepare')"
        >
          {{ prepareLabel }}
          <RefreshCw :size="13" :class="{ spinning: preparing }" />
        </button>
        <button
          type="button"
          class="refresh-action"
          aria-label="刷新总览"
          :disabled="loading || preparing"
          @click="emit('refresh')"
        >
          <RefreshCw :size="14" :class="{ spinning: loading }" />
        </button>
      </div>
    </header>

    <section class="capability-strip" aria-label="项目核心数据">
      <article data-accent="blue">
        <span><Network :size="17" />代码图谱</span>
        <strong>{{ profile?.graphNodes ?? 0 }}</strong>
        <small>节点 · {{ profile?.graphEdges ?? 0 }} 条关系</small>
      </article>
      <article data-accent="cyan">
        <span><Database :size="17" />向量数据</span>
        <strong>{{ vectorCoverage }}%</strong>
        <small>{{ profile?.vectorizedChunks ?? 0 }} / {{ profile?.chunkCount ?? 0 }} 个片段</small>
      </article>
      <article data-accent="green">
        <span><BookOpenCheck :size="17" />可信知识</span>
        <strong>{{ knowledge.trusted }}</strong>
        <small>{{ knowledge.total }} 条知识中的可用依据</small>
      </article>
      <article data-accent="violet">
        <span><FileCode2 :size="17" />代码文件</span>
        <strong>{{ codeFacts?.codeFileCount ?? 0 }}</strong>
        <small>{{ categories.length }} 类 · {{ profile?.fileCount ?? 0 }} 个快照文件</small>
      </article>
    </section>

    <div class="overview-body">
      <main class="primary-column">
        <section class="overview-section knowledge-section" aria-labelledby="knowledge-health-title">
          <header class="section-heading">
            <div>
              <span>知识可信度</span>
              <h2 id="knowledge-health-title">知识真实性</h2>
              <p>计数直接来自知识卡的审核、发布和代码版本状态。</p>
            </div>
            <button v-if="repository.capabilities?.canUpdate" type="button" @click="emit('openKnowledge')">管理知识 <ArrowRight :size="13" /></button>
          </header>

          <div class="knowledge-states">
            <article data-tone="current"><small>当前</small><strong>{{ knowledge.current }}</strong><span>与当前代码一致</span></article>
            <article data-tone="suspect"><small>待复核</small><strong>{{ knowledge.suspect }}</strong><span>变化后待复核</span></article>
            <article data-tone="stale"><small>已失效</small><strong>{{ knowledge.stale }}</strong><span>已排除出可信依据</span></article>
            <article data-tone="unverified"><small>未验证</small><strong>{{ knowledge.unverified }}</strong><span>尚未绑定代码版本</span></article>
          </div>

          <div class="governance-ledger">
            <div><span>可信可用</span><strong>{{ knowledge.trusted }}</strong><small>已发布 + 已审核 + 与当前代码一致</small></div>
            <div><span>未审核</span><strong>{{ knowledge.unreviewed }}</strong><small>不能作为已确认规则</small></div>
            <div><span>必需但无负责人</span><strong>{{ knowledge.requiredWithoutOwner }}</strong><small>审批责任尚未落位</small></div>
          </div>
        </section>

        <section class="overview-section code-section" aria-labelledby="code-types-title">
          <header class="section-heading">
            <div>
              <span>代码分布</span>
              <h2 id="code-types-title">代码类型统计</h2>
              <p>基于当前快照的文件路径和代码内容分类，不读取项目说明。</p>
            </div>
          </header>

          <div v-if="categories.length" class="category-list">
            <article v-for="category in categories" :key="category.key" class="category-row">
              <div class="category-main"><span>{{ category.label }}</span><strong>{{ category.count }}</strong></div>
              <i><b :style="{ width: categoryWidth(category.count) }"></b></i>
              <small>{{ category.detail }}<template v-if="category.samples.length"> · {{ category.samples.slice(0, 2).join('、') }}</template></small>
            </article>
          </div>
          <p v-else class="empty-copy">当前快照还没有可用的代码分类结果。</p>
        </section>

        <section class="overview-section reviews-section" aria-labelledby="recent-reviews-title">
          <header class="section-heading">
            <div>
              <span>最近审查</span>
              <h2 id="recent-reviews-title">最近变更审查</h2>
              <p>每条记录都绑定创建时的快照和 Git 版本。</p>
            </div>
          </header>

          <div v-if="health?.recentReviews.length" class="review-list">
            <article v-for="review in health.recentReviews" :key="review.reviewId" class="review-row">
              <span class="review-status" :data-tone="reviewStatus(review).tone">{{ reviewStatus(review).label }}</span>
              <div class="review-copy">
                <strong>{{ review.task || '未填写任务说明' }}</strong>
                <small>{{ review.changedFileCount }} 文件 · {{ review.changedSymbolCount }} 符号 · {{ review.applicableKnowledgeCount }} 条适用知识</small>
              </div>
              <time>{{ formatTime(review.finishedAt ?? review.createdAt) }}</time>
            </article>
          </div>
          <div v-else class="reviews-empty">
            <p>还没有变更审查记录。完成一次审查后，这里会保留版本和证据摘要。</p>
            <button type="button" :disabled="!health?.readyForReview" @click="emit('startReview')">开始第一次审查 <ArrowRight :size="13" /></button>
          </div>
        </section>
      </main>

      <aside class="secondary-column">
        <section class="side-section issue-section" aria-labelledby="issues-title">
          <header class="side-heading"><span>当前问题</span><h2 id="issues-title">当前阻塞与缺口</h2></header>
          <div v-if="health?.issues.length" class="issue-list">
            <article v-for="issue in health.issues" :key="issue.code" class="issue-row" :data-severity="issue.severity">
              <AlertTriangle :size="15" />
              <div>
                <strong>{{ issue.title }}</strong>
                <p>{{ issue.detail }}</p>
                <button v-if="canResolveIssue(issue)" type="button" @click="issueAction(issue)">{{ issue.actionTarget === 'PREPARATION' ? '处理准备状态' : '处理知识' }} <ArrowRight :size="12" /></button>
              </div>
            </article>
          </div>
          <div v-else class="all-clear"><Check :size="15" /><span>当前没有阻塞项或已知缺口</span></div>
        </section>

        <section class="side-section readiness-section" aria-labelledby="readiness-title">
          <header class="side-heading">
            <span>准备状态</span>
            <h2 id="readiness-title">准备流程</h2>
            <p>{{ preparation?.message ?? '尚未获取准备状态' }}</p>
          </header>
          <div v-if="preparation?.stages.length" class="preparation-track">
            <article v-for="stage in preparation.stages" :key="stage.key" class="preparation-stage" :data-tone="stageTone(stage.state)">
              <span class="stage-marker">
                <Check v-if="stage.state === 'READY'" :size="11" />
                <AlertTriangle v-else-if="stage.state === 'FAILED' || stage.state === 'DEGRADED'" :size="11" />
                <CircleDot v-else :size="11" />
              </span>
              <div>
                <span class="stage-title">
                  <strong>{{ stage.label }}</strong>
                  <button
                    v-if="repository.capabilities?.canIndex && (stage.state === 'FAILED' || stage.state === 'DEGRADED')"
                    type="button"
                    :disabled="preparing"
                    @click="emit('retryStage', stage.key)"
                  >重试此阶段</button>
                </span>
                <small>{{ stage.detail }}</small>
              </div>
            </article>
          </div>
          <p v-else class="empty-copy">准备后会显示快照、内容、向量和代码图谱状态。</p>
        </section>
      </aside>
    </div>
  </article>
</template>

<style scoped>
.overview-sheet {
  --navy: var(--app-color-identity);
  --ink: var(--app-text-primary);
  --text: var(--app-text-regular);
  --muted: var(--app-text-muted);
  --line: var(--app-border);
  --soft: var(--app-surface-subtle);
  --blue: var(--app-color-action);
  --cyan: var(--app-color-evidence);
  --green: var(--app-color-success);
  --amber: var(--app-color-warning);
  --red: var(--app-color-danger);
  --violet: var(--app-color-model);
  min-width: 0;
  height: 100%;
  min-height: 0;
  padding: 14px 2px 32px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  color: var(--ink);
  background: var(--app-canvas);
  font-family: inherit;
}
.overview-sheet button { font: inherit; }
.overview-sheet button:disabled { cursor: not-allowed; opacity: .45; }
.project-hero { display: grid; grid-template-columns: minmax(300px, 1fr) minmax(220px, auto) auto; grid-template-rows: auto auto; align-items: center; gap: 7px 18px; padding: 16px 20px; border: 1px solid #d6e0e6; border-top: 4px solid var(--navy); border-radius: 8px 8px 4px 4px; background: #fff; box-shadow: 0 6px 20px rgb(20 47 69 / 5%); }
.identity-block { display: flex; min-width: 0; align-items: center; gap: 12px; }
.project-mark { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; color: #fff; border-radius: 5px; background: var(--navy); }
.identity-copy { min-width: 0; }
.eyebrow, .section-heading span, .side-heading > span { color: var(--blue); font: 750 12px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .14em; }
.identity-copy h1 { overflow: hidden; margin: 0; font-size: clamp(22px, 2.4vw, 28px); line-height: 1.12; letter-spacing: -.03em; text-overflow: ellipsis; white-space: nowrap; }
.identity-copy p { overflow: hidden; margin: 3px 0 0; color: var(--text); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.version-line { display: flex; grid-column: 1; flex-wrap: wrap; align-items: center; gap: 6px 14px; padding-left: 50px; color: #60727e; font: 600 12px/1.35 "SFMono-Regular", Consolas, monospace; }
.version-line span { display: inline-flex; align-items: center; gap: 5px; }
.version-line .dirty-flag { color: var(--amber); }
.health-callout { display: grid; grid-row: 1 / span 2; grid-column: 2; grid-template-columns: 30px minmax(150px, 205px); align-items: center; gap: 8px; padding: 9px 11px; color: var(--green); border: 1px solid rgb(33 138 96 / 25%); border-radius: 5px; background: rgb(33 138 96 / 6%); }
.health-callout[data-tone='warning'] { color: var(--amber); border-color: rgb(182 106 11 / 25%); background: rgb(182 106 11 / 6%); }
.health-callout[data-tone='danger'] { color: var(--red); border-color: rgb(183 73 66 / 25%); background: rgb(183 73 66 / 6%); }
.health-callout[data-tone='running'] { color: var(--blue); border-color: rgb(38 127 184 / 25%); background: rgb(38 127 184 / 6%); }
.health-icon { display: grid; width: 27px; height: 27px; place-items: center; border-radius: 50%; background: #fff; }
.health-callout div { display: grid; gap: 2px; }
.health-callout strong { color: currentColor; font-size: 13px; }
.health-callout small { color: #6d7a83; font-size: 12px; line-height: 1.35; }
.hero-actions { display: flex; grid-row: 1 / span 2; grid-column: 3; justify-content: flex-end; gap: 6px; }
.hero-actions button, .section-heading button, .reviews-empty button, .issue-row button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; cursor: pointer; }
.hero-actions button { min-height: 34px; padding: 7px 10px; border-radius: 4px; font-size: 12px; font-weight: 700; }
.review-action { color: #fff; border: 1px solid var(--blue); background: var(--blue); }
.prepare-action { color: #2d536e; border: 1px solid #c7d4dc; background: #fff; }
.refresh-action { width: 34px; padding: 0 !important; color: #60727e; border: 1px solid #d1dce2; background: #f8fafb; }
.hero-actions button:hover:not(:disabled), .hero-actions button:focus-visible { outline: 2px solid rgb(38 127 184 / 18%); outline-offset: 2px; }
.capability-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); border-left: 1px solid var(--line); background: #fff; }
.capability-strip article { display: grid; min-width: 0; gap: 4px; padding: 18px 21px; border-right: 1px solid var(--line); }
.capability-strip article:last-child { border-right: 0; }
.capability-strip span { display: flex; align-items: center; gap: 7px; color: var(--blue); font-size: 12px; font-weight: 700; }
.capability-strip article[data-accent='cyan'] span { color: var(--cyan); }
.capability-strip article[data-accent='green'] span { color: var(--green); }
.capability-strip article[data-accent='violet'] span { color: var(--violet); }
.capability-strip strong { font: 740 23px/1.15 "SFMono-Regular", Consolas, monospace; letter-spacing: -.04em; }
.capability-strip small { overflow: hidden; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.overview-body { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(280px, .8fr); align-items: start; gap: 20px; margin-top: 20px; }
.primary-column, .secondary-column { display: grid; gap: 20px; }
.overview-section, .side-section { border: 1px solid var(--line); border-radius: 5px; background: #fff; }
.overview-section { padding: 24px 27px 27px; }
.side-section { padding: 22px 22px 24px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 20px; }
.section-heading h2, .side-heading h2 { margin: 4px 0 0; color: var(--navy); font-size: 16px; }
.section-heading p, .side-heading p { margin: 5px 0 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.section-heading button { padding: 7px 9px; color: var(--blue); border: 1px solid #cbd9e1; border-radius: 4px; background: #fff; font-size: 12px; font-weight: 700; }
.knowledge-states { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--line); }
.knowledge-states article { display: grid; gap: 4px; padding: 16px; border-right: 1px solid var(--line); }
.knowledge-states article:last-child { border-right: 0; }
.knowledge-states small { color: var(--green); font: 750 12px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; }
.knowledge-states article[data-tone='suspect'] small { color: var(--amber); }
.knowledge-states article[data-tone='stale'] small { color: var(--red); }
.knowledge-states article[data-tone='unverified'] small { color: var(--muted); }
.knowledge-states strong { font: 740 22px/1.15 "SFMono-Regular", Consolas, monospace; }
.knowledge-states span { color: var(--muted); font-size: 12px; }
.governance-ledger { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 14px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.governance-ledger div { display: grid; grid-template-columns: 1fr auto; gap: 3px 12px; padding: 13px 10px; border-right: 1px solid var(--line); }
.governance-ledger div:last-child { border-right: 0; }
.governance-ledger span { color: #405663; font-size: 12px; font-weight: 700; }
.governance-ledger strong { color: var(--navy); font: 700 14px/1 "SFMono-Regular", Consolas, monospace; }
.governance-ledger small { grid-column: 1 / -1; color: var(--muted); font-size: 12px; }
.category-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 28px; }
.category-row { display: grid; min-width: 0; gap: 6px; }
.category-main { display: flex; justify-content: space-between; gap: 12px; color: #405663; font-size: 12px; }
.category-main span { font-weight: 700; }
.category-main strong { font-family: "SFMono-Regular", Consolas, monospace; }
.category-row > i { display: block; height: 4px; overflow: hidden; background: #e7edf0; }
.category-row > i b { display: block; height: 100%; background: linear-gradient(90deg, var(--blue), var(--cyan)); }
.category-row > small { overflow: hidden; color: var(--muted); font-size: 12px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.review-list { border-top: 1px solid var(--line); }
.review-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 13px 3px; border-bottom: 1px solid var(--line); }
.review-status { min-width: 44px; padding: 4px 6px; color: var(--green); border: 1px solid rgb(33 138 96 / 24%); border-radius: 3px; background: rgb(33 138 96 / 6%); font-size: 12px; font-weight: 750; text-align: center; }
.review-status[data-tone='running'] { color: var(--blue); border-color: rgb(38 127 184 / 24%); background: rgb(38 127 184 / 6%); }
.review-status[data-tone='danger'] { color: var(--red); border-color: rgb(183 73 66 / 24%); background: rgb(183 73 66 / 6%); }
.review-copy { display: grid; min-width: 0; gap: 3px; }
.review-copy strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.review-copy small { color: var(--muted); font-size: 12px; }
.review-row time { color: #71818b; font: 600 12px/1.3 "SFMono-Regular", Consolas, monospace; }
.reviews-empty { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 15px; border: 1px dashed #cdd8de; background: var(--soft); }
.reviews-empty p { margin: 0; color: var(--text); font-size: 12px; line-height: 1.6; }
.reviews-empty button { flex: 0 0 auto; padding: 7px 9px; color: #fff; border: 0; border-radius: 3px; background: var(--blue); font-size: 12px; font-weight: 700; }
.side-heading { margin-bottom: 17px; }
.issue-list { display: grid; gap: 10px; }
.issue-row { display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 8px; padding: 11px; color: var(--amber); border-left: 3px solid var(--amber); background: var(--app-color-warning-soft); }
.issue-row[data-severity='BLOCKING'] { color: var(--red); border-left-color: var(--red); background: var(--app-color-danger-soft); }
.issue-row > svg { margin-top: 1px; }
.issue-row > div { display: grid; gap: 4px; }
.issue-row strong { color: var(--ink); font-size: 12px; }
.issue-row p { margin: 0; color: #687983; font-size: 12px; line-height: 1.55; }
.issue-row button { width: fit-content; padding: 0; color: currentColor; border: 0; background: transparent; font-size: 12px; font-weight: 750; }
.all-clear { display: flex; align-items: center; gap: 8px; padding: 12px; color: var(--green); border: 1px solid rgb(33 138 96 / 22%); background: rgb(33 138 96 / 5%); font-size: 12px; font-weight: 700; }
.preparation-track { position: relative; display: grid; gap: 15px; }
.preparation-track::before { position: absolute; top: 10px; bottom: 10px; left: 10px; width: 1px; content: ''; background: #c2ced5; }
.preparation-stage { position: relative; z-index: 1; display: grid; grid-template-columns: 21px minmax(0, 1fr); gap: 10px; }
.stage-marker { display: grid; width: 21px; height: 21px; place-items: center; color: #87949c; border: 1px solid #c7d2d8; border-radius: 50%; background: #fff; }
.preparation-stage[data-tone='ready'] .stage-marker { color: #fff; border-color: var(--green); background: var(--green); }
.preparation-stage[data-tone='running'] .stage-marker { color: #fff; border-color: var(--blue); background: var(--blue); }
.preparation-stage[data-tone='danger'] .stage-marker { color: #fff; border-color: var(--red); background: var(--red); }
.preparation-stage > div { display: grid; min-width: 0; gap: 2px; }
.stage-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.stage-title button { padding: 0; color: var(--blue); border: 0; background: transparent; font-size: 12px; font-weight: 700; cursor: pointer; }
.preparation-stage strong { color: #354b58; font-size: 12px; }
.preparation-stage small { overflow: hidden; color: var(--muted); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.empty-copy { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.spinning { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1180px) {
  .project-hero { grid-template-columns: minmax(0, 1fr) auto; }
  .health-callout { grid-row: 1; grid-column: 2; grid-template-columns: 30px minmax(150px, 205px); }
  .hero-actions { grid-row: 2; grid-column: 2; }
  .overview-body { grid-template-columns: minmax(0, 1fr) 290px; }
}
@media (max-width: 820px) {
  .overview-sheet { padding: 12px 0 28px; }
  .project-hero { grid-template-columns: 1fr; gap: 9px; padding: 15px 16px; }
  .version-line { padding-left: 0; }
  .health-callout { grid-row: auto; grid-column: 1; grid-template-columns: 30px minmax(0, 1fr); }
  .hero-actions { grid-row: auto; grid-column: 1; justify-content: flex-start; }
  .capability-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .capability-strip article:nth-child(2) { border-right: 0; }
  .capability-strip article:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .overview-body { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .identity-block { align-items: flex-start; }
  .project-mark { width: 39px; height: 39px; }
  .hero-actions { display: grid; grid-template-columns: 1fr auto auto; }
  .knowledge-states { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .knowledge-states article:nth-child(2) { border-right: 0; }
  .knowledge-states article:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .governance-ledger, .category-list { grid-template-columns: 1fr; }
  .governance-ledger div { border-right: 0; border-bottom: 1px solid var(--line); }
  .governance-ledger div:last-child { border-bottom: 0; }
  .review-row { grid-template-columns: auto minmax(0, 1fr); }
  .review-row time { grid-column: 2; }
}
</style>
